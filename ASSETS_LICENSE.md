# Third-party assets and licenses

## Demo audio: Nine Inch Nails (CC BY-NC-SA 3.0 US)

Files: `audio/playback/src/main/assets/demo/{discipline,echoplex,ghosts34}.flac`
(and the derived `*.ltas.json` spectrum files next to them).

| Clip file | Track | Album | Year | Source |
|---|---|---|---|---|
| `discipline.flac` | "Discipline" (track 4) | *The Slip* | 2008 | https://archive.org/details/nine_inch_nails_the_slip (file `04_discipline.flac`) |
| `echoplex.flac` | "Echoplex" (track 5) | *The Slip* | 2008 | https://archive.org/details/nine_inch_nails_the_slip (file `05_echoplex.flac`) |
| `ghosts34.flac` | "34 Ghosts IV" (track 34) | *Ghosts I–IV* | 2008 | https://archive.org/details/nineinchnails_ghosts_I_IV (file `34_Ghosts_IV.flac`) |

Artist: **Nine Inch Nails**. License: **Creative Commons Attribution-NonCommercial-ShareAlike 3.0 United States
(CC BY-NC-SA 3.0 US)**, https://creativecommons.org/licenses/by-nc-sa/3.0/us/
(legal code: https://creativecommons.org/licenses/by-nc-sa/3.0/us/legalcode).

**Modifications:** each clip is a ~30 s excerpt of the original track, trimmed to a loop on beat boundaries
(10 ms fades at the clip edges), resampled from 44.1 kHz to 48 kHz, loudness-normalized to -18 LUFS
integrated (true peak below -1 dBTP) by a constant gain, and re-encoded as 16-bit FLAC with triangular
dither. Reproduce with `tools/assets/fetch_demo_audio.sh`.

Suggested attribution string: Nine Inch Nails — "Discipline" / "Echoplex" (*The Slip*, 2008), "34 Ghosts IV"
(*Ghosts I–IV*, 2008), CC BY-NC-SA 3.0 US, https://creativecommons.org/licenses/by-nc-sa/3.0/us/ ;
modified: trimmed, loudness-normalized, resampled.

**NonCommercial restriction: PAudio must never be sold or monetized (no price, no ads, no in-app purchases,
no paid distribution) while these assets ship.** Remove the clips (and keep the pink-noise fallback) before any
commercial distribution. ShareAlike: adaptations of these clips are offered under the same license.

## Roboto Flex

`designsystem/src/main/res/font/roboto_flex.ttf` — SIL Open Font License 1.1.
License text: `designsystem/OFL-RobotoFlex.txt`. Copyright the Roboto Flex Project Authors.

## Material Symbols

Google Material Symbols icons (vendored as vector drawables) — Apache License 2.0,
https://github.com/google/material-design-icons (https://www.apache.org/licenses/LICENSE-2.0).
