#!/usr/bin/env python3
"""Generate every golden vector set.

Usage (see docs/VECTORS.md):
    python tools/golden/gen_all.py --out core
    python tools/golden/gen_all.py --check [--out core] [--rtol 1e-9]   # regenerate to a temp dir and diff

Each module in tools/golden/sets/ named `set_*.py` must expose
    generate(core_root: pathlib.Path) -> None
and write its JSON under core_root/<module>/src/test/resources/golden/.
Sets are discovered automatically, so parallel contributors never edit this file.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import math
import shutil
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent


# Environment stamps written into every file; they legitimately differ between machines.
VOLATILE_KEYS = {"python", "numpy", "scipy"}
GOLDEN_GLOB = "*/src/test/resources/golden/*.json"


def _diff(a, b, path, rtol, atol, out):
    """Collect human-readable differences between two parsed JSON values (floats compared with tolerance)."""
    if isinstance(a, bool) or isinstance(b, bool) or a is None or b is None:
        if a != b:
            out.append(f"{path}: {a!r} != {b!r}")
    elif isinstance(a, (int, float)) and isinstance(b, (int, float)):
        if not math.isclose(a, b, rel_tol=rtol, abs_tol=atol):
            out.append(f"{path}: {a!r} != {b!r}")
    elif isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b)):
            if k in VOLATILE_KEYS and path == "$":
                continue
            if k not in a or k not in b:
                out.append(f"{path}.{k}: missing in {'committed' if k not in a else 'regenerated'}")
            else:
                _diff(a[k], b[k], f"{path}.{k}", rtol, atol, out)
    elif isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            out.append(f"{path}: length {len(a)} != {len(b)}")
        else:
            for i, (x, y) in enumerate(zip(a, b)):
                _diff(x, y, f"{path}[{i}]", rtol, atol, out)
    elif a != b:
        out.append(f"{path}: {a!r} != {b!r}")


def check(core_root: Path, mods, rtol: float, atol: float) -> int:
    """Regenerate into a temp dir and compare with the committed vectors under core_root."""
    tmp_root = Path(tempfile.mkdtemp(prefix="paudio-golden-"))
    # Set 4 reads the shipped clip LTAS from core_root.parent/audio/..., so mirror the repo layout: <tmp>/core + <tmp>/audio.
    tmp = tmp_root / "core"
    tmp.mkdir()
    (tmp_root / "audio").symlink_to(core_root.parent / "audio", target_is_directory=True)
    try:
        for path in mods:
            _run(path, tmp)
        fresh = {p.relative_to(tmp) for p in tmp.glob(GOLDEN_GLOB)}
        failures = 0
        for rel in sorted(fresh):
            committed = core_root / rel
            if not committed.exists():
                print(f"MISSING committed file: {rel}")
                failures += 1
                continue
            diffs: list = []
            _diff(json.loads(committed.read_text()), json.loads((tmp / rel).read_text()), "$", rtol, atol, diffs)
            if diffs:
                failures += 1
                print(f"MISMATCH {rel} ({len(diffs)} differences)")
                for d in diffs[:10]:
                    print(f"    {d}")
            else:
                print(f"ok       {rel}")
        if not mods or len(mods) == len(list((HERE / "sets").glob("set_*.py"))):
            # Full run only: a committed vector with no generator is stale.
            for p in sorted(core_root.glob(GOLDEN_GLOB)):
                if p.relative_to(core_root) not in fresh:
                    print(f"STALE committed file with no generator output: {p.relative_to(core_root)}")
                    failures += 1
        print("golden vectors match" if failures == 0 else f"{failures} file(s) differ")
        return 1 if failures else 0
    finally:
        shutil.rmtree(tmp_root, ignore_errors=True)


def _run(path: Path, core_root: Path) -> None:
    spec = importlib.util.spec_from_file_location(path.stem, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    print(f"== {path.stem}")
    mod.generate(core_root)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="core", help="path to the repo's core/ directory")
    ap.add_argument("--only", nargs="*", help="optional subset of set module names, e.g. set_1_rbj")
    ap.add_argument("--check", action="store_true",
                    help="regenerate to a temp dir and diff against the committed vectors; exit 1 on mismatch")
    ap.add_argument("--rtol", type=float, default=1e-9, help="--check: relative float tolerance")
    ap.add_argument("--atol", type=float, default=1e-12, help="--check: absolute float tolerance")
    args = ap.parse_args()
    core_root = Path(args.out).resolve()
    sys.path.insert(0, str(HERE))  # lets set modules `import common`
    mods = sorted((HERE / "sets").glob("set_*.py"))
    if args.only:
        mods = [m for m in mods if m.stem in set(args.only)]
    if not mods:
        print("no set modules found", file=sys.stderr)
        return 1
    if args.check:
        return check(core_root, mods, args.rtol, args.atol)
    for path in mods:
        _run(path, core_root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
