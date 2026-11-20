"""Golden set 1: RBJ cookbook magnitude responses (SPEC 2.1.3, 7.1 #1).

Oracle: W3C Audio EQ Cookbook biquad coefficients -> scipy.signal.freqz at fs = 48 kHz.
Written from the cookbook text; independent of the Kotlin implementation.
"""
from __future__ import annotations

import platform
from pathlib import Path

import numpy as np
import scipy
import scipy.signal as sig

import common

FS = 48000.0
GRID_HZ = [20.0 * 2.0 ** (i / 24.0) for i in range(240)]  # 20 Hz .. 19.70 kHz
FILTERS = {
    "lowshelf": dict(fc=105.0, q=0.707),
    "peaking": dict(fc=3000.0, q=0.8),
    "highshelf": dict(fc=8000.0, q=0.707),
}
GAINS = [-12, -9, -6, -3, 0, 3, 6, 9, 12]


def header(set_name: str, script: str, tolerance: dict) -> dict:
    return {
        "set": set_name, "formatVersion": 1, "generator": f"tools/golden/sets/{script}",
        "python": platform.python_version(), "numpy": np.__version__, "scipy": scipy.__version__,
        "tolerance": tolerance,
    }


def cookbook(kind: str, fc: float, q: float, gain_db: float, fs: float = FS):
    A = 10.0 ** (gain_db / 40.0)
    w0 = 2.0 * np.pi * fc / fs
    cw, sw = np.cos(w0), np.sin(w0)
    alpha = sw / (2.0 * q)
    if kind == "peaking":
        b = [1 + alpha * A, -2 * cw, 1 - alpha * A]
        a = [1 + alpha / A, -2 * cw, 1 - alpha / A]
    elif kind == "lowshelf":
        t = 2 * np.sqrt(A) * alpha
        b = [A * ((A + 1) - (A - 1) * cw + t), 2 * A * ((A - 1) - (A + 1) * cw), A * ((A + 1) - (A - 1) * cw - t)]
        a = [(A + 1) + (A - 1) * cw + t, -2 * ((A - 1) + (A + 1) * cw), (A + 1) + (A - 1) * cw - t]
    elif kind == "highshelf":
        t = 2 * np.sqrt(A) * alpha
        b = [A * ((A + 1) + (A - 1) * cw + t), -2 * A * ((A - 1) + (A + 1) * cw), A * ((A + 1) + (A - 1) * cw - t)]
        a = [(A + 1) - (A - 1) * cw + t, 2 * ((A - 1) - (A + 1) * cw), (A + 1) - (A - 1) * cw - t]
    else:
        raise ValueError(kind)
    return np.array(b), np.array(a)


def mag_db(kind: str, fc: float, q: float, gain_db: float, f_hz) -> np.ndarray:
    b, a = cookbook(kind, fc, q, gain_db)
    w = 2.0 * np.pi * np.asarray(f_hz, dtype=float) / FS
    _, h = sig.freqz(b, a, worN=w)
    return 20.0 * np.log10(np.abs(h))


def pref_db(gb: float, gp: float, gt: float, f_hz) -> np.ndarray:
    return (mag_db("lowshelf", 105.0, 0.707, gb, f_hz)
            + mag_db("peaking", 3000.0, 0.8, gp, f_hz)
            + mag_db("highshelf", 8000.0, 0.707, gt, f_hz))


def generate(core_root: Path) -> None:
    cases = []
    for kind, p in FILTERS.items():
        for g in GAINS:
            cases.append(dict(type=kind, fcHz=p["fc"], q=p["q"], gainDb=g,
                              magDb=[float(x) for x in mag_db(kind, p["fc"], p["q"], g, GRID_HZ)]))
    pref = []
    for gb, gp, gt in [(0, 0, 0), (6, 0, 0), (0, -6, 0), (0, 0, 6), (12, 6, 8), (-6, -6, -8), (4, -3, 5)]:
        pref.append(dict(gB=gb, gP=gp, gT=gt, totalDb=[float(x) for x in pref_db(gb, gp, gt, GRID_HZ)]))
    out = header("rbj_magnitudes", "set_1_rbj.py", {"magDb": 0.01})
    out.update(sampleRate=int(FS), gridHz=GRID_HZ, cases=cases, preferenceCases=pref)
    common.write_json(core_root / "dsp/src/test/resources/golden/rbj_magnitudes.json", out)
