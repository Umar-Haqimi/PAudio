"""Golden set 4: loudness predictor (SPEC 2.1.5, 7.1 #4).

Oracle: direct numpy evaluation of
    dL(G) = 10 log10( sum_b P_b |K(f_b)|^2 10^(Gbar_b/10) / sum_b P_b |K(f_b)|^2 )
with the band-averaging definition fixed by the M2 brief:
  * 1/3-octave centres f_b = 1000 * 2^(b/3), b = -16..13; edges f_b * 2^(+-1/6);
  * per ear, Gbar_b,ear = mean (in dB) of G over 1/24-octave grid points with lo <= f < hi
    (no points -> G at the centre, linear interpolation in log2 f);
  * Gbar_b = 10 log10( mean over the two ears of 10^(Gbar_b,ear / 10) ).
K comes from set 2 (freqz of the BS.1770 biquads), preference curves from set 1.

Cases may carry an optional "ltas" object {"clip", "sampleRate", "bandCentersHz", "bandPower"};
without it the weighting is pink (equal power per band). Per-clip LTAS cases are added later.
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


_rbj = _load("set_1_rbj")
_kw = _load("set_2_kweight")

GRID = np.array(_rbj.GRID_HZ)
BANDS = list(range(-16, 14))
CENTERS = np.array([1000.0 * 2.0 ** (b / 3.0) for b in BANDS])


def band_mean_db(g: np.ndarray) -> np.ndarray:
    out = []
    for fc in CENTERS:
        lo, hi = fc * 2.0 ** (-1.0 / 6.0), fc * 2.0 ** (1.0 / 6.0)
        m = (GRID >= lo) & (GRID < hi)
        if m.any():
            out.append(float(np.mean(g[m])))
        else:
            out.append(float(np.interp(np.log2(fc), np.log2(GRID), g)))
    return np.array(out)


def delta_l(g_left, g_right, power=None) -> float:
    p = np.full(len(CENTERS), 1.0 / len(CENTERS)) if power is None else np.asarray(power, dtype=float)
    k2 = 10.0 ** (_kw.k_db(CENTERS) / 10.0)
    gbar = 10.0 * np.log10(0.5 * (10.0 ** (band_mean_db(np.asarray(g_left)) / 10.0)
                                  + 10.0 ** (band_mean_db(np.asarray(g_right)) / 10.0)))
    w = p * k2
    return float(10.0 * np.log10(np.sum(w * 10.0 ** (gbar / 10.0)) / np.sum(w)))


def generate(core_root: Path) -> None:
    n = len(GRID)
    flat = lambda v: np.full(n, float(v))
    rng = np.random.default_rng(7)
    raw = rng.uniform(0.2, 1.0, len(CENTERS)) * (1.0 + np.cos(np.linspace(0, 3 * np.pi, len(CENTERS))))
    ltas_p = (raw / raw.sum()).tolist()
    ltas = dict(clip="synthetic_seed7", sampleRate=48000, bandCentersHz=CENTERS.tolist(), bandPower=ltas_p)

    specs = [("flat_zero", flat(0), flat(0), None), ("broadband_plus6", flat(6), flat(6), None),
             ("broadband_minus6", flat(-6), flat(-6), None)]
    for axis, args in [("bass", lambda v: (v, 0, 0)), ("presence", lambda v: (0, v, 0)), ("treble", lambda v: (0, 0, v))]:
        for v in (6, -6):
            g = _rbj.pref_db(*args(v), GRID)
            specs.append((f"{axis}_{v:+d}_pink", g, g, None))
    g_b6 = _rbj.pref_db(6, 0, 0, GRID)
    g_bm6 = _rbj.pref_db(-6, 0, 0, GRID)
    g_t6 = _rbj.pref_db(0, 0, 6, GRID)
    specs.append(("asymmetric_bass_pm6_pink", g_b6, g_bm6, None))
    specs.append(("asymmetric_mixed_pink", g_b6 + flat(2), g_t6 - flat(3), None))
    specs.append(("broadband_plus6_ltas", flat(6), flat(6), ltas))
    specs.append(("bass_plus6_ltas", g_b6, g_b6, ltas))
    specs.append(("treble_minus6_ltas", _rbj.pref_db(0, 0, -6, GRID), _rbj.pref_db(0, 0, -6, GRID), ltas))
    specs.append(("asymmetric_mixed_ltas", g_b6 + flat(2), g_t6 - flat(3), ltas))

    cases = []
    for name, gl, gr, lt in specs:
        c = dict(name=name, weighting="ltas" if lt else "pink",
                 gLeft=[float(x) for x in gl], gRight=[float(x) for x in gr],
                 expectedDb=delta_l(gl, gr, lt["bandPower"] if lt else None))
        if lt:
            c["ltas"] = lt
        cases.append(c)
    out = {
        "set": "loudness_predictor", "formatVersion": 1, "generator": "tools/golden/sets/set_4_loudness.py",
        "python": platform.python_version(), "numpy": np.__version__, "scipy": scipy.__version__,
        "tolerance": {"deltaLDb": 0.01}, "gridHz": GRID.tolist(), "bandCentersHz": CENTERS.tolist(),
        "cases": cases,
    }
    common.write_json(core_root / "dsp/src/test/resources/golden/loudness_predictor.json", out)
