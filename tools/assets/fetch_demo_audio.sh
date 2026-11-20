#!/usr/bin/env bash
# Fetch the three CC BY-NC-SA 3.0 US Nine Inch Nails tracks from archive.org (docs/DECISIONS.md §12),
# trim each to its chosen loop, resample to 48 kHz (libsoxr), normalise to -18 LUFS integrated with
# true peak <= -1 dBTP, and encode FLAC 48 kHz / 16-bit with triangular dither.
# Then runs ltas.py to write the LTAS JSON files next to the clips.
#
# Requirements: bash, curl, ffmpeg (with libsoxr), python3 + numpy + scipy.
# Idempotent: downloads are cached (md5-verified) in tools/assets/.cache/ (gitignored); outputs are
# regenerated deterministically on every run.
#
# Loop-edge fades: the clips are cut mid-music on beat boundaries, so wrapping the 12 s loop would
# click. A 10 ms linear fade in/out is therefore applied at the clip edges (and only there).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
CACHE="$HERE/.cache"
OUT="$REPO/audio/playback/src/main/assets/demo"
TARGET_LUFS=-18
MAX_TP=-1.0
FADE=0.010
mkdir -p "$CACHE" "$OUT"

# name | url | md5 | in(s) | out(s)
CLIPS=(
  "discipline|https://archive.org/download/nine_inch_nails_the_slip/04_discipline.flac|db80e4cb05ebfc788473e4aca925c127|214.430|245.852"
  "echoplex|https://archive.org/download/nine_inch_nails_the_slip/05_echoplex.flac|75c98851b0a0d75b89c5277ec24537d1|144.120|175.123"
  "ghosts34|https://archive.org/download/nineinchnails_ghosts_I_IV/34_Ghosts_IV.flac|84e4d7effe88ae5db2ae12c0ccd3d8b2|43.110|74.411"
)

# Prints "I TP" (integrated LUFS, true peak dBTP) of an audio file.
measure() {
  ffmpeg -hide_banner -nostats -i "$1" -af ebur128=peak=true -f null - 2>&1 |
    awk '/Summary:/{s=1} s&&/^[ \t]*I:/{i=$2} s&&/Peak:/{p=$2} END{print i, p}'
}

for entry in "${CLIPS[@]}"; do
  IFS='|' read -r name url md5 tin tout <<<"$entry"
  src="$CACHE/$name.src.flac"
  if [[ ! -f "$src" ]] || [[ "$(md5sum "$src" | cut -d' ' -f1)" != "$md5" ]]; then
    echo "[$name] downloading $url"
    curl -fsSL --retry 5 --retry-delay 3 -o "$src.part" "$url"
    mv "$src.part" "$src"
  fi
  got="$(md5sum "$src" | cut -d' ' -f1)"
  [[ "$got" == "$md5" ]] || { echo "[$name] md5 mismatch: $got != $md5" >&2; exit 1; }

  len="$(awk -v a="$tin" -v b="$tout" 'BEGIN{printf "%.3f", b-a}')"
  fadeout_start="$(awk -v l="$len" -v f="$FADE" 'BEGIN{printf "%.3f", l-f}')"

  # Pass 1: trim + soxr resample to 48 kHz float stereo, no gain, to measure loudness.
  raw="$CACHE/$name.48k.wav"
  ffmpeg -hide_banner -loglevel error -y -ss "$tin" -t "$len" -i "$src" \
    -af "aresample=48000:resampler=soxr:precision=28" -ac 2 -c:a pcm_f32le "$raw"
  read -r lufs0 tp0 < <(measure "$raw")
  gain="$(awk -v t="$TARGET_LUFS" -v i="$lufs0" 'BEGIN{printf "%.2f", t-i}')"
  tp_pred="$(awk -v g="$gain" -v p="$tp0" 'BEGIN{printf "%.2f", p+g}')"
  echo "[$name] source clip: ${lufs0} LUFS, ${tp0} dBTP -> gain ${gain} dB (predicted TP ${tp_pred})"
  awk -v p="$tp_pred" -v m="$MAX_TP" 'BEGIN{exit !(p<=m)}' ||
    { echo "[$name] predicted true peak ${tp_pred} exceeds ${MAX_TP}; a limiter would be needed" >&2; exit 1; }

  # Pass 2: gain + edge fades + soxr/triangular-dither conversion to 16-bit FLAC.
  dst="$OUT/$name.flac"
  ffmpeg -hide_banner -loglevel error -y -i "$raw" \
    -af "volume=${gain}dB:precision=float,afade=t=in:st=0:d=${FADE},afade=t=out:st=${fadeout_start}:d=${FADE},aresample=48000:resampler=soxr:precision=28:dither_method=triangular" \
    -ac 2 -sample_fmt s16 -ar 48000 -c:a flac -compression_level 12 -map_metadata -1 -bitexact "$dst"

  read -r lufs1 tp1 < <(measure "$dst")
  size="$(stat -c %s "$dst")"
  echo "[$name] OUTPUT $dst: ${lufs1} LUFS, ${tp1} dBTP, ${size} bytes"
  awk -v i="$lufs1" -v t="$TARGET_LUFS" -v p="$tp1" -v m="$MAX_TP" \
    'BEGIN{d=i-t; if(d<0)d=-d; exit !(d<=0.3 && p<=m)}' ||
    { echo "[$name] verification failed (need -18 +/-0.3 LUFS and TP <= ${MAX_TP})" >&2; exit 1; }
  if (( size > 6000000 )); then echo "[$name] WARNING: file exceeds 6 MB" >&2; fi
done

python3 "$HERE/ltas.py" --demo-dir "$OUT"
echo "done."
