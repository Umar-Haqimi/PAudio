"""Golden set 7: stimulus synthesis (SPEC 2.1.2, 2.2 #4, 4) for :core:audiometry.

Independent numpy synthesis from the SPEC definition (never ported from Kotlin). Writes
core/audiometry/src/test/resources/golden/stimulus_synthesis.json.

Definition (frozen here and in docs/VECTORS.md set 7):
  * pulse of N = (225*fs + 500)//1000 samples, sample n at pulse-local time t = n/fs
  * raised-cosine ramps r(t) = 0.5*(1 - cos(pi*t/0.025)) for t < 25 ms on the way in, mirrored on the way out
    with t' = (N-1-n)/fs (so the first and last samples are exactly 0)
  * f < 4 kHz pure: phase = 2*pi*f*t
  * f >= 4 kHz warble, f_inst = f*(1 + 0.05*sin(2*pi*8*t)); phase = integral of 2*pi*f_inst dt from 0,
    closed form: 2*pi*f*t + (0.05*f/8)*(1 - cos(2*pi*8*t))
  * x[n] = A*r_on*r_off*sin(phase), A = 10**(L/20); opposite channel exactly 0
Stats are over the single pulse; spectrum = rectangular-window rFFT zero-padded to 65536 points
(the pulse is already tapered by its own ramps), in-band = |f_k - f| <= 12 % of f.
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import common  # noqa: E402

NFFT = 65536
LEVEL_DB = -40.0
N_SAMPLES_OUT = 2048


def pulse(f: float, level_db: float, fs: int) -> np.ndarray:
    n_total = (225 * fs + 500) // 1000
    n = np.arange(n_total, dtype=np.float64)
    t = n / fs
    t_off = (n_total - 1 - n) / fs
    ramp = 0.025
    r_on = np.where(t < ramp, 0.5 * (1.0 - np.cos(np.pi * t / ramp)), 1.0)
    r_off = np.where(t_off < ramp, 0.5 * (1.0 - np.cos(np.pi * t_off / ramp)), 1.0)
    base = 2.0 * np.pi * f * t
    if f >= 4000:
        phase = base + (0.05 * f / 8.0) * (1.0 - np.cos(2.0 * np.pi * 8.0 * t))
    else:
        phase = base
    return (10.0 ** (level_db / 20.0)) * r_on * r_off * np.sin(phase)


def stats(x: np.ndarray, f: float, fs: int) -> dict:
    n_total = len(x)
    n = np.arange(n_total)
    steady = (n / fs >= 0.025) & ((n_total - 1 - n) / fs >= 0.025)
    spec = np.abs(np.fft.rfft(x, NFFT)) ** 2
    freqs = np.arange(len(spec)) * fs / NFFT
    inband = np.abs(freqs - f) <= 0.12 * f
    e_in = float(spec[inband].sum())
    e_out = float(spec[~inband].sum())
    return {
        "frames": n_total,
        "peakDb": 20.0 * math.log10(float(np.max(np.abs(x)))),
        "rmsDb": 20.0 * math.log10(float(np.sqrt(np.mean(x ** 2)))),
        "steadyPeakDb": 20.0 * math.log10(float(np.max(np.abs(x[steady])))),
        "outOfBandRelDb": 10.0 * math.log10(e_out / e_in),
        "firstSample": float(x[0]),
        "lastSample": float(x[-1]),
    }


def generate(core_root: Path) -> None:
    cases = []
    spec = [  # (tone label, f, fs, ear)
        ("1k_pure", 1000, 48000, "LEFT"), ("1k_pure", 1000, 44100, "LEFT"),
        ("8k_warble", 8000, 48000, "LEFT"), ("8k_warble", 8000, 44100, "RIGHT"),
        ("14k_warble", 14000, 48000, "RIGHT"), ("14k_warble", 14000, 44100, "LEFT"),
    ]
    for label, f, fs, ear in spec:
        x = pulse(f, LEVEL_DB, fs)
        case = {"name": f"{label}_{fs}", "freqHz": f, "sampleRate": fs, "ear": ear, "levelDbfs": LEVEL_DB,
                "warble": f >= 4000}
        case.update(stats(x, f, fs))
        case["samples"] = [float(v) for v in x[:N_SAMPLES_OUT]]  # active channel; the other is exactly 0
        cases.append(case)
    obj = {
        "set": "stimulus_synthesis",
        "formatVersion": 1,
        "generator": "tools/golden/sets/set_7_stimulus.py",
        "numpy": np.__version__,
        "tolerance": {"samples": 1e-6, "statsDb": 0.01},
        "fftSize": NFFT,
        "cases": cases,
    }
    common.write_json(core_root / "audiometry/src/test/resources/golden/stimulus_synthesis.json", obj)
