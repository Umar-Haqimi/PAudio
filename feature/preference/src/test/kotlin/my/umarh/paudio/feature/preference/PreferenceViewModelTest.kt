package my.umarh.paudio.feature.preference

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.umarh.paudio.audio.playback.AbCurve
import my.umarh.paudio.audio.playback.AbGainPlanner
import my.umarh.paudio.audio.playback.ListenTick
import my.umarh.paudio.core.dsp.CurveBuilder
import my.umarh.paudio.core.dsp.LoudnessPredictor
import my.umarh.paudio.core.dsp.ThirdOctaveBands
import my.umarh.paudio.core.model.AudioSource
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.preference.AbSide
import my.umarh.paudio.core.preference.DemoClip
import my.umarh.paudio.core.preference.PreferenceBisection
import my.umarh.paudio.data.repo.ProfileSettings
import my.umarh.paudio.testing.FakeAbPlayer
import my.umarh.paudio.testing.FakeBypassController
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PreferenceViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private companion object {
        const val FS = 44_100
        const val SEED = 11
    }

    // ---- fakes ---------------------------------------------------------------------------------------------------

    private class FakeData(val baseline: PreferenceBaseline?, val seed: Int) : PreferenceData {
        class Saved(val profileId: Long, val startedAt: Long, val test: Triple<Float, Float, Float>, val rounds: String, val settings: ProfileSettings)

        val saves = mutableListOf<Saved>()
        val settingsSaves = mutableListOf<Pair<Long, ProfileSettings>>()
        var failLoad = false
        var requested: Long? = -1
        var failSave = false

        override suspend fun load(profileId: Long?): PreferenceBaseline? {
            requested = profileId
            if (failLoad) error("db gone")
            return baseline
        }

        override suspend fun save(profileId: Long, startedAt: Long, test: Triple<Float, Float, Float>, roundsJson: String, settings: ProfileSettings) {
            if (failSave) error("disk full")
            saves += Saved(profileId, startedAt, test, roundsJson, settings)
        }

        override suspend fun saveSettings(profileId: Long, settings: ProfileSettings) {
            if (failSave) error("disk full")
            settingsSaves += profileId to settings
        }

        override fun nowMs() = 1_700_000_000_000L

        override fun random(): Random = Random(seed)
    }

    private class FakeBackend(val bypass: FakeBypassController) : PreferenceAudioBackend {
        val players = mutableListOf<FakeAbPlayer>()
        val ticks = MutableSharedFlow<ListenTick>(extraBufferCapacity = 64)
        var bypassHeldAtCreate = false
        val player: FakeAbPlayer get() = players.last()

        /** Treble-heavy: differs clearly from pink so the DeltaL terms are non-trivial and per-curve. */
        val predictor = LoudnessPredictor(DoubleArray(ThirdOctaveBands.COUNT) { if (it < 14) 0.02 else 1.0 })

        var failCreate = false
        var failLoad = false
        var loadDelayMs = 0L
        var predictorDelayMs = 0L

        override fun createPlayer(): PrefAudio {
            bypassHeldAtCreate = bypass.openCount >= 1
            if (failCreate) error("no player")
            val inner = FakeAbPlayer().also { players += it }
            val p = object : my.umarh.paudio.core.model.AbPlayer by inner {
                override suspend fun load(src: AudioSource, loop: ClosedRange<kotlin.time.Duration>?) {
                    if (loadDelayMs > 0) kotlinx.coroutines.delay(loadDelayMs)
                    if (failLoad) error("bad file")
                    inner.load(src, loop)
                }
            }
            return PrefAudio(p, playing = null, ticks = ticks)
        }

        override fun outputSampleRate() = FS

        override suspend fun predictor(clip: DemoClip): LoudnessPredictor {
            if (predictorDelayMs > 0) kotlinx.coroutines.delay(predictorDelayMs)
            return predictor
        }
    }

    private class Rig(val vm: PreferenceViewModel, val backend: FakeBackend, val bypass: FakeBypassController, val data: FakeData) {
        val ui get() = vm.ui.value
    }

    private fun baseline(hasPrefTest: Boolean = false) = PreferenceBaseline(
        profileId = 3,
        hlLeft = DoubleArray(9) { it * 5.0 },
        hlRight = DoubleArray(9) { it * 3.0 },
        hasTest = true,
        strength = 0.6,
        gB = 1.0,
        gP = 1.0,
        gT = 1.0,
        personalizationOn = false,
        hasPreferenceTest = hasPrefTest,
    )

    private fun rig(b: PreferenceBaseline? = baseline(), seed: Int = SEED): Rig {
        val bypass = FakeBypassController()
        val backend = FakeBackend(bypass)
        val data = FakeData(b, seed)
        val vm = PreferenceViewModel(SavedStateHandle(mapOf("profileId" to 3L)), data, backend, bypass)
        return Rig(vm, backend, bypass, data)
    }

    private fun TestScope.settle() {
        repeat(3) {
            advanceUntilIdle()
            runCurrent()
        }
    }

    private fun TestScope.openGate(r: Rig, aMs: Long = 2000, bMs: Long = 2000) {
        r.backend.ticks.tryEmit(ListenTick(AbSide.A, aMs))
        r.backend.ticks.tryEmit(ListenTick(AbSide.B, bMs))
        settle()
    }

    /** Starts the screen (audio wanted) and the test, returns once the first round is loaded. */
    private fun TestScope.started(r: Rig = rig()): Rig {
        settle()
        r.vm.startAudio()
        r.vm.begin()
        settle()
        return r
    }

    /** "+" = pick the side playing the larger gain, "-" = the smaller, "?" = can't tell; mirrors the VM's rounds. */
    private fun TestScope.play(r: Rig, answers: String, ref: PreferenceBisection = PreferenceBisection(Random(SEED))) {
        for (ch in answers) {
            val round = checkNotNull(ref.currentRound)
            assertThat(r.ui.phase).isEqualTo(PrefPhase.ROUND)
            assertThat(r.ui.axis).isEqualTo(round.axis)
            when (ch) {
                '?' -> {
                    r.vm.cantTell()
                    ref.cantTell()
                }
                else -> {
                    val side = if ((ch == '+') == (round.plusSide == AbSide.A)) AbSide.A else AbSide.B
                    openGate(r)
                    r.vm.choose(side)
                    ref.choose(side)
                }
            }
            settle()
        }
    }

    // ---- bisection ----------------------------------------------------------------------------------------------

    @Test
    fun allPlus_reachesTheBisectionResult() = runTest(dispatcher) {
        val r = started()
        play(r, "+++++++++")
        assertThat(r.ui.phase).isEqualTo(PrefPhase.RESULT)
        // Same values the golden set 9 / core bisection give for nine "+" answers (clamped at 6 and 8).
        assertThat(r.ui.gB).isEqualTo(10.5f)
        assertThat(r.ui.gP).isEqualTo(6f)
        assertThat(r.ui.gT).isEqualTo(8f)
        val ref = PreferenceBisection(Random(SEED))
        while (!ref.isFinished) ref.choose(ref.currentRound!!.plusSide)
        assertThat(r.ui.gB.toDouble()).isEqualTo(ref.outcome.gB)
        assertThat(r.ui.gP.toDouble()).isEqualTo(ref.outcome.gP)
        assertThat(r.ui.gT.toDouble()).isEqualTo(ref.outcome.gT)
        assertThat(r.ui.gLeft).isNotNull()
    }

    @Test
    fun mixedAnswers_matchTheBisectionResult() = runTest(dispatcher) {
        val r = started()
        val ref = PreferenceBisection(Random(SEED))
        play(r, "+-+--+-++", ref)
        assertThat(ref.isFinished).isTrue()
        assertThat(r.ui.phase).isEqualTo(PrefPhase.RESULT)
        assertThat(r.ui.gB.toDouble()).isEqualTo(ref.outcome.gB)
        assertThat(r.ui.gP.toDouble()).isEqualTo(ref.outcome.gP)
        assertThat(r.ui.gT.toDouble()).isEqualTo(ref.outcome.gT)
    }

    @Test
    fun cantTell_endsThatAxis_andTheTestHasFewerRounds() = runTest(dispatcher) {
        val r = started()
        val ref = PreferenceBisection(Random(SEED))
        play(r, "+?++?+", ref) // B+ P? T+ B+ T? B+  (presence is skipped afterwards)
        assertThat(ref.isFinished).isTrue()
        assertThat(r.ui.phase).isEqualTo(PrefPhase.RESULT)
        assertThat(r.ui.gP).isEqualTo(0f)
        assertThat(r.ui.gB).isEqualTo(10.5f)
        assertThat(r.ui.gT).isEqualTo(5f)
    }

    // ---- gate ----------------------------------------------------------------------------------------------------

    @Test
    fun choose_requiresTwoSecondsOnEachSide() = runTest(dispatcher) {
        val r = started()
        assertThat(r.ui.canChoose).isFalse()
        r.vm.choose(AbSide.A) // ignored
        assertThat(r.ui.roundNumber).isEqualTo(1)

        r.backend.ticks.tryEmit(ListenTick(AbSide.A, 30_000)) // only A, however long
        settle()
        assertThat(r.ui.aHeard).isTrue()
        assertThat(r.ui.bHeard).isFalse()
        assertThat(r.ui.canChoose).isFalse()
        r.vm.choose(AbSide.B)
        assertThat(r.ui.roundNumber).isEqualTo(1)

        r.backend.ticks.tryEmit(ListenTick(AbSide.B, 1_999))
        settle()
        assertThat(r.ui.canChoose).isFalse()
        r.backend.ticks.tryEmit(ListenTick(AbSide.B, 1))
        settle()
        assertThat(r.ui.canChoose).isTrue()
        r.vm.choose(AbSide.B)
        settle()
        assertThat(r.ui.roundNumber).isEqualTo(2)
        assertThat(r.ui.canChoose).isFalse() // the gate resets with every round
    }

    // ---- try another song ----------------------------------------------------------------------------------------

    @Test
    fun tryAnotherSong_cyclesClips_keepsTheRound_andReloadsWithTheTwelveSecondLoop() = runTest(dispatcher) {
        val r = started()
        assertThat(r.ui.clip).isEqualTo(DemoClip.ECHOPLEX) // bass default
        assertThat(r.backend.player.source).isEqualTo(AudioSource.Asset(DemoClip.ECHOPLEX.assetPath))
        assertThat(r.backend.player.loop).isEqualTo(my.umarh.paudio.audio.playback.DemoClipSource.PREFERENCE_LOOP)
        r.backend.ticks.tryEmit(ListenTick(AbSide.A, 3_000))
        settle()

        val order = listOf(DemoClip.GHOSTS_34, DemoClip.DISCIPLINE, DemoClip.ECHOPLEX)
        for (expected in order) {
            r.vm.tryAnotherSong()
            settle()
            assertThat(r.ui.clip).isEqualTo(expected)
            assertThat(r.ui.roundNumber).isEqualTo(1)
            assertThat(r.backend.player.source).isEqualTo(AudioSource.Asset(expected.assetPath))
            assertThat(r.ui.aHeard).isTrue() // what was heard in the round is kept
        }
        assertThat(r.backend.players).hasSize(1) // reloaded in place, not re-created
    }

    @Test
    fun defaultClipPerAxis() = runTest(dispatcher) {
        val r = started()
        val clips = mutableListOf<DemoClip>()
        repeat(3) {
            clips += r.ui.clip
            play(r, "+", PreferenceBisection(Random(SEED)).also { ref -> repeat(r.ui.roundNumber - 1) { ref.choose(ref.currentRound!!.plusSide) } })
        }
        assertThat(clips).containsExactly(DemoClip.ECHOPLEX, DemoClip.DISCIPLINE, DemoClip.GHOSTS_34).inOrder()
    }

    // ---- loudness matching ---------------------------------------------------------------------------------------

    @Test
    fun everySwapUsesAbGainPlannerOverBothOptions() = runTest(dispatcher) {
        val r = started()
        val b = baseline()
        val ref = PreferenceBisection(Random(SEED))
        val round = checkNotNull(ref.currentRound)
        // Round 1: axis bass, other centres 0.
        fun curve(g: Double) = CurveBuilder.build(b.hlLeft, b.hlRight, b.strength, g, 0.0, 0.0)
        val ca = curve(round.aGain)
        val cb = curve(round.bGain)
        val plan = AbGainPlanner.preferenceRound(AbCurve.Curve(ca.gLeft, ca.gRight), AbCurve.Curve(cb.gLeft, cb.gRight), r.backend.predictor)
        val cfgA = AbGainPlanner.configFor(AbCurve.Curve(ca.gLeft, ca.gRight), plan, FS)
        val cfgB = AbGainPlanner.configFor(AbCurve.Curve(cb.gLeft, cb.gRight), plan, FS)

        r.vm.listen(AbSide.B)
        r.vm.listen(AbSide.A)
        settle()
        val swaps = r.backend.player.swaps
        assertThat(swaps).isNotEmpty()
        for (s in swaps) {
            assertThat(s.cfg.preampDb).isEqualTo(plan.commonPreampDb)
        }
        val last = swaps.takeLast(2) // B then A
        assertThat(last[0].cfg).isEqualTo(cfgB)
        assertThat(last[0].inputGainDb).isEqualTo(-plan.options[1].deltaLDb.toFloat())
        assertThat(last[1].cfg).isEqualTo(cfgA)
        assertThat(last[1].inputGainDb).isEqualTo(-plan.options[0].deltaLDb.toFloat())
        // The two options really differ in predicted loudness (so the test is not vacuous).
        assertThat(plan.options[0].deltaLDb).isNotEqualTo(plan.options[1].deltaLDb)
        assertThat(plan.commonPreampDb).isLessThan(0f)
    }

    @Test
    fun nextRoundUsesTheCentresOfTheOtherAxes() = runTest(dispatcher) {
        val r = started()
        play(r, "+") // bass -> 6; round 2 is presence
        val b = baseline()
        val ref = PreferenceBisection(Random(SEED))
        ref.choose(ref.currentRound!!.plusSide)
        val round = checkNotNull(ref.currentRound)
        fun curve(g: Double) = CurveBuilder.build(b.hlLeft, b.hlRight, b.strength, 6.0, g, 0.0)
        val ca = curve(round.aGain)
        val cb = curve(round.bGain)
        val plan = AbGainPlanner.preferenceRound(AbCurve.Curve(ca.gLeft, ca.gRight), AbCurve.Curve(cb.gLeft, cb.gRight), r.backend.predictor)
        r.vm.listen(AbSide.A)
        settle()
        val s = r.backend.player.swaps.last()
        assertThat(s.cfg).isEqualTo(AbGainPlanner.configFor(AbCurve.Curve(ca.gLeft, ca.gRight), plan, FS))
        assertThat(s.inputGainDb).isEqualTo(-plan.options[0].deltaLDb.toFloat())
    }

    @Test
    fun fineTuneToggle_usesTheSameLoudnessMatching() = runTest(dispatcher) {
        val r = started()
        play(r, "+++++++++")
        r.vm.openFineTune()
        settle()
        r.vm.setBass(4f)
        r.vm.setStrength(0.8f)
        settle()
        val b = baseline()
        val c = CurveBuilder.build(b.hlLeft, b.hlRight, 0.8f.toDouble(), 4.0, 6.0, 8.0)
        val plan = AbGainPlanner.resultsAb(c.gLeft, c.gRight, r.backend.predictor)
        val swaps = r.backend.player.swaps
        assertThat(swaps.last().cfg).isEqualTo(AbGainPlanner.configFor(AbCurve.Curve(c.gLeft, c.gRight), plan, FS))
        assertThat(swaps.last().inputGainDb).isEqualTo(-plan.options[1].deltaLDb.toFloat())
        r.vm.setPersonalized(false)
        settle()
        assertThat(swaps.last().cfg).isEqualTo(AbGainPlanner.configFor(AbCurve.Flat, plan, FS))
        assertThat(swaps.last().inputGainDb).isEqualTo(0f)
        assertThat(swaps.last().cfg.preampDb).isEqualTo(plan.commonPreampDb)
    }

    @Test
    fun noHearingTest_baselineIsThePreferenceCurveAlone() = runTest(dispatcher) {
        val b = PreferenceBaseline(3, DoubleArray(9), DoubleArray(9), false, 0.5, 0.0, 0.0, 0.0, true)
        val r = started(rig(b))
        assertThat(r.ui.hasHearingTest).isFalse()
        play(r, "+++++++++")
        val c = CurveBuilder.build(DoubleArray(9), DoubleArray(9), 0.5, 10.5, 6.0, 8.0)
        assertThat(r.ui.gLeft!!.toList()).isEqualTo(c.gLeft.toList())
    }

    // ---- fine-tune -----------------------------------------------------------------------------------------------

    @Test
    fun fineTune_slidersClampAndUpdateTheCurve() = runTest(dispatcher) {
        val r = started()
        play(r, "+++++++++")
        val before = r.ui.gLeft!!.toList()
        r.vm.openFineTune()
        r.vm.setBass(99f)
        r.vm.setPresence(-99f)
        r.vm.setTreble(-99f)
        assertThat(r.ui.gB).isEqualTo(12f)
        assertThat(r.ui.gP).isEqualTo(-6f)
        assertThat(r.ui.gT).isEqualTo(-8f)
        r.vm.setStrength(7f)
        assertThat(r.ui.strength).isEqualTo(1f)
        assertThat(r.ui.gLeft!!.toList()).isNotEqualTo(before)
        r.vm.setBass(3.3f) // 0.5 dB steps
        assertThat(r.ui.gB).isEqualTo(3.5f)
    }

    // ---- save ----------------------------------------------------------------------------------------------------

    @Test
    fun save_writesThePreferenceTestAndTheProfileSettings() = runTest(dispatcher) {
        val r = started()
        play(r, "+++++++++")
        r.vm.openFineTune()
        r.vm.setBass(2f)
        r.vm.setStrength(0.25f)
        settle()
        r.vm.save()
        settle()
        assertThat(r.ui.saved).isTrue()
        val s = r.data.saves.single()
        assertThat(s.profileId).isEqualTo(3L)
        assertThat(s.startedAt).isEqualTo(1_700_000_000_000L)
        // The test records what the bisection found; the profile gets the fine-tuned values.
        assertThat(s.test).isEqualTo(Triple(10.5f, 6f, 8f))
        assertThat(s.settings).isEqualTo(ProfileSettings(0.25f, 2f, 6f, 8f, personalizationOn = false))
        val rounds = Json.parseToJsonElement(s.rounds).jsonArray
        assertThat(rounds).hasSize(9)
        val first = rounds[0].jsonObject
        assertThat(first["axis"]!!.jsonPrimitive.content).isEqualTo("BASS")
        assertThat(first["clip"]!!.jsonPrimitive.content).isEqualTo(DemoClip.ECHOPLEX.name)
        assertThat(first["choice"]!!.jsonPrimitive.content).isAnyOf("A", "B")
        assertThat(first["pickedGain"]!!.jsonPrimitive.content).isEqualTo("6.0")
    }

    @Test
    fun save_recordsCantTellAndFailsSoftly() = runTest(dispatcher) {
        val r = started()
        play(r, "?+?++")
        // B? -> bass ended, P+, T?, ... any sequence that finishes; just make sure it ended.
        assertThat(r.ui.phase).isEqualTo(PrefPhase.RESULT)
        r.data.failSave = true
        r.vm.save()
        settle()
        assertThat(r.ui.saveError).isTrue()
        assertThat(r.ui.saved).isFalse()
        r.data.failSave = false
        r.vm.save()
        settle()
        assertThat(r.ui.saved).isTrue()
        val rounds = Json.parseToJsonElement(r.data.saves.single().rounds).jsonArray
        assertThat(rounds.first().jsonObject["choice"]!!.jsonPrimitive.content).isEqualTo("CANT_TELL")
        assertThat(rounds.first().jsonObject["pickedGain"]!!.toString()).isEqualTo("null")
    }

    // ---- P0 ------------------------------------------------------------------------------------------------------

    @Test
    fun bypassTokenIsHeldFromLoadUntilRelease() = runTest(dispatcher) {
        val r = started()
        assertThat(r.backend.bypassHeldAtCreate).isTrue() // bypass first, then the player
        assertThat(r.bypass.acquiredReasons).containsExactly(BypassReason.PREFERENCE_AB)
        assertThat(r.bypass.bypassed.value).isTrue()
        r.vm.listen(AbSide.A)
        assertThat(r.ui.playing).isTrue()

        r.vm.stopAudio() // screen left STARTED
        assertThat(r.backend.player.released).isTrue()
        assertThat(r.backend.player.isPlaying).isFalse()
        assertThat(r.bypass.openCount).isEqualTo(0)
        assertThat(r.bypass.bypassed.value).isFalse()
        assertThat(r.ui.playing).isFalse()

        r.vm.startAudio() // back to the screen: new player, new token, paused
        settle()
        assertThat(r.backend.players).hasSize(2)
        assertThat(r.bypass.openCount).isEqualTo(1)
        assertThat(r.ui.playing).isFalse()
        assertThat(r.ui.roundNumber).isEqualTo(1)
    }

    @Test
    fun bypassIsReleasedWhenTheRoundsEndAndWhenTheViewModelIsCleared() = runTest(dispatcher) {
        val r = started()
        play(r, "+++++++++")
        assertThat(r.ui.phase).isEqualTo(PrefPhase.RESULT)
        assertThat(r.bypass.openCount).isEqualTo(0)
        assertThat(r.backend.player.released).isTrue()

        r.vm.openFineTune()
        settle()
        assertThat(r.bypass.openCount).isEqualTo(1)
        val m = PreferenceViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }
        m.invoke(r.vm)
        assertThat(r.bypass.openCount).isEqualTo(0)
    }

    @Test
    fun noAudioIsCreatedWhileTheScreenIsNotStarted() = runTest(dispatcher) {
        val r = rig()
        settle()
        r.vm.begin()
        settle()
        assertThat(r.backend.players).isEmpty()
        assertThat(r.bypass.acquiredReasons).isEmpty()
    }

    // ---- loading -------------------------------------------------------------------------------------------------

    @Test
    fun noProfile_isUnavailable_andTheRouteProfileIdIsForwarded() = runTest(dispatcher) {
        val r = rig(b = null)
        settle()
        assertThat(r.ui.phase).isEqualTo(PrefPhase.UNAVAILABLE)
        assertThat(r.data.requested).isEqualTo(3L)
        val ok = rig()
        settle()
        assertThat(ok.ui.phase).isEqualTo(PrefPhase.INTRO)
        assertThat(ok.ui.hasHearingTest).isTrue()
    }

    // ---- review fixes --------------------------------------------------------------------------------------------

    @Test
    fun failedCreateOrLoad_releasesTheTokenAndRetryReacquires() = runTest(dispatcher) {
        val r = rig()
        settle()
        r.backend.failCreate = true
        r.vm.startAudio()
        r.vm.begin()
        settle()
        assertThat(r.ui.audio).isEqualTo(AudioStatus.ERROR)
        assertThat(r.bypass.openCount).isEqualTo(0)

        r.backend.failCreate = false
        r.backend.failLoad = true
        r.vm.retryAudio()
        settle()
        assertThat(r.ui.audio).isEqualTo(AudioStatus.ERROR)
        assertThat(r.bypass.openCount).isEqualTo(0)
        assertThat(r.backend.player.released).isTrue()

        r.backend.failLoad = false
        r.vm.retryAudio()
        settle()
        assertThat(r.ui.audio).isEqualTo(AudioStatus.READY)
        assertThat(r.bypass.openCount).isEqualTo(1)
    }

    @Test
    fun doubleTapOnCantTellEndsOnlyOneAxis() = runTest(dispatcher) {
        val r = started()
        r.vm.cantTell(1)
        r.vm.cantTell(1)
        settle()
        assertThat(r.ui.roundNumber).isEqualTo(2)
        assertThat(r.ui.axis).isEqualTo(my.umarh.paudio.core.preference.PreferenceAxis.PRESENCE)
        // Also without the round argument: two immediate calls would still be two different rounds, but a stale
        // round-keyed choose is ignored.
        openGate(r)
        r.vm.choose(AbSide.A, 1)
        assertThat(r.ui.roundNumber).isEqualTo(2)
    }

    @Test
    fun staleTicksBetweenChooseAndTheNewPlanDoNotCount() = runTest(dispatcher) {
        val r = started()
        openGate(r)
        r.backend.predictorDelayMs = 100
        r.vm.choose(AbSide.A)
        r.backend.ticks.tryEmit(ListenTick(AbSide.A, 5_000))
        r.backend.ticks.tryEmit(ListenTick(AbSide.B, 5_000))
        runCurrent() // collector runs while the new round's plan is still being prepared
        assertThat(r.ui.roundNumber).isEqualTo(2)
        assertThat(r.ui.aHeard).isFalse()
        assertThat(r.ui.bHeard).isFalse()
        settle()
        r.backend.ticks.tryEmit(ListenTick(AbSide.A, 2_000))
        settle()
        assertThat(r.ui.aHeard).isTrue()
    }

    @Test
    fun doubleSave_writesOnce() = runTest(dispatcher) {
        val r = started()
        play(r, "+++++++++")
        r.vm.save()
        r.vm.save()
        settle()
        r.vm.save()
        settle()
        assertThat(r.data.saves).hasSize(1)
    }

    @Test
    fun noPlayerIsRecreatedAfterSave() = runTest(dispatcher) {
        val r = started()
        play(r, "+++++++++")
        r.vm.openFineTune()
        settle()
        r.vm.save()
        settle()
        val n = r.backend.players.size
        r.vm.stopAudio()
        r.vm.startAudio()
        settle()
        assertThat(r.backend.players).hasSize(n)
        assertThat(r.bypass.openCount).isEqualTo(0)
    }

    @Test
    fun sliderDrags_areCoalescedIntoOneReplan() = runTest(dispatcher) {
        val r = started()
        play(r, "+++++++++")
        r.vm.openFineTune()
        settle()
        val before = r.backend.player.swaps.size
        for (v in listOf(1f, 2f, 3f, 4f, 5f)) {
            r.vm.setBass(v)
            advanceTimeBy(40)
            runCurrent()
        }
        settle()
        assertThat(r.backend.player.swaps.size - before).isEqualTo(1)
        assertThat(r.ui.gB).isEqualTo(5f)
    }

    @Test
    fun stopDuringAnInFlightLoad_leavesNoTokenOrHandle() = runTest(dispatcher) {
        val r = rig()
        settle()
        r.backend.loadDelayMs = 1_000
        r.vm.startAudio()
        r.vm.begin()
        runCurrent()
        assertThat(r.bypass.openCount).isEqualTo(1) // load in flight
        r.vm.stopAudio()
        settle()
        assertThat(r.bypass.openCount).isEqualTo(0)
        assertThat(r.backend.player.released).isTrue()
        assertThat(r.ui.audio).isEqualTo(AudioStatus.IDLE)
    }

    // ---- re-entry on the fine-tune ---------------------------------------------------------------------------------

    private fun TestScope.reentry(): Rig {
        val r = rig(baseline(hasPrefTest = true).let { b ->
            PreferenceBaseline(b.profileId, b.hlLeft, b.hlRight, b.hasTest, 0.6, 2.5, -1.5, 3.0, false, true)
        })
        settle()
        return r
    }

    @Test
    fun reentry_opensOnFineTuneWithSavedValuesAndTheSameCurveAsAfterTheRounds() = runTest(dispatcher) {
        val r = reentry()
        val b = checkNotNull(r.data.baseline)
        assertThat(r.ui.phase).isEqualTo(PrefPhase.FINE_TUNE)
        assertThat(r.ui.strength).isEqualTo(0.6f)
        assertThat(listOf(r.ui.gB, r.ui.gP, r.ui.gT)).containsExactly(2.5f, -1.5f, 3.0f).inOrder()
        assertThat(r.ui.personalized).isTrue()
        assertThat(r.ui.hasHearingTest).isTrue()
        val expected = PreferencePlanner.curve(b, 0.6f.toDouble(), 2.5, -1.5, 3.0)
        assertThat(r.ui.gLeft!!.toList()).isEqualTo(expected.gLeft.toList())
        assertThat(r.ui.gRight!!.toList()).isEqualTo(expected.gRight.toList())
        assertThat(r.backend.players).isEmpty() // P0: nothing before STARTED
        assertThat(r.bypass.openCount).isEqualTo(0)
    }

    @Test
    fun noPreferenceTest_stillOpensOnTheIntro() = runTest(dispatcher) {
        val r = rig()
        settle()
        assertThat(r.ui.phase).isEqualTo(PrefPhase.INTRO)
    }

    @Test
    fun reentry_audioStartsOnlyWhenStarted_andIsReleasedOnStop() = runTest(dispatcher) {
        val r = reentry()
        r.vm.startAudio()
        advanceTimeBy(500)
        settle()
        assertThat(r.backend.bypassHeldAtCreate).isTrue()
        assertThat(r.bypass.openCount).isEqualTo(1)
        assertThat(r.ui.audio).isEqualTo(AudioStatus.READY)
        r.vm.stopAudio()
        assertThat(r.bypass.openCount).isEqualTo(0)
        assertThat(r.backend.player.released).isTrue()
    }

    @Test
    fun reentry_startedBeforeLoadFinishesStillPreparesOnceLoaded() = runTest(dispatcher) {
        val bypass = FakeBypassController()
        val backend = FakeBackend(bypass)
        val data = FakeData(baseline(hasPrefTest = true), SEED)
        val vm = PreferenceViewModel(SavedStateHandle(mapOf("profileId" to 3L)), data, backend, bypass)
        vm.startAudio() // before load() ran
        settle()
        advanceTimeBy(500)
        settle()
        assertThat(vm.ui.value.phase).isEqualTo(PrefPhase.FINE_TUNE)
        assertThat(vm.ui.value.audio).isEqualTo(AudioStatus.READY)
        assertThat(bypass.openCount).isEqualTo(1)
    }

    @Test
    fun reentry_failedCreateReleasesTheToken() = runTest(dispatcher) {
        val r = reentry()
        r.backend.failCreate = true
        r.vm.startAudio()
        advanceTimeBy(500)
        settle()
        assertThat(r.ui.audio).isEqualTo(AudioStatus.ERROR)
        assertThat(r.bypass.openCount).isEqualTo(0)
    }

    @Test
    fun loadFailure_isUnavailableAndHoldsNoToken() = runTest(dispatcher) {
        val bypass = FakeBypassController()
        val data = FakeData(baseline(hasPrefTest = true), SEED).also { it.failLoad = true }
        val vm = PreferenceViewModel(SavedStateHandle(mapOf("profileId" to 3L)), data, FakeBackend(bypass), bypass)
        vm.startAudio()
        settle()
        assertThat(vm.ui.value.phase).isEqualTo(PrefPhase.UNAVAILABLE)
        assertThat(bypass.openCount).isEqualTo(0)
    }

    @Test
    fun redo_fromTheFineTuneGoesToRoundOneWithAFreshBisection() = runTest(dispatcher) {
        val r = reentry()
        r.vm.startAudio()
        advanceTimeBy(500)
        settle()
        r.vm.begin()
        settle()
        assertThat(r.ui.phase).isEqualTo(PrefPhase.ROUND)
        assertThat(r.ui.roundNumber).isEqualTo(1)
        assertThat(r.ui.axis).isEqualTo(PreferenceBisection(Random(SEED)).currentRound!!.axis)
        assertThat(r.backend.players.first().released).isTrue() // old audio released, a new one prepared
        assertThat(r.bypass.openCount).isEqualTo(1)
        // The wizard runs as usual from the centre 0 (not from the saved gains).
        play(r, "+++++++++")
        assertThat(r.ui.phase).isEqualTo(PrefPhase.RESULT)
        assertThat(r.ui.gB).isEqualTo(10.5f)
    }

    @Test
    fun saveFromReentry_writesOnlyTheSettings_neverATest() = runTest(dispatcher) {
        val r = reentry()
        r.vm.setBass(4f)
        r.vm.save()
        settle()
        assertThat(r.ui.saved).isTrue()
        assertThat(r.data.saves).isEmpty()
        val (id, st) = r.data.settingsSaves.single()
        assertThat(id).isEqualTo(3L)
        assertThat(st).isEqualTo(ProfileSettings(0.6f, 4f, -1.5f, 3f, personalizationOn = false))
    }

    @Test
    fun saveAfterARedo_usesTheFullSave() = runTest(dispatcher) {
        val r = reentry()
        r.vm.startAudio()
        r.vm.begin()
        settle()
        play(r, "+++++++++")
        r.vm.openFineTune()
        r.vm.save()
        settle()
        assertThat(r.data.settingsSaves).isEmpty()
        assertThat(r.data.saves).hasSize(1)
    }
}
