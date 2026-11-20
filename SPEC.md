# SPEC — "PAudio": personalized earphone sound for Android, tuned to your hearing and your taste

> **Working name:** PAudio (rename freely). Package: `my.umarh.paudio`.
> This document is the **single source of truth** for an autonomous build by Claude Code. Build it end to end without coming back to ask questions; where something is genuinely undecidable, pick the default in §10 and record it.
> **Precedence rule:** when this spec and an upstream source (AOSP source, official API reference, library source) disagree, **upstream source code wins**. Record every discrepancy in `docs/DECISIONS.md` with a link and file/line reference.

---

## 1. What we're building

PAudio measures how each of the user's ears hears 1–14 kHz through *their own earphones*, asks a short series of blind A/B questions about tonal taste, and turns both into a per-ear EQ curve. That curve is applied **system-wide** to music apps through Android's `DynamicsProcessing` audio effect. Results are stored as profiles (person × headset) that switch automatically when a headset connects. Inspired by Sonarworks SoundID; fully offline and open source.

Main capabilities:
1. **Hearing test**: per-ear threshold estimation at 9 frequencies, in two modes: **Quick** (yes/no responses, adaptive levels) and **Thorough** (user adjusts the level themselves).
2. **Results and demo**: a "hearing butterfly" visual, a loudness-matched A/B of personalized vs original audio (bundled CC-licensed song or the user's own file), and a live EQ graph.
3. **Hearing health view**: an audiogram-style chart with WHO 2021 grade bands, per-ear summaries, a trend over time, and a **non-dismissable "not a medical diagnosis" disclaimer**.
4. **A/B preference test**: about 9 blind, loudness-matched comparisons that bisect three tonal axes (bass, presence, treble).
5. **Profiles**: listener × headset, auto-switching on headset connect, export/import as JSON.
6. **System-wide engine**: a foreground service that attaches per-ear EQ, preamp and limiter to other apps' audio sessions, with a "legacy" global-mix fallback.

Platform: **Android 9+ (API 28+), Kotlin, Jetpack Compose with Material 3 Expressive.**

**Interop requirement:** Profile JSON (schema v1, §5.6) must round-trip. Export → import on a fresh install must reproduce an identical profile, including raw thresholds, trial logs and the same computed curve to within 0.01 dB.

**Non-goals (v1)**, with clean extension points left for some:
- No absolute (calibrated) audiometry or medical claims. Results are *estimates*.
- No root / Magisk module, and no AudioPlaybackCapture-based processing (RootlessJamesDSP-style). Leave an `EffectBackend` interface so one could be added.
- No `DUMP`-based enhanced session detection in MVP (it's N1; keep the `SessionSource` interface ready).
- No headphone frequency-response database (AutoEQ). Interface hook only (N6).
- No in-app music player beyond demo/A-B playback and a single picked file.
- No network access at all: **the app must not declare `INTERNET`.**
- No iOS, Wear OS or TV.

---

## 2. Critical technical facts (read before designing)

### 2.1 How the underlying things work

#### 2.1.1 System-wide EQ on Android (the hard part)
Read these before writing any engine code:
- `AudioEffect` reference: https://developer.android.com/reference/android/media/audiofx/AudioEffect
- `DynamicsProcessing` reference and nested classes: https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing
- AOSP implementation: `frameworks/av/media/libeffects/dynamicsproc/` (especially `dsp/DPFrequency.cpp` and `EffectDynamicsProcessing.cpp`) on https://cs.android.com
- Background on why EQ apps are inconsistent (Esper): https://www.esper.io/blog/android-equalizer-apps-inconsistent
- Reference OSS implementations (GPL-3.0; **study them, and port code only if PAudio stays GPL-3.0**, see §10): Equalizer314 https://github.com/bearinmindcat/Equalizer314 (DynamicsProcessing on session 0) and RootlessJamesDSP https://github.com/ThePBone/RootlessJamesDSP (DUMP and Shizuku session handling).

Facts the design depends on:
- An app can attach an effect to **another app's audio session** if it knows the session ID. Players announce sessions by broadcasting `AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` / `ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION` with extras `EXTRA_AUDIO_SESSION` and `EXTRA_PACKAGE_NAME`. Players that do this include YouTube Music, Spotify, Plexamp, Pocket Casts, YouTube, Qobuz and most preinstalled players (per Wavelet's compatibility list: https://pittvandewitt.github.io/Wavelet/Settings).
- These broadcasts are **implicit**, so a manifest receiver won't get them on API 26+. EQ apps use a **context-registered receiver inside an always-running service**. PAudio does the same, using a foreground service.
- **Session 0** (global output mix) still works on many devices but is officially **deprecated** for insert effects and requires `MODIFY_AUDIO_SETTINGS`. Whether it works depends on the OEM's audio framework, which is why Wavelet keeps it as an opt-in "Legacy mode". PAudio does the same.
- `DynamicsProcessing` (API 28) chain: inputGain → **PreEQ** → MBC → PostEQ → **Limiter**, with **per-channel configuration** (`setPreEqByChannelIndex`, `setInputGainByChannelIndex`, `setLimiterByChannelIndex`). That's why it's the only built-in effect that can do **different EQ per ear**. `Config.Builder(variant, channelCount, preEqInUse, preEqBandCount, mbcInUse, mbcBandCount, postEqInUse, postEqBandCount, limiterInUse)`.
- **`EqBand(enabled, cutoffFrequency, gain)`: `cutoffFrequency` is the band's *topmost* frequency.** Each band covers (previous cutoff, this cutoff], and band 0 starts at 0 Hz. Bands are not parametric filters: a smooth curve must be *sampled* across many bands (§2.1.4).
- Effect control is priority-based. A higher-priority `AudioEffect` on the same engine takes control away, and `setControlStatusListener` / `hasControl()` report the change. Other EQ apps (Wavelet, Poweramp EQ, Equalizer314, RootlessJamesDSP) compete for the same engines.

#### 2.1.2 Audiometry, adapted to uncalibrated consumer hardware
- **Modified Hughson-Westlake** (the ASHA-recommended manual procedure): after a "heard" response go **down 10 dB**; after "not heard" go **up 5 dB**. Threshold = the lowest level heard on **≥2 of 3 ascending presentations**. References: AAFP overview https://www.aafp.org/afp/2013/0101/p41 and the Grason-Stadler guide https://grason-stadler.com/education/guides/pure-tone-audiometry
- **Warble (FM) tones** reduce standing-wave artefacts. Clinical deviations range from about ±3% to ±10% at around 8 Hz modulation, and ±3% vs ±10% changes thresholds by only ≈2 dB. PAudio uses **±5% sinusoidal FM at 8 Hz for f ≥ 4 kHz** and pure tones below.
- **Reference threshold shape.** Normal hearing thresholds vary with frequency. PAudio uses the **ISO 389-7 diffuse-field reference threshold** R_df(f) (dB SPL), as reproduced in USAARL *Helmet-Mounted Displays*, ch. 11, Table 11-1 (https://usaarl.health.mil/assets/docs/hmds/Section-19-Chapter-11-Auditory-Perception-and-Cognitive-Performance.pdf), from ISO 389-7:2005:

  | f (Hz) | 1000 | 1250 | 1500 | 1600 | 2000 | 2500 | 3000 | 3150 | 4000 | 5000 | 6000 | 6300 | 8000 | 9000 | 10000 | 11200 | 12500 | 14000 | 16000 |
  |---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
  | R_df | 0.8 | 1.9 | 1.0 | 0.5 | −1.5 | −3.1 | −4.0 | −4.0 | −3.8 | −1.8 | 1.4 | 2.5 | 6.8 | 8.4 | 9.8 | 11.5 | 14.4 | 23.2 | 43.7 |

  Rationale: earphones are generally tuned toward diffuse-field-derived targets, so with a "neutral" earphone a normal ear's thresholds in dBFS follow R_df's shape plus a constant. ISO 389-7:2019 is the current edition and may differ slightly. Record the source used.
- **WHO 2021 grades** (better-ear PTA, dB HL): Normal <20 · Mild 20–<35 · Moderate 35–<50 · Moderately severe 50–<65 · Severe 65–<80 · Profound 80–<95 · Complete ≥95. WHO's PTA uses 0.5/1/2/4 kHz, and PAudio doesn't test 0.5 kHz, so its grade is labelled **"approximate"**.

#### 2.1.3 PAudio's measurement model (normative; implement exactly)
Notation:
- Test grid **F = {1000, 2000, 3000, 4000, 6000, 8000, 10000, 12500, 14000} Hz**. Interpolate R_df at grid points not in the table (none, currently) linearly in log2(f).
- **Tone level L (dBFS) = 20·log10(peak amplitude)** of float samples (full scale = 1.0). Use this convention everywhere.
- **Calibration** (once per session, §4 flow 3) yields `L_ref` = the 1 kHz binaural threshold estimate (dBFS) at a locked `STREAM_MUSIC` index.
- Expected level for frequency f: `L_exp(f) = L_ref + (R_df(f) − R_df(1000))`.
- Level bounds per f: `L_max(f) = min(−3, L_exp(f) + 50)` and `L_min(f) = L_exp(f) − 25`. **Never present above L_max(f).** A threshold that isn't reached at L_max is **censored** (stored as "≥ L_max").
- Expect censoring at 12.5–14 kHz: with `L_ref` ≈ −60 dBFS, the −3 dBFS ceiling leaves only ~35–40 dB of range above normal there. The UI labels these points "beyond test range", never "no hearing".

Estimated hearing level (relative; **not** calibrated dB HL):
1. Raw threshold `T(f, ear)` in dBFS.
2. Normalized: `N(f, ear) = T(f, ear) − R_df(f)`.
3. Anchor: `A = min over ears of mean(N(1000, ear), N(2000, ear))`. This assumes the better ear hears normally at 1–2 kHz.
4. `HL_est(f, ear) = N(f, ear) − A`. Display range −10…100. Censored points are plotted at their bound with a ↓ marker and use the bound in calculations.
5. `PTA3(ear) = mean HL_est at {1k, 2k, 4k}`. Approximate grade = WHO band of the better ear's PTA3.
6. `EHF(ear) = mean HL_est at {10k, 12.5k, 14k}` (extended high-frequency average). Show the number, but no WHO grade.
7. **Asymmetry flag:** `|HL_L − HL_R| ≥ 15 dB` at **≥2 adjacent** grid frequencies → gentle "consider seeing an audiologist" copy.
8. **Reliability:**
   - Quick mode: catch-trial false-positive rate > 20% → test marked **unreliable**, with an offer to redo.
   - Thorough mode: a point where |descending − ascending| > 15 dB after one repeat → that point is low-confidence.

Hearing compensation curve, per ear:
- `g_h(f_k) = clamp(s · HL_est(f_k, ear), −6, +12)` dB, where `s` = compensation strength (default **0.5**, range 0–1; half-gain-style partial compensation).
- Evaluate on a **1/24-octave grid from 20 Hz to 20 kHz** using **PCHIP** (monotone cubic) interpolation in log2(f).
  - Below 1 kHz: `g(f) = g(1000) · w(f)`, with w linear in log2(f) from 1 at 1000 Hz to 0 at 250 Hz.
  - 14–16 kHz: hold g(14000).
  - 16–20 kHz: fade linearly in log2(f) to 0.

Preference curve (shared by both ears): the sum of RBJ-cookbook magnitude responses evaluated analytically at fs = 48 kHz (https://www.w3.org/TR/audio-eq-cookbook/):
- Bass: low-shelf, fc 105 Hz, Q 0.707, gain gB
- Presence: peaking, fc 3000 Hz, Q 0.8, gain gP
- Treble: high-shelf, fc 8000 Hz, Q 0.707, gain gT

Total curve: `G(f, ear) = clamp(g_h(f, ear) + g_p(f), −12, +15)`.

Gain staging:
- **Preamp:** `P = −max(0, max over f and ears of G) − 0.5 dB`, floored at −15 dB. Apply via inputGain, the same on both channels.
- **Limiter** always on: threshold −1 dBFS, ratio ≥ 10:1, attack 1 ms, release 50 ms, channels linked (verify the parameter semantics in M0).

#### 2.1.4 Mapping G(f) onto DynamicsProcessing bands
- Default: **40 PreEQ bands** with cutoffs `c_k = 20·2^((k+1)/4)` Hz for k = 0…38, and `c_39 = sampleRate/2`.
- Band gain = mean of G over (c_{k−1}, c_k] in log-frequency, sampled on the 1/24-octave grid.
- The band count, the semantics above the last cutoff, and any interpolation between band gains **must be confirmed from `DPFrequency.cpp` in M0**; adjust the mapper to match.
- Acceptance test: the reconstructed response (per confirmed DP semantics) vs target G must have **RMS error ≤ 0.5 dB over 50 Hz–16 kHz** for all golden curves.

#### 2.1.5 Loudness matching for every A/B
A louder option wins A/B tests regardless of tone, so every comparison is loudness-matched with a *predictive* model. No live metering is needed.
- `ΔL(G) = 10·log10( Σ_b P_b·|K(f_b)|²·10^(Ḡ_b/10) / Σ_b P_b·|K(f_b)|² )` over 1/3-octave bands b, where:
  - `P_b` is the clip's long-term band power (precomputed LTAS JSON; pink noise is used for user files: equal power per 1/3-octave band)
  - `K` is the **ITU-R BS.1770-5 K-weighting** magnitude (pre-filter + RLB biquads, Annex 1 coefficients at 48 kHz: https://www.itu.int/rec/R-REC-BS.1770/en)
  - `Ḡ_b` is the mean of G in band b, power-averaged across the two ears
- Apply `−ΔL` (in addition to the preamp) via inputGain **only for in-app A/B playback**. The system-wide engine uses the preamp only.

#### 2.1.6 Demo audio (CC-licensed)
Nine Inch Nails' *The Slip* and *Ghosts I–IV* (2008) are licensed **CC BY-NC-SA 3.0 US**. Mirrors: https://archive.org/details/nine_inch_nails_the_slip and https://archive.org/details/nineinchnails_ghosts_I_IV
Clips:
- **"Discipline"** (vocals and cymbals; hearing-result A/B and the presence axis)
- **"Echoplex"** (electronic, bass-heavy; bass axis)
- **"34 Ghosts IV"** (acoustic and piano; treble axis)

Each clip is ~30 s, loudness-normalized to −18 LUFS integrated with −1 dBTP, stored as **FLAC 48 kHz/16-bit**, with an attribution screen and `ASSETS_LICENSE.md`. NC means **PAudio must never be sold or monetized while these assets ship**.

**Things the spec leaves out** (extract from upstream and record in `docs/DECISIONS.md` with file/line refs):
- DP: how `DPFrequency.cpp` applies EqBand gains (brick-wall vs interpolated), FFT/frame size and latency per `VARIANT_*`, the max band count, behavior above the last cutoff, and **channel index ↔ left/right** mapping.
- DP: Limiter constructor parameter units and semantics. Whether `setPreEqByChannelIndex` at runtime is glitch-free or needs a duck. The `priority` value PAudio uses, and what happens on control loss.
- `AudioManager.getStreamVolumeDb(stream, index, deviceType)` (API 28): its reliability for computing volume-index changes during calibration.
- Safe-media-volume / CSD behavior when calling `setStreamVolume` above the safe index on headphones.
- How to read the **Mono audio** and **audio balance** settings (`Settings.System` keys `master_mono` / `master_balance`, both hidden; verify readability), `Spatializer.isEnabled()` / `canBeSpatialized()`, and the current DND state (`NotificationManager.getCurrentInterruptionFilter()`) without extra permissions.
- `AudioDeviceInfo.getAddress()` format for Bluetooth **without** `BLUETOOTH_CONNECT` (AOSP anonymizes it to `XX:XX:XX:XX:AB:CD`).
- Foreground service type `specialUse` manifest declaration (`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`) and whether a `BOOT_COMPLETED` start of `specialUse` is allowed on API 35–37.
- Exact Compose API names in material3 1.5.0 and foundation 1.13: the wavy progress indicator name (`LinearWavyProgressIndicator` vs `WavyLinearProgressIndicator`), `MaterialShapes` (still experimental), the expressive theme entry point, `MaterialTheme.motionScheme`, and `MorphPolygonShape`.
- Whether Material Symbols `volume_off`, `graphic_eq_off` and `do_not_disturb_on_total_silence` exist. Fallback for a missing one: `noise_control_off`.
- Exact archive.org file URLs, the license text shipped with each NIN release, and clip in/out timestamps.
- Pinned versions: AGP / Gradle / Kotlin (≥ 2.2) compatible with compileSdk 37.1, Compose BOM 2026.11.01, Media3, Room, Hilt, MaterialKolor.

### 2.2 ⚠️ Compatibility traps

1. **P0: Self-coloring / double processing.**
   - *Wrong:* leave the system-wide effect running while PAudio plays test tones or A/B audio. In Legacy (session 0) mode the global effect processes PAudio's *own* output, so thresholds measure the current EQ and the A/B compares "EQ + EQ" against "EQ".
   - *Right:* the engine enters **`Bypassed`** (all DP instances `setEnabled(false)`) for the whole duration of calibration, testing, results A/B and preference A/B, and restores afterwards (including after crashes and process death: restore on service start). In session mode, ignore any OPEN broadcast whose `EXTRA_PACKAGE_NAME` is PAudio's own package. In-app A/B playback uses its own DP on its own session.
   - **P0 test case:** a fake engine asserts no enabled global/foreign DP exists while `ToneOutput` or the A/B player is active.
2. **Treating raw thresholds as dB HL.** Phone + earphone + volume curve are uncalibrated, so absolute dB HL is impossible. Use §2.1.3 (reference shape + anchor), label everything "estimated", and tell users that flat loss across all frequencies can't be detected.
3. **1:1 compensation.** Boosting by the full threshold shift sounds shrill and clips (above-threshold loudness isn't shifted equally). Use partial compensation `s`, the caps, preamp and limiter (§2.1.3).
4. **Clicks from un-ramped tones.** An abrupt onset is broadband, so a user can "hear" a 14 kHz tone through the click. Every pulse must have **25 ms raised-cosine on/off ramps**. Spectral splatter test in §7.
5. **Creating an `AudioTrack` per beep.** Bluetooth sinks mute or fade the first ~100–500 ms of a new stream and renegotiate routes, so first pulses are lost and thresholds come out biased high. Use **one continuous float `AudioTrack` stream for the whole session**, writing digital silence between stimuli, opened before calibration and closed after the last trial.
6. **Pure tones at high frequencies.** Ear-canal standing waves create fake notches. Use warble tones at f ≥ 4 kHz (§2.1.2).
7. **Misreading `EqBand.cutoffFrequency`** as a center frequency, or using a handful of bands as if they were parametric filters, produces the wrong curve. It is the *top edge*. Sample finely (§2.1.4).
8. **Unmatched loudness in A/B** means "louder" always wins. Use the §2.1.5 predictor on every comparison, including personalized vs original.
9. **Manifest-registered session receiver.** On API 26+ it never fires for these implicit broadcasts. Register it in the running FGS with **`RECEIVER_EXPORTED`** (required on API 34+; the broadcasts come from other apps). Validate extras (session > 0, package non-null) and cap live sessions at 16.
10. **`mediaPlayback` FGS started from `BOOT_COMPLETED`** throws `ForegroundServiceStartNotAllowedException` on Android 15+ (https://developer.android.com/about/versions/15/behavior-changes-15). Use the **`specialUse`** type (verify in M0). If boot starts aren't allowed, post a "Tap to start PAudio" notification instead.
11. **Wrong-ear data.** Swapped earbuds, Accessibility *Mono audio*, a shifted *balance* slider, Spatial Audio, or a single-bud mono mode make per-ear results meaningless. Required checks before testing:
    - a **left/right channel check** ("Which ear did you hear that in?")
    - mono/balance/spatializer detection, with blocking warnings
12. **Volume drift mid-test.** Volume keys, AVRCP absolute volume from the headset, and **CSD sound-dose auto-lowering** (Android 14+) can all change the volume. Observe `STREAM_MUSIC` (content observer plus a check before each trial) and **pause the test** on any change. Safe-media-volume may refuse `setStreamVolume`; read the index back and ask the user to adjust with the keys.
13. **Perceptual Bluetooth codecs and very low digital levels.** Encoders may discard near-silent HF content, so the tone wouldn't reach the ear. Keep tone levels high in the digital domain by lowering the *system* volume during calibration (target window in §4 flow 3). Note it in `HARDWARE_TEST.md`.
14. **Legacy session 0 and per-session mode at the same time** EQs everything twice. The two modes are **mutually exclusive**.
15. **Silent loss of control** to another EQ app looks like "PAudio does nothing". Surface it via `setControlStatusListener` as a `Conflict` state with clear copy (§4.8).
16. **Requesting `BLUETOOTH_CONNECT` just to identify headsets** is an unnecessary scary permission. Key headsets by type + `productName` + the anonymized address suffix (§5.5).
17. **Porting GPL code into a non-GPL repo** is a license violation. If any code from Equalizer314 or RootlessJamesDSP is ported, PAudio must be GPL-3.0-or-later (the default in §10). Note ported files in `docs/DECISIONS.md`.
18. **Comparing tests across headsets in the trend view** is meaningless (the earphone response is baked in). Trends compare tests from the **same profile and the same headset** only.
19. **Experimental M3 APIs** (`MaterialShapes`) may change. Wrap them in `:designsystem`; no direct use in feature modules.
20. **UI sounds, TalkBack speech or haptics during a stimulus** contaminate the test. Suppress UI sound effects on test screens, never announce during stimulus playback (announce results only after the response), and fire haptics only after a response.

### 2.3 Libraries

| Concern | Choice | License | Verify early | Fallback |
|---|---|---|---|---|
| UI | Compose BOM **2026.11.01** (Compose 1.13; needs Kotlin ≥ 2.2, compileSdk 37.1) https://developer.android.com/blog/posts/jetpack-compose-november-2026-release | Apache-2.0 | It builds with the chosen AGP | Previous BOM; record why |
| Material | `androidx.compose.material3:material3` **1.5.0** (stable expressive components; `MaterialShapes` still experimental) https://developer.android.com/jetpack/androidx/releases/compose-material3 | Apache-2.0 | Wavy progress API name and params (`amplitude`, `wavelength`, `waveSpeed`); MaterialShapes `Cookie9Sided`, `Sunny`; `toShape()` / Morph | `androidx.graphics:graphics-shapes` directly |
| Seed color schemes | MaterialKolor https://github.com/jordond/MaterialKolor (`DynamicMaterialTheme`, expressive spec support) | MIT (+ Apache-2.0 MCU) | Latest stable version; expressive spec flag | `material-color-utilities` directly |
| Playback (demo / A/B / user file) | AndroidX Media3 ExoPlayer, latest stable | Apache-2.0 | Setting a known `audioSessionId`; FLAC from assets; seamless looping of a clipped region (`ClippingMediaSource`) | `MediaPlayer` + manual loop |
| Effect | Platform `DynamicsProcessing` (API 28) | Platform | §2.1.4 items | none (core requirement) |
| Tone output | Platform `AudioTrack` (float PCM, stereo, streaming) | Platform | Float support on min devices; native output rate via `AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE` | 16-bit PCM with TPDF dither |
| Persistence | Room (with KSP), DataStore Preferences | Apache-2.0 | Schema export on; migration tests | — |
| Serialization | kotlinx.serialization JSON | Apache-2.0 | — | — |
| DI | Hilt | Apache-2.0 | KSP compatibility with the Kotlin version | Manual DI container |
| Charts | **Custom Compose `Canvas`** (audiogram, EQ graph, trend, butterfly) | — | — | — |
| Font | Roboto Flex (variable), **bundled** in `res/font` (no downloadable fonts; no GMS dependency) | OFL-1.1 | File size | Roboto |
| Icons | Material Symbols, only the needed glyphs imported as vector drawables | Apache-2.0 | Glyph availability (§2.1 list) | Material Icons |
| Tests | JUnit4, Truth, Turbine, kotlinx-coroutines-test, Robolectric (where needed), Compose UI test | Apache-2.0 / EPL | — | — |
| Golden oracles | Python 3 + numpy + scipy (dev-only, `tools/golden/`) | BSD | — | — |

Record every final version and any library swap in `docs/DECISIONS.md` and `gradle/libs.versions.toml`.

---

## 3. Features

### MVP (must ship)

| # | Feature |
|---|---|
| F1 | First-run onboarding: what PAudio does, a **"not a medical device"** disclaimer (must tap "I understand"), the notification permission, and starting the system-wide service. |
| F2 | Pre-test page with three instruction rows, each with its icon on the right: *Go to a quiet area* (`volume_off`), *Turn on noise cancellation if your earphones support it* (`graphic_eq_off`), *Turn on Do Not Disturb* (`do_not_disturb_on_total_silence`). Plus: live DND status, an optional mic noise meter (Quiet / OK / Too noisy), blocking warnings for mono/balance/spatial audio, an "ANC on" checkbox stored with the test, and a tip to disable other EQ / Dolby / adaptive-sound features. |
| F3 | Left/right channel check before calibration. A wrong answer → "Your earbuds may be swapped or in mono mode", then retry. |
| F4 | Calibration ramp (1 kHz, binaural, ascending) → `L_ref`, auto-adjusting the system volume into the target window and locking it. Runs once per session and is reused within 30 min for the same headset and index. |
| F5 | **Quick** hearing test: yes/no per stimulus, interleaved modified Hughson-Westlake staircases for 9 frequencies × 2 ears, ears alternating every trial, ~1 in 8 catch trials. |
| F6 | **Thorough** hearing test: user-adjusted level (no numbers, randomized control offset), a descending and an ascending run per point, ears alternating after every frequency. |
| F7 | Test progress as a wavy progress indicator whose **wavelength tracks the current test frequency** (long at 1 kHz → short at 14 kHz). |
| F8 | Pause/resume on headset disconnect, volume change, audio-focus loss (calls) or app backgrounding, with clear copy. Resume restarts the interrupted staircase only. |
| F9 | Results: animated **hearing butterfly**, reliability notes, and an asymmetry note when flagged. |
| F10 | Results A/B: *Original* vs *Personalized*, loudness-matched, instant switching on the same playhead, using the bundled "Discipline" clip or **the user's own file** (SAF picker, no storage permission). |
| F11 | Live EQ graph (both ears, 20 Hz–20 kHz, ±15 dB): shows G(f) when Personalized and animates to flat when Original. |
| F12 | On leaving Results: dialog "Fine-tune to your taste next? (A/B preference test, ~2 min)" → F14 or later. |
| F13 | **Hearing health** screen (a button on Results, Home and Profile): audiogram (right ear ○ red, left ear ✕ blue), WHO 2021 bands, per-ear PTA3 with approximate grade, EHF average, asymmetry note, a **trend chart** (PTA3 per ear across tests of the same profile and headset, shown when ≥2 tests exist), "How to read this", and a **persistent, non-dismissable disclaimer**. |
| F14 | **A/B preference test**: 9 blind comparisons bisecting bass/presence/treble (§4 flow 5), loudness-matched, with "Can't tell" and "Try another song". Hearing compensation is active as the baseline. |
| F15 | Fine-tune screen: compensation strength slider plus bass/presence/treble sliders, with the live EQ graph and the Original/Personalized toggle. |
| F16 | Profiles: listener × headset. Create, rename, delete, duplicate, and move a profile to another headset (offering a retest). Plus a generic "Any headphones" profile per listener. |
| F17 | Auto-switch the active profile when the output device changes. An unknown headset triggers a notification with "Create profile" / "Use <profile>". |
| F18 | Export/import a profile as JSON (schema v1), via SAF create/open document. |
| F19 | System-wide engine: an FGS (`specialUse`) running the session receiver, with per-session DP (per-ear PreEQ + preamp + limiter). |
| F20 | Legacy (global session 0) mode toggle, mutually exclusive with session mode, with an explanation. |
| F21 | Engine state surfaced on Home and in the notification: Active (list of apps) / Waiting for a player / Legacy / Bypassed (testing) / Conflict (another EQ app) / No profile for this headset / Speaker output (not processed) / Error. |
| F22 | Persistent notification with actions: Personalized ⇄ Original, Open app, Turn off. |
| F23 | Start on boot (opt-in), subject to the §2.2 #10 rules. |
| F24 | Settings and About: system-wide mode, start on boot, compensation defaults, attributions (NIN CC BY-NC-SA, Roboto Flex OFL, Material Symbols), open-source licenses, and the disclaimer text. |
| F25 | Dynamic theming: each profile has a seed color derived from its hearing curve (user-overridable) that themes the app via MaterialKolor. Falls back to wallpaper dynamic color, then a static brand scheme. |

### Nice-to-have (after MVP, in this order)
- **N1** Enhanced session detection via the `DUMP` permission (granted with `adb shell pm grant my.umarh.paudio android.permission.DUMP`): parse `dumpsys` audio output to find sessions of players that don't broadcast. In-app instructions with a copy button.
- **N2** Quick Settings tile: Personalized ⇄ Original.
- **N3** Bahasa Melayu localization (`values-ms`).
- **N4** Profile sharing via QR code (compressed JSON without trial logs).
- **N5** Shizuku-assisted `DUMP` grant.
- **N6** Optional headphone-response import (AutoEQ-format CSV via SAF) to separate earphone deviation from hearing.
- **N7** Optional age/sex-adjusted anchor (ISO 7029 medians) instead of the 0 dB anchor.
- **N8** Retest reminder (every 6 months, opt-in), and a home-screen widget.

---

## 4. UX / Screens

**Style:** Material 3 **Expressive** 🎧. Springy `MaterialTheme.motionScheme` motion, MaterialShapes morphs, big display type in Roboto Flex, edge-to-edge (required at targetSdk ≥ 35), full light/dark support, profile-seeded dynamic color (F25). Shape and color are never the only carrier of meaning: the audiogram uses ○/✕ markers plus color, and states carry text.

Signature elements (implement in `:designsystem`):
- **Morphing hear button:** a large `Cookie9Sided` button that softly pulses while a stimulus plays and springs into `Sunny` on press (Morph progress driven by a spring).
- **Frequency-wave progress:** wavy linear progress where `wavelength = lerp(48.dp, 10.dp, log2(f/1000)/log2(14))` and amplitude eases in while a stimulus plays.
- **Hearing butterfly:** the vertical axis is frequency (1 kHz at the bottom, 14 kHz at the top, log scale). The left wing is the left ear and the right wing the right ear, mirrored around the center. Wing half-width at f = `map(clamp(60 − HL_est, 0, 70))`. Closed Catmull-Rom paths filled with primary/tertiary containers at 70% alpha, drawn in with a 900 ms path-trim animation.
- **Profile seed color:** hue = log-mapped frequency of the largest HL_est (1 kHz → 200°, 14 kHz → 340°, wrapped); chroma rises with asymmetry. Deterministic, stored on the profile.

### Screens and flows
1. **Onboarding (F1):** welcome → how it works (3 short cards) → disclaimer ("I understand") → notification permission → start the service → Home.
2. **Home:** active profile card (mini butterfly, listener name, headset chip), a big Personalized/Original segmented toggle, compensation strength, engine status chip (F21), and actions: Hearing test, Preference test, Hearing health, Profiles. An empty state with no tests gives one big CTA: "Take your hearing test (≈5 min)".
3. **Hearing test (F2–F9):**
   choose mode (Quick ≈4–5 min / Thorough ≈7–9 min) → pre-test page (F2) → left/right check (F3) → calibration (F4) → test (F5/F6) → results (F9–F11) → exit dialog (F12).
   - **Calibration detail:**
     1. Set `STREAM_MUSIC` to round(0.5 × max) (or leave it lower if higher is refused) and read it back.
     2. Play 1 kHz pulsed binaural, starting at −90 dBFS and rising 2 dB every 450 ms until the user taps "I hear it" (or −10 dBFS → "Can't hear anything? Check connection").
     3. `L_ref = L_tap − 4 dB` (reaction-latency allowance).
     4. Target window **[−68, −50] dBFS**, aim −60. If outside, compute the index change using `getStreamVolumeDb`, set it and rerun (max 2 reruns). If still outside the target window after reruns: within [−75, −35], accept with a warning; outside that, abort with "Your volume range doesn't suit testing. Try other earphones or check the connection."
     5. Lock the index. Store the index, max index, `L_ref`, device key and time.
   - **Quick detail (normative):**
     - Start each (ear, f) at `L_exp(f) + 20`, or at the other ear's threshold at that f + 15 if already known, clamped to ≤ L_max. If not heard, step +10 until heard, or censor at L_max.
     - Then: heard → −10, not heard → +5. Threshold = the lowest level with ≥2 "heard" among ≤3 ascending presentations at that level.
     - Max 15 trials per point. Fallback: mean of the last 4 reversal levels, flagged low-confidence.
     - Stimulus: 3 pulses × 225 ms (including 25 ms ramps) with 200 ms gaps. A "heard" response during the stimulus ends it early (25 ms ramp-down). Then a random 400–1200 ms silence before the next trial.
     - Next trial: a random active staircase in the **opposite ear** from the previous trial (same ear only if the other ear is finished). Catch (silent) trials at probability 1/8, at least 1 per 10 trials.
     - Buttons: "I heard it" (the morphing hear button) and "I didn't". A response during the stimulus counts.
   - **Thorough detail (normative):**
     - For each f (random order) and each ear (alternating): a descending run starting at `L_exp + U(15, 25)`, then an ascending run starting at `L_exp − U(15, 25)`.
     - A pulsed tone loops. The user drags a vertical "level dial" (1 dB per detent with a haptic tick; position randomized per run; no numbers shown) and confirms "I can't hear it anymore" or "I can hear it now".
     - Run thresholds: descending = confirmed level + 1 dB, ascending = confirmed level. Point = mean of the two. If they differ by more than 15 dB, repeat once, then flag low-confidence.
   - **Results:** butterfly, PTA3 summary per ear in plain words, reliability/asymmetry notes, the A/B card (clip or "Use my own song"), EQ graph, and a "See hearing health" button.
4. **Hearing health (F13):** WHO band legend, audiogram (x: 1–16 kHz log; y: −10 to 100 dB, increasing downward), summary cards, trend chart, "How to read this" sheet, and the disclaimer card pinned at the bottom. The disclaimer is always visible and can't be collapsed:
   > *"PAudio is not a medical device. These results are estimates for music listening only and are not a medical diagnosis. Phones and earphones can't be calibrated like clinical equipment, so hearing loss that is equal across all frequencies can't be detected. If you notice changes in your hearing, ringing, or pain, see a doctor or audiologist."*
   Never use the words "diagnose", "detected hearing loss" or "your hearing is damaged". Use "estimated", "may" and "consider".
5. **A/B preference test (F14):**
   intro → for each round: playing A/B card → choose → (repeat ×9) → result curve → fine-tune (F15) → save.
   - Rounds interleave the axes B, P, T, B, P, T, B, P, T. Per axis: center c = 0. Each round compares `c − s` against `c + s` (randomized A/B sides); a pick sets c to the picked value and halves s. "Can't tell" ends that axis.
   - Initial steps and clamps: bass s₀ = 6, c ∈ [−6, +12]; presence s₀ = 4, c ∈ [−6, +6]; treble s₀ = 5, c ∈ [−8, +8].
   - Clip per axis: bass → Echoplex, presence → Discipline, treble → 34 Ghosts IV. "Try another song" cycles through the clips.
   - Each round plays a 12 s loop. "Choose" is enabled only after both A and B have played ≥2 s.
6. **Profiles (F16–F18):** list grouped by listener; profile detail (headset, tests history, butterfly, export, delete); import flow with validation errors shown inline.
7. **Settings / About (F20, F23, F24).**
8. **System-wide engine state machine (shared, F19–F22):**
   `Off → Starting → Waiting (session mode, no session) ⇄ Active(sessions)`; `Legacy(active)`; any state → `Bypassed` (test or A/B in progress) → back to the previous state; any state → `Conflict` (control lost) → retry on user tap or when control returns; `NoProfile(device)`; `SpeakerOutput`; `Error(cause)`.

   | Error case | Copy |
   |---|---|
   | Control lost | "Another equalizer app is controlling the audio right now. Close it or turn it off to use PAudio." |
   | Effect creation failed | "This device's audio system refused PAudio's effect. Try Legacy mode in Settings." |
   | No session seen for 60 s while music is playing (when detectable) | "Your music app isn't announcing itself. Try Legacy mode, or check the compatibility list." |
   | Notification permission denied | "PAudio needs its notification to keep working in the background." |

   Shared **hearing test state machine:** `Idle → Prep → ChannelCheck → Calibrating → Testing(progress) ⇄ Paused(reason) → Results | Aborted(reason)`. Copy for the pause reasons:

   | Pause reason | Copy |
   |---|---|
   | Headset disconnected | "Your earphones disconnected. Reconnect them to continue." |
   | Volume changed | "The volume changed, so we'll redo this tone. Please don't touch the volume during the test." |
   | Call / focus loss | "Paused for another app's audio." |
   | Backgrounded | "Paused while PAudio was in the background." |
   | Unreliable answers | "Some answers looked like guesses. Want to redo the test?" |

**Localization:** every user-facing string lives in `res/values/strings.xml` (plurals where needed), with no hard-coded copy in composables. Leave a `values-ms` stub directory for N3. Format numbers and dates with locale-aware formatters.

---

## 5. Architecture

### 5.1 Module layout
```
:app                      — Application, Hilt setup, navigation host, manifest
:designsystem             — Theme (expressive + MaterialKolor), typography, shapes/morph wrappers, wavy progress wrapper, butterfly, audiogram, EQ graph, trend chart (Canvas)
:core:model               — PURE Kotlin/JVM. Data classes, IDs, Ear, Frequency grid, profile JSON schema (kotlinx.serialization)
:core:dsp                 — PURE. RBJ magnitude responses, PCHIP, 1/24-oct grid, K-weighting, loudness predictor, DP band mapper, preamp/curve builder
:core:audiometry          — PURE. ISO 389-7 table, stimulus synthesis (float buffers), calibration math, Hughson-Westlake engine, adjustment engine, catch trials, scheduler, HL estimation, WHO grading, asymmetry, reliability
:core:preference          — PURE. Bisection A/B engine, clip assignment
:data                     — Room DB, DAOs, repositories, DataStore, export/import, schema migrations
:audio:tone               — AudioTrack streaming ToneOutput implementation, volume lock
:audio:device             — Output device tracking, HeadsetKey derivation, environment checks (mono, balance, spatializer, DND), noise meter (AudioRecord, in-memory only)
:audio:effect             — EffectEngine, DynamicsProcessing backend, SessionSource(s) (broadcast, legacy), control-status handling
:audio:playback           — Media3 player for demo/A-B/user file, loop region, duck-and-swap
:service                  — PAudioService (FGS specialUse), notification, boot receiver, engine orchestration
:feature:onboarding | :feature:home | :feature:hearingtest | :feature:results | :feature:health | :feature:preference | :feature:profiles | :feature:settings
tools/golden/             — Python oracles generating golden JSON (dev-only)
tools/assets/             — fetch_demo_audio.sh, ltas.py
```
**`:core:*` modules must have no Android dependencies** (plain `kotlin("jvm")`), so they get fast unit tests. Enforce this with a Gradle check that fails the build if `:core:*` depends on `com.android.*`.

### 5.2 Interfaces that decouple hardware from logic
```kotlin
interface ToneOutput { suspend fun open(sampleRate: Int); suspend fun play(stimulus: StereoBuffer); suspend fun playSilence(ms: Int); fun close() }
interface VolumeController { val maxIndex: Int; fun index(): Int; fun setIndex(i: Int): Int; fun indexDb(i: Int): Float?; val changes: Flow<Int> }
interface OutputDevices { val current: StateFlow<OutputDevice?> }          // OutputDevice(type, productName, addressSuffix)
interface EnvironmentChecks { suspend fun snapshot(): EnvSnapshot }          // mono, balance, spatializer, dnd
interface NoiseMeter { fun levels(): Flow<Float> }                           // dBFS RMS, never persisted
interface EffectBackend { fun create(session: Int, cfg: EqConfig): EffectHandle }   // DP impl + fake
interface EffectHandle { fun apply(cfg: EqConfig); fun setEnabled(on: Boolean); val control: StateFlow<Boolean>; fun release() }
interface SessionSource { val events: Flow<SessionEvent> }                   // Broadcast, Legacy(0), later Dump (N1)
interface AbPlayer { suspend fun load(src: AudioSource, loop: ClosedRange<Duration>?); fun swapTo(cfg: EqConfig, inputGainDb: Float); fun play(); fun pause(); fun release() }
```
Fakes for all of them live in a `:testing` module (`FakeToneOutput` records buffers, `SimulatedListener` answers via a psychometric function, `FakeEffectBackend` records state).

### 5.3 Layering and concurrency
- UI (Compose) → `ViewModel` (StateFlow UI state, one-off events via Channel) → use cases (`RunQuickTest`, `BuildCurve`, `ApplyProfile`…) → repositories → platform adapters.
- Test engines run on a dedicated single-thread dispatcher. Audio writes go through a dedicated high-priority thread inside `ToneOutput`. **All test coroutines are cancellable:** cancelling closes the `AudioTrack`, restores the volume index and un-bypasses the engine in `finally`.
- The engine is owned by `PAudioService` and exposed via a bound interface plus `StateFlow`. Bypass is reference-counted with tokens, so nested callers can't un-bypass prematurely.
- Curve computation is pure and memoized by (test id, strength, prefs, algoVersion).

### 5.4 SDK, tooling and versions
- **minSdk 28** (DynamicsProcessing). **targetSdk 36** (Google Play requirement since Aug 31, 2026). **compileSdk 37.1** (required by Compose BOM 2026.11.01).
- Kotlin ≥ 2.2 with the Compose compiler plugin, KSP, Gradle version catalog `gradle/libs.versions.toml`, JVM toolchain 17 (or newer if AGP requires).
- GitHub Actions: `./gradlew check assembleDebug` on push/PR. F-Droid friendliness: `dependenciesInfo { includeInApk = false; includeInBundle = false }`, and no proprietary dependencies.

### 5.5 Headset identity
`HeadsetKey = "${type}|${productName.trim()}|${addressSuffix}"`, where `addressSuffix` is the last 5 characters of `AudioDeviceInfo.getAddress()` for Bluetooth (anonymized `XX:XX:XX:XX:AB:CD` → `AB:CD`), and empty for wired/USB.
- Speaker, earpiece and HDMI outputs are **never** processed.
- If two different headsets collide on one key (wired), ask "Which headphones are these?" on connect, and remember the choice for the session.

### 5.6 Data model (Room)
- `Listener(id, name, createdAt)`
- `Headset(id, key UNIQUE, displayName, type, productName, addressSuffix, createdAt)`
- `Profile(id, listenerId, headsetId NULLABLE (= Any headphones), name, compStrength, gB, gP, gT, personalizationOn, seedColor, seedOverridden, createdAt, updatedAt)` with UNIQUE(listenerId, headsetId)
- `HearingTest(id, profileId, mode, startedAt, durationMs, volIndex, volMax, lRef, deviceSnapshotJson, ancOn, appVersion, algoVersion, fpRate, reliable)`
- `Threshold(testId, ear, freqHz, levelDbfs NULLABLE, censored, lowConfidence, trials)`
- `Trial(testId, seq, ear, freqHz, levelDbfs, isCatch, response, tMs)`: raw log for future reanalysis
- `PreferenceTest(id, profileId, startedAt, gB, gP, gT, roundsJson)`
- DataStore: active listener, headsetKey → profileId map, engine mode, boot opt-in, onboarding done.
- **Derived curves are never stored. They are recomputed from raw data + `algoVersion`.**

JSON schema v1 (`schemaVersion: 1`): listener name, headset (key fields), profile settings, tests (thresholds + optional trials), preference tests. Unknown fields are ignored; an unknown major version is rejected.

---

## 6. Security and privacy requirements
- **Permissions that must be absent:** `INTERNET`, `BLUETOOTH_CONNECT`, `READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE`, any location permission, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. A Gradle task `verifyPermissions` (part of `check`) parses the merged release manifest and fails if any appear.
- **Allowed permissions:** `MODIFY_AUDIO_SETTINGS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, and `RECORD_AUDIO` (runtime, optional, noise meter only). Add `DUMP` only with N1.
- **Microphone:** audio samples are processed in memory into a single RMS value, never written to disk, logged or retained. The `AudioRecord` stops when the pre-test page leaves composition, and the meter is hidden if the permission is denied.
- **Hearing data** is health-adjacent. It stays in app-private Room storage (protected by Android file-based encryption). `dataExtractionRules`: **exclude the database and DataStore from cloud backup**, allow device-to-device transfer. Users back up via explicit export.
- **Logging:** release builds strip `Log.v/d/i` via R8 rules. Never log thresholds, curves, listener names or device names at any level.
- **Exported components:** the boot receiver (system broadcast only) and the dynamic session receiver (`RECEIVER_EXPORTED`, inherently spoofable). Validate it: integer session > 0, ignore PAudio's own package, cap 16 live sessions, release effects for sessions closed or idle > 10 min. A spoofed event can at worst create an inert effect.
- **Import (untrusted input):** max 2 MB, strict schema parse, numeric ranges clamped (levels −120…0 dBFS, gains within §2.1.3 caps), at most 100 tests and 20,000 trials per file. Never crash on malformed input (fuzz test, §7.2).
- **Hearing safety:** never exceed `L_max(f)`; the calibration ramp caps at −10 dBFS; never raise the system volume during a test beyond the calibrated index; the limiter is always on in the engine; per-point gain caps and preamp as in §2.1.3.
- **Release hardening:** `isMinifyEnabled = true`, `isShrinkResources = true`, not debuggable. Signing config comes from `keystore.properties` / env vars (never committed; `.gitignore` covers them).

---

## 7. Testing strategy (this decides whether the thing works)

### 7.1 Golden test vectors (most important)
Location: `core/*/src/test/resources/golden/*.json`, generated by **independent Python oracles** in `tools/golden/` (never by porting the Kotlin). `docs/VECTORS.md` gives exact commands to run on the user's Arch Linux machine:
```
python -m venv .venv && . .venv/bin/activate && pip install numpy scipy
python tools/golden/gen_all.py --out core
```
Required vector sets:
1. **RBJ magnitudes:** low-shelf / peaking / high-shelf at the §2.1.3 parameters, gains −12…+12 in 3 dB steps, on the 1/24-octave grid (scipy `freqz` at fs = 48 kHz). Tolerance 0.01 dB.
2. **K-weighting** magnitude at 1/3-octave centers (BS.1770-5 coefficients). Tolerance 0.01 dB.
3. **PCHIP** interpolation in log2(f) vs `scipy.interpolate.PchipInterpolator` for 10 threshold sets. Tolerance 1e-6.
4. **Loudness predictor:** flat → 0 dB; broadband +6 → +6.00; each preference axis at ±6 with pink weighting and with each clip's LTAS. Tolerance 0.01 dB.
5. **Measurement model (§2.1.3):** 12 threshold sets (normal, sloping HF loss, notch at 4 kHz, asymmetric, censored points, flat loss) → expected `N`, `A`, `HL_est`, PTA3, grade, EHF, asymmetry flag, `g_h` grid, `G`, preamp. Tolerance 0.01 dB.
6. **DP band mapping:** band gains for the 12 curves and the reconstruction RMS error (must be ≤ 0.5 dB).
7. **Stimulus synthesis:** first 2048 samples and summary stats (peak, RMS, spectral energy in/out of band) for 1 kHz pure and 8/14 kHz warble at −40 dBFS, 48 kHz and 44.1 kHz. Tolerance 1e-6.
8. **Hughson-Westlake scripted sequences:** 8 scripted response strings → exact expected level sequences and thresholds (hand-derived from the rules; document each derivation in `VECTORS.md`).
9. **Preference bisection:** scripted choices → exact final (gB, gP, gT).

### 7.2 Other tests
- **Simulated listeners** (`SimulatedListener`: p(yes|L) = γ + (1−γ−λ)/(1+e^{−(L−T)/σ}), γ = 0.02, λ = 0.02, σ = 2 dB; 1000 seeded runs per profile):
  - Quick: median |error| ≤ 3 dB, 90th percentile ≤ 6 dB, median ≤ 7 trials per point, total ≤ 150 trials including catch trials.
  - Thorough (model confirms when perceived p crosses 0.5 with 1.5 dB noise): median |error| ≤ 3 dB.
  - An "always yes" listener is flagged unreliable in 100% of runs.
  - Ear alternation invariant holds on every trial.
- **Stimulus quality:** ramps start and end within 1e-4 of zero; steady-state peak within ±0.05 dB of target; **out-of-band energy (outside ±12% of f) ≤ −50 dB** relative to in-band; the opposite channel is exactly 0.0.
- **Engine (fakes):** OPEN/CLOSE lifecycle, own-package ignore, the 16-session cap, legacy/session exclusivity, **P0 bypass during ToneOutput / A/B activity**, restore after a simulated process death, conflict state on control loss, device switch → correct profile applied, speaker output → no processing.
- **Data:** Room migration tests (exported schemas), export→import round-trip equality (interop requirement), and an import fuzz test (10k mutated files, no crashes, caps enforced).
- **Cancellation:** cancelling at random points during calibration and the test always closes the track, restores the volume index and releases the bypass token.
- **UI:** Compose tests for the happy paths (onboarding, Quick test with `FakeToneOutput` + `SimulatedListener`, results A/B toggle, preference test, profile export), plus a test that the disclaimer is present and has no dismiss affordance on Hearing health.
- **Hardware-gated tests** use the `@RequiresHardware` annotation and are skipped in CI: AudioTrack float streaming, DP creation on the app's own session, `getStreamVolumeDb` sanity.

### 7.3 Manual device checklist: `docs/HARDWARE_TEST.md`
Run on at least one Pixel-class device and one Samsung or other OEM device, with wired/USB-C **and** Bluetooth earbuds:
- L/R check is correct; swapping buds is caught.
- No audible clicks at 1 kHz and 14 kHz at high levels.
- Calibration lands in the window; volume lock holds; volume keys pause the test.
- Disconnecting mid-test pauses it. A call pauses it.
- **DP channel mapping by ear:** apply −30 dB to channel 0 only → the left ear gets quieter.
- System-wide works with YouTube Music, Spotify and a local player (results logged in `docs/PLAYER_COMPAT.md`). Legacy mode was tried.
- Running Wavelet/Poweramp EQ simultaneously shows the Conflict state.
- Boot start, or the boot notification fallback, works on API 35+.
- A/B swaps are glitch-free.
- **Test-retest:** two Thorough runs, same session and headset → ≥ 90% of points within ±10 dB.
- **Codec check:** the 14 kHz threshold over Bluetooth vs wired with the same earphone model (or note the difference).

---

## 8. Build plan / milestones for Claude Code

**Rule:** work milestone by milestone. At the end of each milestone, `./gradlew check assembleDebug` passes, tests are green, the work is committed with a clear message (`M<n>: <summary>`), and `docs/PROGRESS.md` is updated (done / next / open issues).

- **M0 — Research & decisions.** Clone and read: AOSP `libeffects/dynamicsproc`, `AudioEffect`/`DynamicsProcessing` docs, Equalizer314, RootlessJamesDSP (session handling), the material3 1.5.0 API reference, and MaterialKolor. Fill every "Things the spec leaves out" item (§2.1) into `docs/DECISIONS.md` with file/line refs. Pin all versions. Find the exact archive.org URLs and license texts for the 3 tracks and choose clip timestamps. Write `docs/VECTORS.md`. **Stop and report** if anything makes the plan impossible (e.g., per-channel PreEQ not honored, or DP unavailable on session IDs from other apps).
- **M1 — Skeleton.** Multi-module Gradle with the version catalog, Hilt, empty feature screens with navigation, `:designsystem` theme (expressive + MaterialKolor + Roboto Flex), CI workflow, the `verifyPermissions` task, and the no-Android-in-core check.
- **M2 — `:core:dsp` + golden tooling.** Python oracles (sets 1–4, 6) and the Kotlin implementations. All goldens green.
- **M3 — `:core:audiometry`.** ISO table, stimulus synthesis (set 7), calibration math, Hughson-Westlake and adjustment engines (set 8), scheduler with ear alternation and catch trials, measurement model (set 5), simulated-listener suite.
- **M4 — `:core:preference` + loudness integration.** Bisection engine (set 9) and per-clip LTAS loading. `tools/assets/fetch_demo_audio.sh` and `ltas.py` produce the FLAC clips and LTAS JSON. If the build environment has no network, stop that sub-step, document the commands for the user, and continue with a generated pink-noise placeholder clip.
- **M5 — `:data`.** Room entities/DAOs/migrations, repositories, DataStore, JSON export/import, round-trip and fuzz tests.
- **M6 — `:audio:tone` + `:audio:device`.** Continuous-stream ToneOutput, VolumeController (lock, observe, read-back), OutputDevices + HeadsetKey, EnvironmentChecks, NoiseMeter.
- **M7 — `:audio:effect` + `:audio:playback` + `:service`.** DP backend with band mapper, session/legacy sources, ref-counted bypass, conflict handling, the FGS with notification actions, boot handling, and the A/B player with duck-and-swap (20 ms duck if M0 shows DP updates glitch).
- **M8 — UI: onboarding, Home, Profiles, Settings/About** (F1, F16–F18, F20–F25).
- **M9 — UI: hearing test flow** (F2–F12): pre-test, L/R check, calibration, Quick, Thorough, pause states, results with butterfly, A/B, EQ graph, exit dialog.
- **M10 — UI: Hearing health** (F13): audiogram, bands, summaries, trend, disclaimer.
- **M11 — UI: Preference test + fine-tune** (F14–F15).
- **M12 — Hardening.** All §2.2 traps re-verified with tests, accessibility pass (TalkBack labels, 48 dp targets, contrast in both themes, no announcements during stimuli), R8 release build, privacy checks, performance (no jank in the butterfly/graph animations).
- **M13 — Docs.** README (what it is, screenshots placeholder, build steps, adb/legacy notes, player compatibility table link, disclaimer), `LICENSE` (GPL-3.0-or-later), `ASSETS_LICENSE.md` (NIN CC BY-NC-SA 3.0 US attribution + no-monetization note; Roboto Flex OFL), `docs/PRIVACY.md` (threat model summary: data stored, never sent anywhere, mic handling), and the completed `docs/HARDWARE_TEST.md` template.

---

## 9. Acceptance criteria
- [ ] `./gradlew check assembleDebug` passes on a clean clone; CI is green.
- [ ] All golden vector sets 1–9 pass at the stated tolerances.
- [ ] Simulated-listener accuracy and trial-count targets (§7.2) pass.
- [ ] **P0:** the engine is bypassed whenever ToneOutput or the A/B player is active (fake-engine test), and is restored after the test and after simulated process death.
- [ ] DP mapping reconstruction error ≤ 0.5 dB RMS (50 Hz–16 kHz) for all golden curves.
- [ ] Stimulus out-of-band energy ≤ −50 dB; the opposite channel is exactly silent.
- [ ] Profile JSON export → import round-trips exactly (interop requirement); the import fuzz test passes.
- [ ] `verifyPermissions` confirms no `INTERNET`, `BLUETOOTH_CONNECT`, storage or location permissions in the merged release manifest.
- [ ] No hearing data in logs (grep test over a release-build logcat capture from an instrumented run).
- [ ] The Hearing health screen shows the full disclaimer with no dismiss control (UI test).
- [ ] Quick test completes in ≤ 6 min and Thorough in ≤ 10 min with a simulated listener at realistic response times (1.0 s per answer, 12 s per Thorough run).
- [ ] Manual: every item in `docs/HARDWARE_TEST.md` is checked on ≥ 2 devices, including Bluetooth and wired; test-retest ≥ 90% within ±10 dB.
- [ ] Manual: system-wide processing confirmed with ≥ 2 third-party players in session mode; conflict state shown with another EQ app.
- [ ] `./gradlew assembleRelease` produces a minified, signed (test key) APK that installs and runs the full flow.
- [ ] README, LICENSE, ASSETS_LICENSE, PRIVACY and DECISIONS are complete; About shows all attributions.

---

## 10. Open questions (defaults chosen; the user may override)

| Question | Default |
|---|---|
| App name / package | PAudio / `my.umarh.paudio` |
| License | **GPL-3.0-or-later** (lets the builder port from GPL EQ apps). Demo clips: CC BY-NC-SA 3.0 US |
| Distribution | GitHub Releases first; F-Droid-friendly build; Play later (would need a `specialUse` justification) |
| min / target / compile SDK | 28 / 36 / 37.1 |
| System-wide default mode | Session (broadcast) mode; Legacy global mode is an opt-in toggle |
| Enhanced (DUMP) detection | Not in MVP (N1) |
| FGS type | `specialUse` (enables boot start on API 35+, if M0 confirms) |
| Test frequencies | 1, 2, 3, 4, 6, 8, 10, 12.5, 14 kHz (12.5 kHz instead of 12 kHz to match the ISO 1/3-octave table) |
| Quick method | Modified Hughson-Westlake (10 down / 5 up, 2-of-3 ascending) with catch trials, under the yes/no UI you described |
| Thorough method | Your descending "reduce until gone" run **plus** an ascending run, averaged (reduces direction bias) |
| Tone type | Pure < 4 kHz; warble ±5% / 8 Hz at ≥ 4 kHz; 3 × 225 ms pulses |
| HL estimation | ISO 389-7 diffuse-field shape + anchor "better ear ≈ 0 dB at 1–2 kHz", labelled estimated |
| Health grading | WHO 2021 bands; "approximate" PTA from 1/2/4 kHz; extended high-frequency average shown without a grade |
| "Loss rate graph" interpretation | Audiogram with grade bands **plus** a trend of PTA over time (same headset only) |
| Compensation | 50% of estimated loss, caps −6/+12 dB, preamp + limiter |
| Preference axes | Bass shelf 105 Hz, presence peak 3 kHz, treble shelf 8 kHz; 3 rounds each; "Can't tell" allowed |
| Demo songs | NIN "Discipline", "Echoplex", "34 Ghosts IV" (CC BY-NC-SA) |
| Mic noise meter | Included, optional permission, never stored |
| Profile model | Listener × headset, plus an "Any headphones" profile per listener |
| Cloud backup of hearing data | Excluded; manual JSON export instead |
| Language | English only; strings ready for Bahasa Melayu (N3) |
| Theme | Expressive M3, profile-seeded dynamic color, Roboto Flex bundled |
| Speaker output | Never processed |
