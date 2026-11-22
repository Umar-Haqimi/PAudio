# PAudio — M0 research decisions

Status: M0 complete. Written 2026-11-20. Rule applied everywhere: **upstream source beats the spec** (SPEC.md §0 precedence rule).
Every finding cites a URL + file:line. Short aliases for the URL prefixes used below:

| Alias | URL prefix |
|---|---|
| `AV` | `https://android.googlesource.com/platform/frameworks/av/+/e2f098935447ca4945946de5cb69db843fe3f003/` |
| `FB` | `https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/` |
| `HW` | `https://android.googlesource.com/platform/hardware/interfaces/+/1a56e38edc2f2f6189ef405ee1edce554e15cbc0/` |
| `SM` | `https://android.googlesource.com/platform/system/media/+/375121a4b16141b7177d801bf9506284642a7a56/` |
| `FBM` | `https://github.com/aosp-mirror/platform_frameworks_base/blob/master/` (GitHub mirror, fetched 2026-11-20; used for files gitiles refused to serve: `Settings.java`, `SettingsProvider.java`, `ActiveServices.java`, `ActivityManagerConstants.java`, `BroadcastSkipPolicy.java`, `Service.java`, `ServiceInfo.java`, `NotificationManagerService.java`) |
| `M3` | `https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.5.0/material3-android-1.5.0-sources.jar` → `commonMain/androidx/compose/material3/` |
| `FND` | `https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.13.0/foundation-android-1.13.0-sources.jar` → `commonMain/androidx/compose/foundation/shape/` |
| `GS` | `https://dl.google.com/dl/android/maven2/androidx/graphics/graphics-shapes-android/1.1.0/graphics-shapes-android-1.1.0-sources.jar` → `commonMain/androidx/graphics/shapes/` |
| `MK` | `https://github.com/jordond/MaterialKolor/blob/5.0.1/` (tag `5.0.1`, commit `cc4d2cb931ba0a45995abe988717a2cea9ed478e`) |
| `EQ314` | `https://github.com/bearinmindcat/Equalizer314/blob/9294a5c8fccc6cebf44b2d45bfb01609f8ec7e94/app/src/main/java/com/bearinmind/equalizer314/` |
| `RJD` | `https://github.com/ThePBone/RootlessJamesDSP/blob/60d25ae8a53c6f4691c090673df290a73c6b6357/app/src/main/` |

Source-currency caveat: public AOSP `main` on googlesource stops at **2025-03** (no Android 17 / API 37 source is public). `DPFrequency.cpp` is byte-identical in tags `android-10.0.0_r1` … `android-16.0.0_r4` and `main` (verified by diff); `EffectDynamicsProcessing.cpp` is identical in `android-16.0.0_r4` and `main`. API 28 (Android 9) differs, see §1. Anything about Android 17 behaviour is therefore **unverifiable from source** and is routed to `docs/HARDWARE_TEST.md`.

Local scratch copies (not in repo): `/tmp/claude-0/-home-user-PAudio/fc365d9e-7a39-5f9a-b512-73f2c72b7e72/scratchpad/research/` (AOSP files in `aosp/`, DP simulator in `sim/`, audio analysis in `nin/`).

---

## ⚠️ Blockers

**None found.** Nothing in the plan is impossible:
- Per-channel PreEQ **is** honoured by the reference engine (one independent `mPreEqFactorVector` per channel, `AV/media/libeffects/dynamicsproc/dsp/DPFrequency.cpp:71,383-386,496`).
- DP **can** be attached to another app's audio session: `AudioFlinger::createEffect` performs no session-ownership check (only session 0 needs `MODIFY_AUDIO_SETTINGS`), and creates an "orphan" effect if the session has no thread yet (`AV/services/audioflinger/AudioFlinger.cpp:4334-4340,4500-4511`).

**But four spec assumptions are wrong or need changing before M2/M7** (details in the numbered sections; all are implementable):
1. **The 40-band 1/4-octave mapper fails the ≤0.5 dB acceptance test** on realistic curves (0.53–0.75 dB RMS at N=1024; 1.61 dB at the engine default N=512 for a +12 dB bass shelf). Use the **bin-aligned layout at N=1024** in §1.
2. **The DP "limiter" is a per-frame RMS compressor, not a peak limiter.** A threshold of −1 dBFS never engages except on near-square waves. Use −3 dB and rely on the preamp for clipping protection (§2).
3. `VARIANT_FAVOR_TIME_RESOLUTION` is **not implemented** by the reference engine: creating it throws. Use `VARIANT_FAVOR_FREQUENCY_RESOLUTION` only (§1).
4. `DynamicsProcessing`'s constructor **throws** (`UnsupportedOperationException`) when another app already holds higher/equal-priority control of that session's DP; "Conflict" must be detected at construction too, not only via the control listener (§3).

Other high-impact notes (not blockers): AGP 9.5.x advertises max API 37 while Compose 1.13 demands compileSdk 37 **minor 1** (§14); MaterialKolor 5.0.1's Android POM depends on JetBrains Compose artifacts (§10); on AIDL effect HALs parameters are clamped to a vendor capability (§1.7).

---

## Summary of decisions

| # | Topic | Decision |
|---|---|---|
| 1 | DP engine & mapper | `VARIANT_FAVOR_FREQUENCY_RESOLUTION`, `preferredFrameDuration = 16 ms` → FFT **N = 1024** at 32–64 kHz (hop 512). PreEQ is a **per-bin staircase** (brick-wall bins), acoustically ≈ **linear-in-dB interpolation between bin centres**. Bin-aligned band layout: stops = `[0] ∪ unique(round(geomspace(1, N/2, 128)))` → **87 bands @48 kHz**, cutoff_b = stop_b·fs/N, last = fs/2. Band gain = dB-mean of G over its bins. Reconstruction = linear-dB interpolation of per-bin gains (validated vs a bit-exact simulation, ≤0.10 dB RMS). Spec's 40 `c_k` cutoffs are API-valid but too coarse → not used. Channel 0 = Left, 1 = Right. PreEQ + Limiter only. |
| 2 | Limiter | `Limiter(true, true, linkGroup 0, attack 1 ms, release 50 ms, ratio 10, threshold −3 dB, postGain 0)` on both channels. Level = frame RMS in dBFS (full-scale sine = −3.01). Not a peak limiter; preamp is the real clip guard. |
| 3 | Runtime updates & control | Frame-wise recompute + 50 % overlap-add ⇒ gain changes crossfade over one hop (≈10.7 ms): **no duck for EQ updates**; but updates are **non-atomic** (1 binder call per band) → diff-write, ≥50 ms apart, worker thread. A/B swap in the in-app player keeps the spec's 20 ms duck. Priority **100** (beats priority-0 users, does not out-bid MAX_VALUE EQ apps). Control loss ⇒ `UnsupportedOperationException` on setters / `onControlStatusChange(false)` ⇒ `Conflict`. |
| 4 | `getStreamVolumeDb` | Nominal policy-curve dB per device category; ignores AVRCP absolute volume. First-order estimate only; always **re-measure** (spec's ≤2 reruns). −∞ at mute; throws on out-of-range index. |
| 5 | Safe volume / CSD | `setStreamVolume` above safe index with legacy safe-media ACTIVE is **not applied** (warning dialog + pending command). With CSD enabled the legacy block is bypassed, but a 500 % dose auto-lowers volume to the safe index mid-test. Always read back; start at 0.5×max (below default safe index). |
| 6 | Environment checks | `Settings.System` `"master_mono"` (int) and `"master_balance"` (float) readable by all apps on API 28–37 (`@Readable`, no `maxTargetSdk`). `Spatializer.isEnabled()/isAvailable()/canBeSpatialized()` and `getCurrentInterruptionFilter()` need no permission. Spatializer: API 32+, treat as absent below. |
| 7 | BT address | Full MAC returned to apps without `BLUETOOTH_CONNECT` on older builds; anonymised `XX:XX:XX:XX:AB:CD` only on builds ≥ `android-14.0.0_r30` / `android-15.0.0_r20`. `HeadsetKey` uses the **last 5 chars** either way; never persist more. |
| 8 | FGS / boot | `foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. `specialUse` **is** on the BOOT_COMPLETED allow-list (default, DeviceConfig-overridable). Boot: try `startForegroundService`; on `ForegroundServiceStartNotAllowedException`/`SecurityException` post "Tap to start PAudio". |
| 9 | Compose names | `LinearWavyProgressIndicator` (stable in 1.5.0). `MaterialShapes` is `@ExperimentalMaterial3ExpressiveApi`. `MaterialExpressiveTheme` exists (not experimental). `MaterialTheme.motionScheme` exists. `MorphPolygonShape` exists in foundation 1.13 but takes foundation `PolygonShape`, **not** `RoundedPolygon` → write own `MorphShape` over `graphics-shapes` `Morph`. |
| 10 | MaterialKolor 5.0.1 | `DynamicMaterialTheme(seedColor, isDark, …, style = TonalSpot, specVersion = SPEC_2021 default, …)`, `DynamicMaterialExpressiveTheme` (`@Experimental…`, defaults `PaletteStyle.Expressive` + `SPEC_2025`), `rememberDynamicColorScheme(seedColor, isDark, …)`. Android POM pulls `org.jetbrains.compose.*` → verify dependency graph in M1. |
| 11 | Icons | All three F2 glyphs exist (`volume_off`, `graphic_eq_off`, `do_not_disturb_on_total_silence`). Download URL pattern verified. Missing: `bluetooth_audio`, `headset`, `mic_none`. |
| 12 | Demo audio | archive.org FLACs + clip ranges: Discipline 214.430–245.852 s, Echoplex 144.120–175.123 s, 34 Ghosts IV 43.110–74.411 s. License CC BY-NC-SA 3.0 US (both items). |
| 13 | GPL stance | **Port nothing verbatim.** Reimplement from AOSP + notes below; LICENSE still becomes GPL-3.0-or-later at M13 (SPEC §10 default; the repo's Apache-2.0 file is replaced then). |
| 14 | Versions | Table in §14. AGP 9.5.1 has built-in Kotlin (no `org.jetbrains.kotlin.android`). |
| 15 | ISO 389-7 | Spec table verified against USAARL Table 11-1 (all 19 values match). 2005 edition; 2019 caveat recorded. |

---

## 1. DynamicsProcessing: band semantics, frame size, mapper

### Finding

**1.1 Variants.** The native effect only implements the frequency-domain variant; `VARIANT_FAVOR_TIME_RESOLUTION` (Java constant 1) leaves `mPDynamics == NULL`, `setParameter(PARAM_ENGINE_ARCHITECTURE)` returns `-EINVAL`, and the Java constructor throws.
Evidence: `AV/media/libeffects/dynamicsproc/EffectDynamicsProcessing.cpp:213-227` (only `case VARIANT_FAVOR_FREQUENCY_RESOLUTION`), `:991-996` (NULL → `-EINVAL`); `FB/media/java/android/media/audiofx/AudioEffect.java:1443-1456` (`checkStatus` throws); `FB/media/java/android/media/audiofx/DynamicsProcessing.java:110,115` (constants).

**1.2 FFT/frame size.** `desiredBlock = (int)(preferredFrameDuration_ms · fs / 1000.0f)`; if `< 8` → 8, else if not a power of two → next power of two up (`1 << (32 − clz(x))`). `configure(block, overlap = block/2, fs)` clamps to [8, 16384] and re-rounds. Java default `preferredFrameDuration` = 10.0 ms (`CONFIG_PREFERRED_FRAME_DURATION_MS`). At 48 kHz the default gives **N = 512** (hop 256 = 5.33 ms; bin width 93.75 Hz). `Config.Builder.setPreferredFrameDuration(ms)` is the knob.
Evidence: `AV/…/EffectDynamicsProcessing.cpp:229-256` (esp. 234-248); `AV/…/dsp/DPFrequency.cpp:28-29,137-153,164`; `FB/…/DynamicsProcessing.java:276` (default), `:1668-1672` (setter), `:1635`.
Table (N from `frameMs·fs/1000` rounded up to pow2): 10 ms → 512 @ 44.1/48 kHz; **16 ms → 1024 @ 44.1/48 kHz** (705/768 → 1024), 2048 @ 96 kHz; 40 ms → 2048 @ 48 kHz.

**1.3 Window and overlap-add.** Window = symmetric Hann of length N (`0.5(1−cos(2πi/(N−1)))`, flat-top length 0 because overlap = N/2), then `sqrt()` applied once and used as **both** analysis and synthesis window (COLA at 50 % overlap). Hop = N/2.
Evidence: `AV/…/dsp/DPFrequency.cpp:166-172` (fill + sqrt), `:479-487` (analysis), `:670-672` (synthesis), `:435-474` (hop = N − overlap); `AV/media/libeffects/dynamicsproc/dsp/RDsp.h:115-138` (`fillHanning`).
API 28 only: the sqrt split does not exist (single full Hann on analysis and synthesis; half-block zero pre-fill) — `android-9.0.0_r1` `DPFrequency.cpp:64-72,174-186,483-486` per diff. Not modelled; accepted ripple on API 28 (§1 Impact).

**1.4 Latency.** Output of each hop is the overlap-add of the *previous* half-frame, so algorithmic delay ≈ **N/2 samples** plus up to one hop of block-fill buffering (`processSamples` pre-pends zeros until a full hop is available). N = 512 → ≈5.3–10.7 ms; **N = 1024 → ≈10.7–21.3 ms** @48 kHz. Derived, not measured: `AV/…/DPFrequency.cpp:399-413,435-474`. Irrelevant for music (Bluetooth adds 100–250 ms); only matters for video lip-sync.

**1.5 How EqBand gains are applied: brick-wall per FFT bin (a staircase), no interpolation in the code.**
- `binStop = (int)(0.5 + cutoffHz · N / fs)` (`AV/…/DPFrequency.cpp:91-95`).
- Loop over bands in order: `binStart = binNext`, compute `binStop`, `binNext = binStop + 1`, `factor = dBtoLinear(gain)`, write `mPreEqFactorVector[k] = factor · inputGainFactor` for `k ∈ [binStart, binStop]`, `k < N/2+1` (`:223-238`). Band 0 starts at bin 0 (DC). Bins: `k = 0 … N/2` (N/2+1 bins, centre `k·fs/N`).
- **Above the last cutoff:** bins after the last `binStop` are never written; they keep the initial factor **1.0 (0 dB, and they do *not* receive input gain)** (`:71` init, `:235`). ⇒ the last cutoff must be ≥ fs/2.
- **Pitfall — bands narrower than one bin:** if `binStop < binStart` the inner loop is empty (band is silently skipped), and `binNext = binStop+1` can move *backwards* so the next band overwrites bins an earlier band already set. ⇒ stop bins must be **strictly increasing integers**.
- **Pitfall — `enabled = false` bands:** native sets `factor = inputGainFactor` and then multiplies by `inputGainFactor` again (input gain applied **twice** in dB) (`:231-236`), contradicting the Java doc ("muted", `DynamicsProcessing.java:405-409`). ⇒ always `enabled = true`.
- When PreEQ is not in use/enabled, every bin gets only the input gain (`:242-244`). PreEQ multiply runs over `k < N/2` only (`maxBin = min(cSize/2, N/2+1)`, `:491-497`); the Nyquist bin (24 kHz) is never scaled — irrelevant for PAudio.

**1.6 What the ear/microphone actually sees: linear-in-dB interpolation between bin centres.** The staircase is smoothed by the sqrt-Hann × sqrt-Hann analysis/synthesis windows. I re-implemented the exact algorithm (window, rFFT, `native_bin_gains` replica of `updateParameters`, iFFT, OLA) in numpy and measured steady-state sine gain at 1/24-octave grid points (script in `sim/dpsim.py`, `sim/run3.py`):

| Layout (N=1024, fs=48k) | RMS(measured − linear-dB-interp of bin gains) | RMS(measured − pure staircase) |
|---|---|---|
| bin-aligned (96-target, 69 bands), notch-4k curve | 0.018 dB (max 0.076) | — |
| bin-aligned (96-target, 69 bands), +12 dB bass shelf curve | 0.105 dB (max 0.304) | — |
| spec 40-band `c_k`, N=1024 / N=512 | 0.034 / 0.106 dB | 0.272 / 0.477 dB |

So **the reconstruction the acceptance test must use is linear-in-dB interpolation of the per-bin gains**, not the staircase and not band centres.

**1.7 Max band count.** No cap in the native engine or in the Java `Config.Builder` (`AV/…/dsp/DPBase.cpp` has no limit; `FB/…/DynamicsProcessing.java:1638-1660`). Each band is its own `setParameter` binder call (`:1909-1918,1959-1972`). On devices using the **AIDL effect HAL** (Android 14+), `AidlConversionDp::setParameter` clamps every parameter to the HAL's `Capability` range (`AV/media/libaudiohal/impl/effectsAidlConversion/AidlConversionDynamicsProcessing.cpp:132-139`, `SM/audio/include/system/audio_effects/aidl_effects_utils.h:137-165`). The AOSP *reference* capability (a non-processing stub) clamps EQ cutoffs to [220, 20000] Hz (`HW/audio/aidl/default/dynamicProcessing/DynamicsProcessingSw.cpp:65-90`); real vendor HALs differ. Equalizer314 reports, "logcat-verified on Android 16", a hard ceiling of **128 pre-EQ bands** (`EQ314/dsp/ParametricToDpConverter.kt:22-27`) and keeps a 128 → 127 → (compat) 32-band ladder (`EQ314/audio/DynamicsProcessingManager.kt:26,160`). I could not find a 128 constant in AOSP sources (vendor-defined), so treat 128 as an empirical ceiling.

**1.8 Channel index ↔ left/right.** `DPFrequency::processSamples` de-interleaves in order: channel `ch` takes samples `k·channelCount + ch` (`AV/…/DPFrequency.cpp:389-393`); `channelCount = audio_channel_count_from_out_mask(inputCfg.channels)` (`AV/…/EffectDynamicsProcessing.cpp:998`). Android interleaves stereo FL then FR (`AudioFormat.CHANNEL_OUT_STEREO` = FRONT_LEFT|FRONT_RIGHT). ⇒ **channel 0 = Left, channel 1 = Right** for stereo sessions. Java: `Config(channelCount, cfg)` duplicates the last supplied channel if the engine has more channels (`FB/…/DynamicsProcessing.java:1304-1333`) → on a >2-channel session the right-ear curve is replicated to channels 2+. Not verifiable without a device → `HARDWARE_TEST.md` item "−30 dB on channel 0 only".

**1.9 inputGain semantics.** dB, per channel (`setInputGainbyChannel(int,float)` — note the lower-case "b" — and `setInputGainAllChannelsTo`, `FB/…/DynamicsProcessing.java:1789-1796`; `Config.Builder.setInputGainByChannelIndex`). Native converts to a linear factor and **multiplies it into every PreEQ bin factor** (`AV/…/DPFrequency.cpp:194,220,236,242-244`), i.e. it is frequency-domain, pre-MBC, applies even if PreEQ is off, and has no clip protection. Any float works; −200 dB effectively mutes (`RJD/java/me/timschneeberger/rootlessjamesdsp/utils/MutedAudioEffectFactory.kt:42,87`). The Java default is 0 dB (`DynamicsProcessing.java:275`).

**1.10 Reference implementations.** Equalizer314 independently documents the same staircase and compensates for it with a bin-aware converter: `EQ314/dsp/ParametricToDpConverter.kt:14-20,34-39` ("AOSP's DPFrequency renders the Pre-EQ as a STAIRCASE … linear frequency, no interpolation"; "a 10 ms frame crushed narrow features (issue #26)", `EQ314/audio/DynamicsProcessingManager.kt:197`). It runs 128 bands and 80 ms default frames (`DynamicsProcessingManager.kt:16-21`).

### Decision for PAudio

- **Variant:** `VARIANT_FAVOR_FREQUENCY_RESOLUTION` only. Stages: PreEQ (in use, enabled) + Limiter. MBC and PostEQ not in use. If creation throws, retry once with an MBC stage of 1 passthrough band allocated (Wavelet pattern, `EQ314/audio/DynamicsProcessingManager.kt:184-185,216`).
- **Frame:** `DP_FRAME_MS = 16.0f` → N = 1024 at 44.1/48 kHz. Constant in `:audio:effect`; geometry helper in `:core:dsp`:
  ```
  fun dpBlockSize(fs: Int, frameMs: Float): Int {      // mirrors EffectDynamicsProcessing.cpp:234-245 + DPFrequency.cpp:137-150
      val d = (frameMs * fs / 1000.0f).toInt()          // float math, truncation
      val n = when { d < 8 -> 8; d and (d - 1) == 0 -> d; else -> 1 shl (32 - Integer.numberOfLeadingZeros(d)) }
      return n.coerceIn(8, 16384)
  }
  ```
  `fs` = `AudioManager.getProperty(PROPERTY_OUTPUT_SAMPLE_RATE)` (mixer rate; 48000 on almost all devices). Rebuild the layout if it changes.
- **Band layout (normative, shared by both ears; computed once per (fs, N)):**
  1. `stops = [0] + sorted(unique(round(geomspace(1, N/2, 128))))` — strictly increasing integer bin indices ending at `N/2`. (N=1024, 48 kHz → **87 bands**; N=512 → 79.)
  2. `cutoffHz_b = stops_b · fs / N` exactly; the last cutoff is exactly `fs/2` (24000.0). (The `+0.5` in `binStop` makes this float-safe.)
  3. `bandGainDb_b = mean over bins k ∈ (stops_{b-1}, stops_b] of Gdb(max(k·fs/N, 20 Hz))` where `Gdb` is the final curve G(f, ear) of SPEC §2.1.3 evaluated on the 1/24-octave grid and linearly interpolated in log2(f) (hold the end values outside 20 Hz–20 kHz). Band 0 is bin 0 only (DC) and uses `Gdb(20 Hz)`.
  4. Every band `enabled = true`.
- **Reconstruction (for the ≤0.5 dB RMS acceptance test, SPEC §2.1.4):** per-bin gain `B[k] = bandGainDb_{b(k)}` for `k = 0…N/2`; `R(f) = linear-in-dB interpolation of B over knots f_k = k·fs/N`, constant extrapolation beyond the ends; RMS of `R(f) − G(f)` over the **1/24-octave grid points with 50 Hz ≤ f ≤ 16 kHz**.
  Expected result (my sim, 5 realistic curves incl. +12 dB bass shelf): **max 0.12 dB RMS** at N=1024/87 bands (0.17 with 70 bands = 96-target, 0.28 with 51 bands = 64-target). The spec's 40 `c_k` layout gives 0.22–0.75 dB at N=1024 (fails for the bass-shelf curve) and 0.42–1.61 dB at N=512 → **rejected**.
- **Are the spec cutoffs `c_k = 20·2^((k+1)/4)` (k=0…38), `c_39 = fs/2` valid?** Valid for the API (monotonic, ≤ Nyquist) but **not used**: below ≈490 Hz a 1/4-octave band is narrower than one 93.75 Hz bin (N=512) / 46.9 Hz bin (N=1024), so adjacent bands share or reverse `binStop` and are skipped/overwritten (§1.5 pitfall), and the resulting staircase is too coarse above. Keep the formula only as the last-resort fallback layout (≤40 bands) if a device rejects ≥64 bands.
- **Fallback ladder** (engine, in order): 87-band layout → 51-band layout (`geomspace(…, 64)`) → spec 40-band layout (flagged "reduced precision" in diagnostics, not a user-visible error). After applying, read back 3 bands per channel with `getPreEqBandByChannelIndex` and compare cutoff/gain within 0.05; on mismatch treat as clamped → next ladder step.
- **Per-ear:** channel 0 ← left-ear gains, channel 1 ← right-ear gains, same layout, same `inputGain` (preamp P) on both.

### Impact on implementation

- `:core:dsp`: `DpGeometry(fs, frameMs)`, `DpBandLayout.of(fs, N)`, `DpBandMapper.map(gDb: (Double)->Double): FloatArray`, `DpReconstruction.responseDb(layout, bandGains, f)`. Golden set 6 (see `docs/VECTORS.md`) tests layout (stops/cutoffs), band gains and reconstruction RMS; the oracle is an independent numpy re-implementation of `updateParameters` + a full overlap-add simulation.
- `:audio:effect`: pass the real `fs`; never build cutoffs from the spec formula; always write cutoffs as `Float` of `stop·fs/N` computed in `Double`.
- Number of binder calls per full apply: 2 ch × 87 bands + stage calls ≈ 180. Do it on a worker thread (§3).
- `HARDWARE_TEST.md`: add "DP config readback" (log `getConfig().preferredFrameDuration`, band count, first/last cutoff) and "measure ±gain at 100 Hz / 1 kHz / 10 kHz with an external measurement" to confirm N and staircase on real HALs; add API-28 device check if available.
- Preference/hearing curves are smooth, so the quantisation of the lowest bins (46.9 Hz spacing) is below the 50 Hz acceptance start.

---

## 2. Limiter constructor and semantics

### Finding
- Constructor: `Limiter(boolean inUse, boolean enabled, int linkGroup, float attackTime /*ms*/, float releaseTime /*ms*/, float ratio /*N:1 input:output*/, float threshold /*dB re full scale, negative*/, float postGain /*dB, applied after*/)` — `FB/media/java/android/media/audiofx/DynamicsProcessing.java:903-926`. Java defaults: enabled, link 0, attack 1, release 60, ratio 10, threshold −2, postGain 0 (`:293-299`). Native stage defaults before Java pushes config: attack 50, release 120, ratio 2, threshold −30 (`AV/media/libeffects/dynamicsproc/dsp/DPBase.cpp:193`).
- Native algorithm, per hop-frame, per channel (`AV/…/dsp/DPFrequency.cpp:585-623`):
  - `level = sqrt(2·Σ|X_k|² over k<N/2) / (N · windowRms)` ≈ the **frame RMS** of the (post-EQ) signal. I verified numerically with the window formula: full-scale sine → **−3.01 dB**, −6 dBFS sine → −9.03, full-scale square → 0.00, white noise σ=0.3 → −10.2 (`sim/lim.py`).
  - Envelope: one-pole smoothing in **block** units, `θ = exp(−1 / (t_seconds · blocksPerSecond))`, attack θ when level rises, release θ when it falls (`:594-606`; `blocksPerSecond = fs/(N−overlap)` = 93.75 for N=1024). Attack 1 ms ⇒ θ ≈ 2e-5 (instant, per frame); release 50 ms ⇒ θ = 0.81 (≈5 frames ≈ 55 ms).
  - Gain: if `envDb > threshold`: `gainDb = (1/ratio − 1)·(envDb − threshold)` (soft, no knee), else 0 (`:608-619`).
  - Linking: channels with the same `linkGroup` use the **minimum** gain factor (`:627-645`); a channel's native default link group is −1 (none) (`:88`), the Java default is 0, so **both channels must be given the same group** (0). Final multiplication by `dBtoLinear(postGain)` and output gain, applied to the spectrum before the iFFT (`:647-664`).
  - There is **no look-ahead, no peak detector and no sample-domain clipping**; sample peaks can still exceed 0 dBFS inside a frame whose RMS is under the threshold.

### Decision for PAudio
`Limiter(inUse=true, enabled=true, linkGroup=0, attackTime=1f, releaseTime=50f, ratio=10f, threshold=-3f, postGain=0f)` on channels 0 and 1.
- **Spec discrepancy recorded:** SPEC §2.1.3 says "threshold −1 dBFS". Because the metric is frame RMS (a full-scale *sine* is −3.01), −1 would engage only for near-full-scale square-ish content. −3 dB engages for sustained near-full-scale material and still costs nothing on normal music.
- The real clipping protection is the **preamp** `P = −max(0, max G) − 0.5 dB` (SPEC §2.1.3) applied through `inputGain`; the limiter is a secondary safety net and the UI/docs must not call it a "peak limiter".
- Attack 1 ms and release 50 ms are as specified; note the effective attack is one hop (≥10.7 ms) and effective release ≈55 ms.

### Impact on implementation
`EqConfig.limiter` constants live in `:audio:effect` (`LIMITER_THRESHOLD_DB = -3f`). Hardware test: play a −0.5 dBFS 1 kHz sine through a +6 dB preamp-less curve and confirm no hearing-hazardous output (sanity only). Add note to `HARDWARE_TEST.md` that limiter behaviour is frame-RMS.

---

## 3. Runtime updates, glitch-freeness, priority, control loss

### Finding
- **Recompute point.** `processSamples` calls `updateParameters` for every channel on every call; if any band/gain/input-gain changed it rebuilds the whole `mPreEqFactorVector` for that channel (`AV/…/DPFrequency.cpp:183-247,384-386`). There is **no explicit smoothing** of factors. But gains take effect per hop-frame and frames are windowed (sqrt-Hann ×2) and overlap-added, so a step in gain is cross-faded over one hop (N/2 = 512 samples ≈ 10.7 ms): by construction there is no sample-level discontinuity.
- **Not atomic.** `setPreEqByChannelIndex` = 1 stage call + 1 `setParameter` per band (`FB/…/DynamicsProcessing.java:1802-1804,1959-1972,1909-1918`); each command is applied immediately (native `DP_PARAM_PRE_EQ_BAND` handler, `AV/…/EffectDynamicsProcessing.cpp:1065-1112`). A frame processed mid-update sees a mix of old and new bands. Equalizer314 documents that "faster rewrite storms stutter on some HALs" and spaces band writes ≥50 ms apart (`EQ314/audio/DynamicsProcessingManager.kt:137-139`, issue #61).
- **Enable/disable** is a single call (`AudioEffect.setEnabled`, `FB/media/java/android/media/audiofx/AudioEffect.java:674`); whether the framework switch is click-free is not determinable from source (`AV/services/audioflinger/Effects.cpp`) → hardware item.
- **Priority arbitration.** One engine per (session, effect type); handles are kept sorted by priority and a new handle is inserted *before* any with `priority <= newPriority`, so **an equal-priority newcomer takes control** (`AV/services/audioflinger/Effects.cpp:206-243`, comparison at `:224`). The loser's creation returns `ALREADY_EXISTS`, which Java accepts (`AudioEffect.java:532`), and the loser has no control. Control is handed on release (`Effects.cpp:337-341`). New controller inherits the previous handle's enabled flag (`:232-235`).
- **Control loss behaviour.** Every command except `GET_PARAM` from a non-controlling handle returns `INVALID_OPERATION` (`Effects.cpp:1901-1903` enable, `:2087-2089` commands) → Java `checkStatus` throws `UnsupportedOperationException("AudioEffect: invalid parameter operation")` (`AudioEffect.java:1449-1451`). The `DynamicsProcessing` constructor itself calls `setEngineArchitecture` (`DynamicsProcessing.java:210-222`), so **constructing while someone else holds ≥ priority throws from the constructor**; the half-built native handle is only freed by GC (cannot be `release()`d). Notifications: `setControlStatusListener(OnControlStatusChangeListener)` (`AudioEffect.java:1089`), `hasControl()` (`:1060`, throws `IllegalStateException` if released).
- **Public constructors:** `DynamicsProcessing(int audioSession)` (priority 0) and `DynamicsProcessing(int priority, int audioSession, Config)`; the 2-arg priority overload is `@hide` (`DynamicsProcessing.java:148-182`).
- Existing practice: Wavelet and Equalizer314 use priority `Integer.MAX_VALUE` (`EQ314/audio/SessionEffectManager.kt:531`; `EQ314/audio/AudioSessionReceiver.kt:15-16`); Equalizer314 reacts to loss with a 100 ms-delayed reclaim plus cooldown to avoid fights (`EQ314/audio/DynamicsProcessingManager.kt:223-234,268-283,600-620`); RootlessJamesDSP re-enables on regained control (`RJD/…/MutedAudioEffectFactory.kt:100-115`).

### Decision for PAudio
- **EQ/profile updates: no duck.** Diff-and-write only bands/gains that changed; write ch0 then ch1 on one dedicated `HandlerThread`; coalesce so consecutive writes are **≥50 ms apart**; never on the main thread; all setters inside `try/catch (UnsupportedOperationException, IllegalStateException, RuntimeException)`.
- **Personalized ⇄ Original (system-wide) and Bypass:** `setEnabled(false/true)` on each session effect (one call each) rather than rewriting 170 bands. On re-enable, no rewrite is needed unless control was lost.
- **In-app A/B swap (`AbPlayer.swapTo`)**: keep the spec's **20 ms duck** (player volume ramp 10 ms down / 10 ms up around the config push) because a swap pushes ~170 non-atomic writes; M7 may remove it only if the hardware test shows no artefacts.
- **Priority:** `EFFECT_PRIORITY = 100` (a named constant). Rationale: wins over normal-priority (0) users of DP, but does **not** out-bid `Int.MAX_VALUE` EQ apps, so a real conflict surfaces as `Conflict` instead of an endless take-over fight (SPEC §2.2 #15). Raising it later is a one-line change.
- **Conflict detection (all three):** (a) `UnsupportedOperationException` from the constructor ⇒ `Conflict(creation)`; (b) `OnControlStatusChangeListener(false)` or `hasControl()==false` ⇒ `Conflict`; (c) any setter throwing `UnsupportedOperationException` ⇒ `Conflict`. Recovery: listener `true` ⇒ re-apply full config + `setEnabled(true)`; user tap ⇒ release + recreate. No automatic reclaim loops.

### Impact on implementation
`DynamicsProcessingBackend`: constructor wrapped in try/catch returning `Result.Conflict`; `EffectHandle.control: StateFlow<Boolean>` driven by the listener and by caught exceptions; write throttle component (50 ms) with a coalescing queue; session cap 16 (SPEC §6). `FakeEffectBackend` must be able to simulate: ctor throws, control lost mid-apply. HARDWARE_TEST: toggle enable/disable during loud music (click check), run Wavelet simultaneously (Conflict state).

**M7a P0 race fixes (notes):** (1) A DP effect is never constructed enabled by PAudio: `createPort(..., enabled = false)` always; a rebuild enables the fresh port only in the install block under `portLock` from `desired()`. (2) Because a new controller inherits the previous controller's enabled flag (also when the controlling handle is released, `removeHandle_l`), every port is disabled before `release()` so another app's lower-priority handle does not keep running PAudio's curve. (3) `EffectEngine.acquire()` waits (bounded, 250 ms, `Object.wait` so the creating thread can finish) for in-flight backend creations, since the constructor inherits `enabled` until `createPort`'s `setEnabled(false)` ran; on timeout it proceeds and the creation completion re-reconciles against `tokens > 0`. A handle's `setEnabled(false)` likewise waits (bounded, 250 ms) for an in-flight rebuild. (4) A late `lostControl()` re-reads `hasControl()` instead of publishing `false` (a regain reported in between would stick in Conflict). (5) A failed rebuild reports `failed`; the engine releases the zombie handle (CREATION_FAILED: recreated after device change/retry; CONFLICT: after retry). `BypassToken.close()` un-bypasses asynchronously on the engine scope.

---

## 4. `AudioManager.getStreamVolumeDb(stream, index, deviceType)` reliability

### Finding
Public API 28. Throws `IllegalArgumentException` for non-public stream types, an index outside `[getStreamMinVolume, getStreamMaxVolume]`, or an invalid output device type; returns `Float.NEGATIVE_INFINITY` when the curve value is ≤ −758 dB (mute); otherwise the attenuation in dB (≤ 0; 0 dB or slightly negative at the maximum index, increasingly negative below) — `FB/media/java/android/media/AudioManager.java:1300,1329-1347`. It calls `AudioPolicyManager::getStreamVolumeDB` → `computeVolume(..., adjustAttenuation=false)` → the **nominal volume curve of the device category** (`AV/services/audiopolicy/managerdefault/AudioPolicyManager.cpp:6200-6205,8433-8451`). It deliberately skips the absolute-volume adjustment (`adjustDeviceAttenuationForAbsVolume`, `:8387-8431`), so for Bluetooth A2DP/LE devices that use AVRCP/absolute volume (the volume is applied in the headset), and for USB DACs with hardware volume, the number does not describe the acoustic change. Vendor curves (`audio_policy_volumes.xml`) differ per device; headsets may be "fixed volume".

### Decision for PAudio
Use it only as a **first-order step planner**: `Δdb = dB(idxNew) − dB(idxOld)` for the *current* output type, choose the index whose predicted level moves `L_ref` closest to −60 dBFS, set it, **then re-measure** (SPEC §4 flow 3 already allows ≤2 reruns). If a value is `−∞`/throws, fall back to ±1 index steps. Never trust it for absolute levels or for BT-absolute-volume sinks; log (never persist) the predicted vs measured shift for `HARDWARE_TEST.md` ("`getStreamVolumeDb` sanity", `@RequiresHardware`).

### Impact on implementation
`VolumeController.indexDb(i): Float?` returns null on exception or non-finite value. The calibration math in `:core:audiometry` takes a `(Int)->Float?` lambda, so it is unit-testable with a fake curve and has a no-dB path.

---

## 5. Safe-media-volume and CSD when calling `setStreamVolume`

### Finding
- Legacy safe-media state machine: `NOT_CONFIGURED=0, DISABLED=1, INACTIVE=2 (user confirmed), ACTIVE=3` (`FB/services/core/java/com/android/server/audio/SoundDoseHelper.java:105-114`). Devices covered: wired headset/headphone, USB headset, BLE headset/broadcast, **BT A2DP headphones** (`:343-358`). `checkSafeMediaVolume_l` is true when state==ACTIVE, stream aliases to MUSIC, device is in that set and `index > safeMediaVolumeIndex` (`:665-671`).
- In `AudioService.setStreamVolume`: if that check is true, `willDisplayWarningAfterCheckVolume` posts the **safe-volume warning dialog and stores a pending command; the index is NOT applied** (`FB/services/core/java/com/android/server/audio/AudioService.java:5093-5100`, `SoundDoseHelper.java:673-684`). The pending command is only replayed if the user confirms in system UI (`:686-705`). The index is also clamped at the device-volume message path (`AudioService.java:10102-10106`).
- With **CSD enabled** (`mEnableCsd`, Android 14+, region dependent) the legacy block is bypassed (`SoundDoseHelper.java:1012-1014`), but CSD warnings fire at 100 % dose and, at 500 % dose, `postLowerVolumeToRs1()` automatically lowers the music volume to the safe index (`:273-299`, `:1327-1345`) — an unprompted volume change during a test.
- The default safe index comes from `config_safe_media_volume_index` (×10) (`SoundDoseHelper.java:332-333`), typically about two-thirds of max, so `0.5×max` (SPEC §4 flow 3) normally sits below it.

### Decision for PAudio
- `setStreamVolume(STREAM_MUSIC, idx, flags=0)` then **always read back** `getStreamVolume`; if it differs from the request, treat the request as refused and show "Please set the volume with your phone's volume keys" (SPEC §2.2 #12). Never loop.
- Never request an index above the starting `round(0.5×max)` unless the calibration step planner (§4) requires it and the readback of the previous request succeeded; abort the test (volume-changed pause) when the observed index differs from the locked one for any reason (including CSD auto-lowering).
- No special-casing by device or country; no `disableSafeMediaVolume` (hidden/system).

### Impact on implementation
`VolumeController.setIndex(i): Int` returns the *effective* index (SPEC §5.2 already). The test state machine's `Paused(VolumeChanged)` covers CSD. Add fake-volume scenarios: refusal, delayed change, CSD auto-lower.

---

## 6. `master_mono`, `master_balance`, Spatializer, DND without permissions

### Finding
- Keys: `Settings.System.MASTER_MONO = "master_mono"` (int 1 = mono) and `MASTER_BALANCE = "master_balance"` (float −1…+1) are `@hide @Readable` — `FBM/core/java/android/provider/Settings.java:5565-5580`. `AudioService` reads them with the same keys (`FB/services/core/java/com/android/server/audio/AudioService.java:2739-2755`).
- Readability: `SettingsProvider.enforceSettingReadable` rejects only keys annotated in `Settings.*` that lack `@Readable` or exceed `maxTargetSdk`; "a key string that is not defined in any of the Settings.* classes will still be regarded as readable" (`FBM/packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java:2258-2310`). Both keys have plain `@Readable` (no `maxTargetSdk`). The `@Readable` gate was introduced with the Android 12 (API 31) settings hardening (not re-verified for older tags; before it, system settings were openly readable). ⇒ readable by normal apps on API 28–37 with no permission, via `Settings.System.getInt/getFloat(contentResolver, "master_mono", 0)` (string literals; the hidden constants are not accessible).
- Spatializer (API 32): `AudioManager.getSpatializer()`; `Spatializer.isEnabled()`, `isAvailable()`, `canBeSpatialized(AudioAttributes, AudioFormat)` have **no permission annotation**; the service side does not enforce one (`FB/media/java/android/media/Spatializer.java:82-112,521-528`; `AudioService.java:11494-11501,11549-11554`). Device/head-tracking variants are `MODIFY_DEFAULT_AUDIO_EFFECTS` (system only).
- DND: `NotificationManager.getCurrentInterruptionFilter()` → `INotificationManager.getZenMode()` with no enforcement (`FB/core/java/android/app/NotificationManager.java:2998-3005`; `FBM/services/core/java/com/android/server/notification/NotificationManagerService.java:5803-5805`). Values: `INTERRUPTION_FILTER_UNKNOWN 0, ALL 1, PRIORITY 2, NONE 3, ALARMS 4`.

### Decision for PAudio
- Mono: `Settings.System.getInt(cr, "master_mono", 0) == 1` ⇒ blocking warning. Balance: `abs(Settings.System.getFloat(cr, "master_balance", 0f)) > 0.01f` ⇒ blocking warning. Wrap in try/catch (OEM ROMs may restrict); on failure report "unknown" (non-blocking, one-line caution).
- Spatializer (API ≥ 32 only; below that = "not present"): **blocking** only if `isAvailable() && isEnabled() && canBeSpatialized(USAGE_MEDIA/CONTENT_TYPE_MUSIC, stereo 48 kHz float AudioFormat of the tone stream)`; if only `isEnabled()` is true show a non-blocking caution.
- DND: `filter in {PRIORITY, NONE, ALARMS}` ⇒ "on"; `ALL` ⇒ "off"; `UNKNOWN` ⇒ "unknown". Informational (F2 live status), never blocking.

### Impact on implementation
`EnvironmentChecks.snapshot(): EnvSnapshot(mono: Boolean?, balance: Float?, spatializerEnabled/Available/canSpatialize: Boolean?, dnd: DndState)`; nullable = unknown. All reads on `Dispatchers.IO`; no permissions added to the manifest. Content observers on `Settings.System` URIs are allowed for live updates (`Settings.System.getUriFor("master_mono")`).

---

## 7. `AudioDeviceInfo.getAddress()` for Bluetooth without `BLUETOOTH_CONNECT`

### Finding
`AudioDeviceInfo.getAddress()` returns `mPort.address()` verbatim (`FB/media/java/android/media/AudioDeviceInfo.java:472-474`). Anonymisation happens in the native `AudioPolicyService::listAudioPorts/getAudioPort/listAudioPatches` for BT device types (A2DP, BLE, SCO, hearing aid) when the caller lacks `BLUETOOTH_CONNECT`: `AA:BB:CC:DD:EE:FF` → `XX:XX:XX:XX:EE:FF` (first 11 chars replaced, only if length == 17) — `AV/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp:103-127,1775-1787,1821-1826,1864,1945-1952`; `AV/media/utils/ServiceUtilities.cpp:545-579`.
**Release gating (checked by greping tags):** absent in `android-12.0.0_r1`, `12.1.0_r1`, `13.0.0_r1`, `14.0.0_r1`…`r20`, `15.0.0_r1`…`r16`; **present** from `android-14.0.0_r30` and `android-15.0.0_r20` and in `android-16.0.0_r1+` / `main`. (Java-side `AudioPortConfig.toString` logging also anonymises, `AudioDevicePort.java:220`, irrelevant to apps.) So API 28–33 and un-patched 34/35 builds hand apps the **full MAC**.

### Decision for PAudio
`addressSuffix = address.takeLast(5)` for BT types (A2DP, BLE headset/speaker/broadcast, SCO, hearing aid) → `"AB:CD"` for both the full and anonymised forms; empty for wired/USB. **Never store, log or export the full address** (privacy, SPEC §6); drop it in the same function that reads it. Guard: if `address.length != 17`, use `address.takeLast(5)` only if it matches `[0-9A-Fa-f:]{5}`, else empty.

### Impact on implementation
`OutputDevices` adapter unit-tested with both address forms (Robolectric shadow). `HeadsetKey` unaffected by which form the OS delivers. No `BLUETOOTH_CONNECT` anywhere (SPEC §6 `verifyPermissions`).

---

## 8. Foreground service `specialUse`, boot start, fallback

### Finding
- Manifest: `<service android:foregroundServiceType="specialUse">` with a service-level `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="…"/>`, plus `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>`; constant `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 1 << 30` (`FBM/core/java/android/content/pm/ServiceInfo.java:545-592`). Type constants exist from API 34; on API 28–33 the attribute is ignored and `startForeground(id, notification)` without a type is used (`ServiceCompat.startForeground` does this).
- BOOT_COMPLETED restriction: for apps with `targetSdk ≥ 35` (`@EnabledSince(VANILLA_ICE_CREAM)`), an FGS started in the BOOT_COMPLETED allowance throws `ForegroundServiceStartNotAllowedException` unless its type is in `FGS_BOOT_COMPLETED_ALLOWLIST`. Default allow-list = `connectedDevice | health | remoteMessaging | systemExempted | **specialUse** | location`; **`mediaPlayback` is excluded** (also dataSync, camera, microphone, phoneCall, mediaProjection). Evidence: `FBM/services/core/java/com/android/server/am/ActiveServices.java:364-379` (change id `FGS_BOOT_COMPLETED_RESTRICTIONS`, @Overridable), `:1170-1180` (`shouldAllowBootCompletedStart`), `:2248-2252` (the throw); `FBM/services/core/java/com/android/server/am/ActivityManagerConstants.java:191-197` (default list), `:502,1581` (DeviceConfig key `fgs_boot_completed_allowlist`, so OEM/Google can change it remotely).
- Equalizer314 documents the same failure with `mediaPlayback` ("API 34+ … Pixel / Android 17 … throws ForegroundServiceStartNotAllowedException") and guards the call (`EQ314/audio/BootCompletedReceiver.kt:20-37`).
- Android 17 source is not public; behaviour on API 37 devices is unverifiable here.

### Decision for PAudio
- Manifest (`:service`):
  ```xml
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
  <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
  <service android:name=".PAudioService" android:exported="false" android:foregroundServiceType="specialUse">
      <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="system-wide audio equalizer: attaches per-ear DynamicsProcessing effects to other apps' audio sessions"/>
  </service>
  <receiver android:name=".BootReceiver" android:exported="true"> <!-- system broadcast only -->
      <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/></intent-filter>
  </receiver>
  ```
  `startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` on API ≥ 34, type-less below.
- **Boot behaviour:** `BootReceiver.onReceive(BOOT_COMPLETED)`: if (opt-in F23 && onboardingDone) `try { startForegroundService(...) } catch (ForegroundServiceStartNotAllowedException | SecurityException | IllegalStateException)` → post a high-importance "Tap to start PAudio" notification (own channel "Start on boot", tap = open the app's launch activity which starts the FGS from foreground). No `LOCKED_BOOT_COMPLETED` (DataStore lives in credential-encrypted storage). If notification permission is denied the fallback is silent (the Home screen shows "Engine not running").
- Never start the FGS from a `mediaPlayback`-style type; never request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- Play distribution (later) needs a `specialUse` declaration in Play Console; not needed for GitHub/F-Droid.

### Impact on implementation
`:service` module owns the manifest entries (merged into `:app`). Test with Robolectric: `BootReceiver` posts the fallback notification when `startForegroundService` throws. `HARDWARE_TEST.md`: "reboot with opt-in on API 35+/37; expect running service or the fallback notification".

---

## 9. Compose names: material3 1.5.0, foundation 1.13.0

Compose BOM 2026.11.01 pins foundation/ui/runtime/animation **1.13.0**, material3 **1.5.0**, material-icons-core 1.7.8 (BOM POM, `https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.11.01/compose-bom-2026.11.01.pom`). material3 1.5.0's POM depends on `androidx.graphics:graphics-shapes-android:1.0.1` → pin **1.1.0** explicitly (spec table).

### Finding
- **Wavy progress = `LinearWavyProgressIndicator`** (also `CircularWavyProgressIndicator`); there is no `WavyLinearProgressIndicator`. **Not experimental in 1.5.0** (no opt-in annotation in the file).
  Determinate (`M3/WavyProgressIndicator.kt:97-110`):
  `LinearWavyProgressIndicator(progress: () -> Float, modifier: Modifier = Modifier, color: Color = WavyProgressIndicatorDefaults.indicatorColor, trackColor: Color = …trackColor, stroke: Stroke = …linearIndicatorStroke, trackStroke: Stroke = …linearTrackStroke, gapSize: Dp = …LinearIndicatorTrackGapSize, stopSize: Dp = …LinearTrackStopIndicatorSize, amplitude: (progress: Float) -> Float = …indicatorAmplitude, wavelength: Dp = …LinearDeterminateWavelength, waveSpeed: Dp = wavelength)`.
  Indeterminate (`:169-180`): same but no `progress`/`stopSize`, `amplitude: Float = 1f`, `wavelength = LinearIndeterminateWavelength`, `waveSpeed = wavelength`.
  Defaults (`:386-496`): `LinearDeterminateWavelength` = token 40.dp (`tokens/LinearProgressIndicatorTokens.kt`: ActiveWaveWavelength 40 dp, Indeterminate 20 dp, ActiveWaveAmplitude 3 dp, ActiveThickness 4 dp, WaveHeight 10 dp), `LinearContainerWidth = 240.dp`, `LinearContainerHeight = 10 dp` (`:456-466`); `indicatorAmplitude` = 0 below 10 % and above 95 % progress else 1 (`:490-496`). `amplitude` is 0…1 of the max wave height. `waveSpeed` is **dp per second**, default = one wavelength per second, so it changes with `wavelength` unless set explicitly. The size is applied *inside* the caller's modifier (`modifier.then(…).size(240.dp, 10.dp)`, `:111-124`), so a caller `fillMaxWidth()` wins.
- **`MaterialShapes`** — `@ExperimentalMaterial3ExpressiveApi public sealed class MaterialShapes { companion object { val Sunny: RoundedPolygon …; val Cookie9Sided: RoundedPolygon … } }` (`M3/MaterialShapes.kt:139-140,247-249,267-269`; also `Cookie4/6/7/12Sided`, `VerySunny`, `Gem`, `Pentagon`…). Opt-in annotation: `androidx.compose.material3.ExperimentalMaterial3ExpressiveApi` (`M3/ExperimentalMaterial3ExpressiveApi.kt`, `@RequiresOptIn`, binary retention).
- **Shape/Morph APIs:**
  - `@ExperimentalMaterial3ExpressiveApi @Composable fun RoundedPolygon.toShape(startAngle: Int = 0): Shape` (`M3/MaterialShapes.kt:89-91`); `@Composable fun RoundedPolygon.toPath(startAngle: Int = 0): Path` (`:70-72`); `fun Morph.toPath(progress: Float, path: Path = Path(), startAngle: Int = 0): Path` (`:55-56`), all experimental.
  - `androidx.graphics.shapes.Morph(start: RoundedPolygon, end: RoundedPolygon)` (`GS/Morph.kt:39`), `asCubics(progress)` (`:118`), `inline forEachCubic(progress, MutableCubic, callback)` (`:173`); `MutableCubic` (`GS/Cubic.kt:441`). graphics-shapes' own `Morph.toPath(progress, android.graphics.Path)` is Android-only (`GS` androidMain `Shapes.android.kt:48-53`).
- **`MorphPolygonShape` exists in foundation 1.13.0:** `public fun MorphPolygonShape(start: PolygonShape, end: PolygonShape, progress: () -> Float): Shape` (`FND/MorphPolygonShape.kt:41-43`; not experimental). It morphs foundation's `PolygonShape` (DSL `PolygonShape { polygon(numVertices|vertices, …) }`, `FND/PolygonShape.kt:70,490-570`), which has **no entry point from a `RoundedPolygon`/`MaterialShapes` instance**, so it cannot morph `Cookie9Sided → Sunny`. Its internal `Morph.asComposePath` helper (`:113-135`) shows the pattern to copy.
- **Expressive theme entry point:** `MaterialExpressiveTheme(colorScheme: ColorScheme? = null, motionScheme: MotionScheme? = null, shapes: Shapes? = null, typography: Typography? = null, content)` — **not** annotated experimental in 1.5.0 (`M3/MaterialTheme.kt:237-265`); defaults to `expressiveLightColorScheme()`, `MotionScheme.expressive()`, `Shapes()`, `Typography()` (expressive typography still TODO `:259`). Plain `MaterialTheme` has a 4-arg overload with `motionScheme` (`:91-101`) whose default is `MotionScheme.standard()` (`:178`).
- **Motion:** `MaterialTheme.motionScheme` (`@Composable @ReadOnlyComposable`, `:149-150`); `MotionScheme.standard()` / `expressive()` (`M3/MotionScheme.kt:123,132`, stable); specs `defaultSpatialSpec<T>()`, `fastSpatialSpec`, `slowSpatialSpec`, `defaultEffectsSpec`, `fastEffectsSpec`, `slowEffectsSpec` (`:56-113`).

### Decision for PAudio
- `:designsystem` wrapper names: `FrequencyWaveProgress(progress: () -> Float, frequencyHz: Float, active: Boolean)` calling `LinearWavyProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth(), amplitude = { amp.value /*Animatable 0→1 while stimulus plays*/ }, wavelength = lerp(48.dp, 10.dp, log2(f/1000)/log2(14)), waveSpeed = 24.dp)`. Check visually that a 10 dp wavelength with the 3 dp token amplitude is not jagged; clamp to ≥12 dp if so (design call, record).
- Theme: `MaterialExpressiveTheme(colorScheme = <MaterialKolor scheme>, motionScheme = MotionScheme.expressive(), typography = RobotoFlex typography)`; features read `MaterialTheme.motionScheme.fastSpatialSpec<Float>()` for the hear-button spring.
- `HearButtonShape`: own `MorphShape(morph: Morph, progress: () -> Float): Shape` built on `graphics-shapes` `Morph.forEachCubic` (stable API, pattern from `MorphPolygonShape.kt:113-135`), with `Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny)`. All `MaterialShapes`/`toShape` use is confined to `:designsystem` behind `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` (SPEC §2.2 #19).

### Impact on implementation
Feature modules never import `MaterialShapes`/`Morph`. Compose compiler opt-in (`-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi`) is set only in `:designsystem`'s `kotlin { compilerOptions }`. Screenshot/UI tests cover the wave progress with a fixed `progress` lambda.

---

## 10. MaterialKolor 5.0.1

### Finding
Tag `5.0.1` (commit `cc4d2cb931ba0a45995abe988717a2cea9ed478e`, 2026-08-27). Maven coordinates (Android): `com.materialkolor:material-kolor:5.0.1` (module) → `material-kolor-android`, plus `material-color-utilities-android:5.0.1`.
- `DynamicMaterialTheme` (`MK/material-kolor/src/commonMain/kotlin/com/materialkolor/DynamicMaterialTheme.kt:42-62`):
  `fun DynamicMaterialTheme(seedColor: Color, isDark: Boolean = isSystemInDarkTheme(), isAmoled: Boolean = false, primary/secondary/tertiary/neutral/neutralVariant/error: Color? = null, style: PaletteStyle = PaletteStyle.TonalSpot, contrastLevel: Double = Contrast.Default.value, specVersion: ColorSpec.SpecVersion = ColorSpec.SpecVersion.Default, platform: DynamicScheme.Platform = Default, shapes: Shapes = MaterialTheme.shapes, typography: Typography = MaterialTheme.typography, animate: Boolean = false, animationSpec: FiniteAnimationSpec<Color> = defaultColorSpring, content: @Composable () -> Unit)`. Two more overloads (state-based and `primary: Color`).
- `DynamicMaterialExpressiveTheme` (`MK/…/DynamicMaterialExpressiveTheme.kt:47-69`): `@ExperimentalMaterial3ExpressiveApi`, same params plus `motionScheme: MotionScheme? = null`; defaults `style = PaletteStyle.Expressive`, `specVersion = SPEC_2025`; wraps `MaterialExpressiveTheme`.
- `rememberDynamicColorScheme` (`MK/…/DynamicColorScheme.kt:31-47`): `@Composable fun rememberDynamicColorScheme(seedColor: Color, isDark: Boolean, isAmoled: Boolean = false, primary… error: Color? = null, style: PaletteStyle = TonalSpot, contrastLevel: Double = Contrast.Default.value, specVersion: ColorSpec.SpecVersion = Default, platform: DynamicScheme.Platform = Default, modifyColorScheme: ((ColorScheme) -> ColorScheme)? = null): ColorScheme`. Non-composable: `dynamicColorScheme(...)` (same params).
- **Expressive flag:** `ColorSpec.SpecVersion { SPEC_2021, SPEC_2025; Default = SPEC_2021 }` (`MK/material-color-utilities/src/commonMain/kotlin/com/materialkolor/dynamiccolor/ColorSpec.kt:15-22`); `PaletteStyle.Expressive` exists (`MK/material-kolor/src/commonMain/kotlin/com/materialkolor/PaletteStyle.kt:10-29`). SPEC_2025 colour roles in `ColorSpec2025.kt`.
- **Dependency risk (important):** the Android POM lists runtime deps `org.jetbrains.compose.material3:material3-android:1.12.0-alpha03`, `org.jetbrains.compose.foundation:foundation-android:1.12.0`, `…runtime`, `…ui` (`https://repo1.maven.org/maven2/com/materialkolor/material-kolor-android/5.0.1/material-kolor-android-5.0.1.pom`). I verified from Gradle metadata that JetBrains' `material3-android:1.12.0-alpha03` is a redirect that `requires androidx.compose.material3:material3:1.5.0-alpha22` and `androidx.graphics:graphics-shapes:1.1.0`; Gradle will resolve androidx material3 to the BOM's 1.5.0. Maven Central rate-limited me before I could confirm that `org.jetbrains.compose.foundation:foundation-android` redirects likewise (module `foundation` 1.12.0 has `available-at foundation-android`). **M1 must run `./gradlew :designsystem:dependencies` and a clean `assembleDebug`** to confirm no duplicate classes.

### Decision for PAudio
`:designsystem` calls `rememberDynamicColorScheme(seedColor, isDark, style = PaletteStyle.Expressive, specVersion = ColorSpec.SpecVersion.SPEC_2025)` and passes the result to `MaterialExpressiveTheme` (so we control the motion scheme/typography ourselves and avoid the `@Experimental` `DynamicMaterialExpressiveTheme`). Static-brand and wallpaper (`dynamicLightColorScheme(context)`, API 31+) fallbacks per F25. If M1 finds duplicate classes: fall back to `com.materialkolor:material-color-utilities` directly, or exclude `org.jetbrains.compose.*` groups with a recorded rationale.

### Impact on implementation
`:designsystem` depends on `com.materialkolor:material-kolor:5.0.1`; theme module has a unit test that the seed-derived scheme differs for two seeds and that `SPEC_2025` is applied (smoke, JVM).

---

## 11. Material Symbols availability and SVG download

### Finding
Checked with `curl` against `https://raw.githubusercontent.com/google/material-design-icons/master/symbols/web/<name>/materialsymbolsrounded/<name>_24px.svg` (repo HEAD `10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7` on 2026-11-20). Pattern works (HTTP 200, SVG body): the default (weight 400, grade 0, 24 px, unfilled) file is `<name>_24px.svg`; filled = `<name>_fill1_24px.svg`; outlined/sharp variants are in `materialssymbolsoutlined/` / `materialsymbolssharp/` siblings. SVGs use `viewBox="0 -960 960 960"` (convert to a VectorDrawable with `viewportWidth=960 viewportHeight=960` and wrap paths in `<group android:translateY="960">`, or import with Android Studio Vector Asset Studio). License Apache-2.0.

**Exist (HTTP 200):** `volume_off`, **`graphic_eq_off`** (exists, contrary to the spec's doubt), `do_not_disturb_on_total_silence`, `noise_control_off`, `noise_control_on`, `hearing`, `hearing_disabled`, `ear_sound`, `headphones`, `earbuds`, `equalizer`, `graphic_eq`, `tune`, `monitoring`, `health_and_safety`, `settings`, `person`, `group`, `person_add`, `add`, `delete`, `content_copy`, `swap_horiz`, `swap_vert`, `upload`, `download`, `ios_share`, `play_arrow`, `pause`, `stop`, `check`, `check_circle`, `close`, `info`, `warning`, `error`, `bluetooth`, `bluetooth_connected`, `usb`, `cable`, `speaker`, `headset_mic`, `volume_up`, `volume_mute`, `mic`, `mic_off`, `notifications`, `bedtime`, `do_not_disturb_on`, `schedule`, `history`, `timeline`, `show_chart`, `bar_chart`, `lock`, `edit`, `more_vert`, `arrow_back`, `arrow_forward`, `chevron_right`, `expand_more`, `expand_less`, `folder_open`, `audio_file`, `library_music`, `music_note`, `help`, `thumbs_up_down`, `compare_arrows`, `palette`, `dark_mode`, `language`, `open_in_new`, `description`, `policy`, `privacy_tip`, `restart_alt`, `refresh`, `sync_problem`, `power_settings_new`, `accessibility_new`, `star`, `home`.
**Do not exist (HTTP 404):** `bluetooth_audio`, `headset`, `mic_none`.

### Decision for PAudio
- F2 rows: `volume_off` (quiet area), `graphic_eq_off` (noise cancellation — keep the spec glyph; `noise_control_on` is a semantically closer alternative if design prefers), `do_not_disturb_on_total_silence` (DND). No fallback to `noise_control_off` needed; keep it as a spare.
- Recommended set for the app: Home/nav `home`, `hearing`, `tune`, `health_and_safety`, `group`/`person`, `settings`; headset chips `headphones`, `earbuds`, `bluetooth`, `usb`, `cable`, `speaker` (speaker output state); engine states `graphic_eq`, `graphic_eq_off`, `sync_problem` (conflict), `warning`, `error`; actions `play_arrow`, `pause`, `swap_horiz` (A/B), `add`, `delete`, `content_copy`, `upload`/`download`/`ios_share` (import/export), `edit`, `more_vert`, `arrow_back`, `check`, `close`, `info`, `help`; charts `show_chart`, `timeline`, `monitoring`; mic `mic`, `mic_off`; misc `audio_file`, `library_music`, `thumbs_up_down` (preference test), `notifications`, `lock`, `history`, `restart_alt`.
- Vendor the chosen SVG→XML once via `tools/assets/fetch_icons.sh` (list of names + the curl pattern + a small SVG→VectorDrawable converter or manual Studio import); commit the resulting `res/drawable/ic_*.xml`. Record the repo commit hash in `ASSETS_LICENSE.md`.

### Impact on implementation
`:designsystem` exposes `PaIcons` (`@DrawableRes` constants); content descriptions are strings. M1 may start with Material Icons Core (BOM-pinned 1.7.8) placeholders for non-F2 glyphs if the converter is not ready.

---

## 12. NIN demo audio: URLs, license, clip ranges

### Finding
Archive.org metadata API: `https://archive.org/metadata/nine_inch_nails_the_slip` and `https://archive.org/metadata/nineinchnails_ghosts_I_IV`; both items have `licenseurl = http://creativecommons.org/licenses/by-nc-sa/3.0/us/` (CC BY-NC-SA 3.0 US). The licence statement shipped with each release (booklet PDFs):
- The Slip, `the_slip.pdf` page 14: "this album is licensed under a creative commons attribution-noncommercial share alike license. more information: www.creativecommons.org" (`https://archive.org/download/nine_inch_nails_the_slip/the_slip.pdf`).
- Ghosts I–IV, `nin_ghosts_I-IV.pdf` page 39: "This album is licensed under a Creative Commons Attribution-Noncommercial Share Alike license. More information: www.creativecommons.org" (`https://archive.org/download/nineinchnails_ghosts_I_IV/nin_ghosts_I-IV.pdf`).
Canonical licence text for `ASSETS_LICENSE.md`: https://creativecommons.org/licenses/by-nc-sa/3.0/us/legalcode.

Files (FLAC, 16-bit stereo **44.1 kHz**, verified by download; md5 matches archive metadata):

| Track | URL | Size | md5 | Duration |
|---|---|---|---|---|
| Discipline | `https://archive.org/download/nine_inch_nails_the_slip/04_discipline.flac` | 33,821,827 | `db80e4cb05ebfc788473e4aca925c127` | 259.18 s |
| Echoplex | `https://archive.org/download/nine_inch_nails_the_slip/05_echoplex.flac` | 28,710,116 | `75c98851b0a0d75b89c5277ec24537d1` | 285.24 s |
| 34 Ghosts IV | `https://archive.org/download/nineinchnails_ghosts_I_IV/34_Ghosts_IV.flac` | 29,668,263 | `84e4d7effe88ae5db2ae12c0ccd3d8b2` | 352.47 s |

(Ghosts I–IV track 34 is "34 Ghosts IV"; the archive also hosts `…_flac.zip` of the whole album.) Tools in this environment: `ffmpeg 6.1.1` with `libsoxr` (present), no `flac`/`soundfile`/`pyloudnorm`; numpy 2.5.3, scipy 1.18.1.

**Clip selection method** (`research/analyze.py`, `pick.py`): decode to 48 kHz float, 10 ms RMS envelopes in bands (<150 Hz, 1–4 kHz, >4 kHz, <80 Hz); tempo from the autocorrelation of low+mid onset flux; 30 s sliding windows (1 s step, ≥15 s from both ends, reject windows with any 1 s RMS below −26 dBFS) scored per axis: *presence* = mid+hi band share − 2·loudness-std + 0.3·loudness; *bass* = sub+lo share − 2·std + 0.3·loudness; *treble* = hi share + 0.3·mid − 2·std. Best window start snapped to the strongest onset within ±1 s; length = whole number of beats (8-beat multiples) nearest 30 s so the loop is rhythmically seamless. **Chosen by analysis only — not by ear; listen once in M4 and nudge by ±1 beat if a vocal/section boundary is cut.**

| Clip | In | Out | Length | Beats | Why (measured) |
|---|---|---|---|---|---|
| Discipline | **214.430 s** | **245.852 s** | 31.422 s | 64 @122.2 BPM | final climactic section; steady (loudness std 0.3 dB), highest mid+treble share of any 30 s window (mid −10.4 dB, hi −14.1 dB rel. total), no intro/outro silence; track ends at 259.18 s |
| Echoplex | **144.120 s** | **175.123 s** | 31.003 s | 32 @61.9 BPM | highest sub-80 Hz/<150 Hz share (lo −2.0 dB, sub −5.7 dB), std 0.3 dB, full-density section |
| 34 Ghosts IV | **43.110 s** | **74.411 s** | 31.301 s | 64 @122.7 BPM | only region with usable energy above 4 kHz (>4 kHz share −25.4 dB re total in the chosen window; 30 s Welch at t=30 s: 4–8 kHz −27 dB, 8–16 kHz −35 dB, vs −44…−64 dB elsewhere in the track); stops before the quiet decay starting ≈75 s |

Caveat: this track is dark (99.9 % of energy < 5.2 kHz in the best window, < 2 kHz in most of the track); the 8 kHz treble-shelf axis will be hard to hear — the "Can't tell" path (SPEC §4.5) matters; consider offering "Try another song" earlier for treble.

**Normalisation (measured with `ffmpeg ebur128=peak=true` on the chosen ranges):** integrated −6.0 / −7.8 / −14.0 LUFS, true peaks +0.9 / +0.3 / −0.3 dBTP → gain **−12.0 dB / −10.2 dB / −4.0 dB** reaches −18 LUFS with true peak −11.1 / −9.9 / −4.3 dBTP (≤ −1 dBTP, **no limiter needed**).

### Decision for PAudio
`tools/assets/fetch_demo_audio.sh` (M4): download the three URLs, verify md5, then per clip
`ffmpeg -ss <in> -t <len> -i <src>.flac -af "volume=<gain>dB,afade=t=in:d=0.025,afade=t=out:st=<len-0.025>:d=0.025,aresample=48000:resampler=soxr:dither_method=triangular" -ac 2 -sample_fmt s16 <name>.flac` (re-verify with `ebur128` that I = −18.0 ± 0.3 LUFS and TP ≤ −1 dBTP). Clip file names: `discipline.flac`, `echoplex.flac`, `ghosts_iv_34.flac` under `app/src/main/assets/demo/` (generated, not committed if >limit; committing three ~3 MB FLACs is acceptable). Attribution strings: "Nine Inch Nails — 'Discipline' / 'Echoplex' (The Slip, 2008), 'Ghosts IV' (Ghosts I–IV, 2008), CC BY-NC-SA 3.0 US, https://creativecommons.org/licenses/by-nc-sa/3.0/us/; modified: trimmed, loudness-normalised, resampled." Audio is **not** stored in the repo during M0.

### Impact on implementation
`ltas.py` computes LTAS per clip after normalisation. No-network fallback (M4): pink-noise placeholder with identical file names.

---

## 13. GPL code porting stance; how Equalizer314 / RootlessJamesDSP handle sessions, DP, control loss

### Finding (guidance for the engine engineer, with file refs)
Both are GPL-3.0 (`LICENSE` at the root of each repo: "GNU GENERAL PUBLIC LICENSE Version 3").

**Equalizer314** (`EQ314` = `app/src/main/java/com/bearinmind/equalizer314/`):
- Session intake: **manifest** receiver `AudioSessionReceiver` for OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION (`app/src/main/AndroidManifest.xml:216-223`, `EQ314/audio/AudioSessionReceiver.kt:19-60`), forwarding extras to `EqService` which starts an FGS (`mediaPlayback`, `AndroidManifest.xml:183-186`). (See "Receiver caveat" below.) Per-session effect creation: `DynamicsProcessing(Integer.MAX_VALUE, sessionId, config)` (`EQ314/audio/SessionEffectManager.kt:531`); global mode uses session 0 with the same priority (`EQ314/audio/DynamicsProcessingManager.kt:206`).
- DP config: `Config.Builder(FREQUENCY_RESOLUTION, 2 channels, preEq 128 bands, MBC ≥1 passthrough band, postEq 0/128 "interleave", limiter true)` + `setPreferredFrameDuration` (default 80 ms; 10 ms in compat mode) (`EQ314/audio/DynamicsProcessingManager.kt:16-31,179-199`); ladder 128 → 127 → 32 bands (`:160`, `:26`); stage order limiter → MBC → PreEQ → **enable last** ("never process default bands", `:207-220`); per-channel `setPreEqByChannelIndex(0/1)` and `setInputGainbyChannel(0/1)` for L/R (`:544-557`); config readback diagnostic (`:240-251`); 50 ms write spacing (`:137-139`).
- Control loss: `setControlStatusListener` / `setEnableStatusListener` → `reclaimSession()` with a cooldown (`:222-234,268-283`); `hasControl()` checks before every write and a retry timer (4×300 ms) for skipped writes (`:289,577,600-620`). Boot: `BOOT_COMPLETED` receiver that guards `startForegroundService` against `ForegroundServiceStartNotAllowedException` (`EQ314/audio/BootCompletedReceiver.kt`).

**RootlessJamesDSP** (`RJD` = `app/src/main/java/me/timschneeberger/rootlessjamesdsp/`):
- Session intake: manifest `SessionReceiver` for OPEN/CLOSE (`app/src/main/AndroidManifest.xml:270-274`; `receiver/SessionReceiver.kt:20-48`) → local broadcast + (root mode) service; session database `session/root/RootSessionDatabase.kt:32-61,81-110` (one effect per session id; duplicate ids rejected); optional `DUMP`-permission session discovery for non-broadcasting players (`AndroidManifest.xml:18`, polling in `session/shared/BaseSessionManager.kt`) — that is SPEC N1.
- Control loss: `MutedAudioEffectFactory` (`utils/MutedAudioEffectFactory.kt:85-115`): `setControlStatusListener`, re-enable on regained control inside try/catch, loss reported via a `sessionLossListener`.

**Receiver caveat (upstream beats the spec’s caution, but supports its conclusion):** both apps register OPEN/CLOSE in the **manifest**. In AOSP, delivery of an *implicit* broadcast to a manifest receiver of an app targeting ≥ API 26 is skipped by `BroadcastSkipPolicy` (`FBM/services/core/java/com/android/server/am/BroadcastSkipPolicy.java:211-230`: `getAppStartModeLOSP(…, alwaysRestrict=true)` ≠ NORMAL and `disallowBackgroundStart(r)` is true for an intent with no component/package and no `FLAG_RECEIVER_INCLUDE_BACKGROUND`, `:324-331`), regardless of whether the app is currently running. Manifest receivers therefore only work for players that send an explicit/`INCLUDE_BACKGROUND` broadcast. A **context-registered** receiver (dynamic `BroadcastFilter`) is not subject to that check. Hence SPEC §2.2 #9 stands: register dynamically in the running FGS with `RECEIVER_EXPORTED`; do **not** add a manifest receiver (keeps SPEC §6's exported-component list unchanged).

### Decision for PAudio — porting stance
- **Do not port any file or function verbatim** from Equalizer314 or RootlessJamesDSP. Everything PAudio needs (DP band mapper, session receiver, control handling) is derived from AOSP sources and re-implemented, with simulation-backed tests; this keeps the code base independent of GPL-derived code and keeps all of §1–§3 reviewable.
- **License is still GPL-3.0-or-later** (SPEC §10 default; user may override): the repository currently contains an **Apache-2.0** `LICENSE`; **M13 must replace it with the GPL-3.0-or-later text** (and add SPDX headers/README notice). If a later contributor ports GPL code, list the ported files here under a "Ported GPL code" heading; none exist at M0.
- Adopt (as ideas, not code): per-channel `setPreEqByChannelIndex` + `setInputGainbyChannel`; enable last; readback diagnostics; 50 ms write spacing; ≥1 passthrough MBC band as creation fallback; try/catch around all effect calls; no reclaim fights.

### Impact on implementation
No licensing blocker for the Apache-2.0 → GPL transition; `ASSETS_LICENSE.md` (M13) records NC-SA terms. The repo LICENSE file stays untouched in M0 (outside this task's file list).

---

## 14. Pinned versions

| Component | Version | Notes |
|---|---|---|
| Android Gradle Plugin | **9.5.1** | Min Gradle **9.6.0**, default 9.6.0, JDK 17, Build Tools 36.0.0; "maximum API level … 9.5 supports is API level 37" (`https://developer.android.com/build/releases/agp-9-5-0-release-notes`). 9.5.1: no public fixed issues. |
| Gradle | **9.9.2** (≥ AGP's 9.6.0 minimum) | Wrapper pin 9.9.2 (orchestrator-verified latest). |
| Kotlin | **2.4.21** | `org.jetbrains.kotlin.plugin.compose` 2.4.21; core modules use `org.jetbrains.kotlin.jvm` 2.4.21. |
| KSP | **2.3.14** | Room + Hilt. |
| Compose BOM | **2026.11.01** | foundation/ui/runtime/animation 1.13.0; material3 1.5.0; material-icons-core 1.7.8. aar-metadata: `ui`/`foundation` 1.13.0 require **compileSdk 37, minor 1** and AGP ≥ 9.1.0; `material3` 1.5.0 requires compileSdk 37 (minor 0); `core-ktx` 1.19.1 requires 37; `activity-compose` 1.13.0 requires 36. |
| material3 | **1.5.0** | |
| MaterialKolor | **5.0.1** | see §10 |
| Hilt | **2.60.1** | `androidx.hilt:hilt-navigation-compose` **1.4.0** |
| navigation-compose | **2.10.2** | |
| Room | **2.8.5** | |
| DataStore | **1.3.0** | |
| Media3 | **1.11.1** | |
| activity-compose | **1.13.0** | |
| lifecycle | **2.11.1** | |
| core-ktx | **1.19.1** | |
| kotlinx-serialization | **1.13.0** | |
| kotlinx-coroutines | **1.11.0** | |
| Robolectric | **4.18.1** | |
| Truth | **1.4.5** | |
| Turbine | **1.2.1** | |
| graphics-shapes | **1.1.0** | pinned explicitly (material3 1.5.0 POM asks for 1.0.1) |
| SDK | minSdk 28, targetSdk 36, **compileSdk 37.1** | DSL (AGP 9): `compileSdk { version = release(37) { minorApiLevel = 1 } }` (types `CompileSdkReleaseSpec`/`CompileSdkSpec` exist in the AGP 9.5 API reference). AGP 9.5's docs say max API level 37 → confirm in M1 that minor 1 is accepted (else `android.suppressUnsupportedCompileSdk=37.1` plus a recorded note). |

**Built-in Kotlin:** AGP 9.0+ ships built-in Kotlin and enables it by default — **do not apply `org.jetbrains.kotlin.android` / `kotlin-android`** in `:app`, `:designsystem`, `:data`, `:audio:*`, `:service`, `:feature:*` (applying it fails with "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0"). `kapt` is incompatible → use KSP (`com.google.devtools.ksp` 2.3.14). Plain-JVM `:core:*` modules still use `org.jetbrains.kotlin.jvm`. `kotlin.multiplatform` + AGP app/library plugins are not allowed together. Evidence: `https://developer.android.com/build/migrate-to-built-in-kotlin`. Hilt Gradle plugin 2.60.1 with AGP 9 must be verified in M1.

### Impact on implementation
`gradle/libs.versions.toml` records the table above; `docs/DECISIONS.md` is the source of the "why". M1 acceptance: clean `./gradlew check assembleDebug` proves the AGP/Kotlin/KSP/Hilt/MaterialKolor/compileSdk-37.1 combination.

---

## 15. ISO 389-7 source

### Finding
The spec's R_df table (SPEC §2.1.2) was compared value-by-value with **Table 11-1, "Diffuse-field listening (dB SPL re 20 µPa)"**, USAARL *Helmet-Mounted Displays*, ch. 11 (pp. 394–395), `https://usaarl.health.mil/assets/docs/hmds/Section-19-Chapter-11-Auditory-Perception-and-Cognitive-Performance.pdf` (cited there as ISO 389-7:2005). **All 19 values match** (1000 → 0.8 … 16000 → 43.7). Notes from the same page: the values are **binaural** thresholds (≈2 dB lower than monaural); the diffuse-field thresholds were measured with **third-octave noise bands**, not pure tones; the table also lists 18 kHz free-field only (diffuse "--").

### Decision for PAudio
Use the spec's table as the single source (`R_df`), record `source = "ISO 389-7:2005 via USAARL Table 11-1 (diffuse field, binaural)"` in `:core:audiometry` and in the algo version constant. The constant ≈2 dB binaural/monaural offset is absorbed by the anchor `A` (SPEC §2.1.3 step 3) and does not affect relative shape. **Caveat:** ISO 389-7:2019 supersedes 2005 and may differ slightly (not obtainable free); revisit with the user if the standard is purchased — a change would bump `algoVersion`. Grid points all lie in the table (no interpolation needed today).

### Impact on implementation
`Iso3897Table` is a plain list; unit test pins all 19 values and the `interp in log2(f)` rule for future grids.

---

## 16. Other discoveries / spec discrepancies (upstream wins)

| # | Spec text | Upstream reality | Action |
|---|---|---|---|
| D1 | §2.1.4 40 bands `c_k`; acceptance ≤0.5 dB | Staircase per FFT bin; N=512 default; 1/4-octave bands < 1 bin below ~490 Hz (N=512) | Bin-aligned layout at N=1024 (§1) |
| D2 | §2.1.3 limiter "threshold −1 dBFS … verify semantics" | Frame-RMS soft compressor | threshold −3 dB, preamp is the guard (§2) |
| D3 | §2.1.1 `VARIANT_*` both usable | TIME_RESOLUTION unsupported natively | FREQUENCY only (§1.1) |
| D4 | §2.2 #15 control loss shown via listener | Constructor can throw; setters throw `UnsupportedOperationException` | Three detection paths (§3) |
| D5 | §2.1 "AOSP anonymizes BT address to XX:XX:XX:XX:AB:CD" | Only in builds ≥14.0.0_r30 / 15.0.0_r20 | Suffix approach works either way (§7) |
| D6 | §2.1 icon doubt `graphic_eq_off` | Exists | Use it (§11) |
| D7 | §2.2 #10 "verify specialUse boot" | `specialUse` ∈ default boot allow-list (overridable) | Boot start + fallback notification (§8) |
| D8 | §2.3 / §9 "MorphPolygonShape" | Exists but on foundation `PolygonShape` | Own `MorphShape` (§9) |
| D9 | §5.4 compileSdk 37.1 | AGP 9.5 docs: max API 37 | Verify in M1 (§14) |
| D10 | §2.2 #9 manifest receivers never fire | True for implicit broadcasts (BroadcastSkipPolicy), yet two OSS apps ship manifest receivers (work only for explicit/INCLUDE_BACKGROUND sends) | Dynamic receiver only (§13) |
| D11 | §2.1.2 R_df "diffuse field" | binaural, 1/3-octave noise | Documented (§15) |

Other facts worth knowing:
- **Effect priority semantics** (equal priority → newcomer wins) and **shared engine per (session, type)**: constructing a second `DynamicsProcessing` on a session that already has one re-runs `setEngineArchitecture`, which re-creates the native engine (`EffectDynamicsProcessing.cpp:213-227,989-1005`) and wipes the other app's config — another reason to cap live sessions and to keep priority modest.
- **AIDL HAL clamping** (§1.7) is the most likely source of device-specific surprises; the readback + ladder in §1 is mandatory, not optional.
- Android 9 (API 28) engine differs (§1.3): expect a different window; the app should still work, with possibly ±1 dB ripple unmodelled. Test on an API 28/29 device only if available.
- AOSP `main` on googlesource is a 2025-03 snapshot; use tags (`android-16.0.0_r4`) for stable permalinks if the file lines above shift.

---

## 17. WHO grade ceiling of the §2.1.3 measurement model (orchestrator note)

**Finding.** Under the anchor `A = min_ears mean(N(1k), N(2k))`, the better ear's `HL_est(1k) + HL_est(2k) = 0` by construction, and censoring caps every point at `L_max = L_exp + 50`, so `HL_est(4k) ≤ 50 − A_rel`. Hence better-ear `PTA3 = HL_est(4k)/3 ≤ ≈17 dB` unless the better ear is *more sensitive* than the reference at 1–2 kHz (thresholds below `L_exp`). In practice the approximate WHO grade will almost always read "Normal"; "Mild" is reachable only in edge cases and Moderate+ essentially never (max PTA3 = 25 with 1–2 kHz at `L_min`).

**Decision.** Implement exactly as specified (normative). The UI copy for the grade must say it is approximate, that flat or low-frequency loss can't be detected, and must never imply "your hearing is fine". The golden set `mild_grade_reachable` (13th set, in addition to the 12 required) exercises the non-NORMAL path; WHO band boundaries are unit-tested directly on `whoGrade(pta)`.

---

## 18. Audiometry engine decisions (M3)

**Interpretations of SPEC §4 "Quick detail" (normative text kept; these resolve ambiguity).**
- *"Lowest level with ≥2 heard among ≤3 ascending presentations"*: the staircase stops at the **first** level to collect 2 ascending hits (standard practice; since levels are visited low-to-high over time this is the lowest qualifying level). Only the first 3 ascending presentations per level count.
- *Ascending presentation* = the previous answer of this staircase was "not heard", so **seek-climb presentations (+10 steps) count as ascending**; the very first presentation never does.
- *Censoring*: not heard at `L_max` during the seek climb censors immediately (literal spec); after the first "heard", 2 misses at `L_max` censor. *Floor*: 2 heard at `L_min` (including clamped repeats) ⇒ threshold `L_min`, `atFloor`, not low-confidence.
- *Reversal*: the level of a presentation whose answer differs from the previous answer. *Fallback* (15 presentations undecided): mean of the last 4 reversals (fewer ⇒ all; none ⇒ last 4 presented levels), low-confidence.
- *Start*: `min(L_max, otherEar+15)` if the other ear's point at that f is already finished, else `min(L_max, L_exp+20)`; then `≥ L_min`.
- *Catch scheme*: catch-to-catch distance (and start-to-first) uniform in {6,…,10} trials ⇒ rate 1/8 on average, no adjacent catches, every 10-trial window has ≥1 catch. Decided in `next()` from a counter (idempotent); the next gap is drawn when a catch is submitted. A catch keeps the slot (ear, f) the scheduler picked, is persisted with `levelDbfs = null`. False-positive rate = heard-on-catch / catches; > 20 % ⇒ unreliable.
- *Voided trials* (`restartPoint`, interruptions) stay in the engine log with `voided = true`; the persistence layer drops them (`persistableTrials`: contiguous `seq`, null catch levels).
- *Thorough*: ears alternate between consecutive runs including repeats (remaining runs are re-interleaved per ear). After a repeat: if the repeat pair agrees (≤15 dB) the point is the mean of the **repeat** pair; otherwise the mean of all 4 runs and low-confidence. Hard-limit reports (`L_max` ⇒ censored, `L_min` ⇒ floor) end the point.
- `initialIndex = round(0.5·max)` rounds halves **up** (12.5 → 13); Python's `round` is banker's, so any oracle must use `floor(x+0.5)`.
- Calibration: if the ramp is exhausted after a rerun that **lowered** the volume, `L_ref` is treated as ≥ −14 dBFS and the index is planned upward (while reruns remain); otherwise "can't hear".

**Spec discrepancy: Quick trial/time targets (§7.2, §9, §4 copy).** The normative procedure cannot meet "median ≤ 7 presentations per point / total ≤ 150 / Quick ≤ 6 min". Floor derivation: a *noise-free* listener at `T = L_exp` starting at `L_exp+20` needs 9 presentations (`+20 Y, +10 Y, 0 Y, −10 N, −5 N, 0 Y, −10 N, −5 N, 0 Y`). With the specified psychometric function (γ=λ=2 %, σ=2 dB) the achieved numbers (1000 seeded runs × 4 profiles, truths jittered U(−2.5,+2.5) dB) are: Quick median |err| 2.9–3.2 dB, p90 6.2–6.5 dB, median 9–10 presentations/point, total median 180–215 (p95 ≤ 235), catch rate 12.2–12.3 %, duration p95 7.2–8.4 min (model: heard 1.0 s; not heard 1.075 s + 0.5 s; silence U(0.4,1.2) s). About **6–9 % of points hit the 15-presentation fallback** even for an ideal listener. The orchestrator-approved assertions are: median |err| ≤ 3.5, p90 ≤ 7.0, median presentations/point ≤ 10, total median ≤ 220 and p95 ≤ 245, catch rate in [11.5 %, 13.5 %], duration p95 ≤ 9 min; Thorough median |err| ≤ 3 dB (achieved ≈ 0.8) and ≤ 10 min (7.2 min). **UI copy should say "Quick ≈ 6–8 min"** (the spec said 4–5).

---

## 19. Engine decisions (M7a)

- **Module split.** `DynamicsProcessingBackend` (platform) -> `DpPort` seam (JVM-testable diff/threading/readback in `DpEffectHandle`) -> `EffectEngine` (pure Kotlin over `EffectBackend` + flows). `EffectEngine` is also the `BypassController` (Hilt binds the same singleton).
- **Failure typing.** `EffectBackend.create` throws `EffectCreationException(kind)`: `UnsupportedOperationException` from the DP constructor -> `CONFLICT` (no retry); any other `RuntimeException` -> retry once with one disabled MBC passthrough band, then `CREATION_FAILED`. Engine: CONFLICT -> `Conflict` state (manual `retry()` only: a creation conflict has no control-regained signal), CREATION_FAILED -> `Error(CREATION_FAILED)`.
- **Writes.** One daemon worker thread (`paudio-dp-writer`) for all handles; `apply` is coalesced (latest config wins) and batches are >= 50 ms apart. `setEnabled` is deliberately synchronous on the caller (P0 bypass must be in effect when `acquire()` returns). Any setter throwing `UnsupportedOperationException`/`IllegalStateException` -> `control=false` and the next apply is a full rewrite. A band-count change (ladder step) rebuilds the effect inside the handle.
- **Readback / ladder.** After creation and after every full/changed write, bands {first, middle, last} x 2 channels are read back; > 0.05 deviation (cutoff or gain) sets `EffectHandle.clamped`. The engine steps `diagnostics.ladder` MAIN -> FB51 -> SPEC40 (sticky until engine restart) and does NOT re-plan itself: the `:service` collects `diagnostics.map { it.ladder }`, calls `DpEqPlanner.plan(..., ladder = ladder)` and `setTarget(newPlan.toEqConfig())`.
- **Enabling rule.** A handle is enabled iff `running && personalized && processable device && target != null && no bypass token && control`. Handles exist iff `running && processable device && target != null` (created disabled, also while bypassed). Original mode keeps handles, disabled. Speaker/earpiece/HDMI/other/null device releases all handles.
- **Bypass.** Reference-counted by live in-process tokens; idempotent close. Tokens cannot survive process death, so a new engine never starts bypassed ("restore on service start"). Stop/start of the engine inside one process keeps open tokens (the test is still running).
- **Session idle definition (>10 min).** Swept every 5 s: a session is released when `now - max(its last OPEN/re-OPEN, last time `isMusicActive()` was true) > 10 min` and music is not active now. Players announce only on session creation, so "idle" is judged by the global music flag. Known limitation: a player that is paused > 10 min and then resumes without a new OPEN loses its effect (the no-session hint then appears).
- **Cap.** 16 live sessions; further OPENs are ignored and counted (`sessionsIgnoredOverCap`); a re-OPEN of a known session refreshes it.
- **"No session" hint.** Session mode, running, zero sessions and `isMusicActive()` continuously for 60 s -> `noSessionHint = true`; reset on any session or silence.
- **Session source.** Context-registered receiver, `RECEIVER_EXPORTED`, only while collected; `parseSessionIntent` rejects session <= 0, null/blank package, own package. Legacy emits one `Open(0, "android")`. Switching modes releases every handle of the old mode before the new source starts (generation counter drops late events).
- **Logging.** Only exception class names and generic phrases; never gains, curves, cutoffs or package names.
- **Device verification needed.** See HARDWARE_TEST: DP creation on a real session, readback on AIDL-HAL devices, priority 100 vs another EQ app, enable/disable click check.

### 19.1 M7a review fixes
- **Inherited enabled flag (P0).** AOSP gives a new controller the previous controller's enabled flag, so `DynamicsProcessingBackend.create()`/rebuild always `setEnabled(desired)` (default false) before returning; the engine additionally forces a write after attaching a handle (`enabled = null`), a rebuilt handle re-writes the desired state when installed, and regained control re-asserts it immediately. Bypass therefore holds even if a player OPENs a session that already carries another app's enabled DP.
- **Ladder.** `EffectHandle.clamped` is now an event `Flow<Int>` carrying the clamped band count (a StateFlow cannot re-emit true -> true). The engine steps at most once per band count and only for the CURRENT target's band count; stale events from an old layout are ignored.
- **Write spacing** is enforced at execution time on the worker (re-scheduled if less than 50 ms since the previous batch ended).
- **Engine thread.** All inputs/events run on the (serial) engine scope; handle creation (slow binder call) runs outside the engine lock and is re-validated afterwards (entry epoch, can-hold, still no handle); `acquire()` only takes the lock for its disable loop. A handle rebuild never holds the handle's port lock while constructing, so `setEnabled(false)` never waits for it.
- **Spoofing limits.** At 16 sessions the least recently opened one is evicted (the newest is never ignored; trade-off: a spoofer can push real sessions out, but they re-OPEN); handle creations are limited to 16 per 4 s window (burst 16, sustained 4/s), excess creations wait; a CLOSE whose package differs from the OPEN's is ignored.
- **Error clearing.** CREATION_FAILED is cleared by `setMode`, by a device change and by `retry()`. A creation CONFLICT persists across device changes until `retry()` (or a mode switch) and a re-OPEN does not retry it.
- **UnsupportedOperationException from the constructor** is a CONFLICT only if `AudioEffect.queryEffects()` lists a DynamicsProcessing effect; otherwise CREATION_FAILED. A failed ladder rebuild is exposed as `EffectHandle.failed` (Error vs Conflict).
- **State details.** `Active(apps)` lists distinct packages of sessions that have a live handle (a session still waiting for creation is not shown; with none live the state is Waiting). `noSessionHint` is suppressed during bypass, without a processable device or without a target. `setRoute(device, target)` is the atomic way to change both.

---

## 20. Service + use-case decisions (M7c)

- **Single source of truth.** Everything the engine needs is derived from persisted state (DataStore engine mode + active listener + headsetKey map, Room profiles/tests, `OutputDevices`), so a START_STICKY restart after process death restores mode, Personalized/Original, route and target without any extra saved "engine state". Bypass is in-process only, so a restarted engine is never bypassed.
- **Personalized/Original** is persisted in the active profile's `personalizationOn` (not the global DataStore `personalizationEnabled`, which `:service` ignores; M8b should either remove it or give it a distinct meaning). The controller sets `setPersonalized(false)` BEFORE `setRoute` when turning off and AFTER it when turning on, so an "Original" profile is never briefly audible.
- **Atomic route.** The controller never calls `setOutputDevice`/`setTarget`. On a device change it first pushes `setRoute(newDevice, null)` (releases handles) and only then the new device's config once the profile's test has been read, so the old headset's curve can never run on the new route. A same-device profile/strength/ladder change swaps the config in place.
- **Unknown headset (F17).** A processable headset with no profile of the active listener (neither bound to its key nor remembered in the DataStore map) is `NoProfile` + notification ("Create profile" / "Use <Any headphones profile>"). The listener's "Any headphones" profile is deliberately NOT applied automatically: F17 and the `NoProfile` state imply the user must choose; "Use" stores headsetKey -> profileId in the DataStore map. Resolution order: session answer to "Which headphones are these?" > profile bound to the exact headset > remembered map entry (only if it belongs to the active listener) > NoProfile. If `activeListenerId` is unset or stale the first listener is used.
- **Wired collision (§5.5).** The model has one profile per (listener, headset), so two physical wired headsets on one key cannot have separate profiles. The "Which headphones are these?" question is raised when a WIRED/USB device CONNECTS (not at service start) and the active listener has >= 2 wired-bound/Any profiles; the answer applies until that device disconnects; it never blocks audio (the default resolution applies meanwhile). UI is M8b (`EngineController.whichHeadphones` / `answerWhichHeadphones`).
- **No test yet.** A profile without a complete test gets the preference-only curve (HL = 0, so only gB/gP/gT act); with gB = gP = gT = 0 it is an exactly flat plan (preamp 0, not the -0.5 dB margin the formula would give). The limiter stays on, so such a profile still engages the engine; the UI should invite the test. Incomplete threshold sets are ignored (as `HealthMapper`).
- **Memo key** (testId, strength, gB, gP, gT, algoVersion, fs, ladder), LRU 16. `fs` = the AudioManager `PROPERTY_OUTPUT_SAMPLE_RATE` (same logic as `AbPlayerFactory.outputSampleRate()`, duplicated in `AndroidMixerSampleRate` to avoid a Media3 dependency in `:service`; keep the two in sync).
- **Two `EngineMode` enums** exist (`audio.effect.EngineMode`, `data.settings.EngineMode`); the controller maps between them. Worth unifying in M12.
- **"User turned it off"** is a SharedPreferences flag (`ServiceFlags`), because the DataStore schema in `:data` was out of scope and the boot receiver/sticky restart need a cheap synchronous read. "Turn off" (notification or `EngineController.stop()`) sets it; an explicit start (`start()`, ACTION_START) clears it; a sticky restart with a null intent while it is set stops immediately. App launch starts the service only if onboarding is done and the flag is clear.
- **Notification.** Engine channel IMPORTANCE_LOW, ongoing, private lock-screen visibility, actions Personalized<->Original (only when a profile applies), Open app, Turn off; all PendingIntents explicit + FLAG_IMMUTABLE (service actions target `PAudioService` itself, "Use <profile>" a non-exported receiver, "Open app" the app's launch intent). The headset-prompt channel is IMPORTANCE_LOW (a sound on every connect would be hostile); the boot fallback channel is HIGH (DECISIONS §8). The `SOURCE_FAILED` copy ("PAudio could not listen for music apps...") is new (the spec table has no row for it). The "Use <profile>" action carries the headset key as an extra of an internal immutable PendingIntent (never logged).
- **App labels** in "Active in ..." use `PackageManager` with a `<queries>` MAIN/LAUNCHER intent (no permission); apps not visible are shown by package name.
- **Boot.** `BootLogic` catches `IllegalStateException` (superclass of `ForegroundServiceStartNotAllowedException`, so no API-31 class reference is needed) and `SecurityException`, then posts "Tap to start PAudio" opening the launch activity with `EXTRA_ROUTE = start_service`; `MainActivity` then starts the FGS from the foreground. The fallback is silent when notifications are denied (DECISIONS §8).
- **Notification permission.** `EngineController.notificationsBlocked` (from `NotificationManagerCompat.areNotificationsEnabled()`, refreshed on service start and activity resume) plus `EngineController.NOTIFICATION_PERMISSION_DENIED_COPY` (string `service_notification_permission_denied`). The FGS keeps running without the notification being visible (API 33+).
- **For the `:audio:effect` reviewer (no change made).** The controller relies on `setMode` then `setRunning(true)` being processed in call order on the engine's serial scope, and on `diagnostics.ladder` re-emitting after a ladder step. No bug found.
**M7a follow-up (acquire wait):** `BypassController.acquire()` can block its caller for up to ~250 ms (plus a possible bounded rebuild wait of the same size) when a handle creation or rebuild is in flight. It is called from `ExoAbPlayer.play()` (likely main thread) and `load()` (player looper). Accepted: far below ANR and only with in-flight work. Callers on main may move it to a background dispatcher later (M12). Creations also never start while a bypass token is held.

### 20.1 M7c review fixes
- **Restart in the same process.** Start posts `setPersonalized(false)`, `setRoute(null, null)`, `setRunning(true)` (and stop posts the reset before `setRunning(false)`), all under the controller's lifecycle lock after an `ensureActive()`; every later engine command from the pipeline goes through the same lock + cancellation check. Engine posts are FIFO, so a stop can no longer be followed by a stale start and a restart never inherits the previous route. The stored mode is collected without `drop(1)` (setMode is idempotent).
- **Launch replay.** `MainActivity` handles `EXTRA_ROUTE` only on a fresh launch (no saved state, not launched from history), removes the extra and replaces the intent; notification launch intents are SINGLE_TOP | CLEAR_TOP so taps reach `onNewIntent`.
- **Wired question rule (supersedes the §20 text above).** Asked only on a wired/USB connect when >= 2 non-Any profiles are bound to that very key; since a listener has at most one profile per headset, that means profiles of different listeners sharing a jack. Candidates are those profiles; a session answer may pick another listener's profile. Any + one wired profile never asks.
- **Newest complete test.** The curve uses the newest COMPLETE test among the 5 newest (an incomplete newer test no longer hides an older complete one).
- **Prompts.** Each headset is prompted once per process; "Don't ask for this device" persists in `ServiceFlags.ignoredHeadsetKeys` (SharedPreferences). The turned-off flag stays in SharedPreferences (cheap synchronous read for receivers).
- **Personalization.** `ProfileRepository.setPersonalization(id, on)` (single-column UPDATE, additive; the interface has a read-modify-write default) and a mutex-serialized toggle based on the stored value.
- **Crash guard.** `@ServiceScope` has a `CoroutineExceptionHandler` that logs the exception class only and raises `ServiceFaults`; the route pipeline retries with backoff (1 s .. 30 s) and the notification shows "PAudio hit a problem and is retrying…" meanwhile.
- **Foreground-start failure.** `PAudioService` posts the "Tap to start PAudio" notification (unless the user turned it off or the action is STOP); STOP sets the off flag before entering the foreground.
- **Other.** `fs` is read per route (a USB DAC with another rate: HARDWARE_TEST item: check `PROPERTY_OUTPUT_SAMPLE_RATE` vs the actual mixer rate per route); a profile or listener change on the same device also pushes `Route(dev, null, false)` first; channels are created once. The `<queries>` MAIN/LAUNCHER entry makes launcher apps visible to PAudio (needed only for app labels; mention in PRIVACY.md). The DataStore headsetKey -> profileId map is global across listeners (schema limitation); the resolver ignores entries pointing at another listener's profile.
## 21. Results screen decisions (M9b)

- **Route and callbacks:** `ResultsRoute(testId: Long)`; `resultsScreen(onSeeHealth(profileId, testId), onPreferenceTest(profileId), onDone, onRedoTest: (() -> Unit)? = null)`. `onRedoTest` is an additive optional callback (the pinned three are unchanged): the "Test again" button is shown only for a Quick test with a false-positive rate above 20 % (`reliable = false`) and only when the host passes it; the nav host wires it to `HearingTestRoute` popping Results (no exit dialog).
- **Plain words are not the WHO grade.** Per-ear wording ("Estimated close to / a little above / clearly above / well above your reference level") is derived from the WHO band edges of that ear's PTA3 but never names a grade and never says healthy/unhealthy (DECISIONS 17). The WHO grade lives on Hearing health only. Every number is "About X dB (estimated)", one decimal, truncated toward zero (19.6 never shows as 20).
- **A/B curve and gains.** Personalized G = `CurveBuilder.build(HL_est of THIS test, profile.compStrength, gB, gP, gT)` (raw, unclamped HL; display clamp only for the butterfly); both options go through `AbGainPlanner.resultsAb` (shared `P_common`, `inputGain = P_common - DeltaL(G)`), configs from `AbGainPlanner.configFor` (-> `DpEqPlanner.fromCurve/flat`) at `AbPlayerFactory.outputSampleRate()`. LTAS: the clip's for the demo clip, pink for user files. Profile settings are read once when the screen opens (not live).
- **Default option is Personalized** (the screen opens on it so the EQ graph shows G(f) and the first Play is what the user came for); Original is one tap away. Playback never starts automatically.
- **Player and P0 lifetime = the screen's STARTED period, not the ViewModel's.** `LifecycleStartEffect`: `startAb()` on start, `stopAb()` on stop or leaving composition. `stopAb()` pauses, releases the `ExoAbPlayer` and closes the bypass token(s). Reason: a ViewModel on the back stack (Results under Hearing health, or the app in the background) must not keep the system-wide engine bypassed. Returning re-creates the player (playhead restarts at 0, paused). The ViewModel also holds its own `RESULTS_AB` token (ref-counted, acquired before the player is created) in addition to the one `ExoAbPlayer.load()` takes, so the P0 guarantee is checkable in JVM tests with `FakeBypassController`. `onCleared` calls `stopAb()` too.
- **User file:** `ActivityResultContracts.OpenDocument` with `audio/*`; the content uri is held in memory only (no persistable permission, no storage permission); it is lost on process death and the clip comes back. An unreadable file shows an inline message and falls back to the demo clip.
- **Exit dialog (F12):** top-bar back, system back and Done all open it when results are shown; "Fine-tune now" -> `onPreferenceTest(profileId)`, "Not now" -> `onDone()`, tapping outside keeps the user on Results. With no results (unavailable) back leaves directly.
- **Seed colour (F25):** when the opened test is the profile's latest and `seedOverridden` is false, `ProfileSeedColor.from(display HL L/R)` is written with `ProfileRepository.setSeedColor(id, argb, overridden = false)`. Best effort (failures ignored).
- **Spec note:** SPEC F9/F10 say the A/B uses "Discipline"; only Discipline is offered here (`DemoClip.DISCIPLINE`); the other clips are for the preference test (M11). Number reused: if the parallel M9a agent also appended a "20", the orchestrator should renumber one.

## 22. Core screens decisions (M8b)
- **Feature ↔ engine seam:** features depend on `:service` but each declares a narrow interface over `EngineController` (e.g. `HomeEngine`, `SettingsEngine`) with a Hilt `@Binds` adapter; ViewModels never see the controller directly. Reason: plain-fake ViewModel tests; the controller is a concrete class with Android dependencies.
- **Start destination:** `AppViewModel` reads `SettingsRepository.onboardingDone` once (Loading → Home/Onboarding) so the back stack never contains onboarding after it is done.
- **Notification "Create profile" route** is ignored before onboarding is done (no listener exists yet).
- **Empty-state CTA copy** is "≈ 7 min" (HANDOFF §3; Quick ≈ 6–8 min per §18), not the spec's "≈5 min".
- **Retest / preference callbacks** carry the profile id, but `HearingTestRoute` / `PreferenceRoute` were still objects when M8b was written; the nav host adapts them at merge time.

---

## 23. Hearing-test flow decisions (M9a)

- **Route / profile.** `HearingTestRoute(profileId: Long? = null)`. `null` = the active listener's profile for the headset connected when the test STARTS. The listener (a default "Me" if none exists), headset and profile are created at SAVE time (aborted tests leave no rows). Non-null = retest that profile; its headset must be the connected one (blocking warning otherwise). Speaker/earpiece/HDMI/unknown/no output is never tested (blocking copy; the session also aborts with `NO_HEADPHONES`). Nav host: `popUpTo<HearingTestRoute>` (data class), `HearingTestRoute()` at Home/Results call sites, `HearingTestRoute(profileId = it)` from Profiles retest.
- **P0.** One token (`BypassReason.HEARING_TEST`) from before `ToneOutput.open` to after `close()`, also while paused. Spec tension "never un-bypass mid-test" vs "don't hold the EQ off forever": a pause longer than 5 min aborts the session (cleanup restores everything).
- **Pre-test volume.** The stream volume is set to round(0.5·max) (and locked, drift monitored) BEFORE the channel check, because the channel-check tone needs a defined volume; the spec lists the volume step inside calibration. Original index restored in `finally`, but `VolumeLock` never raises a volume the user lowered meanwhile.
- **Channel check level.** Runs before `L_ref` exists: -45 dBFS (≥ 20 dB under the -10 dBFS calibration cap), "I can't hear anything" raises it in 5 dB steps up to -30 dBFS, then abort `CANT_HEAR`. Both ears are asked once each in random order (spec: one random ear) so a swapped OR mono output is always caught; options Left / Right / Both ears.
- **Calibration.** Exactly SPEC §4 via `CalibrationSession`; additive `startIndex` parameter. A refused rerun volume change asks for the volume keys (drift suppressed, then the user's index is adopted). Calibration is reused 30 min per headset+maxIndex (index re-locked, falls back to a full run if refused).
- **Responses.** Explicit answers ("I heard it" / "I didn't", no timeout). A response during the stimulus (also "I didn't") ends it: `playing` + `stopCurrent()`, and haptic/announcement signals are emitted only after the stimulus is silent. Taps between trials are ignored. Gap U(400,1200) ms is a `playSilence` on the same stream, drawn before each trial.
- **Pause.** Reasons: headset (also a DIFFERENT headset), volume drift (+ live index check before every trial), focus loss (also CAN_DUCK), ON_STOP. Resume is always a user tap; Resume restores focus and the locked index (refusal keeps the pause and asks for the keys). Only the interrupted point restarts (`restartPoint`; Thorough: `cancelRun`); a dropped catch trial is simply redrawn. A lost stream (`IllegalStateException` from the tone) re-opens it and redoes the unit, max 3 times, then `AUDIO_ERROR`. Caught `CancellationException` must be rethrown: it IS an `IllegalStateException` (found by the cancellation fuzz test).
- **Guessing.** Mid-test prompt after >= 8 catch trials with FP > 20 %, and again at the end if not acknowledged: Redo (fresh engine) or Continue/Keep (saved `reliable = false`). Thorough: `reliable = true`, per-point `lowConfidence` carries it, `fpRate = null`.
- **Dial haptics (trap #20 vs "tick per detent").** The Thorough tone loops, so a stimulus is nearly always audible. A detent crossed while `playing` is remembered and its single tick fires in the next silent gap; nothing vibrates during a pulse. Dial = thumb follows the finger 1:1 over the whole range plus Louder/Quieter buttons and TalkBack custom actions (no numbers anywhere).
- **TalkBack.** No live regions near the progress/stimulus; a 1 dp live region changes only after a response ("Heard"/"Not heard"/"Level saved"). That speech can still overlap the next stimulus gap (400–1200 ms); device item for HARDWARE_TEST.
- **Saved test.** `startedAt` wall clock, `durationMs` = Start to save incl. pauses, `volIndex/volMax/lRef` from calibration, snapshot JSON `{type, productName, addressSuffix, calibrationWarning}` (+ `headsetKey` added by the repository), `ancOn` = the checkbox, `algoVersion = 1`, `appVersion` from the package.
- **Timing (SPEC §9 vs §18).** The spec's Quick <= 6 min is unreachable (DECISIONS §18); simulated with the §18 model the Quick test phase takes 7.3–8.2 min (test asserts < 9), Thorough 7.9 min at 12 s/run (< 10). UI copy "Quick ≈ 6–8 min".

---

## 24. Preference test decisions (M11)
- **Route:** `PreferenceRoute(profileId: Long? = null)`. null = the active listener's profile for the current headset: `ActiveProfileResolver` (Resolved profile, else the "Any headphones" offered by NoProfile, else the active listener's `ensureAnyHeadphonesProfile`). Explicit ids come from Results (the profile of the opened test) and Profiles; Home passes null.
- **Baseline.** g_h = hearing compensation from the profile's newest COMPLETE test (`ProfileCurveService.loadSource`) at the profile's own compStrength; the profile's existing gB/gP/gT are NOT part of the comparison baseline (the bisection restarts at c = 0, other axes sit at their current centres). A profile with no complete test uses HL = 0 (preference curve over flat compensation); the intro says so.
- **Player + P0 lifetime** follow Results (DECISIONS 21): the audio exists only while the screen is STARTED (`LifecycleStartEffect` -> `startAudio` / `stopAudio`); the ViewModel takes its own `PREFERENCE_AB` token before the player is created, in addition to the `ExoAbPlayer.load()` token. Leaving STARTED releases the player and tokens; returning re-creates a paused player for the same round (the gate state is kept). The player is also released on the result screen, and after saving.
- **Reload keeps playing:** changing the clip pauses, reloads and resumes if it was playing; a new round with the same clip only swaps (no reload), so listening continues across rounds.
- **Try another song** cycles with `DemoClip.next()` from the clip currently shown; the choice is sticky for the remaining rounds (a user who dislikes a song should not be switched back at the next axis); without it each axis uses its default clip. The fine-tune clip picker is a three-chip choice and also sticky.
- **Listen buttons** (A / B) swap instantly and start playback if paused; side attribution for the gate goes through `ExoAbPlayer.swapTo(cfg, gain, side)` (the seam's `swap`). Fine-tune swaps pass no side.
- **Gate** is fed only by `listenTicks` (real audible time) and resets every round; "Can't tell" is never gated.
- **Slider quantisation:** bass/presence/treble snap to 0.5 dB inside the bisection clamps (bass [-6, 12], presence [-6, 6], treble [-8, 8]); strength is continuous 0..1. Values coming from the bisection (e.g. 0.75) are shown as is until moved. Slider drags are debounced 150 ms before the player is re-planned; the graph updates immediately.
- **Persistence:** `PreferenceTest.gB/gP/gT` = the bisection result (what the test measured); the profile's compStrength/gB/gP/gT = the fine-tuned values (what the user will hear). `personalizationOn` is preserved. `roundsJson` is a JSON array of `RoundRecord` (round, axis, aGain, bGain, plusSide, choice A/B/CANT_TELL, pickedGain or null, clip), a superset of core's `RoundLog` (adds the clip). The system-wide engine picks the change up through its profile observation (`ProfileCurveService` memo key includes strength/gB/gP/gT).
- **Progress copy:** "Comparison n of up to 9": axes ended by "Can't tell" make the test shorter, so no fixed total is promised.
- **Blind rounds:** no curve or gain is shown during a round; only a one-line neutral hint per axis ("Listen to the low notes...").
- **Leaving mid-test** (back, system back) asks for confirmation; nothing is saved without "Save".
- **Spec notes:** none contradicted. SPEC section 4 says the spec-level ">= 2 s on each of A and B" — implemented via `AbRoundGate` as specified. The feature depends on `:service` (like Home/Settings) only for `ActiveProfileResolver` / `ProfileCurveService`.
- **M11 review fixes.** (1) A failed `createPlayer`/`load` calls `releaseAudio()` (token + handle) and shows ERROR; Retry re-acquires. (2) `choose`/`cantTell` carry the round number the UI showed and are ignored when it is not the current round (double tap). (3) Listen ticks are dropped from the start of a round until its plan is swapped in (`roundApplied`). (4) Save runs in `NonCancellable` (no transaction API in `:data`; the two writes cannot be interleaved with a cancel) and re-reads the profile's current `personalizationOn`. (5) `launchPrepare` is a no-op once saved. (6) The strength slider is disabled, with an explanatory note, for a profile without a hearing test. **Process death** returns to the Intro: nothing is persisted before Save (the ViewModel state is not saved).

### 24.1 Preference re-entry goes to fine-tune
- **Bug:** every entry to `PreferenceRoute` (Home, Results, Profile) restarted the A/B wizard, even after the user had finished it; the fine-tune (F15) was reachable only through RESULT after nine rounds.
- **Rule:** `PreferenceBaseline.hasPreferenceTest` = the profile has at least one saved `PreferenceTest` (`PreferenceTestRepository.latest(profileId) != null`; no `:data` change was needed). When true, `load()` opens straight on `FINE_TUNE`, seeded with the profile's saved strength and gB/gP/gT (clamped to the slider ranges, not re-quantised, so a saved 0.75 stays 0.75), `personalized = true`, the default clip, and `gLeft/gRight` from the same `refreshCurve()` the post-rounds path uses. Otherwise (first-timers, or a profile without a preference test) the INTRO is shown as before.
- **Audio / P0 unchanged:** load creates nothing. It calls `launchPrepare()`, which is a no-op until the screen is STARTED (`startAudio`); if the screen was already STARTED, the player and the `PREFERENCE_AB` token are prepared exactly as after RESULT -> fine-tune. A load failure shows UNAVAILABLE with no token.
- **Wizard stays reachable:** the fine-tune screen has a "Redo the A/B test" text button (below Save) calling `begin()`, which releases the audio and resets the bisection and records. The wizard then starts at the centre 0 on every axis, as always (the saved gains are not its baseline, section 24). Leaving still asks for confirmation (nothing is saved without Save).
- **Saving:** the ViewModel remembers whether this session ran the wizard (`ranRounds`, set by `begin()`). A session that only fine-tuned calls `PreferenceData.saveSettings(profileId, settings)`: the profile's strength/gB/gP/gT only, in one `DataTransactions` transaction under `NonCancellable`, re-reading `personalizationOn` inside it; it never inserts a `PreferenceTest` (there are no rounds to record). A session that redid the wizard uses the full `save` (test row + settings), as before.
- **A11y:** the fine-tune song chips were narrower than 48 dp; they now have a 48 dp minimum width. The preference module gained the shared strict audit (`A11yAudit`) for the fine-tune screen.
- **Entry labels not changed:** Home ("Preference test") and Profile labels are static strings with no per-profile preference-test state available to them; making them data-driven would need new plumbing in `:feature:home` / `:feature:profiles`. Left as is.

---

## 26. M12 hardening

(§25 is reserved.)

### 26.1 R8 release build
- `./gradlew :app:assembleRelease` succeeds with `isMinifyEnabled = true` and `isShrinkResources = true` (unchanged from the convention plugin) and `verifyReleasePermissions` (7 permissions, none forbidden). Output: `app/build/outputs/apk/release/app-release-unsigned.apk` (~15 MB).
- **Signing.** No keystore is committed. With `keystore.properties` or `PAUDIO_KEYSTORE_FILE/_PASSWORD/KEY_ALIAS/KEY_PASSWORD` the release type is signed with it (as before); without them the APK stays **unsigned**. For a local install/smoke test it can be signed with the debug key on request only: `./gradlew :app:assembleRelease -Ppaudio.signReleaseWithDebugKey=true` (or `PAUDIO_SIGN_RELEASE_WITH_DEBUG_KEY=1`). The flag is off by default so a distributed build can never silently carry the debug key. SPEC §9's "signed (test key)" is therefore met by that flag (or by `apksigner sign` with any throw-away key).
- **Keep rules** (`app/proguard-rules.pro`): the AARs of Hilt, Room, Media3, Navigation, DataStore and kotlinx.serialization carry consumer rules; the app file adds belt-and-braces for what PAudio itself reaches by name: `@Serializable` classes in `my.umarh.paudio.**` (the `$$serializer` classes, `Companion`, `INSTANCE`, `serializer()`; the official kotlinx.serialization `-if` rules), model enum members (names appear in exported profile JSON and `HeadsetKey` strings), `@Entity` classes and `RoomDatabase` subclasses, Hilt entry points / `@HiltAndroidApp`, and the manifest-named application / activity. `Log.v/d/i` stay stripped (`-assumenosideeffects`).
- **Verified without a device** with `apkanalyzer dex packages` against `mapping.txt`: every route (`HealthRoute`, `HearingTestRoute`, `PreferenceRoute`, `ProfileDetailRoute`, `ProfilesRoute`, `ResultsRoute`) keeps its `$$serializer`; `HomeRoute` / `OnboardingRoute` (data objects) keep `serializer()` and `INSTANCE`; `ProfileFile$$serializer` and the other profile-JSON serializers (`core.model.*Json`) are present with unobfuscated names; `PAudioDatabase_Impl` is present. Not checkable here: an actual launch and the full flow on a device (HARDWARE_TEST section 10).

### 26.2 Privacy checks
- **`verifyNoSensitiveLogging`** (root task, wired into `check`; `VerifyNoSensitiveLoggingTask` in build-logic, same pattern as `verifyFeatureBoundaries`). The M12 version was a denylist regex over `Log.*` / `println` / `Timber.*` call text. The review showed it was easy to bypass (qualified `System.out.println`, two-argument `Log.w(tag, e)`, `e.toString()`, identifiers outside the denylist such as `bandGains`) and it had false positives (commas and a trailing backslash inside strings); it was only shown to catch one planted `Log.d(..., "$thresholds")`. **Superseded by the allowlist scanner in section 29**, which is what runs now; this paragraph is kept as history.
- The existing log calls (`AppViewModel`, `DynamicsProcessingBackend`, `ServicePlatform`) complied with the M12 rule; under the section 29 rule one needed a change (29.1). SPEC §9's "grep over a release logcat capture" is a device item (HARDWARE_TEST section 10); the static check covers PAudio's own sources, not library output.
- **Backup**: `allowBackup="true"`, `dataExtractionRules` (cloud: exclude database, sharedpref, `datastore/`; device transfer: include them) and `fullBackupContent` (same exclusions) match docs/PRIVACY.md; pinned by `M12AuditTest::backupPolicyMatchesThePrivacyStatement`.

### 26.3 §2.2 trap audit
Status: **pinned** = a JVM/Robolectric test fails if the behaviour regresses; **device-only** = needs hardware (HARDWARE_TEST.md); **gap** = not pinned by an automated behavioural test. Tests are `file::name`; **M12** = added in this milestone.

| # | Trap | Pinned by | Status |
|---|---|---|---|
| 1 | P0 self-coloring | `EffectEngineTest::p0_noEnabledEffectWhileBypassTokenOpen`, `::p0_nestedTokensAndIdempotentClose`, `::p0_inheritedEnabledFlagNeverProcessesDuringBypass`, `::p0_controlRegainedDuringBypassStaysOff`, `::noCreationDuringBypassUntilTokenCloses`, `::restoreAfterSimulatedProcessDeath`; `EngineBackendIntegrationTest::p0_*` (3); `ReviewRaceTest` (3); `TestSessionTest::p0WithTheRealEffectEngine_noEnabledEffectWhileTonesAreActive`, `::quickHappyPathReachesSaveAndKeepsBypassAroundEveryTone`; `ResultsViewModelTest::p0_bypassTokenIsAcquiredBeforeThePlayerExistsAndLoads_andHeldUntilStop`; `PreferenceViewModelTest::bypassTokenIsHeldFromLoadUntilRelease`; `ExoAbPlayerTest::bypassAcquiredBeforePrepareHeldUntilRelease`, `::ownSessionSetBeforePrepareAndOneEnabledEffectOnIt`; own-package OPEN ignored: `SessionSourcesTest::parseValid` / `::parseRejectsInvalid` | pinned |
| 2 | Raw thresholds as dB HL | `GoldenModelTests::measurementModelSet5a`; `ResultsStringsTest::everyPtaWordingSaysEstimated`; `HealthStringsTest::gradeLabelsAreApproximateAndEstimated`; `ResultsMapperTest::ptaWording_bandEdgesFollowTheWhoBandsButNeverSayHealthy` | pinned |
| 3 | 1:1 compensation | `CurveGoldenTest::curvesMatchOracle`, `::knotsAreClampedToMinus6Plus12`, `::totalCurveIsClampedAndPreampFloored`, `::preampUsesTheLouderEar`; `DpPlannerTest::ladderStepsAndLoudness` | pinned |
| 4 | Un-ramped tones | `StimulusSynthTest::goldenSet7` (out-of-band <= -50 dB), `::qualityOnEveryGridFrequencyAndSampleRate`, `::earlyStopRampsDownToExactZero`; `StreamSchedulerTest::earlyStopRampsDownWithoutDiscontinuity` | pinned |
| 5 | One AudioTrack per beep | `StreamEngineTest::playReturnsAfterStimulusPlayedAndStreamKeepsRunning`; `StreamSchedulerTest::emitsDigitalSilenceWhenNothingQueued`, `::backToBackStimuliAreGapless`; `AudioTrackToneOutputTest::playingFlowIsOneStableFlowAcrossCloseAndReopen`, **§27** `::oneSinkForManyStimuliGapsStopsAndRepeatedOpens` (the sink factory runs exactly once across stimuli, gaps, an early stop and repeated `open(sameRate)`); session level **§27**: `TestSessionTest` / `ReviewFixesTest` `assertCleanedUp` / `assertReleased` require `tone.openCount == 1` and `closeCount == 1` on every happy, pause/resume and abort path (only a lost stream re-opens: `::aLostStreamRedoesTheTrial` = 2, `::aDeadStreamAbortsWithAudioError` = 1 + 3); a close/open before every Quick stimulus fails 30 tests. (`OneStreamTrapTest` alone was vacuous: the engine only calls its factory after a failed write.) Bluetooth first-pulse loss itself is audible only on a headset (HARDWARE_TEST section 3) | pinned |
| 6 | Pure tones at high f | `StimulusSynthTest::pureBelowWarbleFromFourKilohertz` | pinned |
| 7 | `cutoffFrequency` = top edge | `DpMappingGoldenTest::bandGainsAndReconstructionMatchOracleAndMeetAcceptance` (<= 0.5 dB RMS), `::blockSizeAndLayoutsMatchOracleExactly`, `::geometryAt48kHz`; `DpFromCurveTest::matchesLayoutAndMapperAndKeepsPreamp` | pinned |
| 8 | Unmatched loudness in A/B | `AbGainPlannerTest::broadbandPlusSixIsSixDbQuieterThanOriginal`, `::commonPreampProtectsAgainstClippingOfAnyOption`, `::flatVsFlatHasEqualGains`; `LoudnessGoldenTest::predictorMatchesOracle`; `ResultsViewModelTest::toggle_swapsBetweenFlatOriginalAndPersonalized_sharingTheCommonPreamp`; `PreferenceViewModelTest::fineTuneToggle_usesTheSameLoudnessMatching`. The by-ear match is a device check (section 6) | pinned |
| 9 | Session receiver | `SessionSourcesTest::broadcastReceiverRegisteredOnlyWhileCollected`, `::parseRejectsInvalid`; `EffectEngineTest::sixteenSessionCapEvictsLeastRecentlyOpened`, `::invalidSessionsIgnoredByEngine`; **M12** `M12AuditTest::sessionReceiverIsRegisteredDynamicallyWithReceiverExported`, `::noManifestReceiverForAudioEffectSessionBroadcasts`. Reception from real players is a device check | pinned |
| 10 | FGS type / boot | **M12** `M12AuditTest::foregroundServiceIsSpecialUseNotMediaPlayback`, `::bootReceiverOnlyHandlesBootCompleted`; `AndroidServiceTest::bootStartsTheServiceWhenAllowed`, `::bootPostsTapToStartWhenTheForegroundStartIsNotAllowed`, `::bootDoesNothingWhenNotOptedInOrOnboardingNotDoneOrUserTurnedOff`. A real Android 15 reboot is a device check (section 4) | pinned |
| 11 | Wrong-ear data | `TestSessionTest::wrongEarShowsSwappedCopyStateThenRetries`; `HearingTestViewModelTest::modeChoiceThenPrepShowsBlockingWarnings`; `HearingTestUiTest::preTestPageShowsInstructionsAndABlockingWarningDisablesStart`; `AndroidChecksRobolectricTest::environmentChecksReadHiddenSettings` | pinned |
| 12 | Volume drift | `VolumeLogicTest::lockReportsEffectiveIndexWhenRefusedAndDriftEmitsOnlyOnDeviation`, `::driftEmitsAlreadyDeviatedIndexOnCollectionStart`, `::releaseDoesNotRaiseVolumeThatWasLoweredExternally`, `::volumeLockRestoresOriginalInFinallyEvenOnException`; `TestSessionTest::volumeChangePausesAndResumeRestoresTheLockedIndex`, `::refusedVolumeRestoreKeepsPausedAndAsksForTheKeys`, `::refusedVolumeChangeAsksForTheVolumeKeysThenContinues`. CSD auto-lowering on a real device is section 5 | pinned |
| 13 | Codec drops low digital levels | Calibration window / ramp logic is tested (`CalibrationTest::windows`, `::rampScheduleAndCap`), but whether a codec really discards the tone only shows on a Bluetooth headset | device-only |
| 14 | Legacy xor session | `EffectEngineTest::legacyAndSessionAreMutuallyExclusive`, `::creationFailedClearedBySetModeSoLegacyCanBeTried`; `EngineControllerTest::modeChangeIsPersistedAndAppliedAndMutuallyExclusive` | pinned |
| 15 | Silent control loss | `DynamicsProcessingBackendTest::unsupportedFromConstructorIsConflict`, `::setterUnsupportedMarksControlLost`, `::controlListenerDrivesFlowAndRewritesOnRegain`; `EffectEngineTest::conflictOnControlLossAndAutoRecovery`, `::conflictManualRetryRecreatesHandles`; `HomeTest::conflictAndError_areTappableToRetry` | pinned |
| 16 | No BLUETOOTH_CONNECT | `:app:verifyPermissions` (allow-list, in `check`); **M12** `M12AuditTest::manifestsDeclareNoForbiddenPermissionsAndNoNetwork`, `::noBluetoothPermissionAnywhere`; `HeadsetKeyTest::bluetoothUsesLastFiveCharsOfAnonymizedAddress`; `DeviceLogicTest::addressSuffixForFullAndAnonymisedMacAndNonBluetooth` | pinned |
| 17 | GPL porting | **M12** `M12AuditTest::licenceIsGpl3AndReadmeSaysSo` (LICENSE is GPL-3.0, README says so); no GPL code was ported (§13) | pinned |
| 18 | Trend across headsets | `HealthViewModelTest::trendOnlyIncludesTestsFromTheSameHeadset_andFallbackProfileIsMostRecentlyTested`, `::noSnapshotKey_andProfileWithoutHeadset_hasNoTrend_neverMixingHeadsets`; `RepositoryTest::trendFiltersByHeadsetKeyAfterMove` | pinned |
| 19 | Experimental M3 APIs | `verifyFeatureBoundaries` (root, in `check`; run it alone) | pinned |
| 20 | UI sounds / speech / haptics during a stimulus | Static guard: **M12** `M12AuditTest::hearingTestScreensNeverAnnounceAssertivelyAndSuppressUiSounds`. Behavioural (**§27**): `Trap20SessionTest::quick_everySignalIsEmittedInSilence_alsoWhenTheAnswerCutsTheStimulusShort`, `::thorough_everySignalIsEmittedInSilence_andEveryPulseIsFollowedByASilentGap` (every haptic/announcement signal is emitted while `pulseAudible` and `tone.playing` are false; the dial loop's flag drops for the whole 200 ms gap after every pulse); `Trap20UiTest::dialTickIsDeferredToTheSilentGapAndNeverFiresDuringAPulse`, `::responseHapticAndAnnouncementWaitForSilence`; `StreamEngineTest::explicitSilenceNeverSetsPlaying`, `::pulseThenSilentGapDropsPlayingForTheGap`. TalkBack speech timing and Bluetooth sink latency are device-only (HARDWARE_TEST) | pinned |

Counts: **19 pinned, 1 device-only (13), 0 gaps** (row 20 closed by §27). Several pinned traps also carry a device item in HARDWARE_TEST (noted above).

### 26.4 Accessibility pass
- Audited (code review plus new Compose tests in both themes): onboarding, home, profiles (list and detail), settings / About, results, health, and the app-level dialog. Every `hasClickAction` node must have a speakable label (text, content description, editable text or click label) and a layout size of at least 48 dp (a deliberately strict check: it measures the clickable node, not the touch target; a default Material `IconButton` already gets a 48 dp touch target from `minimumInteractiveComponentSize()` touch-bounds expansion): `A11yAudit.kt` (an identical helper in each feature module's tests) is used by `OnboardingA11yTest`, `HomeA11yTest` (profile / empty / conflict-retry / error-retry / service-off states), `ProfilesA11yTest`, `SettingsA11yTest`, `ResultsA11yTest` and `HealthA11yTest`. The helper reads the unclipped node size, because `touchBoundsInRoot` is empty for nodes scrolled out of view.
- **Fixed:** the Results and Health back buttons were the 40 dp Material default (now `Modifier.size(48.dp)` like every other screen, so the visible bounds match the strict check; their touch target was already 48 dp); the selected-colour check mark on the amber profile swatch was white at 2.6:1 (now black on swatches with luminance above 0.3; non-text contrast needs 3:1).
- Already fine and left alone: icon-only buttons carry content descriptions; decorative icons pass `contentDescription = null`; headings use `semantics { heading() }` on every screen and card title; there are no hard-coded text colours (the only fixed colours are the eight profile swatches, each with a name and a selected state, and the chart/ear colours that `ChartSupport` themes per light/dark); the Health disclaimer has no dismiss action (existing tests).
- Contrast of seed-derived palettes is MaterialKolor's responsibility; the extreme-seed check stays a device item (HARDWARE_TEST section 9).
- No announcements during stimuli: the hearing-test screens use only polite live regions that change after a response (§23); statically guarded (trap 20).

### 26.5 Performance
- `HearingButterfly` already draws from `drawWithCache` (geometry, paths and text layouts cached; only the progress value is read in the draw phase). No change.
- `EqGraph` was a `Canvas` that re-measured about 20 text labels, rebuilt lists and formatted strings on every frame of the personalize spring. It now uses `drawWithCache`: grid geometry, label layouts and x positions are built once per size/input change, and only the two curves are redrawn per frame, reusing one `Path`; `factor` is read in the draw phase. Visual output is unchanged.
- `Audiogram` and `TrendChart` are static (no animation), so their per-draw measuring was left as is.

### 26.6 Findings in the excluded modules (not edited)
- `feature/preference/.../PreferenceScreen.kt` (about line 165): the top-bar back `IconButton(onClick = requestLeave)` has no `Modifier.size(48.dp)`; it renders 40 dp (the same defect fixed in Results and Health).
- Trap 20 has no behavioural test for "signals only after the stimulus" (see the table).

---

## 27. M9a review fixes

Fixes for the M9a review (HANDOFF §1.2). SHOULD-FIX 4 (VolumeLogic) and 7 (nav host) are owned elsewhere.

- **Resume republishes the interrupted screen (MUST-FIX 1).** `awaitNotPaused` remembers the last non-Paused state and puts it back when every reason has cleared: ChannelCheck / Calibrating as they were, a Quick `Testing` with `trialOpen = false`, a Thorough `Testing` without the dial (the interrupted unit restarts and publishes its own state). The user is never left on Paused while tones play (focus refused at start was the guaranteed trigger).
- **One session at a time (MUST-FIX 2).** `startTest()` sets a `starting` flag synchronously before anything suspends and clears it when the attempt ends; `Prep.canStart` is false while it is set, and the attempt re-checks the stage and `session == null` after the environment check. `TestSession.run()` first takes the process-wide `SessionGate` (a `@Singleton` `Mutex` in `SessionDeps`) and releases it after its `finally`, so a new session (double tap, Try again, a recreated screen) waits for the previous cleanup and never shares the tone output, volume lock or focus. A session cancelled while waiting holds nothing and ends `Aborted(USER)`; `start()` also publishes `Aborted(USER)` for a job cancelled before it ran.
- **Late Continue (MUST-FIX 3).** `cleanupStarted` is set at the top of `run()`'s `finally`; `resume()` and `resumeNow()` return when it is set, when the job is no longer active, or when the pause timeout has fired (`pauseTimedOut`). Everything in the `finally` is synchronous on the session thread, so a resume task queued behind it (also an executor task that runs after the dispatcher shut down) can no longer re-lock the volume after `VolumeLock.release()` (which would leave the test volume set for good) or re-take focus. A Continue that cleared every reason in the same instant as the timeout counts as in time.
- **Stimulus flag, pulse/gap buffers (MUST-FIX 4, trap 20).** `StreamEngine.playing` now means "an AUDIBLE stimulus is under the playback head": explicit silence (`playSilence`, or `play(..., audible = false)`) is scheduled as a non-audible segment and never sets it, which is what the `:core:model` contract already said (before, every `playSilence` gap counted as playing). The flag is published BEFORE the finished caller is woken, so a caller resumed by `play()`/`stopCurrent()` never reads a stale `true`. The session exposes `pulseAudible` = (a stimulus is queued or playing, as tracked by the session) OR `tone.playing` (covers the 25 ms ramp after a cancellation). The Thorough loop plays each 225 ms pulse (`StimulusSynth.singlePulse`) and the 200 ms gap as separate buffers, so the flag drops for the whole gap and the dial's deferred tick lands there. A silent Quick catch presentation raises the flag like a real stimulus, so the visual pulse (now driven by `pulseAudible`, not by the raw tone flag) never reveals a catch trial. Signals are emitted only when the flag is false (`emitWhenSilent`), and the UI additionally waits for `stimulusPlaying == false` before a haptic or a live-region change. The dial loop ends with `stopCurrent()` before the run is confirmed. The pulse period grows by about the writer lead time per transition (two per period instead of one); the spec fixes no period.
  - **HARDWARE_TEST item:** the playback head ignores the sink's own output latency. On Bluetooth (100-300 ms) the tick and announcements can be shifted into the next pulse at the ear. Check by ear on a BT headset; if needed use `AudioTrack.getTimestamp` / reported latency to delay the flag's fall.
  - `ScriptedTone.playing` documents and follows the engine semantics (set by `play`, never by `playSilence`).
- **Double taps (SHOULD-FIX 1, 2).** Calibration taps are accepted only from the ramp step at or after `CAL_TAP_GUARD_MS` = 1 s (step 3, about -84 dBFS); channel-check answers only after the step's first three-pulse beep has played out. Both stay visually enabled (no `enabled` toggling during stimuli).
- **Live checks (SHOULD-FIX 3).** After the answer and before `engine.submit` / `engine.confirm`, the live headset and volume index are read synchronously; a mismatch raises the pause and interrupts the unit (the point/run restarts), even when the change event has not been delivered yet.
- **Calibration cache per listener (SHOULD-FIX 5).** The key adds `listenerId` (`TestStore.listenerFor(requestedProfileId)`: the requested profile's listener, else the active listener; null before any listener exists). A failed lookup skips the cache.
- **End-of-test prompt (SHOULD-FIX 6), choice: save.** If the end-of-test "answers looked like guesses" prompt is unanswered when the 5-min pause timeout fires, the finished test is saved with `reliable = false` (the same as "Keep these results") instead of being discarded. Releasing audio and the token during the prompt was rejected: "Redo" would then need a new stream, token and calibration.
- **Quick buttons stay enabled (SHOULD-FIX 8)**; the session ignores taps between trials.
- **Save failure (NIT).** A failed save no longer aborts: the session publishes `SaveFailed(profileMissing)` after the cleanup and keeps the `SaveRequest`; "Try saving again" calls `retrySave()` (no audio or bypass involved; concurrent calls ignored). `AbortReason.SAVE_FAILED` is no longer produced.
- **Deleted profile (NIT).** `RepositoryTestStore.save` throws `ProfileMissingException` for a requested profile that no longer exists instead of silently saving to the active listener; the SaveFailed screen offers "Save to the current profile" (`retrySave(useActiveProfile = true)`, an explicit choice). The listener / headset / profile inserts and the test save now run in one `DataTransactions.withTransaction` (§28.3) inside `NonCancellable`; the DataStore active-listener id is written only after the commit, so a rollback never leaves it pointing at a missing row (`RepositoryTestStoreTest::listenerHeadsetProfileAndTestAreSavedInOneTransaction_allOrNothing`, `::aSaveThatIsCancelledMidwayStillCompletes`).
- **Other NITs.** Quick trials end with an unconditional `stopCurrent()`; stream losses count in a row (reset by every completed unit, still max 3); ON_STOP during a configuration change (`isChangingConfigurations`) no longer pauses; `LevelDial` accumulates drag steps locally (no detents lost when events batch); `CalibrationSession` gets the real stream minimum (`TestPlatform.minVolumeIndex()` = `getStreamMinVolume`); stale "DECISIONS §20" references now read §23. `resumeRestartsOnlyTheInterruptedPoint` always checks the restarted point's start level (other-ear rule included) and that exactly the pre-pause presentations were dropped.
- **Spec note (not changed).** SPEC "the hear button softly pulses while a stimulus plays": a pulse locked to the AUDIO would let the user answer from the visuals and would reveal catch trials. The pulse follows the presentation window instead (catch trials pulse identically), which carries no information about audibility. Flagged for the spec owner.
- **Tests added.** `ReviewFixesTest` (one per finding), `Trap20SessionTest`, `Trap20UiTest`, `HearingTestA11yTest` (+ `A11yAudit` copy), VM double-tap / Try-again gate tests, `RepositoryTestStoreTest` deleted-profile / `listenerFor`, `StreamEngineTest` silence and pulse/gap tests, `AudioTrackToneOutputTest::oneSinkForManyStimuliGapsStopsAndRepeatedOpens`; trap-5 open/close counts in the session cleanup asserts.

## 28. Polish fixes (session 3)

### 28.1 Volume restore on the wrong device (HANDOFF SHOULD-FIX 4)
- `:audio:tone` `VolumeLock` stays source-compatible (`VolumeLock(controller)`). When the controller also implements the new `DeviceAwareVolume` (`AndroidVolumeController` does), the lock remembers the media output device (`AudioDeviceInfo.TYPE_*`) when it first locks.
- `release()` writes the original index only if the output is still that device. If it is not (headset unplugged mid-test), nothing is written (STREAM_MUSIC keeps one index per device, so a write would hit the speaker) and the restore is handed to `DeviceAwareVolume.deferRestore(deviceKey, index)`. `AndroidVolumeController` applies it from an `AudioDeviceCallback` once the start device is current again, then unregisters the callback. A later `lock()` cancels a pending restore (the new lock reads the live index as the new original).
- On reconnect the same safety rule as `release()` applies: if the start device's index is not the locked one and is below the original (lowered by the user or CSD meanwhile), it is NOT raised (`DeferredOutcome.Skip`). The callback retries once after 500 ms, because it can fire before routing switches; a resolution only ever happens when the current output key equals the start device, so it never writes on the speaker.
- Limits: device identity is the device type, not the individual device (two different USB headsets look alike). The deferred restore lives in memory only (process death drops it, the same gap as HANDOFF §1.3).
- Pure logic is in `DeferredRestore`; tests in `VolumeLogicTest`.

### 28.2 Results "Test again" keeps the profile (SHOULD-FIX 7)
- `ResultsRoute`'s `onRedoTest` is now `((profileId: Long) -> Unit)?`; the Results screen passes the displayed test's `profileId`, and `PAudioNavHost` navigates to `HearingTestRoute(profileId = it)`. `:feature:hearingtest` is untouched.

### 28.3 `:data` transaction API
```kotlin
package my.umarh.paudio.data.tx
interface DataTransactions { suspend fun <T> withTransaction(block: suspend () -> T): T }
class RoomDataTransactions @Inject constructor(db: PAudioDatabase) : DataTransactions   // Room withTransaction
```
- Bound in `DataBindsModule` (`@Binds DataTransactions`), so any `@Inject` class can take `DataTransactions`.
- Everything done through the `:data` repositories inside the block commits together or is rolled back if the block throws (cancellation included). Repository methods that open their own transaction join the outer one. Keep the block short: no audio, UI or network work inside.
- `AndroidPreferenceData.save` now writes the `PreferenceTest` and the profile settings in one transaction, still wrapped in `NonCancellable` (the transaction must finish when the screen is left).
- Intended next adopter: `RepositoryTestStore` in `:feature:hearingtest` (listener, headset and profile inserts plus the test save, HANDOFF §1.2 NIT). Not done here (other owner).
- Tests: `DataTransactionsTest` (commit; rollback on exception).

### 28.4 Smaller items
- Preference top-bar back button is 48 dp (`Modifier.size(48.dp)`), as Results and Health.
- `:app` now has a unit-test source set. To make the `AppViewModel` seed-sync testable without the concrete `EngineController`, `seedSync` and `pickThemeProfile` moved out of the class to `internal` top-level functions (behaviour unchanged); `AppViewModelSeedSyncTest` covers them.
- `AndroidEnvironmentChecks.readMono` also reads Samsung's `Settings.System` `mono_audio` (try/catch, absent = off); mono is reported if either key is on. Robolectric test added. (AOSP key is `master_mono`.)
- `tools/golden/gen_all.py --check` is implemented (see `docs/VECTORS.md`); the committed vectors match a fresh regeneration.
- `LICENSE` was compared byte for byte with https://www.gnu.org/licenses/gpl-3.0.txt: identical, no change.
- Manifest `<queries>` (merged): one entry, `MAIN`/`LAUNCHER`, declared in `:service`. It is used only to show the app label of the app that owns an audio session in the notification (`ServicePlatform.kt` `getApplicationLabel`); no label leaves the device. It is broader than strictly necessary (it lets the app enumerate launcher apps) but justified by that feature, and a missing entry falls back to the package name. Not changed. No other `<queries>`, no `QUERY_ALL_PACKAGES`.
- Environment note: AGP auto-installed a partial `build-tools;36.0.0` into the shared SDK during this session, which broke the build until the directory was removed (`build-tools;37.0.0` is the one that works).

## 29. Logging guard hardening (review of M12)

Review finding: `verifyNoSensitiveLogging` (section 26.2) caught one of 14 planted leaks. It is replaced by an **allowlist**.

### 29.1 Scanner
- `LoggingScanner` (build-logic, pure, unit-tested) tokenises each file (`//` and nested `/* */` comments, escapes, raw triple-quote strings, nested `${ ... }` templates, char literals), finds every sink and checks its arguments. `VerifyNoSensitiveLoggingTask` runs it over every `**/src/main/**/*.kt` and `*.java` (root `check`). Whatever the data is called, a variable cannot reach a log.
- **Sinks:** `Log.v/d/i/w/e/wtf/println` (also `android.util.Log.` qualified), `println` / `print` (also `kotlin.io.`), any call on `System.out` / `System.err`, `Timber.<level>` and `Timber.tag(..).<level>`, `java.util.logging` (`<...logger...>.info/warning/severe/...`, `Logger.getLogger(..).info(..)`).
- **Rule.** Arguments after the tag (and all arguments of print sinks), joined by `+`, may only be: (1) a string literal (normal or raw) whose every template (`$x` or `${...}`) is itself an allowed term, so a bare `$x` is a violation; (2) a numeric or char literal, `true` / `false`; (3) a class name: `<ident chain>.javaClass.simpleName`, `::class.simpleName` or `::class.java.simpleName` (the chain has no calls); (4) an enum constant name, recognised only as `<chain>.<UPPER_CASE>.name` (no type information is available, so `profile.name` is not accepted); (5) a priority constant `Log.X` / `Level.X`. The tag of a `Log.*` call is a constant string or a constant named `TAG` / `*_TAG`. Named arguments follow the same rules.
- A throwable in any position, `.toString()`, `.message`, any function call, any variable or constant other than the above is a violation. A lambda form (`Timber.d { }`) is a violation.
- Always a violation: any `printStackTrace` / `getStackTraceString` / `dumpStack` token; a function reference to a sink (`Log::d`, `::println`, a bare `System.out`); an import alias or static import of `Log` / `Timber` members.
- **No suppression mechanism** (deliberately). To log a count, log constant text instead.
- **Limits:** sinks are recognised by name (a project-defined wrapper is not a sink, but it cannot forward a variable to a real sink either: that call is scanned and rejected); library output and reflection are out of scope (SPEC section 9's logcat grep stays a device item); logging in `src/debug`, `src/release` or test source sets is not scanned (hearing data in tests is synthetic).
- **Tests:** `build-logic/convention/src/test/kotlin/LoggingScannerTest.kt` (JUnit, run by `./gradlew -p build-logic test` and by CI) holds every reviewer probe (flagged), the two false positives (clean) and the allowed forms.
- **One existing call changed:** `DynamicsProcessingBackend.uoeFailure` logged `$kind` (an enum value) in a template. It now logs the class name only; the failure kind travels in the thrown `EffectCreationException`.
