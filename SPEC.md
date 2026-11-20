# PAudio — Build Spec

> Android app that personalizes earphone sound to the listener's hearing and taste, applied **system-wide**.
> Inspired by Sonarworks SoundID. Open source.
>
> This file is the source of truth for Claude Code. Reference it from `CLAUDE.md`. When something here is ambiguous, ask before inventing.

---

## 0. Ground rules

- **Not a medical device.** Every surface that shows hearing data carries the disclaimer (§7.4). Never use the words "diagnosis", "diagnose", or "hearing loss detected" in the UI. Say "estimated".
- **Safety first:** a hard output ceiling during tests, and a limiter at the end of every EQ chain.
- **Store raw data, derive everything else.** Thresholds and preference answers are the source of truth. EQ curves are always recomputed, so improving the algorithm later upgrades every saved profile.
- **Biggest technical risk = system-wide audio effects on Android.** Milestone 0 is a spike that proves it works on the target device *before* any UI work.

---

## 1. Stack

| Concern | Choice |
|---|---|
| Language | Kotlin 2.x |
| UI | Jetpack Compose + Material 3 **Expressive** APIs (`@OptIn(ExperimentalMaterial3ExpressiveApi::class)`), latest stable Compose BOM |
| Shapes / morphing | `MaterialShapes` (material3) + `androidx.graphics:graphics-shapes` (`Morph`) |
| Color | Dynamic color (Android 12+) + per-profile seed schemes via **MaterialKolor** |
| Navigation | Navigation Compose (type-safe routes) |
| Persistence | Room (profiles, tests, results) + DataStore (settings) |
| In-app playback | Media3 ExoPlayer (demo track + A/B clips) |
| Tone generation | `AudioTrack`, float PCM, 48 kHz stereo |
| DSP | `android.media.audiofx.DynamicsProcessing` (API 28+) |
| DI | Hilt (or manual DI if it stays small) |
| QR | zxing-cpp / CameraX. **No Google ML Kit** (keeps it F-Droid friendly) |
| minSdk / targetSdk | 28 / latest stable |

Package: `my.umarh.paudio`

---

## 2. Project layout

```
app/src/main/java/my/umarh/paudio/
├── audio/
│   ├── engine/          EffectService (FGS), SessionTracker, EffectController, DpConfigFactory
│   ├── tone/            ToneGenerator (pulsed + warble), LevelMath
│   └── player/          DemoPlayer (Media3, owns its own DP instance)
├── dsp/                 BandLayout, Compensation, PreferenceCurves (RBJ biquads), Loudness, CurveInterp (PCHIP)
├── hearingtest/         Calibration, QuickStaircase, AdjustmentRun, TestScheduler, CatchTrials, HearingTestViewModel
├── preftest/            KnobSearch, PreferenceTestEngine, PreferenceTestViewModel
├── health/              HearingLevel (HL conversion, bands, averages, asymmetry), HealthViewModel
├── profiles/            HeadsetMonitor (AudioDeviceCallback), ProfileSwitcher, Export (JSON + QR)
├── data/                Room entities/DAOs, repositories, SettingsDataStore
└── ui/
    ├── theme/           PAudioTheme, ProfileScheme, ear colors, type scale
    ├── components/      MorphButton, HearingButterfly, Audiogram, EqGraph, ToneWaveProgress, ABToggle
    └── screens/         home, profiles, test/*, results, demo, preftest/*, health, settings
```

---

## 3. Audio engine — system-wide EQ

### 3.1 How it reaches other apps (in priority order)

1. **Session mode (default).** Listen for `AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` / `ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION`. Read `EXTRA_AUDIO_SESSION` + `EXTRA_PACKAGE_NAME`, then attach/release one `DynamicsProcessing` per session. Register the receiver in the manifest **and** dynamically inside the service. Verify which one actually fires on the target device during M0. Works only with players that send the broadcast (YouTube Music, Spotify and others do; many don't).
2. **Global mode ("legacy").** One `DynamicsProcessing` on audio session `0` (output mix). Deprecated, and whether it works depends on the manufacturer's audio framework. Offer it as a toggle in Settings with a clear "may not work on your device" note.
3. **Enhanced detection (later / optional, advanced).** Mirrors Wavelet: the user grants `android.permission.DUMP` via ADB so the app can discover sessions from players that don't broadcast. Behind an "Advanced" screen with copy-paste ADB instructions. **Not in v1.**

### 3.2 EffectService

- Foreground service (Android 14+ needs a type, so use `specialUse` with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explanation). Persistent notification shows the active profile and an on/off action.
- Holds `Map<sessionId, DynamicsProcessing>`. On profile/curve change, it pushes new params to all live instances.
- Handles `onControlStatusChange`. If another app takes control of a session, show "Another audio app is controlling playback" in the UI.
- **Must be suspendable:** the hearing test and the "Original" side of A/B call `EffectController.bypass(true)`. That disables all instances, *including* the session-0 instance in legacy mode, because that one would also color the test tones.

### 3.3 DynamicsProcessing config

```
variant            = VARIANT_FAVOR_FREQUENCY_RESOLUTION
channelCount       = 2
preEq              = in use, BAND_COUNT bands   // per-channel → separate L/R curves
mbc                = not in use
postEq             = not in use
limiter            = in use
```

- `BAND_COUNT = 32`, log-spaced 20 Hz–20 kHz. DP EQ bands are **band-split** (each `EqBand` covers from the previous cutoff up to its own `cutoffFrequency`), so set cutoff = upper edge and gain = target curve sampled at the band's geometric center.
- Per ear: `setPreEqByChannelIndex(0, left)`, `setPreEqByChannelIndex(1, right)`.
- **Headroom:** `setInputGainAllChannelsTo(-(maxBoostAcrossBothChannels + 1 dB))`, plus the loudness-match offset (§5.4) when in A/B.
- **Limiter:** threshold −1 dB, ratio 10:1, attack 1 ms, release 60 ms, post-gain 0.
- **Smooth param changes:** never jump gains. Interpolate from old to new band gains in ~6 steps over ~60 ms (coroutine). Test for zipper noise on the target device. If it's audible, try more steps.

### 3.4 Permissions

`MODIFY_AUDIO_SETTINGS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`.
Optional, each asked in context: `RECORD_AUDIO` (room-noise meter), `BLUETOOTH_CONNECT` (headset address for auto-switch on 12+), `CAMERA` (QR import).

---

## 4. Hearing test

### 4.1 Constants

```kotlin
val TEST_FREQS_HZ = intArrayOf(1000, 2000, 3000, 4000, 6000, 8000, 10000, 12000, 14000)
// 9 freqs × 2 ears = 18 threshold tracks. Both modes test all 18 (keeps profiles comparable).
const val SAMPLE_RATE = 48_000
```

Below 1 kHz is skipped deliberately: there, results depend mostly on earphone seal and ambient noise, not hearing.

### 4.2 Stimulus

- **Pulsed:** 3 pulses × (200 ms on / 200 ms off). Each pulse has **20 ms raised-cosine on/off ramps**. This is non-negotiable, because an unramped tone clicks, the click is broadband, and that leaks audibility at frequencies the user can't actually hear.
- **Warble for f ≥ 6 kHz:** sinusoidal FM, ±5 % deviation, 8 Hz rate. This avoids landing on sharp ear-canal resonance notches in insert earphones.
- Mono-to-one-ear: the other channel is digital silence.
- Level `L` is expressed in **dBFS peak**: amplitude = `10^(L/20)`.
- Tones play on their own `AudioTrack` (USAGE_MEDIA) with **no effects attached** (§3.2 bypass).

### 4.3 Pre-test screen (single page)

A checklist of rows, each with its text on the left and a Material Symbol on the right:

| Text | Icon |
|---|---|
| Go to a quiet place | `volume_off` |
| Turn on noise cancellation, if your earphones have it | `graphic_eq_off` |
| Turn on Do Not Disturb | `do_not_disturb_on_total_silence` |
| *(suggested 4th)* Turn off other sound effects (Dolby Atmos, earbud-app EQ, etc.) | `tune` |

- Optional **live room-noise meter** (mic, A-weighted-ish RMS → "Quiet enough" / "A bit noisy" / "Too loud") if `RECORD_AUDIO` is granted. Never blocks; it only advises.
- "Do Not Disturb" row: if notification-policy access is granted, offer a one-tap toggle; otherwise deep-link to settings.

### 4.4 Calibration (once per test session)

1. Remember the user's current `STREAM_MUSIC` index → set it to ~40 % of max. **Restore it when the test ends or is abandoned.**
2. Play a 1 kHz pulsed tone **in both ears**, starting at −90 dBFS and rising 3 dB/s. The user taps **"I hear it"**.
3. `C = levelAtTap − 3 dB` (reaction-time compensation).
4. If `C > −35 dBFS` → device too quiet: raise volume 2 steps, re-run. If `C < −85 dBFS` → too loud: lower 2 steps, re-run.
5. Working range per session: `floor = max(C − 25, −110)`, **`ceiling = min(C + 70, −6) dBFS`**. Nothing ever plays above `ceiling`.
6. **Lock volume:** observe volume changes (`VOLUME_CHANGED_ACTION` / ContentObserver). If changed → pause and show "Volume changed — put it back to continue" with a one-tap restore.

### 4.5 Quick mode — adaptive yes/no

UI: two big buttons, **"I hear it"** (MorphButton, §8) and **"I don't hear it"**.

- One **staircase per track** (ear × freq). Start level: `C + 20 + (R(f) − R(1000))` (R from §7.1), clamped to [floor, ceiling].
- Steps: 10 dB until the first reversal, then 5 dB. "Yes" → go down, "No" → go up.
- Stop after **4 reversals** or **10 trials**. Threshold = mean of the last 2 reversal levels.
- Two "no" answers at `ceiling` → threshold = ceiling, flag `NO_RESPONSE`. Two "yes" at `floor` → threshold = floor, flag `AT_FLOOR`.
- **Scheduling:** strictly alternate ears every trial. Within an ear, pick a random unfinished frequency. Random inter-trial gap of 700–1500 ms.
- **Bluetooth latency guard:** "I hear it" is enabled from stimulus start. "I don't hear it" is enabled only after stimulus end + 400 ms (LDAC/AAC latency can be 200–300 ms+).
- **Catch trials:** ~1 in 8 trials is silent (same timing and UI). Any "I hear it" on a catch trial = false alarm. ≥3 false alarms or a >20 % rate → `reliability = LOW`. The results screen then offers "Retake test" prominently.
- Target duration: ~4 min.

### 4.6 Thorough mode — method of adjustment

UI: the tone loops (pulsed) while the user adjusts the level with a slider plus large **−1 dB / +1 dB** buttons (and ±5 dB on long-press). A confirm button.

- **Two runs per track:**
  1. *Descending:* start at `C + 25 + (R(f) − R(1000)) ± random(5)` → lower until inaudible → **"I can't hear it anymore"**.
  2. *Ascending:* start at `run1 − 15 ± random(5)` → raise until audible → **"I can hear it now"**.
- Threshold = mean of both runs. If they differ by more than 10 dB → repeat that track once and use the latest pair.
- Same ear alternation, random frequency order, and ceiling/floor clamps as Quick.
- Target duration: ~8–10 min.

### 4.7 During the test

- **Progress:** `ToneWaveProgress`, a `LinearWavyProgressIndicator` whose **`wavelength` follows the current test frequency**: `lerp(48.dp, 8.dp, ln(f/1000)/ln(14))`. Long lazy waves at 1 kHz, tight squiggles at 14 kHz.
- Show which ear is playing (a small "L"/"R" chip in the ear color, §8).
- Pause/quit always available; quitting discards the session (no partial saves in v1).

### 4.8 Stored result

```kotlin
data class TrackResult(val ear: Ear, val freqHz: Int, val thresholdDbfs: Float, val flags: Set<TrackFlag>)
// HearingTest entity: profileId, takenAt, mode, volumeIndex, volumeMax, calibrationDbfs,
//                     reliability, falseAlarms, tracks: List<TrackResult> (JSON), appVersion, algoVersion
```

---

## 5. DSP math

### 5.1 Curve interpolation

- Work in log-frequency. Use **PCHIP** (monotone cubic) between test points. No overshoot.
- Below 1 kHz: gain tapers from `g(1000)` at 1 kHz to 0 at 500 Hz (smoothstep). Boosts never extend into untested frequencies.
- Above 14 kHz: hold `g(14000)` to 16 kHz, taper to 0 at 20 kHz.

### 5.2 Hearing compensation (per ear)

```
HL(f)   = estimated hearing level (§7.1)
g(f)    = strength × sign(HL) × max(0, |HL| − 5)        // 5 dB deadband = measurement noise
g(f)    = clamp(g(f), −6, +12)
strength default = 0.5   // partial compensation, like hearing-aid half-gain logic
```

`strength` is user-adjustable (0–100 %) as "Personalization strength".

### 5.3 Preference layer (shared by both ears)

Three knobs, each an RBJ-cookbook biquad. Evaluate the magnitude response at the band centers with fs = 48 kHz:

| Knob | Filter | fc | Q | Range |
|---|---|---|---|---|
| Bass | low shelf | 105 Hz | 0.7 | −9 … +12 dB |
| Presence | peaking | 3 kHz | 0.9 | −6 … +6 dB |
| Treble | high shelf | 9 kHz | 0.7 | −9 … +9 dB |

**Final curve per ear** = `compensation(ear) + bass + presence + treble`, then headroom (§3.3).

### 5.4 Loudness matching (for A/B and the demo)

Louder always wins A/B comparisons, so every compared variant is level-matched:

```
w_i  = pinkEnergy(band_i) × kWeightPower(fc_i)          // pink: equal energy per octave → ∝ log-bandwidth
ΔL   = 10·log10( Σ w_i·10^(G_i/10) / Σ w_i )             // G = variant's curve in dB (avg of L/R)
inputGain_variant = −ΔL − headroom
```

This is an approximation and that's fine. Unit-test that a flat curve gives ΔL = 0 and a +6 dB flat curve gives ΔL = +6.

---

## 6. A/B features

### 6.1 Results → Demo (right after a hearing test)

1. **Results screen:** HearingButterfly (§8), reliability chip, a **"Hearing health"** button (icon `hearing`) → §7, and a "Hear the difference" CTA.
2. **Demo screen:**
   - Source picker (connected button group): **Demo track** | **Your own audio**.
     - *Demo track:* bundled **"Discipline" – Nine Inch Nails** (*The Slip*, CC BY-NC-SA 3.0). Played by `DemoPlayer` on its own session with its own DP instance. Loop the 0:40–1:05 section by default; scrubbing allowed.
     - *Your own audio:* toggling switches the **system-wide engine** between bypass and personalized while whatever app is playing keeps playing. Show the detected session/app ("Applying to Spotify"), or "Play something in any app" if none.
   - **Original | Personalized** big segmented toggle. Both sides are loudness-matched (§5.4).
   - **EqGraph:** L (blue) and R (red) curves for the personalized side. When "Original" is selected, the curves **spring-animate down to flat**.
3. **On leaving the Demo screen** → dialog: "Want to tune it to your taste next? Takes about 2 minutes." → [Start preference test] [Later].

### 6.2 Preference test (the optometrist "1 or 2?" test)

- **Clips:** 3 short loops (10–15 s), cycled across rounds: full-band rock ("Discipline"), sparse/vocal ("Lights in the Sky", *The Slip*), and a bass-forward track (pick by ear from *The Slip* / *Ghosts I–IV*). All CC BY-NC-SA.
- **The hearing compensation is active underneath.** Preference knobs layer on top of it.
- **Per-knob search** (`KnobSearch`):
  ```
  center = 0, step = 6
  each round: present (center − step) vs (center + step), sides randomized as "A"/"B"
  on choice: center = chosen value (clamped to knob range); step /= 2
  on "Can't tell": stop this knob at center
  stop when step < 1.5           // → 3 rounds per knob: steps 6, 3, 1.5
  ```
- **Interleave knobs:** Bass r1 → Presence r1 → Treble r1 → Bass r2 … (9 comparisons max).
- **Final check (round 10):** "Your tuning" vs "Neutral" (knobs at 0, compensation still on), loudness-matched. If Neutral wins → halve all three knob values and tell the user "We toned it down a bit".
- **Switching:** a single player; the A/B toggle swaps DP params with the 60 ms glide (§3.3). Tapping A or B switches instantly and in sync; a "This one" button under each locks the choice.
- **Blind labels only.** Never "More bass".
- **Result screen:** the combined EqGraph, three fine-tune sliders (Bass / Presence / Treble), the "Personalization strength" slider, and L/R balance. Save to the active profile.

---

## 7. Hearing health screen

Opened from the Results screen and from each profile's detail page (`hearing` icon button).

### 7.1 Converting thresholds to estimated hearing level (HL)

The app can't know true dB HL (unknown phone output and earphone sensitivity). It estimates a *shape* anchored to the listener's own best hearing:

```
R(f)     = reference normal-hearing threshold curve (approx. ISO 226:2003 / ISO 389-7 free-field), dB:
           1k: 2.4 · 2k: −1.3 · 3k: −5.6 · 4k: −5.4 · 6k: 4.6 · 8k: 12.6 · 10k: 13.9 · 12k: 12.6 · 14k: 18.4
           (3k/6k/12k interpolated; VERIFY against the standards before release — keep in one constants file)
raw(f)   = thresholdDbfs(f) − R(f)
anchor   = min over ears of mean(raw(1k), raw(2k))       // better ear's 1–2 kHz ≈ 0 dB
HL(f)    = raw(f) − anchor
```

**Known limitation (say so in the "How this works" sheet):** a hearing loss that's equal at every frequency in both ears can't be detected with this method. The chart shows *differences* across frequencies and between ears. Results are also specific to the earphones used, which is why profiles are per person × headset.

### 7.2 Audiogram chart (`Audiogram` composable)

- **Y axis inverted** (audiogram convention): −10 dB at top → 100 dB at bottom, gridlines every 10.
- **X axis log**, showing the tested range only (≈ 800 Hz – 16 kHz), labels: 1k · 2k · 4k · 8k · 14k.
- **Bands** (labels inside, left-aligned, like the reference screenshot):
  | Band | Range (dB) |
  |---|---|
  | Normal | −10 – 20 |
  | Mild | 20 – 40 |
  | Moderate | 40 – 70 |
  | Severe | 70 – 90 |
  | Profound | 90+ |
  Band fills use a green → amber → red ramp **harmonized toward the current scheme's primary** (material-color-utilities `Blend.harmonize`) at low alpha on `surfaceContainer`, so it never clashes with the profile theme.
- **Series:** right ear = **red circles (O)**, left ear = **blue crosses (X)**. Real audiogram symbols, in harmonized red/blue. Lines connect the points of each ear.
- `NO_RESPONSE` points are drawn at the ceiling with a small downward arrow.
- Tap or long-press a point → tooltip: "Left · 8 kHz · ~14 dB (estimated)".
- Legend: "— Right ear  — Left ear".

### 7.3 Summary + history

- **Summary chips per ear:** estimated band at 1–4 kHz average ("Left: normal range") and a high-frequency average (8–14 kHz).
- **Asymmetry card:** if L and R differ by ≥15 dB at ≥2 adjacent frequencies → a gentle tonal card: "Your ears differ noticeably. If that's new or you notice ringing or muffled hearing, consider seeing an audiologist." No alarm colors, no "loss" wording.
- **History tab ("Over time"):** for the current profile only (same headset → comparable), plot the per-ear 1–4 kHz average and 8–14 kHz average for each saved test, and show the change since the first test ("−3 dB since March", estimated). Hidden until there are ≥2 tests.

### 7.4 Disclaimer (exact copy, always visible at the bottom of the screen, plus an `info` icon in the top app bar opening the "How this works" sheet)

> These results are estimates for music personalization and entertainment only. They are **not** a medical hearing test or a diagnosis. If you notice changes in your hearing, ringing in your ears, or a difference between your ears, please see an audiologist or doctor.

The first time the user opens this screen, show the disclaimer as a bottom sheet they must acknowledge ("Got it").

---

## 8. Design

**Concept: "Your hearing, as an object."** Full M3 Expressive: springy motion, shape morphing, big display type.

- **Theme:** dynamic color by default. Optional **profile colors**: each profile gets a seed color derived from its hearing curve (e.g. hue from a hash of the rounded thresholds; chroma from the L/R asymmetry), turned into a full scheme via MaterialKolor. Switching profiles **re-themes the app** with an animated color transition. Settings toggle: Wallpaper colors | Profile colors.
- **Ear colors:** right = red, left = blue (audiogram convention), both harmonized to the scheme. Used consistently in every chart, chip and L/R indicator.
- **Type:** Roboto Flex. Large display weights for screen titles, tight tracking on numbers.
- **Signature components:**
  - `MorphButton`: the big "I hear it" button. Rests as `MaterialShapes.Cookie9Sided`, **gently pulses while a tone plays**, and morphs to `MaterialShapes.SoftBurst` with a spring + haptic tick when pressed, then settles back.
  - `ToneWaveProgress`: §4.7.
  - `HearingButterfly`: the L and R curves mirrored around a vertical center line (frequency runs outward/up), filled with ear-colored gradients → an organic, symmetric shape unique to each person. Shown large on Results, small on profile cards and the home screen.
  - `EqGraph`: smooth curves on a log axis, spring-animated between states.
  - `ABToggle`: two large morphing shapes labeled A and B. The active one breathes subtly.
- **Home screen:** active profile card (mini butterfly + person · headset), a big expressive on/off switch for personalization, engine status ("Applying to: YouTube Music" / "Waiting for playback" / "Not supported in this app"), quick actions: Retake test · Tune taste · Hearing health.
- **Haptics:** tick on every answer; a confirm pattern when a test completes.
- **Edge-to-edge**, predictive back, large-screen layouts as a stretch goal.

---

## 9. Profiles

### 9.1 Model

```
Person(id, name, createdAt)
Headset(id, displayName, deviceType, productName, address?, createdAt)
Profile(id, personId, headsetId, name, seedColor?, strength=0.5, balanceDb=0, createdAt)
HearingTest(...)            // §4.8, many per profile, latest = active
PreferenceResult(id, profileId, takenAt, bassDb, presenceDb, trebleDb, answersJson, neutralWonFinal)
ActiveProfile → DataStore
```

A profile = **one person × one headset**, because the measurement includes the earphone's own response.

### 9.2 Auto-switch

- `HeadsetMonitor` uses `AudioManager.registerAudioDeviceCallback`. Types: BT A2DP, BLE headset, wired, USB headset.
- Match on (type, productName, address if available).
  - Exactly one profile for the headset → activate it + a quiet notification "Switched to {profile}".
  - Several profiles → a notification "Who's listening?" with one action button per person.
  - None → a notification "New headphones — set up a profile?" → the test flow.

### 9.3 Export / import

- JSON schema `v1`: person name, headset name, raw tracks, preference result, strength, balance. **No derived curves.**
- QR: minified JSON → deflate → base64url. Scanned in-app (CameraX + zxing-cpp). Imported profiles bind to the currently connected headset (with confirmation).

---

## 10. Settings

- Engine mode: Session (default) | Global/legacy | (later) Enhanced.
- Compatibility check: plays a short panned test (boosts only the left channel) on the demo player and asks "Did you hear it mostly on the left?" → confirms per-channel EQ works on this device.
- Safety: limiter always on (not user-disableable). Max boost cap shown read-only.
- Theme: Wallpaper colors | Profile colors.
- About / licenses: app license, **NIN attribution** — "Discipline" & "Lights in the Sky" by Nine Inch Nails, from *The Slip* (2008), licensed CC BY-NC-SA 3.0.

> Licensing note: the NC clause means the bundled tracks must be swapped if PAudio ever becomes commercial. F-Droid would tag the app **NonFreeAssets** because of them. The alternative is downloading the clips on first run instead of bundling them.

---

## 11. Milestones

| # | Goal | Done when |
|---|---|---|
| **M0** | **Engine spike** (throwaway is fine) | On the target device (Honor phone, Soundcore R60i NC over LDAC): DP attaches via session broadcast with Spotify **and** YouTube Music; session-0 legacy behavior is documented; **per-channel** EQ is audibly confirmed (left-only boost); 60 ms param glide has no audible clicks. Write findings to `docs/m0-findings.md`. |
| M1 | Tone generator + calibration + both test modes (headless) | Unit tests: a simulated listener (logistic psychometric function, known thresholds ± noise) recovers thresholds within ±5 dB (Quick) / ±3 dB (Thorough); catch-trial logic flags a "always yes" listener. |
| M2 | DSP math + EffectService | Unit tests for PCHIP, compensation clamps/deadband, RBJ magnitudes, loudness ΔL. Service applies a saved curve system-wide. |
| M3 | Test UI flow + Results + Demo A/B | The full flow works on the device; volume restore verified on quit. |
| M4 | Preference test | KnobSearch unit-tested; the flow ends in a saved PreferenceResult. |
| M5 | Profiles, auto-switch, export/import | Connecting the headset activates its profile. |
| M6 | Hearing health screen + history | Matches §7, including the disclaimer behavior. |
| M7 | Design polish | Morphing, wavy progress, butterfly, profile theming, haptics. |

---

## 12. Testing notes

- **Before any test on a real device:** disable the earbud companion app's EQ (Soundcore app EQ / HearID if present), Dolby/manufacturer sound effects, and PAudio's own engine (the app does the last one automatically).
- Keep all DSP and test logic in plain Kotlin (no Android deps) so it runs as JVM unit tests.
- Log every trial (level, answer, catch flag, timestamp) in debug builds. Export as CSV from a hidden debug menu for tuning.
