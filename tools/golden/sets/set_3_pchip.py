"""Golden set 3: PCHIP in log2(f) vs scipy.interpolate.PchipInterpolator (SPEC 2.1.3, 7.1 #3)."""
from __future__ import annotations

import platform
from pathlib import Path

import numpy as np
import scipy
from scipy.interpolate import PchipInterpolator

import common

F = np.array(common.GRID, dtype=float)
GRID_HZ = [20.0 * 2.0 ** (i / 24.0) for i in range(240)]


def knot_sets() -> list[tuple[str, list[float]]]:
    rng = np.random.default_rng(1)
    rnd = [round(float(x), 2) for x in rng.uniform(-6, 12, 9)]
    return [
        ("all_zero", [0.0] * 9),
        ("monotone_rising", [0, 0, 1, 2.5, 4, 6, 8, 10, 12]),
        ("monotone_falling", [12, 10, 8, 6, 4, 2.5, 1, 0, -2]),
        ("single_peak", [0, 0, 0, 0, 12, 0, 0, 0, 0]),
        ("notch_4k", [0, 0, 5, 12, 6, 0, 0, 0, 0]),
        ("plateau", [3, 3, 3, 6, 6, 6, 6, 12, 12]),
        ("alternating", [6, -6, 6, -6, 6, -6, 6, -6, 6]),
        ("step_8k", [0, 0, 0, 0, 0, 0, 12, 12, 12]),
        ("ramp_clamped", [0, 2.5, 5, 7.5, 10, 12, 12, 12, 12]),
        ("random_seed1", rnd),
    ]


def generate(core_root: Path) -> None:
    x = np.log2(F)
    eval_hz = sorted(set([float(f) for f in F] + [f for f in GRID_HZ if 1000.0 <= f <= 14000.0]))
    cases = []
    for name, v in knot_sets():
        p = PchipInterpolator(x, np.array(v, dtype=float))
        cases.append(dict(name=name, knotsHz=[float(f) for f in F], values=[float(a) for a in v],
                          evalHz=eval_hz, expected=[float(a) for a in p(np.log2(eval_hz))]))
    out = {
        "set": "pchip", "formatVersion": 1, "generator": "tools/golden/sets/set_3_pchip.py",
        "python": platform.python_version(), "numpy": np.__version__, "scipy": scipy.__version__,
        "tolerance": {"valueDb": 1e-6, "knotExact": 1e-9}, "cases": cases,
    }
    common.write_json(core_root / "dsp/src/test/resources/golden/pchip.json", out)
