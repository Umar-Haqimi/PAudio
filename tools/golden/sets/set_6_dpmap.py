"""Golden set 6: DynamicsProcessing band layout, mapping and reconstruction (SPEC 2.1.4, DECISIONS 1).

Oracle: numpy re-implementation of the DECISIONS.md section 1 definitions:
  * block size from EffectDynamicsProcessing.cpp / DPFrequency.cpp rounding (float32 math),
  * stops = [0] + unique(round(geomspace(1, N/2, 128)))   (numpy round = half-to-even),
  * band gain = mean over bins k in (stops[b-1], stops[b]] of G(max(k fs/N, 20 Hz)),
    G = linear interpolation in log2 f of the 1/24-octave grid values (ends held),
  * replica of DPFrequency::updateParameters for the per-bin factors,
  * reconstruction = linear-in-dB interpolation over bin-centre knots, RMS vs G on 50 Hz..16 kHz.
Curves: every G from set 5c plus three extreme preference curves. The generator also runs an
INDEPENDENT overlap-add simulation (sqrt-Hann analysis/synthesis, hop N/2) of the 87-band layout
for two curves and aborts if the simulated steady-state sine gain deviates from the linear-in-dB
reconstruction by more than 0.3 dB (DECISIONS 1.6). Layouts "fb51" (geomspace ... 64) and "spec40"
(SPEC c_k layout, negative control, not asserted <= 0.5 dB) are exported too.
"""
from __future__ import annotations

import importlib.util
import platform
from pathlib import Path

import numpy as np
import scipy

import common

HERE = Path(__file__).resolve().parent


def _load(stem: str):
    spec = importlib.util.spec_from_file_location(stem, HERE / f"{stem}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


_c5 = _load("set_5c_curve")
GRID = _c5.GRID
FRAME_MS = 16.0
SAMPLE_RATES = [44100, 48000]


def dp_block_size(fs: int, frame_ms: float) -> int:
    d = int(np.float32(frame_ms) * np.float32(fs) / np.float32(1000.0))
    n = 8 if d < 8 else (d if (d & (d - 1)) == 0 else 1 << d.bit_length())
    return int(min(max(n, 8), 16384))


def band_stops(n: int, target: int = 128) -> list[int]:
    return [0] + sorted({int(round(x)) for x in np.geomspace(1, n // 2, target)})


def layout_main(fs, n):
    s = band_stops(n, 128)
    return s, [x * fs / n for x in s]


def layout_fb51(fs, n):
    s = band_stops(n, 64)
    return s, [x * fs / n for x in s]


def layout_spec40(fs, n):
    cut = [20.0 * 2.0 ** ((k + 1) / 4.0) for k in range(39)] + [fs / 2.0]
    return [int(0.5 + c * n / fs) for c in cut], cut


def g_of_f(g_grid, f):
    return np.interp(np.log2(f), np.log2(GRID), g_grid)


def band_gains_db(stops, fs, n, g_grid):
    out, prev = [], None
    for i, s in enumerate(stops):
        start = 0 if i == 0 else stops[i - 1] + 1
        k = np.arange(start, s + 1)
        if len(k) == 0:                      # degenerate band (spec40 only): G at the stop bin
            k = np.array([s])
        out.append(float(np.mean(g_of_f(g_grid, np.maximum(k * fs / n, 20.0)))))
    return out


def native_bin_gains(cut_hz, gain_db, n, fs):
    vec = np.ones(n // 2 + 1)
    bin_next = 0
    for fc, g in zip(cut_hz, gain_db):
        start, stop = bin_next, int(0.5 + fc * n / fs)
        bin_next = stop + 1
        for k in range(start, min(stop, n // 2) + 1):
            vec[k] = 10 ** (g / 20)
    return vec


def reconstruct_db(vec, n, fs, f_hz):
    return np.interp(f_hz, np.arange(n // 2 + 1) * fs / n, 20 * np.log10(vec))


def rms_err(vec, n, fs, g_grid):
    m = (GRID >= 50.0) & (GRID <= 16000.0)
    return float(np.sqrt(np.mean((reconstruct_db(vec, n, fs, GRID[m]) - g_grid[m]) ** 2)))


def simulate_db(vec, n, fs, f_hz, hops=40):
    """Steady-state sine gain of sqrt-Hann OLA processing with per-bin factors `vec`."""
    i = np.arange(n)
    win = np.sqrt(0.5 * (1.0 - np.cos(2 * np.pi * i / (n - 1))))
    hop = n // 2
    total = hop * (hops + 1)
    t = np.arange(total)
    res = []
    for f in f_hz:
        x = np.sin(2 * np.pi * f * t / fs)
        y = np.zeros(total)
        for h in range(hops):
            seg = x[h * hop:h * hop + n] * win
            spec = np.fft.rfft(seg)
            spec[:n // 2] *= vec[:n // 2]          # native scales k < N/2 only
            y[h * hop:h * hop + n] += np.fft.irfft(spec, n) * win
        a, b = 4 * n, total - 5 * n                # steady-state interior
        tt = t[a:b]
        m = np.stack([np.sin(2 * np.pi * f * tt / fs), np.cos(2 * np.pi * f * tt / fs)], axis=1)
        coef, *_ = np.linalg.lstsq(m, y[a:b], rcond=None)
        res.append(20 * np.log10(np.hypot(*coef)))
    return np.array(res)


def generate(core_root: Path) -> None:
    curves = [dict(name=c["name"], gLeft=c["gLeft"], gRight=c["gRight"]) for c in _c5.build_cases()]
    for nm, args in [("extreme_bass_+12", (12, 0, 0)), ("extreme_presence_+6", (0, 6, 0)),
                     ("extreme_treble_+8", (0, 0, 8))]:
        _, g, _ = _c5.curve([0.0] * 9, [0.0] * 9, 0.0, *args)
        curves.append(dict(name=nm, gLeft=[float(x) for x in g[0]], gRight=[float(x) for x in g[1]]))

    layouts, results = [], []
    worst_main = 0.0
    for fs in SAMPLE_RATES:
        n = dp_block_size(fs, FRAME_MS)
        for lid, fn in [("main", layout_main), ("fb51", layout_fb51), ("spec40", layout_spec40)]:
            stops, cut = fn(fs, n)
            layouts.append(dict(id=lid, fs=fs, frameMs=FRAME_MS, blockSize=n, stops=stops, cutoffsHz=cut))
            for c in curves:
                rec = dict(curve=c["name"], layout=lid, fs=fs, bandGainsDb={}, reconRmsDb={})
                for ear, key in (("L", "gLeft"), ("R", "gRight")):
                    g = np.array(c[key])
                    bg = band_gains_db(stops, fs, n, g)
                    r = rms_err(native_bin_gains(cut, bg, n, fs), n, fs, g)
                    rec["bandGainsDb"][ear] = bg
                    rec["reconRmsDb"][ear] = r
                    if lid == "main":
                        worst_main = max(worst_main, r)
                results.append(rec)
    assert worst_main <= 0.5, f"87-band reconstruction RMS {worst_main} exceeds 0.5 dB"

    # Independent OLA simulation cross-check (48 kHz, main layout, two curves).
    fs = 48000
    n = dp_block_size(fs, FRAME_MS)
    stops, cut = layout_main(fs, n)
    chk = np.array([f for f in GRID[::7] if 50.0 <= f <= 16000.0])
    worst_sim = 0.0
    for nm in ("extreme_bass_+12", "notch_4k:s0.5_flat", "sloping_hf_severe_censored:s0.5_flat"):
        g = np.array(next(c for c in curves if c["name"] == nm)["gLeft"])
        vec = native_bin_gains(cut, band_gains_db(stops, fs, n, g), n, fs)
        d = np.abs(simulate_db(vec, n, fs, chk) - reconstruct_db(vec, n, fs, chk))
        worst_sim = max(worst_sim, float(d.max()))
    print(f"  set6: worst 87-band recon RMS {worst_main:.4f} dB; max |OLA sim - recon| {worst_sim:.4f} dB")
    assert worst_sim <= 0.3, f"OLA simulation deviates {worst_sim} dB from linear-in-dB reconstruction"

    out = {
        "set": "dp_band_mapping", "formatVersion": 1, "generator": "tools/golden/sets/set_6_dpmap.py",
        "python": platform.python_version(), "numpy": np.__version__, "scipy": scipy.__version__,
        "tolerance": {"bandGainDb": 0.01, "reconRmsDb": 0.01, "reconRmsAcceptDb": 0.5},
        "gridHz": GRID.tolist(), "layouts": layouts, "curves": curves, "results": results,
    }
    common.write_json(core_root / "dsp/src/test/resources/golden/dp_band_mapping.json", out)
