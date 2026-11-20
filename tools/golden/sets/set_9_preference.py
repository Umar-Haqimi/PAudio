"""Golden set 9: preference bisection (SPEC §4 flow 5), independent oracle.

Written from the SPEC text only. Choices are scripted relative to the centre so the oracle
does not depend on A/B side randomisation:
    "+"  pick the higher-gain option (c + s, clamped)
    "-"  pick the lower-gain option  (c - s, clamped)
    "?"  "Can't tell": ends that axis
Choices are consumed in order, one per *presented* round (rounds of ended axes are skipped).

Rules: slots interleave axes B,P,T,B,P,T,B,P,T; c starts at 0; the two compared values are
clamp(c-s) and clamp(c+s) to the axis range; picking sets c to the picked value and halves s.
"""
from __future__ import annotations

from pathlib import Path

from common import write_json

# axis -> (initial step, min, max)
AXES = {"BASS": (6.0, -6.0, 12.0), "PRESENCE": (4.0, -6.0, 6.0), "TREBLE": (5.0, -8.0, 8.0)}
ORDER = ["BASS", "PRESENCE", "TREBLE"] * 3


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def run(choices):
    c = {a: 0.0 for a in AXES}
    s = {a: AXES[a][0] for a in AXES}
    done = {a: False for a in AXES}
    it = iter(choices)
    rounds = []
    for axis in ORDER:
        if done[axis]:
            continue
        _, lo_lim, hi_lim = AXES[axis]
        low = clamp(c[axis] - s[axis], lo_lim, hi_lim)
        high = clamp(c[axis] + s[axis], lo_lim, hi_lim)
        ch = next(it)
        if ch == "?":
            done[axis] = True
            picked = None
        elif ch == "+":
            picked = high
        elif ch == "-":
            picked = low
        else:
            raise ValueError(ch)
        if picked is not None:
            c[axis] = picked
            s[axis] /= 2.0
        rounds.append(dict(axis=axis, low=low, high=high, choice=ch, picked=picked))
    leftover = list(it)
    if leftover:
        raise ValueError(f"unused choices: {leftover}")
    return dict(gB=c["BASS"], gP=c["PRESENCE"], gT=c["TREBLE"], rounds=rounds)


CASES = {
    # 9 presented rounds
    "all_plus_hits_clamps": "+" * 9,
    "all_minus": "-" * 9,
    "alternating_plus_first": "+-+-+-+-+",
    "alternating_minus_first": "-+-+-+-+-",
    "mixed": "+--++-+-+",
    # B, P, T each "can't tell" immediately -> 3 rounds
    "cant_tell_all_immediately": "???",
    # B P T B? (bass ends in its 2nd round) then P T, P T -> 8 rounds
    "cant_tell_bass_round2": "+++?" + "++" + "++",
    # B P T B P? (presence ends in its 2nd round) T B T -> 8 rounds
    "cant_tell_presence_round2": "+-+-?+-+",
    # B P T B P T? (treble ends in its 2nd round) B P -> 8 rounds
    "cant_tell_treble_round2": "+-+-+?-+",
}


def generate(core_root: Path) -> None:
    out = []
    for name, seq in CASES.items():
        choices = list(seq)
        try:
            res = run(choices)
        except (StopIteration, ValueError) as exc:  # fail loudly: scripts must be consistent
            raise SystemExit(f"case {name}: bad script {seq!r}: {exc!r}")
        out.append(dict(name=name, choices=choices, **res))
    write_json(core_root / "preference/src/test/resources/golden/preference.json",
               dict(version=1, cases=out))
