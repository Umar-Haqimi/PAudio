#!/usr/bin/env python3
"""Long-term average spectrum (LTAS) per 1/3-octave band for the PAudio demo clips.

Band definition (NORMATIVE, shared with :core:dsp): centres f_b = 1000 * 2**(b/3), b = -16..13
(30 bands); edges f_b * 2**(+-1/6), i.e. [lo, hi). Welch PSD (scipy.signal.welch, mono = mean of
channels, nperseg 8192 at 48 kHz); PSD bins with lo <= f < hi are summed (times df). The 30 powers
are normalised to sum 1 (linear).

Usage: python3 ltas.py --demo-dir audio/playback/src/main/assets/demo
Writes <clip>.ltas.json for every <clip>.flac found there, plus pink.ltas.json (equal power per band).
Decoding uses ffmpeg (no soundfile dependency).
"""
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

import numpy as np
from scipy import signal

SAMPLE_RATE = 48000
NPERSEG = 8192
BANDS = list(range(-16, 14))
CENTERS = [1000.0 * 2.0 ** (b / 3.0) for b in BANDS]
CLIPS = ["discipline", "echoplex", "ghosts34"]


def decode_mono(path: Path) -> np.ndarray:
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-f", "f32le", "-acodec", "pcm_f32le",
         "-ac", "2", "-ar", str(SAMPLE_RATE), "-"],
        check=True, stdout=subprocess.PIPE).stdout
    x = np.frombuffer(raw, dtype="<f4").astype(np.float64).reshape(-1, 2)
    return x.mean(axis=1)


def band_power(mono: np.ndarray) -> list[float]:
    f, pxx = signal.welch(mono, fs=SAMPLE_RATE, nperseg=NPERSEG)
    df = f[1] - f[0]
    powers = []
    for fc in CENTERS:
        lo, hi = fc * 2.0 ** (-1 / 6), fc * 2.0 ** (1 / 6)
        sel = (f >= lo) & (f < hi)
        if not sel.any():
            raise SystemExit(f"band {fc:.1f} Hz has no PSD bins")
        powers.append(float(pxx[sel].sum() * df))
    total = sum(powers)
    return [p / total for p in powers]


def write(path: Path, clip: str, power: list[float]) -> None:
    obj = {"clip": clip, "sampleRate": SAMPLE_RATE, "bandCentersHz": CENTERS, "bandPower": power}
    path.write_text(json.dumps(obj, indent=1) + "\n")
    print(f"wrote {path}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--demo-dir", required=True, type=Path)
    args = ap.parse_args()
    d: Path = args.demo_dir
    for clip in CLIPS:
        flac = d / f"{clip}.flac"
        if not flac.exists():
            print(f"skip {clip}: {flac} missing")
            continue
        write(d / f"{clip}.ltas.json", clip, band_power(decode_mono(flac)))
    write(d / "pink.ltas.json", "pink", [1.0 / len(BANDS)] * len(BANDS))


if __name__ == "__main__":
    main()
