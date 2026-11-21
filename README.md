# PAudio

PAudio is an Android app (package `my.umarh.paudio`) that tunes the sound of your earphones to your own ears and your own taste, across your music apps. It runs a short hearing test on your phone and earphones, turns the result into an approximate per-ear equalizer curve, and applies it system-wide.

> **PAudio is not a medical device.** Its results are estimates for music listening only and are not a medical diagnosis. Phones and earphones cannot be calibrated like clinical equipment, so hearing loss that is equal across all frequencies cannot be detected. If you notice changes in your hearing, ringing or pain, see a doctor or audiologist. The same text is shown in the app (onboarding, Settings > About, and permanently on the Hearing health screen).

<!-- Screenshots: placeholder, to be added after the first on-device run (see docs/HARDWARE_TEST.md). -->

## Features

- **Hearing test, two modes.** Quick (about 6-8 minutes) and Thorough (about 7-9 minutes). Nine test frequencies from 1 kHz to 14 kHz, both ears, with a left/right channel check, an automatic volume calibration step that locks the system volume, catch trials in Quick mode, and pause/resume when the headset disconnects, the volume changes, audio focus is lost or the app goes to the background.
- **Results.** An animated "hearing butterfly", plain-words summary per ear (always labelled as estimated), reliability and asymmetry notes, and a live EQ graph.
- **Original vs Personalized A/B.** Loudness-matched, instant switching on the same playhead, using a bundled demo clip or a song you pick yourself (no storage permission needed).
- **Hearing health screen.** Audiogram, approximate per-ear averages, a trend chart across tests of the same profile and headset, and a disclaimer that cannot be dismissed.
- **Preference test and fine-tune.** Up to nine blind A/B comparisons for bass, presence and treble, then sliders for compensation strength and the three tone controls with a live EQ graph.
- **Profiles.** Listener x headset profiles, an "Any headphones" profile per listener, automatic switching when the output device changes, rename/duplicate/move/delete, and JSON export/import through the system file picker.
- **System-wide engine.** A foreground service applies the active profile to your music apps (see below), with a notification offering Personalized/Original, Open app and Turn off. Optional start on boot.
- **Theming.** Each profile gets a seed colour derived from its measured curve (overridable); falls back to wallpaper colours, then a static brand scheme.

## How the system-wide EQ works

PAudio uses Android's `DynamicsProcessing` audio effect: per-ear pre-EQ, a preamp and a limiter.

- **Session mode (default, "Per app").** Many music apps announce their audio session with the `OPEN_AUDIO_EFFECT_CONTROL_SESSION` broadcast. PAudio's foreground service listens for it (a receiver registered at run time, not in the manifest) and attaches one effect to each announced session. Sessions are validated, PAudio's own package is ignored, at most 16 are live, and sessions that are closed or idle for more than 10 minutes are released. Apps that do not announce their session are not processed in this mode.
- **Legacy mode ("whole device").** Attaches one effect to the global mix (session 0). Android deprecates this and it only works on some devices, so it is an opt-in toggle in Settings. The two modes are mutually exclusive, because using both would equalize the music twice.
- **Another EQ app.** If another app holds control of the same effect, PAudio shows a Conflict state instead of fighting for it; tap to retry.
- **Speakers are never processed.** Only headphone-like outputs (wired, USB, Bluetooth) are processed, using the profile for that headset.
- **Bypass while PAudio makes sound.** While a hearing test, the A/B comparison or the preference test is playing, the system-wide effect is bypassed through a reference-counted token, so thresholds are never measured through your own EQ and the A/B never compares "EQ plus EQ" with "EQ". The engine is restored automatically afterwards; a bypass cannot survive a process restart.

Design details and the evidence behind them (frame size, band layout, limiter semantics, priority, boot behaviour) are in [docs/DECISIONS.md](docs/DECISIONS.md).

## Supported Android

`minSdk` 28 (Android 9), `targetSdk` 36, `compileSdk` 37 (minor 1). Values come from `build-logic/convention/src/main/kotlin/ProjectExtensions.kt`. Behaviour differs by version: some checks (Spatializer) need Android 12L / API 32 or later, the `specialUse` foreground-service type exists from API 34, and on Android 15 and later the system may refuse to start the service by itself after a restart (PAudio then shows a "Tap to start PAudio" notification). Per-device behaviour is tracked in [docs/HARDWARE_TEST.md](docs/HARDWARE_TEST.md) and [docs/PLAYER_COMPAT.md](docs/PLAYER_COMPAT.md).

## Build

Requirements:

- JDK 21 (CI uses Temurin 21; bytecode and Kotlin target 17). No Gradle toolchain is declared, so use a JDK 21 on your `PATH` or `JAVA_HOME`.
- Android SDK with platform `android-37.1` and build-tools 36.0.0 (plus platform-tools). Point Gradle at it with a `local.properties` file in the repository root:

  ```
  sdk.dir=/path/to/android-sdk
  ```

- The Gradle wrapper (Gradle 9.9.2) downloads everything else. The first build needs network access to Maven Central / Google Maven; a transient HTTP 429 from Maven Central is normal, rerun.

Common commands:

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew check assembleDebug         # what CI runs (plus :app:assembleRelease)
./gradlew :app:verifyPermissions      # merged release manifest vs. the permission deny-list and allow-list
./gradlew verifyFeatureBoundaries     # run ALONE: see note below
```

Notes on the check tasks:

- `verifyPermissions` fails the build if the merged release manifest declares a forbidden permission (`INTERNET`, `BLUETOOTH_CONNECT`, `READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE`, location, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, ...) or any permission that is not on the allow-list. It is part of `check` and gates `assemble<Variant>`/`bundle<Variant>`.
- `verifyPureKotlin` runs on every `:core:*` module and fails if one depends on Android or on a non-core module.
- `verifyFeatureBoundaries` fails if code under `feature/` uses the experimental Material 3 Expressive shape APIs (they are allowed only in `:designsystem`). It is part of `check`, but when started in the same invocation as other tasks it can report "implicit dependency" validation errors (a known build-logic issue), so run `./gradlew verifyFeatureBoundaries` on its own.
- Hardware-gated tests are marked `@RequiresHardware` and are skipped in CI: pass `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=my.umarh.paudio.testing.RequiresHardware` for instrumented runs.
- Release builds use R8 and resource shrinking and strip `Log.v/d/i`. Signing is optional: provide `keystore.properties` (git-ignored) or the `PAUDIO_KEYSTORE_FILE`, `PAUDIO_KEYSTORE_PASSWORD`, `PAUDIO_KEY_ALIAS` and `PAUDIO_KEY_PASSWORD` environment variables.

Golden test vectors are generated by independent Python oracles (`pip install numpy scipy`, then `python3 tools/golden/gen_all.py --out core`); see [docs/VECTORS.md](docs/VECTORS.md). The demo clips are produced by `tools/assets/fetch_demo_audio.sh` (needs network and ffmpeg).

## Module map

| Module | Role |
|---|---|
| `:app` | Application, `MainActivity`, navigation host, app-level view model, manifest merge and release config |
| `:designsystem` | Theme, signature components (butterfly, audiogram, EQ graph, hear button, wave progress), icons, bundled font |
| `:core:model` | Shared pure-Kotlin types and interfaces (devices, effect and bypass contracts, profile JSON schema v1) |
| `:core:dsp` | EQ curve building, DynamicsProcessing band planner, filters, loudness predictor |
| `:core:audiometry` | Stimulus synthesis, Quick/Thorough engines, calibration, measurement model |
| `:core:preference` | Preference bisection and the A/B listening gate |
| `:data` | Room database, DataStore settings, repositories, profile export/import |
| `:audio:tone` | Test-tone output (one continuous `AudioTrack`), volume control and lock, audio focus |
| `:audio:device` | Output-device mapping, environment checks (mono, balance, spatial audio, DND), noise meter |
| `:audio:effect` | `DynamicsProcessing` backend, session sources (broadcast, legacy), effect engine and bypass controller |
| `:audio:playback` | In-app A/B player (Media3), loudness-matched gain planning, bundled demo clips |
| `:service` | Foreground service, notification, boot receiver, active-profile and curve use cases |
| `:feature:onboarding`, `:feature:home`, `:feature:hearingtest`, `:feature:results`, `:feature:health`, `:feature:preference`, `:feature:profiles`, `:feature:settings` | The screens |
| `:testing` | Fakes and test helpers shared by unit tests |
| `build-logic/` | Convention plugins and the `verify*` tasks |
| `tools/` | Golden-vector generators (`tools/golden`) and demo-audio scripts (`tools/assets`) |

`:core:*` modules are pure Kotlin and may not depend on Android.

## Documentation

- [SPEC.md](SPEC.md): product and engineering specification.
- [docs/DECISIONS.md](docs/DECISIONS.md): research findings and decisions; they override the spec where upstream behaviour differs.
- [docs/PRIVACY.md](docs/PRIVACY.md): what is stored and what is never sent anywhere.
- [docs/HARDWARE_TEST.md](docs/HARDWARE_TEST.md): on-device test checklist.
- [docs/PLAYER_COMPAT.md](docs/PLAYER_COMPAT.md): player-app compatibility table (template).
- [docs/VECTORS.md](docs/VECTORS.md): golden test vectors.
- [docs/PROGRESS.md](docs/PROGRESS.md), [docs/HANDOFF.md](docs/HANDOFF.md): milestone log and handoff notes.

## Attributions

These match the About screen (Settings > About, licenses and disclaimer); the full details are in [ASSETS_LICENSE.md](ASSETS_LICENSE.md).

- **Demo music:** Nine Inch Nails, "Discipline" and "Echoplex" from *The Slip* (2008), and "34 Ghosts IV" from *Ghosts I-IV* (2008), licensed under Creative Commons Attribution-NonCommercial-ShareAlike 3.0 United States (CC BY-NC-SA 3.0 US). The clips were trimmed, loudness-normalized and resampled. Because of this NonCommercial license, **PAudio is free and must never be sold or monetized: no price, no ads and no in-app purchases** while these clips ship.
- **Roboto Flex:** typeface by the Roboto Flex Project Authors, SIL Open Font License 1.1.
- **Material Symbols:** icons by Google, Apache License 2.0.
- **Open-source libraries** (AndroidX, Jetpack Compose, Media3, Room, Hilt, kotlinx libraries, AndroidX Graphics Shapes, MaterialKolor, and others) are listed with their licenses (Apache 2.0 or MIT) in the app's About screen.

## License

PAudio's source code is licensed under the **GNU General Public License, version 3 or (at your option) any later version** (GPL-3.0-or-later). The full text is in [LICENSE](LICENSE). No code from other GPL projects has been ported (see DECISIONS section 13); the license choice keeps that option open.

The bundled demo audio is **not** covered by the GPL: it keeps its own CC BY-NC-SA 3.0 US license (see [ASSETS_LICENSE.md](ASSETS_LICENSE.md)).
