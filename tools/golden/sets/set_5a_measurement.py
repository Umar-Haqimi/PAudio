"""Golden set 5a: measurement model (SPEC 2.1.3 steps 1-7) for :core:audiometry.

Writes core/audiometry/src/test/resources/golden/measurement_model.json. The reference implementation is
common.measurement_model (written from the SPEC text). Inputs are the 13 common threshold sets.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import common  # noqa: E402


def generate(core_root: Path) -> None:
    cases = []
    for tset in common.threshold_sets():
        m = common.measurement_model(tset)
        cases.append({
            "name": tset["name"],
            "lRef": tset["l_ref"],
            "freqsHz": common.GRID,
            "thresholds": tset["thresholds"],
            "expected": {
                "N": m["N"],
                "A": m["A"],
                "HL": m["HL"],
                "pta3": m["pta3"],
                "betterEarPta3": m["betterEarPta3"],
                "grade": m["grade"],
                "ehf": m["ehf"],
                "asymmetry": m["asymmetry"],
            },
        })
    obj = {
        "set": "measurement_model",
        "formatVersion": 1,
        "generator": "tools/golden/sets/set_5a_measurement.py",
        "numpy": np.__version__,
        "tolerance": {"dB": 0.01},
        "cases": cases,
    }
    common.write_json(core_root / "audiometry/src/test/resources/golden/measurement_model.json", obj)
