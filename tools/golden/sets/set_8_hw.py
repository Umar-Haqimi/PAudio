"""Golden set 8: Hughson-Westlake scripted sequences (SPEC 4 "Quick detail (normative)") for :core:audiometry.

Writes core/audiometry/src/test/resources/golden/hughson_westlake.json.

The expected values in HAND_DERIVED are the step-by-step derivations documented in docs/VECTORS.md
("Hughson-Westlake scripted derivations"), written by hand BEFORE encoding. As a guard against arithmetic slips,
`replay()` below is an independent re-statement of the rules (Python, written from the spec) and the generator
asserts that it reproduces every hand-derived sequence exactly; the JSON is the hand-derived data.

Rules recap (all levels dBFS, multiples of 5 here):
  start = min(Lmax, other + 15) if other known else min(Lmax, Lexp + 20); then max(Lmin, start)
  SEEK (before the first "heard"): not heard -> +10 (<= Lmax); not heard at Lmax -> censored
  after the first heard: heard -> -10 (>= Lmin); not heard -> +5 (<= Lmax)
  ascending presentation = previous answer was "not heard"; first 3 per level count; 2 heard -> threshold
  floor: heard at Lmin twice -> threshold Lmin (atFloor); HW-phase ceiling: 2 misses at Lmax -> censored
  max 15 presentations, else mean of last 4 reversal levels, lowConfidence
  reversal = level of a presentation whose answer differs from the previous answer
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import common  # noqa: E402

# name, Lexp, Lmax, Lmin, other-ear threshold (or None), responses, levels, reversals,
# threshold, censored, lowConfidence, atFloor
HAND_DERIVED = [
    ("normal_convergence", -60, -10, -85, None, "YYYNNYNNY",
     [-40, -50, -60, -70, -65, -60, -70, -65, -60], [-70, -60, -70, -60], -60, False, False, False),
    ("censored_at_lmax", -60, -10, -85, None, "NNNN",
     [-40, -30, -20, -10], [], -10, True, False, False),
    ("immediate_yes_at_start", -60, -10, -85, None, "YNYNNY",
     [-40, -50, -45, -55, -50, -45], [-50, -45, -55, -45], -45, False, False, False),
    ("max15_fallback", -60, -10, -85, None, "YYYNYNNNYNNNYNN",
     [-40, -50, -60, -70, -65, -75, -70, -65, -60, -70, -65, -60, -55, -65, -60],
     [-70, -65, -75, -60, -70, -55, -65], -62.5, False, True, False),
    ("ascending_2of3_with_miss", -60, -10, -85, None, "NYYYNYNNNYNY",
     [-40, -30, -40, -50, -60, -55, -65, -60, -55, -50, -60, -55],
     [-30, -60, -55, -65, -50, -60, -55], -55, False, False, False),
    ("start_from_other_ear", -60, -10, -85, -52, "YYNYNNY",
     [-37, -47, -57, -52, -62, -57, -52], [-57, -52, -62, -52], -52, False, False, False),
    ("clamp_at_lmin", -60, -10, -85, None, "YYYYYYY",
     [-40, -50, -60, -70, -80, -85, -85], [], -85, False, False, True),
    ("mixed_start_clamped_to_lmax", -60, -10, -85, -20, "YYNNYNNY",
     [-10, -20, -30, -25, -20, -30, -25, -20], [-30, -20, -30, -20], -20, False, False, False),
]


def replay(l_exp, l_max, l_min, other, responses):
    """Independent re-statement of the rules; returns (levels, reversals, thr, censored, low, floor)."""
    level = min(l_max, other + 15 if other is not None else l_exp + 20)
    level = max(l_min, level)
    levels, reversals = [], []
    seeking = True
    prev = None  # previous answer
    asc_n, asc_h = {}, {}
    floor_hits = 0
    ceil_miss = 0
    for resp in responses:
        heard = resp == "Y"
        ascending = prev is False
        levels.append(level)
        if prev is not None and prev != heard:
            reversals.append(level)
        # decide termination
        if ascending and asc_n.get(level, 0) < 3:
            asc_n[level] = asc_n.get(level, 0) + 1
            if heard:
                asc_h[level] = asc_h.get(level, 0) + 1
                if asc_h[level] >= 2:
                    return levels, reversals, level, False, False, level <= l_min
        if heard and level <= l_min:
            floor_hits += 1
            if floor_hits >= 2:
                return levels, reversals, l_min, False, False, True
        if not heard and level >= l_max:
            ceil_miss += 1
            if seeking or ceil_miss >= 2:
                return levels, reversals, l_max, True, False, False
        if len(levels) >= 15:
            src = reversals if reversals else levels
            tail = src[-4:]
            return levels, reversals, sum(tail) / len(tail), False, True, False
        if heard:
            seeking = False
            level = max(l_min, level - 10)
        else:
            level = min(l_max, level + (10 if seeking else 5))
        prev = heard
    raise AssertionError("script ended without a decision")


def generate(core_root: Path) -> None:
    cases = []
    for (name, l_exp, l_max, l_min, other, resp, levels, revs, thr, cens, low, floor) in HAND_DERIVED:
        got = replay(l_exp, l_max, l_min, other, resp)
        assert got[0] == levels, (name, "levels", got[0], levels)
        assert got[1] == revs, (name, "reversals", got[1], revs)
        assert got[2] == thr and got[3] == cens and got[4] == low and got[5] == floor, (name, got)
        assert len(levels) == len(resp), name
        cases.append({
            "name": name, "Lexp": l_exp, "Lmax": l_max, "Lmin": l_min,
            "otherEarThresholdDb": other, "responses": resp, "levels": levels, "reversals": revs,
            "thresholdDb": thr, "censored": cens, "lowConfidence": low, "atFloor": floor, "trials": len(levels),
        })
    obj = {
        "set": "hughson_westlake",
        "formatVersion": 1,
        "generator": "tools/golden/sets/set_8_hw.py",
        "tolerance": {"levelsDb": 0.0},
        "cases": cases,
    }
    common.write_json(core_root / "audiometry/src/test/resources/golden/hughson_westlake.json", obj)
