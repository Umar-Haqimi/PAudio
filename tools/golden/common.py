"""Shared, independent Python reference for the PAudio golden oracles (SPEC §2.1.3).

This module is the single source of the 12 measurement-model threshold sets and the
reference implementation of the estimated-hearing-level math. It is written from the
SPEC text, NOT ported from Kotlin. Both the :core:audiometry (set 5) and :core:dsp
(sets 5-curve and 6) oracles import it so they agree on inputs.
"""
from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np

# Test grid F (Hz), SPEC §2.1.3
GRID = [1000, 2000, 3000, 4000, 6000, 8000, 10000, 12500, 14000]

# ISO 389-7:2005 diffuse-field reference thresholds R_df (dB SPL), SPEC §2.1.2 / USAARL Table 11-1
RDF_TABLE = {
    1000: 0.8, 1250: 1.9, 1500: 1.0, 1600: 0.5, 2000: -1.5, 2500: -3.1, 3000: -4.0,
    3150: -4.0, 4000: -3.8, 5000: -1.8, 6000: 1.4, 6300: 2.5, 8000: 6.8, 9000: 8.4,
    10000: 9.8, 11200: 11.5, 12500: 14.4, 14000: 23.2, 16000: 43.7,
}

EARS = ["LEFT", "RIGHT"]

WHO_BANDS = [  # (upper bound exclusive, grade id)
    (20.0, "NORMAL"),
    (35.0, "MILD"),
    (50.0, "MODERATE"),
    (65.0, "MODERATELY_SEVERE"),
    (80.0, "SEVERE"),
    (95.0, "PROFOUND"),
    (math.inf, "COMPLETE"),
]


def rdf(f: float) -> float:
    """R_df(f); exact table value or linear interpolation in log2(f)."""
    if f in RDF_TABLE:
        return RDF_TABLE[f]
    xs = sorted(RDF_TABLE)
    if f <= xs[0]:
        return RDF_TABLE[xs[0]]
    if f >= xs[-1]:
        return RDF_TABLE[xs[-1]]
    for a, b in zip(xs, xs[1:]):
        if a <= f <= b:
            t = (math.log2(f) - math.log2(a)) / (math.log2(b) - math.log2(a))
            return RDF_TABLE[a] + t * (RDF_TABLE[b] - RDF_TABLE[a])
    raise AssertionError


def l_exp(f: float, l_ref: float) -> float:
    return l_ref + (rdf(f) - rdf(1000))


def l_max(f: float, l_ref: float) -> float:
    return min(-3.0, l_exp(f, l_ref) + 50.0)


def l_min(f: float, l_ref: float) -> float:
    return l_exp(f, l_ref) - 25.0


def who_grade(pta: float) -> str:
    for upper, gid in WHO_BANDS:
        if pta < upper:
            return gid
    return "COMPLETE"


# --------------------------------------------------------------------------------------
# The 12 threshold sets. Each is defined by a "true" relative hearing level per ear and
# frequency plus an L_ref and a constant device offset; thresholds are derived as
#   T = L_exp(f) + hl_true + offset + jitter
# and censored (stored as the bound L_max) when T > L_max. The derived raw thresholds are
# what goes into the golden JSON, so consumers never re-derive them.
# --------------------------------------------------------------------------------------

def _set(name, l_ref, left, right, offset=0.0, jitter_seed=None):
    return dict(name=name, l_ref=l_ref, hl_true={"LEFT": left, "RIGHT": right},
                offset=offset, jitter_seed=jitter_seed)


_SLOPE_MILD = [0, 0, 5, 10, 15, 20, 25, 30, 35]
_SLOPE_SEVERE = [5, 10, 20, 35, 45, 55, 60, 70, 75]
_NOTCH4K = [0, 0, 10, 30, 20, 5, 5, 10, 10]

THRESHOLD_SETS_DEF = [
    _set("normal", -60.0, [0] * 9, [0] * 9),
    _set("normal_jitter", -58.0, [0] * 9, [0] * 9, jitter_seed=11),
    _set("sloping_hf_mild", -60.0, _SLOPE_MILD, _SLOPE_MILD),
    _set("sloping_hf_severe_censored", -60.0, _SLOPE_SEVERE, _SLOPE_SEVERE),
    _set("notch_4k", -62.0, _NOTCH4K, _NOTCH4K, jitter_seed=23),
    _set("asymmetric_right_worse", -60.0, [0] * 9, [5, 20, 25, 30, 30, 25, 20, 20, 20]),
    _set("asymmetric_single_point", -60.0, [0] * 9, [0, 0, 0, 20, 0, 0, 0, 0, 0]),
    _set("censored_ehf_only", -60.0, [0, 0, 0, 0, 5, 10, 30, 45, 45], [0, 0, 0, 0, 5, 10, 30, 45, 45]),
    _set("flat_loss_hidden", -60.0, [25] * 9, [25] * 9),
    _set("low_lref_window_edge", -68.0, _SLOPE_MILD, [0, 0, 5, 15, 25, 20, 25, 30, 35], jitter_seed=5),
    _set("high_lref_window_edge", -50.0, [0, -5, 0, 5, 10, 10, 15, 20, 25], [0, 0, 0, 5, 5, 10, 15, 20, 25]),
    _set("mixed_left_notch_right_slope", -60.0, _NOTCH4K, _SLOPE_SEVERE, offset=3.0, jitter_seed=42),
    # Extra (13th) set: the only realistic way to reach a non-NORMAL grade under the anchor +
    # censoring model (see docs/DECISIONS.md "WHO grade ceiling").
    _set("mild_grade_reachable", -60.0, [-12, -12, 20, 50, 50, 50, 50, 50, 50],
         [-12, -12, 25, 55, 55, 55, 55, 55, 55]),
]


def derive_thresholds(sdef) -> dict:
    """Return {'name','l_ref','thresholds':{ear:[{freqHz, levelDbfs, censored}]}}."""
    rng = np.random.default_rng(sdef["jitter_seed"]) if sdef["jitter_seed"] is not None else None
    out = {}
    for ear in EARS:
        pts = []
        for i, f in enumerate(GRID):
            t = l_exp(f, sdef["l_ref"]) + sdef["hl_true"][ear][i] + sdef["offset"]
            if rng is not None:
                t += round(float(rng.uniform(-2.0, 2.0)), 1)  # deterministic 0.1 dB jitter
            t = round(t, 2)
            bound = l_max(f, sdef["l_ref"])
            if t > bound:
                pts.append(dict(freqHz=f, levelDbfs=round(bound, 6), censored=True))
            else:
                pts.append(dict(freqHz=f, levelDbfs=t, censored=False))
        out[ear] = pts
    return dict(name=sdef["name"], l_ref=sdef["l_ref"], thresholds=out)


def threshold_sets() -> list[dict]:
    return [derive_thresholds(s) for s in THRESHOLD_SETS_DEF]


# --------------------------------------------------------------------------------------
# Measurement model, SPEC §2.1.3 steps 1-7. Censored points use their bound.
# --------------------------------------------------------------------------------------

def measurement_model(tset: dict) -> dict:
    T = {ear: {p["freqHz"]: p["levelDbfs"] for p in tset["thresholds"][ear]} for ear in EARS}
    N = {ear: {f: T[ear][f] - rdf(f) for f in GRID} for ear in EARS}
    A = min((N[ear][1000] + N[ear][2000]) / 2.0 for ear in EARS)
    HL = {ear: {f: N[ear][f] - A for f in GRID} for ear in EARS}
    pta3 = {ear: (HL[ear][1000] + HL[ear][2000] + HL[ear][4000]) / 3.0 for ear in EARS}
    ehf = {ear: (HL[ear][10000] + HL[ear][12500] + HL[ear][14000]) / 3.0 for ear in EARS}
    better_pta = min(pta3.values())
    diffs = [abs(HL["LEFT"][f] - HL["RIGHT"][f]) >= 15.0 for f in GRID]
    asym = any(a and b for a, b in zip(diffs, diffs[1:]))
    return dict(
        N={ear: [N[ear][f] for f in GRID] for ear in EARS},
        A=A,
        HL={ear: [HL[ear][f] for f in GRID] for ear in EARS},
        pta3=pta3,
        betterEarPta3=better_pta,
        grade=who_grade(better_pta),
        ehf=ehf,
        asymmetry=asym,
    )


def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w") as fh:
        json.dump(obj, fh, indent=1, sort_keys=False)
        fh.write("\n")
    print(f"wrote {path}")
