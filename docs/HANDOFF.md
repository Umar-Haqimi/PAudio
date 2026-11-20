# PAudio — Handoff note

> Written by the orchestrating Claude session when usage ran low. Read this first, then
> `SPEC.md` (source of truth), `docs/DECISIONS.md` (research + decisions that override the spec
> where upstream source code disagrees) and `docs/PROGRESS.md` (per-milestone details).
> Branch: `claude/wonderful-hamilton-vnduvv`. Everything listed as merged is pushed.

## 1. Where things stand

| Milestone | Module(s) | State | Implemented by → reviewed by |
|---|---|---|---|
| M0 Research & decisions | `docs/DECISIONS.md`, `docs/VECTORS.md` | ✅ merged | Sonnet → orchestrator spot-check |
| M1 Skeleton | build-logic, all 21 modules + `:testing`, `:core:model`, theme, CI, `verifyPermissions`, core purity guard | ✅ merged (+ review fixes) | Sonnet → Opus |
| M2 DSP | `:core:dsp` + golden sets 1–4, 5c, 6 | ✅ merged (+ fixes) | Sonnet → Opus |
| M3 Audiometry | `:core:audiometry` + golden sets 5a, 7, 8 + simulated listeners | ✅ merged (+ fixes) | Sonnet → Opus |
| M4 Preference + demo assets | `:core:preference`, golden set 9, NIN FLAC clips + LTAS, `ASSETS_LICENSE.md` | ✅ merged | Sonnet → Sonnet (clean) |
| M5 Data | `:data` (Room v1, repos, DataStore, export/import, fuzz) | ✅ merged (+ fixes) | Sonnet → Sonnet |
| M6 Tone + device | `:audio:tone`, `:audio:device` | ✅ merged (+ fixes) | Sonnet → Sonnet |
| M7a Effect engine | `:audio:effect` | ✅ merged (+ 13 fixes + clamp-race fix); **Opus re-verify pending** | Sonnet → Opus |
| M7b A/B player | `:audio:playback` | ✅ merged (+ fixes) | Sonnet → Sonnet |
| M8a Design system | `:designsystem` components + icons | ✅ merged (+ fixes) | Sonnet → Sonnet |
| M10 Hearing health | `:feature:health` | ✅ merged; **review pending** | Sonnet → (none yet) |
| M7c Service + use cases | `:service` | ❌ not started | — |
| M8b Onboarding/Home/Profiles/Settings | `:feature:onboarding/home/profiles/settings` | ❌ not started | — |
| M9 Hearing test flow + results | `:feature:hearingtest`, `:feature:results` | ❌ not started | — |
| M11 Preference test + fine-tune | `:feature:preference` | ❌ not started | — |
| M12 Hardening | all | ❌ not started | — |
| M13 Docs | README, LICENSE → GPL-3.0-or-later, PRIVACY, HARDWARE_TEST | ❌ not started | — |

### 1.1 In flight at handoff time
<!-- HANDOFF-INFLIGHT -->
No agents are running. Branch tip at handoff: see `git log`; `./gradlew :app:assembleDebug :app:verifyPermissions` is green
and every merged module's `check` passed on the merged tree.

**First actions for the next session (in order):**
1. **Opus re-verification of M7a's P0 fixes** (inherited-enable hole, bypass during create/rebuild/control regain,
   execution-time 50 ms spacing, clamp event `replay = 1`, engine single-thread + creation outside the lock). The
   implementer also found and fixed a real lost-clamp-event bug after review, so the re-check is worth it.
2. **Sonnet review of M10 `:feature:health`** (commit 3bfb13f). Note its forbidden-word scan exempts "diagnos" only
   for the mandated disclaimer string.
3. Then M7c `:service` (§3 item 1). The features after it depend on its use cases.

**Engine API facts for M7c** (from M7a's final report): prefer `EffectEngine.setRoute(device, target)` on device
changes; all setters and `retry()` are **asynchronous** (posted to a single-threaded engine scope), `acquire()` is
synchronous; `setRunning(true)` resets the ladder to MAIN; feed the ladder back via `diagnostics.ladder` → re-plan
with `DpEqPlanner.plan(..., ladder)` → `setTarget`; `EffectHandle.clamped` is `Flow<Int>` and `failed` is
`StateFlow<EffectFailureKind?>`. **Health nav:** `HealthRoute(profileId: Long? = null, testId: Long? = null)`;
Results and Profile screens should pass `profileId` (and `testId`).

**What the current debug APK shows:** app shell, navigation, the expressive theme, placeholder screens for
onboarding/home/hearing test/results/preference/profiles/settings, and the real Hearing health screen (empty state
until a test exists, since nothing can record tests until M9). There is no system-wide processing yet (no service).

## 2. Key decisions a successor must know (details in DECISIONS.md)

- **DynamicsProcessing EQ is a brick-wall FFT staircase**, not parametric. Use `VARIANT_FAVOR_FREQUENCY_RESOLUTION` (the time variant throws), **16 ms frame (N=1024)** and **87 bin-aligned bands** (`DpBandLayout.of`), with fallback ladder MAIN → FB51 → SPEC40 on readback mismatch. The spec's 40 log bands fail the 0.5 dB RMS test. Always go through `core:dsp` `DpEqPlanner` (`plan`, `flat`, `fromCurve`), never hand-build cutoffs. Channel 0 = left.
- **The DP "limiter" is a frame-RMS compressor**: `Limiter(true,true,0,1,50,10,-3,0)`. The preamp is the real clipping guard.
- **Priority 100**; the constructor throws on conflict; **a new controller inherits the previous controller's enabled flag** → always set the enabled state explicitly after create/rebuild/control regain (this was the P0 hole Opus found in M7a).
- **Quick-test targets in SPEC §7.2/§9 are unreachable** by the normative Hughson-Westlake procedure (≥9 presentations even for a perfect listener). The procedure was kept; targets were relaxed and recorded in DECISIONS §18 (median err ≤3.5 dB, p90 ≤7, ≤10/pt, total median ≤220, catch rate 11.5–13.5% via catch gaps U{6..10}, duration p95 ≤9 min). **UI copy: "Quick ≈ 6–8 min"** (the spec says 4–5).
- **The WHO grade structurally almost always reads "Normal"** under the anchor + censoring model (DECISIONS §17). Copy must say "approximate" and never imply "your hearing is fine".
- **Loudness matching** (`AbGainPlanner`): shared preamp over all options, `P_common = max(-15, -max(0, max G) - 0.5)`; per option `inputGain = P_common − ΔL(G)`; ΔL uses the clip LTAS (pink for user files). The DP layout must use the real mixer rate (`AbPlayerFactory.outputSampleRate()`).
- **P0 bypass contract**: `core:model` `BypassController` / `BypassToken` (ref-counted, `withBypass`). The tone/test layer and `ExoAbPlayer` (acquired in `load()`, held until `release()`) must hold a token while PAudio makes sound. The engine implements it.
- **Permissions**: `verifyPermissions` enforces both a deny-list and an **allow-list** on the merged release manifest. Media3's `ACCESS_NETWORK_STATE`/`WAKE_LOCK` are removed with `tools:node="remove"`.
- **Experimental M3 APIs** (MaterialShapes, graphics-shapes, `@ExperimentalMaterial3ExpressiveApi`) are allowed only in `:designsystem`; `verifyFeatureBoundaries` enforces this.
- **Schema v1** (`ProfileJson.kt`): `TrialJson.levelDbfs` is nullable (catch trials), and there is an optional `run` ("DESC"/"ASC" for Thorough). The Room entities mirror it.
- **License**: no GPL code was ported, but the spec defaults to GPL-3.0-or-later and the repo still has an Apache-2.0 `LICENSE` → **M13 must replace it**.

## 3. Remaining work (suggested agent breakdown)

Use the orchestration recipe in §4. Each bullet below is one implementer agent; the reviewer model is in brackets.

1. **M7c `:service` + use-case layer** [Opus review]. `PAudioService` FGS `specialUse` (manifest `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`), started from onboarding; boot receiver (opt-in) that tries `startForegroundService` and falls back to a "Tap to start PAudio" notification on `ForegroundServiceStartNotAllowedException` (DECISIONS §8); persistent notification with actions Personalized ⇄ Original / Open app / Turn off, plus state text from `EngineState` (F21/F22); notification-permission-denied copy. Use cases (Hilt singletons, consumed by features): `ActiveProfileResolver` (OutputDevices → HeadsetKey → DataStore map / `ProfileRepository.resolveForHeadset` → profile or NoProfile; unknown-headset notification with "Create profile"/"Use <profile>", F17; wired-key collision prompt §5.5), `ProfileCurveService` (latest test thresholds → `MeasurementModel` → HL → `DpEqPlanner.plan(..., fs = mixer rate, ladder = engine.diagnostics.ladder)`, memoized by (testId, strength, prefs, algoVersion)), `EngineController` facade (state, `setPersonalized`, `retry`, mode SESSION/LEGACY, running) calling `engine.setRoute(device, plan.toEqConfig())` atomically. Tests: device switch → correct profile applied; speaker → no processing; process-death restore.
2. **M8b UI: onboarding, Home, Profiles, Settings/About** (F1, F16–F18, F20–F25) [Sonnet review]. Home: active profile card (mini butterfly, listener, headset chip), Personalized/Original `SegmentedToggle`, strength, engine `StatusChip`, actions, empty-state CTA "Take your hearing test (≈ 7 min)". Profiles: grouped list, detail, rename/duplicate/move (offer retest)/delete, export/import via SAF (`ProfileExporter`/`ProfileImporter`; handle `ImportResult.Conflict` with REPLACE / IMPORT_AS_ANY). Settings: mode toggle with explanation, boot opt-in, compensation default, About with attributions (NIN CC BY-NC-SA, Roboto Flex OFL, Material Symbols Apache-2.0), licenses, disclaimer. Seed color from `ProfileSeedColor` → `PAudioTheme`.
3. **M9 UI: hearing-test flow + results** (F2–F12) [Opus review — timing/P0/cancellation]. Pre-test page (`InstructionRow`s, live DND, optional noise meter that stops when leaving composition, blocking `EnvironmentVerdict` warnings, ANC checkbox); L/R channel check; calibration driven by `Calibration`/`CalibrationSession` + `VolumeLock` + `AudioTrackToneOutput` (one stream for the whole session); Quick (`QuickTestEngine`, `MorphingHearButton`, responses during the stimulus count via `InterruptibleToneOutput.playing`, random 400–1200 ms gaps) and Thorough (`ThoroughTestEngine`, level dial with haptic ticks, no numbers); `FrequencyWaveProgress`; pause/resume (headset disconnect, `VolumeLock.drift`, focus loss, backgrounding) restarting only the interrupted point; the whole flow inside `withBypass`; no TalkBack announcements or haptics during a stimulus (trap #20); save via `HearingTestRepository.save(..., headsetKey)` using `persistableTrials`. Results: butterfly, PTA3 plain words, notes, A/B card (Discipline or user SAF file via `ExoAbPlayer` + `AbGainPlanner`), `EqGraph`, "See hearing health", exit dialog → preference test.
4. **M11 UI: preference test + fine-tune** (F14–F15) [Sonnet review]. `PreferenceBisection` rounds with `ExoAbPlayer` (12 s loop, `AbRoundGate` fed by `listenTicks`), "Can't tell", "Try another song" (`DemoClip.next()`), result curve, fine-tune sliders + live `EqGraph` + toggle, save `PreferenceTest` (roundsJson) and profile gB/gP/gT.
5. **M12 Hardening** [Opus review]: re-verify every §2.2 trap with tests; a11y pass; R8 release build runs; privacy (no hearing data in logs, grep test); performance; UI happy-path Compose tests (§7.2 UI).
6. **M13 Docs** [Haiku or Sonnet]: README, LICENSE → GPL-3.0-or-later, `docs/PRIVACY.md`, `docs/HARDWARE_TEST.md` (merge the device-verification lists from each milestone's report in PROGRESS.md: DP readback/ladder, channel mapping −30 dB on ch0, control conflict with Wavelet/Poweramp, AudioTrack continuity & head reset, `getStreamVolumeDb` per route, safe-volume/CSD, `master_mono` incl. Samsung `mono_audio`, BT address anonymisation, noise thresholds, A/B duck click-free, ClippingMediaSource loop seam, boot start on API 35+, test-retest), `docs/PLAYER_COMPAT.md` template. Also update `VECTORS.md` (some file names and counts drifted; see PROGRESS).

Manual-only acceptance items (need a phone, cannot be done in the cloud container): everything in HARDWARE_TEST, ≥2 third-party players, the Conflict state against another EQ app, test-retest ≥90% within ±10 dB, and a full flow on a minified signed APK. The listening check of the clip cut points (DECISIONS §12) is also still open.

## 4. Orchestration plan (how this was built, and how to continue)

**Roles.** One orchestrator session (Opus) never writes feature code itself. It (a) pins cross-cutting contracts and shared definitions before parallel work (e.g. `tools/golden/common.py`, the 1/24-oct grid, the 1/3-oct bands, the LTAS JSON format, `BypassController`), (b) spawns implementers, (c) spawns independent reviewers, (d) relays review findings back to the *same* implementer (it keeps its context), (e) merges, runs the module checks on the merged tree, commits and pushes.

**Per milestone loop.**
1. Implementer (Sonnet; Haiku for docs) in an **isolated git worktree** (`isolation: "worktree"`), with a self-contained prompt: spec sections to read, exact scope (directories it may touch), normative definitions, required tests and golden vectors, the verify command, and a commit message with the attribution trailers. Do not push.
2. Reviewer: **Opus for correctness-critical modules** (dsp, audiometry, effect engine/P0, service, hearing-test flow, hardening); **Sonnet** for the rest. Read-only; it must run the build, regenerate goldens and do its *own* numeric/interleaving checks; it outputs MUST-FIX / SHOULD-FIX / NIT with file:line.
3. Send the findings to the implementer via `SendMessage` (first `git merge claude/wonderful-hamilton-vnduvv` to pick up new main), then it commits the fixes.
4. Orchestrator: `git merge --no-ff worktree-agent-<id>` into the branch, resolves trivial conflicts (`docs/PROGRESS.md` appends: keep both sides), runs `./gradlew --max-workers=2 <module>:check :app:assembleDebug`, then pushes.

**Parallelism rules.** At most about 3 concurrent agents: the 4-CPU container and the account's 5-hour usage limit were both hit once. Each agent runs `./gradlew --max-workers=1`. Parallel agents get disjoint directory scopes; shared files (`docs/DECISIONS.md` sections, `docs/PROGRESS.md` appends, the version catalog) are append-only or explicitly assigned.

**Gotchas learned.**
- Worktrees may start from an **old commit**. Every prompt must start with `git merge --ff-only claude/wonderful-hamilton-vnduvv`.
- `local.properties` (`sdk.dir=/opt/android-sdk`) is gitignored, so each worktree must create it. The Android SDK was installed by hand into `/opt/android-sdk` (cmdline-tools, `platforms;android-37.1`, build-tools 37.0.0); a fresh container needs that again (see §5).
- Maven Central intermittently returns 429 behind the proxy. The plugin portal is configured as a mirror; just retry.
- Sonnet tests are often single-threaded fakes. Ask reviewers explicitly for real-thread interleavings, inherited platform state and timing (that is how the P0 hole and the writer-thread hang were found).
- Ask implementers to report spec contradictions instead of "fixing" them silently (M3 did this well).

## 5. Environment setup for a fresh container

```bash
mkdir -p /opt/android-sdk/cmdline-tools && cd /tmp
curl -sSfo clt.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
unzip -q clt.zip -d /opt/android-sdk/cmdline-tools && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-37.1" "build-tools;37.0.0" "platform-tools"
echo "sdk.dir=/opt/android-sdk" > local.properties
pip install numpy scipy          # golden oracles
./gradlew --max-workers=2 check assembleDebug
```
Golden vectors: `python3 tools/golden/gen_all.py --out core` (see `docs/VECTORS.md`). Demo assets: `tools/assets/fetch_demo_audio.sh` (needs network and ffmpeg).
