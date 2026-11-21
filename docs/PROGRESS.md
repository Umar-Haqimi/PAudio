# PAudio progress

## Status at handoff (orchestrator)
Merged: M0, M1, M2, M3, M4, M5, M6, M7a, M7b, M8a, M10. Not started: M7c, M8b, M9, M11, M12, M13.
Pending reviews: Opus re-verify of M7a P0 fixes; Sonnet review of M10. See `docs/HANDOFF.md` for the plan.

## Done

### M1 — Skeleton (complete)
- Multi-module Gradle build (Gradle 9.9.2 wrapper, AGP 9.5.1, Kotlin 2.4.21), version catalog `gradle/libs.versions.toml`.
- `build-logic/` included build with convention plugins: `paudio.android.application`, `paudio.android.library`,
  `paudio.android.compose`, `paudio.android.feature`, `paudio.jvm.library`, `paudio.hilt`, `paudio.core.guard`.
  Module build files are 3–25 lines.
- Modules (SPEC §5.1 + `:testing`): `:app`, `:designsystem`, `:core:{model,dsp,audiometry,preference}`, `:data`,
  `:audio:{tone,device,effect,playback}`, `:service`, `:feature:{onboarding,home,hearingtest,results,health,preference,profiles,settings}`, `:testing`.
  Android namespaces derive from the path (`:feature:home` -> `my.umarh.paudio.feature.home`).
- `:core:model`: `Ear`, `FrequencyGrid`, `TestMode`, `OutputDevice(Type)`, `HeadsetKey`, `StereoBuffer`, `EnvSnapshot`,
  `SessionEvent`, `EqConfig`, `AudioSource`, the §5.2 interfaces verbatim, Profile JSON schema v1 (`ProfileFile` + `ProfileJson.encode/decode`,
  unknown keys ignored, unknown major version rejected). Unit tests: HeadsetKey, JSON round trip / unknown fields / version / malformed input.
- `:testing`: `FakeToneOutput`, `FakeVolumeController`, `FakeOutputDevices`, `FakeEnvironmentChecks`, `FakeEffectBackend`/`FakeEffectHandle` (+ tests).
- `:designsystem`: `PAudioTheme(seedColor, darkTheme, content)` (MaterialKolor seed scheme -> wallpaper dynamic colour on API 31+ -> static brand scheme),
  `MaterialExpressiveTheme` + `MotionScheme.expressive()`, bundled Roboto Flex variable font (`res/font/roboto_flex.ttf`, OFL text in `designsystem/OFL-RobotoFlex.txt`),
  `PlaceholderScreen`.
- `:app`: `@HiltAndroidApp` Application, edge-to-edge `@AndroidEntryPoint` `MainActivity`, type-safe Navigation Compose host; every feature exposes
  `NavGraphBuilder.xxxScreen(...)` with a placeholder screen (strings in each module's `strings.xml`). No `INTERNET`; backup rules exclude
  database/sharedpref/datastore from cloud backup and allow device transfer; `dependenciesInfo` off; release = R8 + shrinkResources + `Log.v/d/i` stripped;
  optional signing from `keystore.properties` or `PAUDIO_KEYSTORE_FILE/PAUDIO_KEYSTORE_PASSWORD/PAUDIO_KEY_ALIAS/PAUDIO_KEY_PASSWORD`; `values-ms/` stub.
- `:app:verifyPermissions` (merged release manifest, wired into `check`) and `verifyPureKotlin` on every `:core:*` module (wired into `check`). Both proven to fail on a violation.
- CI: `.github/workflows/ci.yml` (temurin 21, Android SDK, Gradle cache, `./gradlew check assembleDebug`).

### M1 review fixes (applied after af91ec5)
- Manifest: removed Media3's `ACCESS_NETWORK_STATE`/`WAKE_LOCK` and the exported `androidx.profileinstaller.ProfileInstallReceiver` (`tools:node="remove"`).
- `verifyPermissions` is now an aggregate over per-variant `verify<Variant>Permissions` tasks (release-type variants only; safe with flavors). Besides the forbidden list it
  fails on any permission not on an allow-list (`MODIFY_AUDIO_SETTINGS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`,
  `RECORD_AUDIO`, `$applicationId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`). `assemble<Variant>`/`bundle<Variant>` depend on it. Not cacheable; writes `app/build/reports/permissions/<variant>.txt`.
  Both failure paths were demonstrated (WAKE_LOCK -> allow-list error, INTERNET -> forbidden error) and reverted.
- JSON schema: `TrialJson.levelDbfs` nullable (catch trials), optional `run` ("DESC"/"ASC", Thorough only) with documented Thorough semantics, `HearingTestJson.reliable` required,
  `deviceSnapshotJson` is a `JsonElement?`, unknown `OutputDeviceType` decodes to `OTHER` (unknown `TestMode` still rejected). `HeadsetKey` upper-cases the BT suffix and maps `|` in names to `/`; enum names are pinned by a test.
- Test setup: `isReturnDefaultValues` removed, `isIncludeAndroidResources = true`, `AndroidJUnitRunner`; catalog has androidx.test runner/core 1.7.0 and hilt-android-testing; Android modules get
  junit/truth/coroutines-test/turbine/robolectric (+ androidx.test) and Compose modules get the BOM and ui-test-junit4 (+ ui-test-manifest in debug). `failOnNoDiscoveredTests` is off.
- Room: `paudio.android.room` convention (KSP + `androidx.room` plugin, `schemaDirectory("$projectDir/schemas")`, room-testing) is applied, with `paudio.hilt`, to `:data` (builds fine with no entities yet).
- `:testing`: added `FakeNoiseMeter`, `FakeSessionSource`, `FakeAbPlayer`; `FakeVolumeController.changes` emits external changes only (`externalChange(i)`); `FakeEffectBackend.activeEnabled`
  now also requires control (`enabledRequested` ignores it).
- `:designsystem` exposes graphics-shapes and MaterialKolor as `implementation`. Root `verifyFeatureBoundaries` (in `check`) fails if `feature/**` references `MaterialShapes`, `androidx.graphics.shapes` or `ExperimentalMaterial3ExpressiveApi`.
- CI: `cmdline-tools-version: 16111833` (latest found), `:app:assembleRelease` added.

## Next
- M2: `:core:dsp` + golden tooling. M0 (research, `docs/DECISIONS.md`) is being written separately; confirm `Ear.channelIndex` mapping, DP band semantics there.
- Add permissions as the milestones need them (`MODIFY_AUDIO_SETTINGS`, FGS, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, optional `RECORD_AUDIO`); `verifyPermissions` guards the forbidden list.
- `:data` has only DataStore on its classpath; Room + KSP schema export are in the catalog but not wired (M5).
- `:designsystem` still needs the signature components (hear button, wavy progress, butterfly, charts) and the Material Symbols vector drawables.
- Malay translations (`values-ms/strings.xml` is an empty stub).

## Open issues
- Maven Central intermittently returns HTTP 429 in this environment; just rerun. `plugins.gradle.org/m2` is configured as a fallback.
- The lint `MissingTranslation` check is not an issue yet because `values-ms` has no entries; revisit when translations start.
- `:core:*` guard treats every `androidx.*`, `com.android.*` and `com.google.android.*` artifact as Android, including KMP `-jvm` artifacts of androidx (e.g. `androidx.annotation`). Core must not use them.
- `OutputDeviceType.OTHER.isProcessable = false` (unknown routes are never processed) — revisit in M6 if needed.

## Decisions
- **Versions** (all in `gradle/libs.versions.toml`, all pinned as requested, no fallbacks needed): AGP 9.5.1, Gradle 9.9.2, Kotlin 2.4.21, KSP 2.3.14, Compose BOM 2026.11.01,
  material3 1.5.0 (explicit pin over the BOM), MaterialKolor 5.0.1, Hilt 2.60.1, hilt-navigation-compose 1.4.0, navigation-compose 2.10.2, Room 2.8.5 (catalog only),
  DataStore 1.3.0, Media3 1.11.1, activity-compose 1.13.0, lifecycle 2.11.1, core-ktx 1.19.1, kotlinx-serialization 1.13.0, coroutines 1.11.0,
  Robolectric 4.18.1, Truth 1.4.5, Turbine 1.2.1, JUnit 4.13.2, androidx.test.ext:junit 1.3.0, graphics-shapes 1.1.0.
- **AGP 9 built-in Kotlin**: `org.jetbrains.kotlin.android` is NOT applied anywhere (AGP 9 compiles Kotlin itself). Only `kotlin.plugin.compose`, `kotlin.plugin.serialization`,
  KSP and the Hilt plugin are applied on Android modules; `org.jetbrains.kotlin.jvm` on JVM modules. KSP 2.3.14 and Hilt 2.60.1 work with it.
- **compileSdk 37.1** DSL in AGP 9.5: `compileSdk { version = release(37) { minorApiLevel = 1 } }`. `CommonExtension` has no `defaultConfig {}` lambda blocks, so the
  shared convention uses property access (`android.defaultConfig.minSdk = 28`).
- **JVM level**: bytecode/Kotlin target 17 (`compileOptions`, `jvmTarget`), compiled on JDK 21. No Gradle toolchain is declared because only JDK 21 is installed and
  toolchain auto-provisioning needs network; CI uses JDK 21.
- **Build tools**: AGP 9.5.1 auto-installs build-tools 36.0.0 (37.0.0 is present but not selected).
- **Expressive theme**: `MaterialExpressiveTheme(colorScheme, motionScheme, shapes, typography)` and `MotionScheme.expressive()` exist in material3 1.5.0 and compile
  without an opt-in. `LinearWavyProgressIndicator` / `CircularWavyProgressIndicator` and `MaterialShapes` also exist (M0 confirms parameters).
  Seed schemes use MaterialKolor `rememberDynamicColorScheme(..., style = PaletteStyle.Expressive, specVersion = ColorSpec.SpecVersion.SPEC_2025)` fed into
  `MaterialExpressiveTheme` (rather than `DynamicMaterialTheme`, which wraps plain `MaterialTheme` and would lose the expressive motion scheme).
- **Roboto Flex**: downloaded from google/fonts `ofl/robotoflex/RobotoFlex[GRAD,XOPQ,XTRA,YOPQ,YTAS,YTDE,YTFI,YTLC,YTUC,opsz,slnt,wdth,wght].ttf` (1.7 MB) via jsDelivr
  (raw.githubusercontent was blocked in the sandbox); stored as `res/font/roboto_flex.ttf`; weights via the `wght` variation axis.
- **No-Android-in-core check** (`build-logic/convention/.../VerifyPureKotlinTask.kt`, applied to every `:core:*` project from the root `build.gradle.kts` through
  `CoreGuardConventionPlugin`, so modules cannot opt out): fails if a project applies a `com.android.*` plugin, resolves any `com.android.*`/`androidx.*`/`com.google.android.*`
  artifact on compile/runtime/test classpaths, depends on a non-`:core:` project, or has an unresolvable dependency.
- **verifyPermissions** (`VerifyPermissionsTask.kt`, registered in `AndroidApplicationConventionPlugin` through the variant API for the `release` variant,
  input `SingleArtifact.MERGED_MANIFEST`): parses `uses-permission*` and fails on the §6 forbidden list.
- **Configuration cache, parallel and build cache are on** and working.
- **Navigation**: Navigation Compose 2.10 type-safe routes (`@Serializable data object XxxRoute`); the feature convention applies the serialization plugin.
- **Android launcher icon**: placeholder vector headphones (adaptive + monochrome).

### M5 — Data layer (complete)
- `:data` (`my.umarh.paudio.data`): Room v1 (`db/`, schema exported to `data/schemas`), repositories (`repo/`), DataStore `SettingsRepository`, `transfer/` (pure `ProfileImportValidator`, `ProfileExporter`, `ProfileImporter`), Hilt in `di/`.
- Any-headphones uniqueness: SQLite UNIQUE ignores NULLs, so `RoomProfileRepository` checks the slot (`headsetId IS :x`) inside a Room transaction for create/duplicate/move/ensureAny; import does the same.
- `duplicate` needs a free target slot (`ProfileSlot`), else `Conflict`. Import conflict: `ImportResult.Conflict` (no writes) resolved via `ConflictPolicy` REPLACE / IMPORT_AS_ANY / REPLACE_ANY.
- Import rejects (never truncates) >2 MB, >100 tests, >20,000 trials, nesting >32, NaN/Inf; clamps numeric ranges; recomputes `HeadsetKey`.
- Trend filtering by `headsetKey` inside `deviceSnapshotJson` (`DeviceSnapshot.withHeadsetKey`).
- `androidTest/MigrationTest` is ready for version 2 (not run in CI).

### M7b — In-app A/B player (`:audio:playback`)
- `ExoAbPlayer` (Media3 ExoPlayer): own audio session id set before `prepare`, ONE enabled `EffectHandle` on it via injected `EffectBackend`, `BypassToken` (RESULTS_AB / PREFERENCE_AB) held from first `play()` to `release()`, 20 ms duck on `swapTo` (rapid swaps coalesce), transient audio focus, `listenTicks` (side + delta ms) for `AbRoundGate`.
- `AbGainPlanner`: `P_common = max(-15, -max(0, max G over ALL options) - 0.5)`; effect inputGain = `P_common - ΔL(G)` (flat: ΔL = 0). `swapTo(cfg, inputGainDb)` is passed `cfg.preampDb = P_common` and `inputGainDb = -ΔL`.
- `LtasRepository`, `DemoClipSource` (preference loop = first 12 s), `AbPlayerFactory` (`@Inject`, app must bind `EffectBackend` + `BypassController`).
- Build changes: catalog `androidx-media3-test-utils`; `:audio:playback` applies `paudio.hilt`, depends on `:core:dsp`, `:core:preference`; `RequiresHardware` annotation in `:testing`.


### M7a — `:audio:effect` engine (complete)
- `DynamicsProcessingBackend` (priority 100, frequency variant, 16 ms frames, PreEQ + limiter(-3 dB), per-ear gains, diff writes on one worker thread >= 50 ms apart, readback -> ladder, MBC-passthrough creation retry), `BroadcastSessionSource` / `LegacySessionSource` + pure `parseSessionIntent`, `EffectEngine` (state machine, 16-session cap, idle sweep, no-session hint, conflict/error handling) which is also the ref-counted P0 `BypassController`; Hilt `EffectModule`.
- Additive `core:model` changes: `EffectCreationException`/`EffectFailureKind`, `EffectHandle.clamped` (default never). `:testing`: `FakeEffectBackend.failCreates`, `FakeEffectHandle.setClamped`, `@RequiresHardware`.
- Tests: engine (fakes, P0 bypass incl. sessions/config/device changes during bypass, nested tokens, process-death restore, conflict, ladder, idle, hint), backend via a fake `DpPort` (Robolectric), session source receiver (Robolectric); androidTest `DynamicsProcessingHardwareTest` (`@RequiresHardware`, not run in CI).
- Needs a device: real DP creation/readback, priority arbitration against another EQ app, enable-toggle clicks. The `:service` consumes `EffectEngine` (`setRunning/setMode/setOutputDevice/setTarget/setPersonalized/retry`, `state`, `diagnostics`, `noSessionHint`).
- P0 race fixes (Opus review): rebuild creates the fresh port disabled; `acquire()` waits (<= 250 ms) for in-flight creations; ports disabled before every release; late `lostControl()` no longer overrides a regain; failed rebuild drops the zombie handle and keeps writes dirty; token close reconciles off the caller thread. `FakeEffectBackend` now models enabled-flag inheritance on create, regain and release (`releasedWhileEnabled`, `FakeEffectHandle.enabledAtRelease`). Race tests in `ReviewRaceTest` (real threads, latch-driven).
## M6 — audio:tone + audio:device

- `:audio:tone`: `StreamScheduler` (pure silence/stimulus/25 ms raised-cosine ramp-down), `StreamEngine` (writer thread over a `PcmSink`), `AudioTrackToneOutput` (one continuous float AudioTrack, 16-bit TPDF fallback), `AndroidVolumeController` + `VolumeLock`, `AudioFocusMonitor`, Hilt `ToneModule`.
- `:audio:device`: `DeviceMapping`, `AndroidOutputDevices`, `AndroidEnvironmentChecks` + `EnvironmentVerdict`, `AndroidNoiseMeter` + `NoiseLevel`, Hilt `DeviceModule`.
- `:core:model`: added `InterruptibleToneOutput` (extension of `ToneOutput`); `:testing`: `RequiresHardware`, `FakeToneOutput` implements the extension.
- Hardware tests (`@RequiresHardware`) in `audio/tone/src/androidTest`; exclude in CI via `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=my.umarh.paudio.testing.RequiresHardware`.
- M6 review fixes: engine failure recovery (dead-object sink re-creation once, failed engine rebuilt by `open()`), single stable `playing` flow, orphan-waiter/close races, echo suppression (`expectSelf`), `VolumeLock` initial drift + no raising after external lowering, exact-zero silence in 16-bit path, head-reset detection, muted-mic noise chunks skipped.
- Device verification gap: Samsung `mono_audio` setting key is not covered by `AndroidEnvironmentChecks` (only `master_mono`).

## M8a — designsystem signature components

- Packages: `designsystem.chart` (HearingButterfly, Audiogram + WhoBandLegend/WhoBand, EqGraph, TrendChart), `designsystem.hear` (MorphingHearButton, MorphShape, FrequencyWaveProgress), `designsystem.component` (StatusChip, DisclaimerCard, SectionCard, InstructionRow, SegmentedToggle), root (`PAudioIcons`/`PAudioIcon`, `ProfileSeedColor`, `PAudioTheme`).
- Experimental M3 Expressive APIs are opted in only in `:designsystem` (`kotlin.compilerOptions.optIn` in its build.gradle.kts); `MaterialShapes`/graphics-shapes never leak into the public API.
- 42 Material Symbols (Rounded, 400, 24px) vendored as `res/drawable/ic_*.xml` (viewport 960, `translateY=960` group); no tint attr, tint via `Icon`. All strings in `res/values/strings.xml` (`ds_*`).
- Hear button: no haptics/sound; fires `onPress` on pointer-down and on accessibility click; `View.isSoundEffectsEnabled=false` while composed.
- Seed colour: hue 200..340 deg (log 1..14 kHz) of the worst-HL frequency, HSV saturation 0.35..0.85 by mean |L-R| (0..30 dB), value 0.75; fallback brand blue.
- Reduced motion: `ANIMATOR_DURATION_SCALE == 0` skips butterfly draw-in, EQ spring, wave amplitude ease and button pulse.
- Tests: JVM math/seed tests + Robolectric Compose tests (light/dark, semantics, DisclaimerCard has no click/dismiss/expand/collapse action).
- Note: `./gradlew verifyFeatureBoundaries` reports "implicit dependency" validation errors when run in the same invocation as other build tasks (pre-existing build-logic issue); run it alone.
- M7b review fixes: `configFor` takes the real output sample rate and ladder (`AbPlayerFactory.outputSampleRate()`, `DpEqPlanner.fromCurve`); `swapTo` required before `play`; bypass acquired in `load`; device checklist: ClippingMediaSource loop is re-seek (not sample-gapless), duck click-free, focus loss.

## M10 — Hearing health (feature:health)
- `HealthRoute(profileId: Long? = null, testId: Long? = null)` (type-safe, `@Serializable`); no profileId => most recently tested profile (active listener's first). `nav.navigate(HealthRoute(profileId = id))`.
- `HealthViewModel` -> `HealthUiState` (Loading / Empty / Content). Pure mapping in `HealthMapper` (MeasurementModel.compute per test; trend = PTA3 per ear recomputed from raw thresholds for `observeForTrend(profileId, headsetKey)`, shown when >= 2). Headset key = test's deviceSnapshot key, else the profile's headset.
- Screen: WHO legend + Audiogram, per-ear PTA3/EHF (EHF number only, no grade), approximate grade of better ear with DECISIONS 17 caveat, asymmetry/reliability notes, trend card, "How to read this" ModalBottomSheet; disclaimer is the Scaffold bottomBar (always visible, no actions).
- Tests: mapper goldens, VM (fakes), Robolectric UI (disclaimer no click/dismiss/expand/collapse incl. ancestors, stays after scroll; empty; content+trend), strings scan for forbidden words (the normative disclaimer's "medical diagnosis" is the only allowed "diagnos").
- app: `nav.navigate(HealthRoute())` (route became a data class).

## M7c — `:service` (complete)
- `PAudioService` (`specialUse` FGS, `START_STICKY`, notification + actions), `BootReceiver`/`BootLogic` (opt-in, fallback "Tap to start PAudio"), `NotificationActionReceiver` (non-exported, "Use <profile>"), `NotificationPoster` (channels, engine/boot/headset notifications), `EngineStatus` (EngineState -> copy, shared with Home), `ServicePlatform.kt` (intents, `ServiceFlags`, `ServiceLauncher`, `LaunchRequests`, platform seams).
- Use cases in `my.umarh.paudio.service.engine`: `ActiveProfileResolver` (`ActiveProfile` Loading/Resolved/NoProfile/NotProcessable, wired-collision question), `ProfileCurveService` (memoized `config(profile, source, fs, ladder)`, `observeSource`, `loadSource`), `EngineController` (state, noSessionHint, running, personalized, setPersonalized/togglePersonalized, retry, setMode, start/stop/startIfAllowed, notificationsBlocked), `UnknownHeadsetPrompter` (F17).
- `:app`: `MainActivity` offers launch intents to `LaunchRequests`, starts the FGS from `ROUTE_START_SERVICE` and on foreground when onboarding is done and the user did not turn it off. Manifest permissions live in `:service` (allow-list passes).
- Tests (25): controller (device switch via `setRoute`, speaker never processed, unknown headset, load gap, personalized persistence, process-death restore in Legacy/Original and Session, ladder re-plan, mode switch, start/stop flags, a real-thread run with the engine on its own single thread and 100 rapid device switches), resolver/curve (order, wired question, memoization, flat/preference-only/loss curves), Robolectric (boot opt-in/fallback for three exception types, text for every `EngineState`, notification actions immutable + explicit, headset prompt).
- Needs a device: FGS `specialUse` start on API 34-37, boot start/fallback on API 35+, sticky restart after process kill, notification actions from the shade, POST_NOTIFICATIONS denial, `<queries>` app labels on API 30+, real `AudioDeviceCallback` BT <-> wired switching with a playing app (no stale curve), wired-collision prompt.
- Gaps: no UI for the unknown-headset/wired question (M8b); `PAudioService` itself is covered only by the manifest merge and the controller tests (no Hilt-Robolectric service test).
### M10 review fixes
- Disclaimer: the "How to read this" sheet scrims over the Scaffold footer, so the same non-dismissable `DisclaimerCard` is pinned at the bottom of the sheet (outside its scrolling list). Both copies are capped at 40 % of the viewport height and scroll inside (200 % font scale); still no click/dismiss/expand/collapse actions, full text stays in the semantics tree.
- `HealthUiState.Unavailable(reason, canShowLatest)`: requested testId not in the profile (foreign/deleted) -> TEST_MISSING with a "Show latest test" action (`HealthViewModel.showLatest()`); explicit profileId that no longer exists -> PROFILE_MISSING; incomplete thresholds -> INCOMPLETE (no longer "No hearing test yet"); repository flow errors -> ERROR. `testId == null` still means latest; null profileId keeps the "active listener first, most recently tested" resolution, now reactive (combine/flatMapLatest over `observeLatest`) instead of `.first()`.
- Copy: neutral "Test from %1$s · %2$s"; per-ear note that the approximate grade refers to the better ear; American spelling ("neighboring", "Colored").
- `formatDb`: one decimal, truncated toward zero with epsilon (19.6 shows "19.6", never "20" next to "Normal"), never "-0", locale-aware.
- Tests: VM (no-snapshot/no-headset => no trend, snapshot fallback to profile headset, foreign testId + showLatest, deleted profile, incomplete, repository error, active-listener ordering and reactivity; `FakeTests.observeForTrend` mirrors the repository's `?: currentKey`), UI (disclaimer in sheet, Reliability/Asymmetry/censored notes, Unavailable states, neutral info line, formatDb), strings test finds `strings.xml` by walking up from `user.dir`. Note: VM tests need `runCurrent()` after `advanceUntilIdle()` because the collector runs in `backgroundScope`.

- M7c review fixes (DECISIONS 20.1): restart route reset, lifecycle-ordered engine commands, launch replay guard, prompt dedupe + "Don't ask", stricter wired question, newest complete test, single-column personalization, crash guard. Regression tests in `ReviewRegressionTest` (the 20,000-iteration real-thread test runs 3,000 by default; `PAUDIO_STRESS=20000` for more).
## M9b — Results (feature:results)
- `ResultsRoute(testId)`; `ResultsViewModel` (Hilt) -> `ResultsUiState` (Loading / Unavailable / Content) + `AbUiState`. Pure mapping in `ResultsMapper` (thresholds -> `MeasurementModel` -> butterfly input, per-ear PTA3 + `PtaWording`, EHF beyond-range, asymmetry, reliability); `AbPlanner` (curve + `AbGainPlanner` + `DpEqPlanner` configs). Audio is behind `ResultsAbBackend` (Android impl = `AbPlayerFactory` + `LtasRepository`, Hilt `@Binds` in this module).
- UI: animated `HearingButterfly` (wings labelled in text, rings for censored), plain-words summary, notes card (unreliable + redo, low-confidence plural, asymmetry, beyond test range), A/B card (`SegmentedToggle` Original/Personalized, Play/Pause, "Use my own song" SAF picker, source + CC attribution), `EqGraph` (G(f) <-> flat), "See hearing health", Done, exit dialog. Player + bypass token live only while the screen is STARTED (see DECISIONS 20).
- app: only the `resultsScreen(...)` call (plus `ResultsRoute(testId = 0L)` placeholder with `// TODO(M9a)` and a `// TODO(M11)` for `PreferenceRoute(profileId)`).
- Tests (36): mapper goldens (known threshold sets), VM with `FakeAbPlayer` + `FakeBypassController` (swap gains/config vs an independent AbGainPlanner computation at 44.1 kHz, flat Original, pink LTAS for own file + fallback, P0 token before create/load and released on stop/clear, background stop/start, seed colour rules, unavailable states), Robolectric Compose (toggle label/graph description, play enable, picker, notes, exit dialog on system back/Done/Not now, health args), strings scan.
- Device-only checks: duck click-free on toggle, loop seam of the 30 s clip, loudness match by ear on real earphones, focus loss while playing (Play/Pause follows `ExoAbPlayer.playing`), SAF picker with real files (mp3/m4a/flac/ogg), TalkBack reading of butterfly/graph, 200 % font scale.

## M8b — Onboarding, Home, Profiles, Settings/About (implemented; review pending)
- Implemented by a Sonnet agent that hit the usage limit after its tests were green; the orchestrator finished it (Settings VM/UI tests, docs) and merged it. **Sonnet review still pending.**
- `:feature:onboarding`: welcome → how it works → disclaimer ("I understand" required) → notification permission (denial copy, continues) → listener name (default "Me") → `EngineController.start()` → onboarding done → Home.
- `:feature:home`: active profile card (mini butterfly, listener, headset chip), Personalized/Original toggle, strength slider, engine status chip via `EngineStatus.textOf` (Conflict/Error tap → retry, NoProfile → create profile), notification-blocked + service-off banners, actions; empty-state CTA "Take your hearing test (≈ 7 min)".
- `:feature:profiles`: `ProfilesRoute(createForCurrent: Boolean = false)` grouped list; `ProfileDetailRoute(id)` with rename / duplicate / move (offers retest) / delete / seed-colour override / SAF export / import with inline validation errors and `ImportResult.Conflict` → REPLACE / IMPORT_AS_ANY.
- `:feature:settings`: mode Session/Legacy, start on boot, compensation default, service on/off; `AboutRoute`: NIN CC BY-NC-SA + no-monetization note, Roboto Flex OFL, Material Symbols Apache-2.0, hand-written open-source licence list, disclaimer, version.
- `:app`: `AppViewModel` (start destination from onboarding flag, theme seed from the active profile, `LaunchRequests` routing, app-level "Which headphones are these?" dialog from `EngineController.whichHeadphones`), `AppRoot`.
- Each feature talks to `EngineController` through a small feature-local interface (`HomeEngine`, `SettingsEngine`, …) bound in a Hilt module, so ViewModels are tested with plain fakes.
- Tests: onboarding 11, home 19, profiles 38, settings 5 (+ forbidden-word string scans in each module). `verifyFeatureBoundaries` passes.

## M9a — Hearing-test flow UI (`:feature:hearingtest`)
- `HearingTestRoute(profileId: Long? = null)`; `hearingTestScreen(onShowResults: (testId: Long) -> Unit, onBack)`. Mode choice (Quick ≈ 6–8 min, Thorough ≈ 7–9 min) → pre-test page → L/R channel check → calibration → Quick / Thorough → pause/resume → save → `onShowResults(id)`.
- `TestSession` (pure Kotlin over interfaces, one coroutine on a dedicated single-thread dispatcher owned by the ViewModel) implements `Starting → ChannelCheck → Calibrating → Testing ⇄ Paused → Saving → Finished | Aborted`. ONE `BypassToken` (acquired before the stream is opened, closed last in `finally`); one stream for the whole session. `finally`: tone close → `VolumeLock.release` → focus abandon → token close; the terminal state is published only after that.
- `HearingTestViewModel` (pre-test state, noise meter opt-in, env polling 1 Hz while the page is subscribed), `RepositoryTestStore` (profile/listener/headset resolution at save time), Hilt module with `TestPlatform`/`FocusSource`/`Clock`/dispatcher factory, `CalibrationCache` (30 min reuse).
- UI: `MorphingHearButton` + "I didn't", `FrequencyWaveProgress`, `LevelDial` (no numbers, custom a11y actions), pause/abort screens, exit dialog, sound effects off while composed, no live regions around stimuli.
- Additive change in `core:audiometry`: `CalibrationSession(startIndex = ...)` (a refused initial `setStreamVolume` must plan from the real index).
- Tests (47): session (virtual time): Quick/Thorough happy path to save, P0 with a TraceBypass AND with the real `EffectEngine` + `FakeEffectBackend`, 240 seeded random cancellations, every pause reason + resume (only the interrupted point restarts), calibration rerun / manual volume / reuse / can't-hear, stream loss, timing; `RealThreadCancellationTest` (real `StreamEngine` writer thread + executor dispatcher, random wall-clock cancellation incl. while paused, no leaked writer thread); ViewModel (blockers, DND, noise opt-in/stop/denied, full flow, `onCleared` teardown); store; Compose/Robolectric UI (blocking warning, pause copy verbatim, exit dialog, Quick happy path through the real ViewModel).
- Simulated timing (DECISIONS §18 model): Quick test phase 7.3–8.2 min (5 seeds), Thorough 7.9 min at 12 s/run.
- Known gaps: process death mid-test leaves the calibrated volume (the original index is not persisted) and the engine restores on service start; a paused session is aborted after 5 min (`PAUSE_TIMEOUT`).
- Device-only checks (HARDWARE_TEST): tones through TalkBack speech, haptic tick timing vs gaps on the dial, volume restore after refused `setStreamVolume`, headset unplug mid-stimulus, focus loss during a call, channel-check level (-45..-30 dBFS at 0.5×max) audible/safe on real earphones, BT dead-object recovery.
