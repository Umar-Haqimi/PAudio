# PAudio — Handoff note

> Written by the orchestrating Claude session at the end of session 3 (stopped at the user's usage limit while
> M9a review round 3 was in progress; see §1.2 to resume it). Read this first, then `SPEC.md` (source of
> truth), `docs/DECISIONS.md` (decisions that override the spec where upstream code or measurement disagrees) and
> `docs/PROGRESS.md` (per-milestone details).
> Branch: `ccr-3fc57dcb-jfyrv1`. Everything listed as merged is pushed.

## 1. Where things stand

Every milestone M0–M13 has code merged. Every review finding from sessions 2 and 3 is fixed or explicitly deferred
(§1.3), **except the M9a round-3 items in §1.2**. Those are partly done as an uncommitted patch. After them, what
remains is the on-device checklist (`docs/HARDWARE_TEST.md`) and small polish.

| Milestone | State | Reviews |
|---|---|---|
| M0–M8b, M9b–M11, M13 | ✅ merged (sessions 1–2) | see `docs/PROGRESS.md` |
| M9a Hearing-test flow | ✅ merged; all session-2 findings fixed (DECISIONS §27), review round 2 fixed (§27.1) | round 1 approved (no MUST-FIX); round 2: see §1.2 |
| M12 Hardening | ✅ merged; reviewed this session: approve, no MUST-FIX. Both SHOULD-FIXes fixed (logging guard §29, trap 5 pinned §27) | ✅ |
| Session-3 polish | ✅ merged (§28): deferred volume restore, Results redo profileId, `:data` transactions, `:app` tests, Samsung mono, `gen_all.py --check` | reviewed by the orchestrator (one hearing-safety fix sent back and fixed) |
| Preference re-entry bug (user-reported) | ✅ merged (§24.1): re-entry opens on fine-tune, "Redo the A/B test", settings-only save | reviewed by the orchestrator |

### 1.1 State at handoff
- **APK:** the user was sent a debug APK built from `1810781`: everything merged, without the round-3 patch. To
  rebuild it, run `./gradlew :app:assembleDebug`; the output is `app/build/outputs/apk/debug/app-debug.apk`.
- Whole-project `./gradlew check` passes on the merged tree (`4552e35` and later); `verifyFeatureBoundaries` passes
  alone; `:app:assembleDebug` builds. The trap table (DECISIONS §26.3) reads **19 pinned, 1 device-only (#13),
  0 gaps**.
- **What changed for users this session:**
  - **Hearing test:**
    - No double sessions on a double-tapped Start, and no stuck Paused screen.
    - Haptics and announcements only in silence.
    - Answers count only from 200 ms after a tone reaches the playback head.
    - **No visual timing cue in Quick.** This was the spec owner's decision; SPEC was updated (§27.1).
    - A failed save offers Retry, and Back asks before discarding.
    - The save is one transaction.
  - **Volume:** leaving a test while the headset is unplugged never writes to the speaker. The original volume
    returns when the same headset type reconnects, and is never raised if it was lowered during the test.
  - **Preference test:** opening it again goes straight to fine-tune.

### 1.2 Review of M9a round 2
Independent review of round 2 (`641d78f..2caf5e9`, merged as `4552e35`): **request changes, no MUST-FIX**. The
P0 order was verified, and 108 + 45 tests passed on a forced rerun. Round 3 (§27.2) was sent to the implementer to
fix these items. **The implementer was stopped part-way through round 3.** Its uncommitted, untested work is saved in
`docs/wip/m9a-round3-partial.patch`, made against `1810781` (this branch's tip at the time). Unless a
"Merge M9a review round 3" commit is in `git log`, the items below are still open.

**Status of the partial patch.** It touches `TestSession.kt`, `SessionState.kt` and `SessionScreens.kt`. It is not
compiled or tested, so other call sites of the changed signals may still need updating.
- Done in code, no tests:
  - item 1, the gate-lock release: `holdsLock` checked on the catch and timeout paths;
  - item 2, publishing the idle Quick UI before every gap;
  - the channel-check wait-for-silence before the guard;
  - the new `SessionState.RetrySaving` (Back during a save retry should ask "Leave without saving?"; the screen-side
    Back handling is still to check);
  - a `stimulusSeq` counter, emitted on `SessionSignal`.
- Not started: the UI half of the late-haptic fix (drop a signal whose `stimulusSeq` is older than the session's
  current one), item 3 (TalkBack), item 4 (SPEC), `lifecycle-process`, the `ScriptedTone` tail fix, the §27.2
  DECISIONS entry, and **every test**.

To resume:
1. `git apply docs/wip/m9a-round3-partial.patch`.
2. Finish the open items, with a test for each fix (each must fail when its fix is reverted).
3. Write DECISIONS §27.2.
4. Delete `docs/wip/`.
5. Verify, then get an independent review.

Verify commands: `:feature:hearingtest:check :audio:tone:check`, `verifyNoSensitiveLogging`, the permissions guard,
`:app:assembleDebug`, then `verifyFeatureBoundaries` alone.

Open items:
1. **Gate lock leak** (`TestSession.kt` ~374). `withTimeoutOrNull { mutex.lock(owner) }`: a cancel or timeout right
   after `lock()` returns leaves the `@Singleton` mutex held, so every later test ends PREVIOUS_SESSION_BUSY until
   the process dies. A probe confirmed it. Fix: unlock if `holdsLock(owner)` on the null and catch paths.
2. **Quick onset cue after resume or Redo.** `afterPause` republishes stale progress, so the progress drops exactly at
   the next onset. A probe confirmed it. Fix: publish `quickUi(engine, shownFreq, false)` before the gap whenever it
   differs from the current state.
3. **TalkBack acknowledgement skipped on repeated identical answers.** The one-frame blank is lost in Compose's
   100 ms semantics diff window. Fix: blank for 150 ms or more, or make consecutive texts differ.
4. **SPEC lines 206 (F7), 244, 263/265 and F6** still describe the trial-locked wave, the current frequency, and
   "a response during the stimulus counts". Add the Quick exceptions and the 200 ms minimum.
5. **NITs:**
   - the channel-check guard can start writer lead + ramp early, not 25 ms;
   - the rotation mitigation almost never fires, so add `lifecycle-process`/`ProcessLifecycleOwner` (authorized);
   - a deferred haptic can mark a trial after a main-thread stall;
   - Back during `retrySave` shows "Stop the test?";
   - `ScriptedTone.dropPlaying` tail race;
   - docs only: an early tap under 200 ms leaves the trial open with no feedback, and a Bluetooth re-tap is only
     usually rejected.

### 1.3 Known gaps and deferred items (lower priority)
- **Hearing test:**
  - **Process death mid-test:** if the process dies, the calibrated volume is left as is (the original index is not
    persisted).
  - **Channel check:** checks both ears, where the spec asks for one random ear (§23).
  - **Quick duration:** simulates at 7.3–8.2 min against the spec's ≤ 6 (unreachable, §18).
  - **Rotation, then screen-off:** if the screen turns off before the recreated activity composes, a window of a few
    hundred ms remains. Closing it fully needs `androidx.lifecycle:lifecycle-process` (`ProcessLifecycleOwner`)
    added to the version catalog (§27.1).
  - **Thorough pulse period:** about 425 ms plus the writer lead, not sample-accurate (optional; §27.1).
  - **Live headset check:** it reads the device StateFlow, so a late `AudioDeviceCallback` can still race it.
  - **First-test calibration cache:** the first test's calibration is cached with listener = null, so a retest
    within 30 min recalibrates (harmless).
- **Volume restore (§28.1):** the deferred restore is in memory only. It identifies a device by its type, so two
  USB headsets look the same.
- **Preference:**
  - The Home and Profile entry labels still read "Preference test" after one exists ("Fine-tune your sound" would
    need per-profile state plumbed into `:feature:home` and `:feature:profiles`).
  - The M11 Compose test does not run the real ViewModel.
- **Guards:**
  - `verifyNoSensitiveLogging` scans `src/main` only (not `src/debug`, `src/release` or tests).
  - `verifyFeatureBoundaries` matches text only: a wildcard import plus a module-wide `-opt-in` would get past it
    (trap 19).
- **R8:** `proguard-rules.pro` keeps more than needed. It disables enum unboxing for all app enums and has broad
  Hilt keeps. This is not a correctness issue; only trim it with a device test of a minified build.
- **M7a:** `acquire()` waits up to 250 ms (`ACQUIRE_WAIT_MS`) for an in-flight create.
- **Verified this session, no action needed:** `LICENSE` is byte-identical to gnu.org's GPL-3.0 text; `<queries>`
  holds only MAIN/LAUNCHER (for app labels in the notification) and there is no `QUERY_ALL_PACKAGES`.

## 2. Key decisions a successor must know (details in DECISIONS.md)

- **DynamicsProcessing EQ is a brick-wall FFT staircase**:
  - `VARIANT_FAVOR_FREQUENCY_RESOLUTION`, 16 ms frame, **87 bin-aligned bands** (`DpBandLayout.of`).
  - Fallback ladder MAIN → FB51 → SPEC40.
  - Always plan through `core:dsp` `DpEqPlanner`; never hand-build cutoffs. Channel 0 = left. Priority 100.
- **A new DP controller inherits the previous controller's enabled flag.** AOSP passes it on at creation, at control
  regain and when the controlling handle is released. So the engine:
  - creates disabled, and enables only under `portLock` at install;
  - never creates while a bypass token is held;
  - calls `disableAndRelease` before every release (§19).
- **P0 bypass contract**: `BypassController`/`BypassToken` (ref-counted).
  - **Hearing test:** one `HEARING_TEST` token from before `ToneOutput.open` until after cleanup.
    - Cleanup order: tone close → volume restore → focus abandon → token close (§23).
    - A process-wide `SessionGate` (5 s timeout) means a new session waits for the old one's cleanup (§27).
    - Late resumes are refused once `resumeClosed` is set (§27.1).
  - **Results/Preference:** the A/B audio exists only while the screen is STARTED (`LifecycleStartEffect`). The VM
    takes its token before creating the player and releases it on every exit, including a load failure (§21, §24).
- **Stimulus timing (trap 20):**
  - **Pulse/gap buffers:** the Thorough loop plays each pulse and its gap as separate buffers, and
    `StreamEngine.playing` excludes explicit silence.
  - **Signals:** the session exposes `pulseAudible`/`isStimulusAudibleNow()`, and haptics and announcements fire
    only in silence.
  - **Answer arming:** answers are armed 200 ms after the stimulus reaches the playback head (§27, §27.1).
- **Quick test shows no timing cue** (the hear button and wave are steady); calibration and Thorough pulse (§27.1).
- **Quick-test targets in SPEC §7.2/§9 are unreachable**; targets relaxed in §18. UI copy "Quick ≈ 6–8 min".
- **WHO grade almost always reads "Normal"** under the model (§17).
  - Copy: use "estimated/approximate/may/consider".
  - Never say "diagnose", "detected hearing loss" or "your hearing is damaged".
  - Censored points read "beyond test range".
- **Loudness matching** (`AbGainPlanner`):
  - shared preamp `P_common = max(-15, -max(0,maxG) - 0.5)`;
  - per option `inputGain = P_common − ΔL(G)`;
  - the real mixer rate (`outputSampleRate()`).
- **Transactions**: `:data` exposes `DataTransactions.withTransaction { }` (§28.3). The hearing-test save and the
  preference save each use one transaction inside `NonCancellable`.
- **Volume lock** (§28.1): if the output device at release differs from the start device, nothing is written. The
  restore is deferred until the start device returns, and is skipped if the index was lowered externally.
- **Preference re-entry** (§24.1): a profile with a saved preference test opens on FINE_TUNE. A fine-tune-only save
  writes settings only (`saveSettings`), never a `PreferenceTest` row.
- **Routes**:
  - `HearingTestRoute(profileId: Long? = null)` (null = profile created at save time);
  - `ResultsRoute(testId)`, whose "Test again" passes the test's profileId;
  - `PreferenceRoute(profileId: Long? = null)` (null = active profile);
  - `HealthRoute(profileId?, testId?)`, `ProfilesRoute(createForCurrent)`, `ProfileDetailRoute(id)`.
- **Gradle guards**, all wired into root `check`:
  - `verifyPermissions`: a deny and allow list on the merged release manifest.
  - `verifyFeatureBoundaries`: Expressive/MaterialShapes APIs only in `:designsystem`. **Run it alone.**
  - `verifyPureKotlin`: for `:core:*`.
  - `verifyNoSensitiveLogging` (§29): an **allow list**. Log/print arguments after the tag may only be:
    - string literals whose templates are limited to `javaClass.simpleName`, `::class.simpleName` or
      `<chain>.UPPER_CASE.name`;
    - numeric, char or boolean literals.

    Anything else, including any throwable and `printStackTrace`, fails. There is no suppression. The scanner lives
    in `build-logic/convention/src/main/kotlin/LoggingScanner.kt` with 82 unit tests, run in CI by
    `./gradlew -p build-logic test`. It runs only in the root `check`, so run it after module checks.
- **Release build** (M12):
  - `:app:assembleRelease` runs R8 with resource shrinking; the keep rules are in `app/proguard-rules.pro`.
  - The session-3 review checked the minified dex: all routes, serializers, `PAudioDatabase_Impl` and the Hilt VM
    keys survive.
  - The APK is unsigned unless `-Ppaudio.signReleaseWithDebugKey=true` or `PAUDIO_SIGN_RELEASE_WITH_DEBUG_KEY=1` is
    set.
- **Golden vectors**: `python3 tools/golden/gen_all.py --check` regenerates into a temp dir and diffs the committed
  vectors with float tolerance; it exits 1 on any mismatch (`docs/VECTORS.md`).
- **License**: GPL-3.0-or-later (SPEC §10). The NIN demo clips are CC BY-NC-SA 3.0 US, so never monetize while they
  ship.

## 3. Remaining work (in order)

1. **Finish M9a review round 3** (§1.2): apply `docs/wip/m9a-round3-partial.patch`, complete it with tests, get it
   independently reviewed (an Opus reviewer is appropriate: P0/concurrency), then merge.
2. Run the whole `docs/HARDWARE_TEST.md` checklist on a phone and fill in `docs/PLAYER_COMPAT.md`. New items this
   session cover Bluetooth haptic latency, the Quick no-cue screen, re-tap, abort while unplugged, Samsung mono and
   preference re-entry.
3. Optional polish from §1.3:
   - the `lifecycle-process` dependency for the rotation gap;
   - the "Fine-tune your sound" entry label;
   - a sample-accurate Thorough period.

## 4. Orchestration recipe (how this was built)

- **Roles.** One orchestrator session works like this:
  1. Pins shared contracts.
  2. Spawns implementers in isolated git worktrees: Sonnet for contained work; Opus for P0/concurrency-critical
     work such as the hearing-test session.
  3. Spawns independent read-only reviewers: **Opus** for correctness-critical work (dsp, audiometry, effect/P0,
     service, hearing-test flow, hardening); otherwise the orchestrator reviews the diff itself.
  4. Relays findings to the *same* implementer via `SendMessage`, so its context survives.
  5. Merges with `git merge --no-ff worktree-agent-<id>`, runs the checks on the merged tree, and pushes.
- **Prompts** are self-contained:
  - spec sections, exact directory scope, the verify command;
  - a reserved DECISIONS section number;
  - the commit trailers;
  - "report spec contradictions, don't silently fix them".

  Ask reviewers explicitly for real-thread interleavings, inherited platform state, token leaks on error paths, and
  "would this test fail if the fix were reverted?" (have them probe it). Every P0 bug and every vacuous test so far
  was found that way.
- **Session-3 lessons:**
  - **Worktree base:** an agent worktree can start at a very old commit. Tell every agent to
    `git reset --hard <branch>` first and to verify its base.
  - **SDK:** AGP may auto-install `build-tools;36.0.0` into the shared SDK. A half-extracted copy breaks every
    build; check its file count before you blame code.
  - **Memory:** the container OOM-killed a Gradle process with three builds running. Keep `--max-workers=1` per
    agent, at most about 3 concurrent agents, and never two builds in one worktree.
  - **Worker restart:** a worker restart kills running agents. Their transcripts survive, so resume each with
    `SendMessage` and tell it to inspect its uncommitted diff first.
  - **Cached checks:** a merged tree whose `check` comes back in under a minute is Gradle's build cache reusing
    worktree results with identical inputs. That is valid, but force `--rerun` when you need real test counts.
- **Parallelism.** Parallel agents get disjoint directories. `docs/PROGRESS.md` and `DECISIONS.md` are append-only,
  so merge conflicts there are "keep both sides"; check the section numbering afterwards.

## 5. Environment setup for a fresh container

```bash
mkdir -p /opt/android-sdk/cmdline-tools && cd /tmp
curl -sSfo clt.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
unzip -q clt.zip -d /opt/android-sdk/cmdline-tools && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses   # sdkmanager now delegates to the new Android CLI; still works
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-37.1" "build-tools;37.0.0" "platform-tools"
echo "sdk.dir=/opt/android-sdk" > local.properties   # also in every worktree
pip install numpy scipy                               # golden oracles
./gradlew --max-workers=2 check assembleDebug
./gradlew --max-workers=2 verifyFeatureBoundaries     # alone
./gradlew -p build-logic test                         # logging-scanner tests
python3 tools/golden/gen_all.py --check               # golden vectors match
```
Maven Central intermittently returns 429 behind the proxy: retry.
