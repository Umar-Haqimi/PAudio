# PAudio progress

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
