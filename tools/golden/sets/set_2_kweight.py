"""Golden set 2: ITU-R BS.1770 K-weighting magnitude (SPEC 2.1.5, 7.1 #2).

Oracle: scipy.signal.freqz of the two Annex 1 biquads at 48 kHz (no -0.691 dB offset).
"""
from __future__ import annotations

import platform
from pathlib import Path

import numpy as np
import scipy
import scipy.signal as sig

import common

FS = 48000.0
PRE_B = [1.53512485958697, -2.69169618940638, 1.19839281085285]
PRE_A = [1.0, -1.69065929318241, 0.73248077421585]
RLB_B = [1.0, -2.0, 1.0]
RLB_A = [1.0, -1.99004745483398, 0.99007225036621]


def k_db(f_hz) -> np.ndarray:
    w = 2.0 * np.pi * np.asarray(f_hz, dtype=float) / FS
    _, h1 = sig.freqz(PRE_B, PRE_A, worN=w)
    _, h2 = sig.freqz(RLB_B, RLB_A, worN=w)
    return 20.0 * np.log10(np.abs(h1) * np.abs(h2))


def generate(core_root: Path) -> None:
    centers = [1000.0 * 2.0 ** (b / 3.0) for b in range(-16, 14)]
    grid = [20.0 * 2.0 ** (i / 24.0) for i in range(240)]
    out = {
        "set": "k_weighting", "formatVersion": 1, "generator": "tools/golden/sets/set_2_kweight.py",
        "python": platform.python_version(), "numpy": np.__version__, "scipy": scipy.__version__,
        "tolerance": {"kDb": 0.01}, "sampleRate": int(FS),
        "cases": [dict(b=b, fcHz=f, kDb=float(k)) for b, f, k in zip(range(-16, 14), centers, k_db(centers))],
        "gridHz": grid, "gridKDb": [float(x) for x in k_db(grid)],
    }
    common.write_json(core_root / "dsp/src/test/resources/golden/k_weighting.json", out)
