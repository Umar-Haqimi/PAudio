#!/usr/bin/env python3
"""Generate every golden vector set.

Usage (see docs/VECTORS.md):
    python tools/golden/gen_all.py --out core

Each module in tools/golden/sets/ named `set_*.py` must expose
    generate(core_root: pathlib.Path) -> None
and write its JSON under core_root/<module>/src/test/resources/golden/.
Sets are discovered automatically, so parallel contributors never edit this file.
"""
from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="core", help="path to the repo's core/ directory")
    ap.add_argument("--only", nargs="*", help="optional subset of set module names, e.g. set_1_rbj")
    args = ap.parse_args()
    core_root = Path(args.out).resolve()
    sys.path.insert(0, str(HERE))  # lets set modules `import common`
    mods = sorted((HERE / "sets").glob("set_*.py"))
    if args.only:
        mods = [m for m in mods if m.stem in set(args.only)]
    if not mods:
        print("no set modules found", file=sys.stderr)
        return 1
    for path in mods:
        spec = importlib.util.spec_from_file_location(path.stem, path)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        print(f"== {path.stem}")
        mod.generate(core_root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
