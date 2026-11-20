"""Golden set 5c: hearing-compensation curve, final G and preamp (SPEC 2.1.3, 7.1 #5 curve half).

Inputs: the 13 threshold sets of common.threshold_sets() pushed through common.measurement_model
(censored points already at their bound) -> HL_est per ear on the 9-point test grid. This module
then computes, written from the SPEC text with scipy's PchipInterpolator:
    g_h(f_k) = clamp(s * HL_est, -6, +12)  at the 9 test frequencies
    1-14 kHz: PCHIP in log2 f; < 1 kHz: g(1000) * w(f), w linear in log2 f from 1 at 1 kHz to 0 at 250 Hz;
    14-16 kHz: hold g(14000); 16-20 kHz: linear (log2 f) fade to 0 at 20 kHz
    G = clamp(g_h + g_p, -12, +15);  P = max(-15, -max(0, max G over f, ears) - 0.5)
with g_p the RBJ preference curve from set 1. Also exposes build_cases() for set 6.
"""
from __future__ import annotations

import importlib.util
import platform
from pathlib import Path

import numpy as np
import scipy
from scipy.interpolate import PchipInterpolator

import common

HERE = Path(__file__).resolve().parent


def _load(stem: str):
    spec = importlib.util.spec_from_file_location(stem, HERE / f"{stem}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


_rbj = _load("set_1_rbj")
GRID = np.array(_rbj.GRID_HZ)
F9 = np.array(common.GRID, dtype=float)


def gh_grid(hl9, s: float) -> np.ndarray:
    k = np.clip(s * np.asarray(hl9, dtype=float), -6.0, 12.0)
    l = np.log2(GRID)
    pch = PchipInterpolator(np.log2(F9), k)
    g = np.zeros_like(GRID)
    mid = (GRID >= 1000.0) & (GRID <= 14000.0)
    g[mid] = pch(l[mid])
    low = GRID < 1000.0
    w = np.clip((l[low] - np.log2(250.0)) / (np.log2(1000.0) - np.log2(250.0)), 0.0, 1.0)
    g[low] = k[0] * w
    hold = (GRID > 14000.0) & (GRID <= 16000.0)
    g[hold] = k[-1]
    fade = GRID > 16000.0
    t = np.clip((l[fade] - np.log2(16000.0)) / (np.log2(20000.0) - np.log2(16000.0)), 0.0, 1.0)
    g[fade] = k[-1] * (1.0 - t)
    return g


def curve(hl_left, hl_right, s, gb, gp, gt):
    gpref = _rbj.pref_db(gb, gp, gt, GRID)
    gh = [gh_grid(hl_left, s), gh_grid(hl_right, s)]
    g = [np.clip(x + gpref, -12.0, 15.0) for x in gh]
    peak = max(0.0, max(float(x.max()) for x in g))
    return gh, g, max(-15.0, -peak - 0.5)


def build_cases() -> list[dict]:
    sets = common.threshold_sets()
    extra = {"sloping_hf_mild", "notch_4k", "mixed_left_notch_right_slope"}
    cases = []
    for ts in sets:
        mm = common.measurement_model(ts)
        hl_l, hl_r = mm["HL"]["LEFT"], mm["HL"]["RIGHT"]
        variants = [(0.5, 0.0, 0.0, 0.0, "s0.5_flat")]
        if ts["name"] in extra:
            variants += [(1.0, 6.0, -3.0, 4.0, "s1.0_pref+6-3+4"), (0.0, -6.0, 6.0, -8.0, "s0.0_pref-6+6-8")]
        for s, gb, gp, gt, tag in variants:
            gh, g, p = curve(hl_l, hl_r, s, gb, gp, gt)
            cases.append(dict(
                name=f"{ts['name']}:{tag}", thresholdSet=ts["name"], strength=s, gB=gb, gP=gp, gT=gt,
                hlLeft=[float(x) for x in hl_l], hlRight=[float(x) for x in hl_r],
                ghLeft=[float(x) for x in gh[0]], ghRight=[float(x) for x in gh[1]],
                gLeft=[float(x) for x in g[0]], gRight=[float(x) for x in g[1]], preampDb=float(p)))
    return cases


def generate(core_root: Path) -> None:
    out = {
        "set": "hearing_curve", "formatVersion": 1, "generator": "tools/golden/sets/set_5c_curve.py",
        "python": platform.python_version(), "numpy": np.__version__, "scipy": scipy.__version__,
        "tolerance": {"gDb": 0.01, "preampDb": 0.01}, "gridHz": GRID.tolist(), "testFrequenciesHz": F9.tolist(),
        "cases": build_cases(),
    }
    common.write_json(core_root / "dsp/src/test/resources/golden/hearing_curve.json", out)
