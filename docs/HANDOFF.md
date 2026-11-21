# PAudio — Handoff note

> Written by the orchestrating Claude session when usage ran low (second session). Read this first, then
> `SPEC.md` (source of truth), `docs/DECISIONS.md` (decisions that override the spec where upstream code or
> measurement disagrees) and `docs/PROGRESS.md` (per-milestone details).
> Branch: `ccr-87631b24-yga4qb`. Everything listed as merged is pushed.

## 1. Where things stand

Every milestone M0–M13 now has code merged. What remains is fixing review findings (§1.2), checks that need a
real phone (`docs/HARDWARE_TEST.md`), and polish.

| Milestone | Module(s) | State | Implemented by → reviewed by |
|---|---|---|---|
| M0 Research & decisions | `docs/DECISIONS.md`, `docs/VECTORS.md` | ✅ merged | Sonnet → orchestrator |
| M1 Skeleton | build-logic, all modules, CI, `verifyPermissions`, core purity guard | ✅ merged (+ fixes) | Sonnet → Opus |
| M2 DSP | `:core:dsp` + golden sets 1–4, 5c, 6 | ✅ merged (+ fixes) | Sonnet → Opus |
| M3 Audiometry | `:core:audiometry` + golden sets 5a, 7, 8 | ✅ merged (+ fixes) | Sonnet → Opus |
| M4 Preference + demo assets | `:core:preference`, golden set 9, NIN clips | ✅ merged | Sonnet → Sonnet |
| M5 Data | `:data` | ✅ merged (+ fixes) | Sonnet → Sonnet |
| M6 Tone + device | `:audio:tone`, `:audio:device` | ✅ merged (+ fixes) | Sonnet → Sonnet |
| M7a Effect engine | `:audio:effect` | ✅ merged; P0 races fixed and **Opus re-verified** (DECISIONS §19) | Sonnet → Opus ×2 |
| M7b A/B player | `:audio:playback` | ✅ merged (+ fixes) | Sonnet → Sonnet |
| M7c Service + use cases | `:service` | ✅ merged (+ Opus review fixes, §20/§20.1) | Sonnet → Opus |
| M8a Design system | `:designsystem` | ✅ merged (+ fixes) | Sonnet → Sonnet |
| M8b Onboarding/Home/Profiles/Settings | `:feature:onboarding/home/profiles/settings`, `:app` | ✅ merged (+ review fixes) | Sonnet (finished by orchestrator) → Sonnet |
| M9a Hearing-test flow | `:feature:hearingtest` | ✅ merged; **Opus review findings NOT yet fixed — see §1.2** | Sonnet → Opus |
| M9b Results | `:feature:results` | ✅ merged (+ review fixes) | Sonnet → Sonnet |
| M10 Hearing health | `:feature:health` | ✅ merged (+ review fixes) | Sonnet → Sonnet |
| M11 Preference test + fine-tune | `:feature:preference` | ✅ merged (+ all 9 review fixes, §24) | Sonnet → Sonnet |
| M12 Hardening | all | ✅ merged (DECISIONS §26); **no Opus review yet** | Sonnet → (none) |
| M13 Docs | README, LICENSE (GPL-3.0), PRIVACY, HARDWARE_TEST, PLAYER_COMPAT, VECTORS | ✅ merged | Sonnet → orchestrator |

### 1.1 State at handoff
No agents are running. The whole-project `./gradlew check assembleDebug` passed on M12's branch and the debug APK builds (`./gradlew :app:assembleDebug`); the module checks of
every merged module passed on the merged tree; `verifyFeatureBoundaries` (run alone) passes.

**The current debug APK** has the full flow: onboarding → Home (status chip, Personalized/Original, strength) →
hearing test (pre-test checks, channel check, calibration, Quick/Thorough, pause/resume) → Results (butterfly,
loudness-matched A/B, EQ graph) → preference test (bisection + fine-tune) → Profiles/Settings/About/Health, with the
foreground service applying the system-wide DynamicsProcessing EQ.

### 1.2 Open review findings (fix these first)
Opus reviewed M9a at f4cd05c (verdict: request changes). These findings are **not fixed yet**; the user asked to
stop spawning agents. All paths are under `feature/hearingtest/src/main/kotlin/.../hearingtest/` unless noted. Each was
confirmed by the reviewer with throwaway probe tests (not committed).

**MUST-FIX**
1. **Resume during the channel check (or calibration) leaves the Paused screen up while tones play.**
   - **Where:** `TestSession.kt` ~512 and ~419-442.
   - **Cause:** `channelCheck` publishes `ChannelCheck` before `pausable{}`. `awaitNotPaused` publishes `Paused`, but
     when the reasons clear nothing republishes the previous state. The user is stuck with no Left/Right buttons and
     the only way out is Exit.
   - **Guaranteed trigger:** focus refused at start (a call is active). Also likely after volume-up on a quiet beep.
   - **Quick is affected too:** `Paused` stays up through the 400-1200 ms gap and swaps exactly at stimulus onset
     (trap #20).
   - **Fix:** remember the last non-Paused state in `awaitNotPaused` and republish it (Quick with `trialOpen=false`).
   - **Tests:** add pause tests in ChannelCheck and Calibrating.
2. **P0 leak: a double tap on "Start the test" starts two sessions.**
   - **Where:** `HearingTestViewModel.kt` ~249-276.
   - **Cause:** the `session != null` guard runs before a `launch` that suspends in `env.snapshot()`/`store.check`. The
     orphan session holds a bypass token forever and shares the singleton tone output, volume controller and focus
     monitor.
   - **Fix:** set a synchronous `starting`/`startJob` flag, disable Start while it is set, and add a process-wide
     session mutex so a new session waits for the old one's `finally`.
   - **Tests:** a VM test that calls `startTest()` twice.
3. **A `resume()` that runs after cleanup re-raises the volume and keeps exclusive audio focus.**
   - **Where:** `TestSession.kt` ~239-241 and ~397-417.
   - **Cause:** `resumeNow` has no liveness check, and queued executor tasks still run after shutdown.
   - **Trigger:** Exit then Continue within the up-to-500 ms writer join, or Continue racing the pause timeout.
   - **Fix:** set a `cleanupStarted` flag at the top of `run()`'s finally; `resumeNow` returns if it is set or
     `job?.isActive != true`.
4. **(Likely MUST-FIX, trap #20) The Thorough dial's deferred haptic tick lands at the next pulse onset, not in the
   gap.**
   - **Where:** `LevelDial.kt` ~53-64; `audio/tone/.../StreamEngine.kt` `poll` ~141-154;
     `StimulusSynth.loopingPulsedTone` ~98-104.
   - **Cause:** `playing` stays true through each 425 ms period (225 ms pulse + 200 ms gap) and drops only for the
     writer's lead time just before the next pulse.
   - **Fix:** the session exposes its own `pulseAudible` flow, with pulse and gap played as separate buffers, and the
     tick fires when the flag drops.
   - **On Bluetooth:** head position ignores sink latency (consider `AudioTrack.getTimestamp`; HARDWARE_TEST item).

**SHOULD-FIX**
1. **Calibration double tap** after a Rerun registers at about -90 dBFS and lowers the volume in the wrong
   direction. Fix: ignore taps in roughly the first 1 s of each ramp (`TestSession.kt` ~576-646).
2. **Channel-check double taps:** answers are accepted before the step's first beep (false WRONG_EAR, +10 dB jumps).
   Fix: accept answers only after the first beep (`TestSession.kt` ~510-556).
3. **An answer can be recorded before the volume/headset event arrives.** Fix: in `quickStep` (~725-748) check the
   live volume index and the headset synchronously before `engine.submit`.
4. **Abort while unplugged restores the volume on the wrong device** (`audio/tone/VolumeLogic.kt` ~100-113). Fix:
   defer the restore until the start device reconnects, and never write to the speaker.
5. **Calibration cache key lacks the listener** (`Ports.kt` ~94-107). Retesting another listener within 30 min
   reuses `L_ref`. Fix: add the listener id to the key.
6. **The end-of-test "unreliable" prompt counts toward the 5-min pause timeout** and can discard a finished test.
   Fix: on timeout save with `reliable=false`, or release the audio and token while the prompt shows.
7. **Results "Test again" drops the profileId** (`app/.../navigation/PAudioNavHost.kt` ~71). Fix: pass the test's
   profileId to `HearingTestRoute(profileId)`.
8. **Quick buttons toggle `enabled` at stimulus onset** (`SessionScreens.kt` ~194/200), so TalkBack may announce the
   change. Fix: keep the buttons enabled; the session already ignores taps between trials.

**NIT**
- **Quick trial cancel:** call `stopCurrent()` unconditionally in `presentQuickTrial`.
- **Stream failures:** count consecutive ones, not session-wide.
- **Rotation:** ON_STOP on rotation pauses the test (`HearingTestScreen.kt` ~133).
- **`RepositoryTestStore` (~50):**
  - a deleted profile silently saves to the active listener;
  - the listener, headset and profile inserts and the test save are not in one transaction.
- **Save failure:** keep the `SaveRequest` and offer a retry.
- **`LevelDial` drag:** it can lose detents when events batch.
- **Stale references:** "DECISIONS §20" should read §23 in four files.
- **`CalibrationSession(minIndex = 0)`:** it ignores the real stream minimum.

**Test-quality notes:**
- `resumeRestartsOnlyTheInterruptedPoint` asserts little: its main check is skipped when the other ear was done.
- `ScriptedTone.playing` differs from `StreamEngine`: `playSilence` never sets it.

**Checked OK by the reviewer:**
- P0 order: token before `tone.open`; cleanup tone → volume → focus → token; terminal state after cleanup.
- Every exit path goes through the `finally`.
- CancellationException is rethrown before every broad catch.
- Responses are attributed per trial.
- Procedure levels, the guessing prompt and the saved fields match §23.
- The `HearingTestRoute`/`ResultsRoute` hand-off is correct.
- Copy rules are kept.

### 1.3 Known gaps and nits (lower priority)
- **M9a:** if the process dies mid-test, the calibrated volume is left as is, because the original index is not
  persisted. The spec asks for one random ear in the channel check; both ears are checked instead (§23). Quick
  simulates at 7.3–8.2 min against the spec's ≤ 6 (unreachable, §18).
- **M11:** `AndroidPreferenceData.save` writes a `PreferenceTest` and the profile settings as two writes (no `:data`
  transaction API), guarded only by `NonCancellable`. The Compose test does not run the real ViewModel.
- **M8b/M9b:** `:app` has no test source set (the `AppViewModel` seed-sync is untested). The `TopAppBar`
  `ExperimentalMaterial3Api` opt-in in feature modules is deliberate (stable M3; health does the same).
- **M7a:** `acquire()` waits up to 250 ms (`ACQUIRE_WAIT_MS`) for an in-flight create; `<queries>` and the global
  headset map need a privacy and real-device check.
- **M13:** `LICENSE` was copied from the container's `/usr/share/common-licenses/GPL-3` because gnu.org was
  unreachable; diff it once against https://www.gnu.org/licenses/gpl-3.0.txt. Samsung `mono_audio` is not read by
  `AndroidEnvironmentChecks`. `gen_all.py --check` is not implemented (VECTORS.md).
- **Manual-only acceptance items:** everything in `docs/HARDWARE_TEST.md` (USB-DAC mixer rate, BT, boot start on
  API 35+, Conflict state against another EQ app, ≥ 2 third-party players → `docs/PLAYER_COMPAT.md`, test-retest
  ≥ 90 % within ±10 dB, A/B duck click-free, loop seams, loudness match by ear, TalkBack and haptics vs stimuli,
  a full flow on a minified signed APK). The listening check of the clip cut points (DECISIONS §12) is still open.

## 2. Key decisions a successor must know (details in DECISIONS.md)

- **DynamicsProcessing EQ is a brick-wall FFT staircase**: `VARIANT_FAVOR_FREQUENCY_RESOLUTION`, 16 ms frame,
  **87 bin-aligned bands** (`DpBandLayout.of`), fallback ladder MAIN → FB51 → SPEC40. Always plan through
  `core:dsp` `DpEqPlanner`; never hand-build cutoffs. Channel 0 = left. Priority 100.
- **A new DP controller inherits the previous controller's enabled flag** (AOSP passes it on at creation, at
  control regain and when the controlling handle is released). The engine creates disabled, enables only under
  `portLock` at install, never creates while a bypass token is held, and calls `disableAndRelease` before every
  release (§19).
- **P0 bypass contract**: `BypassController`/`BypassToken` (ref-counted). Hearing test: one `HEARING_TEST` token
  from before `ToneOutput.open` until after cleanup; cleanup order tone close → volume restore → focus abandon →
  token close (§23). Results/Preference: the A/B audio exists only while the screen is STARTED
  (`LifecycleStartEffect`); the VM takes its token before creating the player and releases it on every exit,
  including a load failure (§21, §24).
- **Engine API**: setters and `retry()` are asynchronous on a single-thread engine scope; `acquire()` is synchronous.
  Features talk to `EngineController` (in `:service`) through small feature-local interfaces (`HomeEngine`,
  `SettingsEngine`) bound with Hilt `@Binds` (§22).
- **Quick-test targets in SPEC §7.2/§9 are unreachable**; targets relaxed in §18. UI copy "Quick ≈ 6–8 min".
- **WHO grade almost always reads "Normal"** under the model (§17). Copy: "estimated/approximate/may/consider"; never
  "diagnose", "detected hearing loss", "your hearing is damaged"; censored points read "beyond test range".
- **Loudness matching** (`AbGainPlanner`): shared preamp `P_common = max(-15, -max(0,maxG) - 0.5)`, per option
  `inputGain = P_common − ΔL(G)`, real mixer rate (`outputSampleRate()`).
- **Routes**: `HearingTestRoute(profileId: Long? = null)` (null = profile created at save time),
  `ResultsRoute(testId)`, `PreferenceRoute(profileId: Long? = null)` (null = active profile),
  `HealthRoute(profileId?, testId?)`, `ProfilesRoute(createForCurrent)`, `ProfileDetailRoute(id)`.
- **Gradle guards**: `verifyPermissions` (deny + allow list on the merged release manifest),
  `verifyFeatureBoundaries` (Expressive/MaterialShapes APIs only in `:designsystem`; **run it alone**),
  `verifyPureKotlin` for `:core:*`, and (M12) `verifyNoSensitiveLogging`, wired into `check`.
  - `verifyNoSensitiveLogging` scans `src/main` for log/print calls that carry hearing data, names, addresses or
    throwable messages.
- **Release build** (M12):
  - `:app:assembleRelease` runs R8 with resource shrinking; the keep rules are in `app/proguard-rules.pro`
    (serialization, Room, Hilt).
  - The APK is unsigned unless `-Ppaudio.signReleaseWithDebugKey=true` or `PAUDIO_SIGN_RELEASE_WITH_DEBUG_KEY=1` is
    set.
- **License**: GPL-3.0-or-later (SPEC §10); NIN demo clips are CC BY-NC-SA 3.0 US → never monetize while they ship.

## 3. Remaining work (in order)

1. Fix the M9a Opus findings in §1.2 (MUST-FIX first), then re-run `:feature:hearingtest:check`.
   Then give M12 its Opus review: trap table (DECISIONS §26.3, 18 pinned / 1 device-only (13) / 1 gap (20)), keep
   rules, the logging guard.
   - **Trap 20 gap:** add a behavioural test in `:feature:hearingtest` that haptics and announcements fire only
     after the stimulus is silent. Do it together with M9a MUST-FIX 4.
   - **Also:** the preference top-bar back button is 40 dp (`PreferenceScreen.kt` ~165); make it 48 dp, as
     Results/Health now are.
   - **Also:** `:feature:hearingtest` has no a11y Compose test yet (pattern: `A11yAudit` in the other features).
2. Run the whole `docs/HARDWARE_TEST.md` checklist on a phone; fill `docs/PLAYER_COMPAT.md`.
3. Diff `LICENSE` against the canonical GPL-3.0 text.
4. Clear the nits in §1.3 as time allows.

## 4. Orchestration recipe (how this was built)

- **Roles.** One orchestrator session pins shared contracts, spawns implementers (Sonnet; Haiku/Sonnet for docs) in
  isolated git worktrees, spawns independent read-only reviewers (**Opus** for correctness-critical modules: dsp,
  audiometry, effect/P0, service, hearing-test flow, hardening; **Sonnet** otherwise), relays findings to the
  *same* implementer via `SendMessage`, then merges (`git merge --no-ff worktree-agent-<id>`), runs the module
  checks on the merged tree and pushes.
- **Prompts** are self-contained: spec sections, exact directory scope, verify command, DECISIONS section number
  to use (reserve numbers up front to avoid clashes), commit trailers, "report spec contradictions, don't silently
  fix them". Ask reviewers explicitly for real-thread interleavings, inherited platform state and token leaks on
  error paths: every P0 bug so far was found that way.
- **Parallelism.** At most ~3 concurrent agents (4-CPU container, 5-hour usage window — hit twice). Each agent uses
  `--max-workers=1`. Parallel agents get disjoint directories; `docs/PROGRESS.md`/`DECISIONS.md` are append-only, so
  merge conflicts there are "keep both sides" (check the section numbering afterwards).
- **When an agent hits the usage limit**, snapshot its worktree as a WIP commit and resume it later with
  `SendMessage` (its context survives).

## 5. Environment setup for a fresh container

```bash
mkdir -p /opt/android-sdk/cmdline-tools && cd /tmp
curl -sSfo clt.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
unzip -q clt.zip -d /opt/android-sdk/cmdline-tools && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-37.1" "build-tools;37.0.0" "platform-tools"
echo "sdk.dir=/opt/android-sdk" > local.properties   # also in every worktree
pip install numpy scipy                               # golden oracles
./gradlew --max-workers=2 check assembleDebug
./gradlew --max-workers=2 verifyFeatureBoundaries     # alone
```
Maven Central intermittently returns 429 behind the proxy: retry. Golden vectors:
`python3 tools/golden/gen_all.py --out core` (see `docs/VECTORS.md`).
