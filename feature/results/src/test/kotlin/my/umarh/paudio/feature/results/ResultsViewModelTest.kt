package my.umarh.paudio.feature.results

import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import my.umarh.paudio.audio.playback.AbGainPlanner
import my.umarh.paudio.core.dsp.CurveBuilder
import my.umarh.paudio.core.dsp.DpEqPlanner
import my.umarh.paudio.core.dsp.LoudnessPredictor
import my.umarh.paudio.core.dsp.ThirdOctaveBands
import my.umarh.paudio.core.model.AbPlayer
import my.umarh.paudio.core.model.AudioSource
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.core.model.HeadsetKey
import my.umarh.paudio.core.preference.DemoClip
import my.umarh.paudio.data.db.HearingTestEntity
import my.umarh.paudio.data.db.ProfileEntity
import my.umarh.paudio.data.db.ThresholdEntity
import my.umarh.paudio.data.db.TrialEntity
import my.umarh.paudio.data.repo.HearingTestRepository
import my.umarh.paudio.data.repo.ProfileRepository
import my.umarh.paudio.data.repo.ProfileSettings
import my.umarh.paudio.data.repo.ProfileSlot
import my.umarh.paudio.data.repo.ProfileWithHeadset
import my.umarh.paudio.testing.FakeAbPlayer
import my.umarh.paudio.testing.FakeBypassController
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ResultsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private companion object {
        const val FS = 44_100
    }

    // ---- fakes ---------------------------------------------------------------------------------------------------

    private class FakeTests(
        private val tests: List<HearingTestEntity>,
        private val th: Map<Long, List<ThresholdEntity>>,
    ) : HearingTestRepository {
        override suspend fun save(test: HearingTestEntity, thresholds: List<ThresholdEntity>, trials: List<TrialEntity>, headsetKey: HeadsetKey?) = TODO()
        override fun observeForProfile(profileId: Long): Flow<List<HearingTestEntity>> = TODO()
        override fun observeLatest(profileId: Long): Flow<HearingTestEntity?> = TODO()
        override suspend fun latest(profileId: Long) = tests.filter { it.profileId == profileId }.maxByOrNull { it.startedAt }
        override suspend fun get(testId: Long) = tests.firstOrNull { it.id == testId }
        override suspend fun thresholds(testId: Long) = th[testId].orEmpty()
        override fun observeThresholds(testId: Long): Flow<List<ThresholdEntity>> = flowOf(th[testId].orEmpty())
        override suspend fun trials(testId: Long) = emptyList<TrialEntity>()
        override suspend fun delete(testId: Long) = TODO()
        override fun observeForTrend(profileId: Long, headsetKey: HeadsetKey): Flow<List<HearingTestEntity>> = TODO()
    }

    private class FakeProfiles(private val profile: ProfileEntity?) : ProfileRepository {
        data class SeedWrite(val profileId: Long, val color: Int?, val overridden: Boolean)

        val seedWrites = mutableListOf<SeedWrite>()
        override fun observeAll() = TODO()
        override fun observe(profileId: Long): Flow<ProfileWithHeadset?> = TODO()
        override suspend fun get(profileId: Long) = profile?.takeIf { it.id == profileId }?.let { ProfileWithHeadset(it, null) }
        override suspend fun create(listenerId: Long, headsetId: Long?, name: String, settings: ProfileSettings) = TODO()
        override suspend fun rename(profileId: Long, name: String) = TODO()
        override suspend fun updateSettings(profileId: Long, settings: ProfileSettings) = TODO()
        override suspend fun setSeedColor(profileId: Long, seedColor: Int?, overridden: Boolean) {
            seedWrites += SeedWrite(profileId, seedColor, overridden)
        }
        override suspend fun delete(profileId: Long) = TODO()
        override suspend fun duplicate(profileId: Long, newName: String, target: ProfileSlot) = TODO()
        override suspend fun moveToHeadset(profileId: Long, headsetId: Long?) = TODO()
        override suspend fun ensureAnyHeadphonesProfile(listenerId: Long) = TODO()
        override suspend fun resolveForHeadset(listenerId: Long, headsetKey: HeadsetKey) = TODO()
    }

    /** Wraps [FakeAbPlayer] to record the bypass count at `load` and to fail loads of user files on demand. */
    private class ProbedPlayer(val inner: FakeAbPlayer, val bypass: FakeBypassController, val backend: FakeBackend) : AbPlayer by inner {
        val bypassOpenAtLoad = mutableListOf<Int>()
        override suspend fun load(src: AudioSource, loop: ClosedRange<kotlin.time.Duration>?) {
            bypassOpenAtLoad += bypass.openCount
            if (backend.failUserFiles && src is AudioSource.Uri) throw IOException("unsupported")
            if (backend.failClips && src !is AudioSource.Uri) throw IOException("clip failed")
            inner.load(src, loop)
        }
    }

    private class FakeBackend(val bypass: FakeBypassController) : ResultsAbBackend {
        val players = mutableListOf<ProbedPlayer>()
        val player: ProbedPlayer get() = players.last()
        var failUserFiles = false
        var failClips = false

        /** Treble-heavy clip LTAS: differs clearly from pink so the clip and user-file plans can be told apart. */
        val clipPredictor = LoudnessPredictor(DoubleArray(ThirdOctaveBands.COUNT) { if (it < 14) 0.02 else 1.0 })
        val predictorRequests = mutableListOf<DemoClip?>()
        var created = 0
        var bypassHeldAtCreate = false

        override fun createPlayer(): AbHandle {
            created++
            bypassHeldAtCreate = bypass.openCount >= 1
            return AbHandle(ProbedPlayer(FakeAbPlayer(), bypass, this).also { players += it })
        }

        override fun outputSampleRate() = FS

        override suspend fun predictor(clip: DemoClip?): LoudnessPredictor {
            predictorRequests += clip
            return if (clip != null) clipPredictor else LoudnessPredictor.pink()
        }
    }

    private class Rig(
        val vm: ResultsViewModel,
        val backend: FakeBackend,
        val bypass: FakeBypassController,
        val profiles: FakeProfiles,
    ) {
        /** The most recently created player (the screen creates one per STARTED period). */
        val fake: FakeAbPlayer get() = backend.player.inner
        val probed: ProbedPlayer get() = backend.player
    }

    // A sloping loss so the Personalized curve is clearly non-flat: left worse than right above 4 kHz.
    private fun slope(f: Int) = if (f >= 4000) (f - 3000) / 400.0 else 0.0

    private fun rig(
        tests: List<HearingTestEntity> = listOf(test(1)),
        th: Map<Long, List<ThresholdEntity>> = mapOf(1L to thresholds(1, right = { slope(it) / 2 }, left = ::slope)),
        profile: ProfileEntity? = profileOf(strength = 0.8f, gB = 2f, gP = -1f, gT = 3f),
        testId: Long = 1,
    ): Rig {
        val bypass = FakeBypassController()
        val backend = FakeBackend(bypass)
        val profiles = FakeProfiles(profile)
        val vm = ResultsViewModel(SavedStateHandle(mapOf("testId" to testId)), FakeTests(tests, th), profiles, backend, bypass)
        return Rig(vm, backend, bypass, profiles)
    }

    private fun profileOf(id: Long = 1, strength: Float = 0.5f, gB: Float = 0f, gP: Float = 0f, gT: Float = 0f, overridden: Boolean = false) =
        ProfileEntity(
            id = id, listenerId = 1, headsetId = null, name = "P", compStrength = strength, gB = gB, gP = gP, gT = gT,
            seedOverridden = overridden, createdAt = 0, updatedAt = 0,
        )

    private fun TestScope.settle() {
        repeat(3) {
            advanceUntilIdle()
            runCurrent()
        }
    }

    // ---- expected values, computed independently from the pinned planner contract ------------------------------------

    private class Expected(val commonPreamp: Float, val cfgOriginal: EqConfig, val cfgPersonalized: EqConfig, val gainPersonalized: Float)

    private fun expected(c: ResultsContent, s: CurveSettings, predictor: LoudnessPredictor): Expected {
        val curve = CurveBuilder.build(c.hlLeft, c.hlRight, s.strength, s.gB, s.gP, s.gT)
        val plan = AbGainPlanner.resultsAb(curve.gLeft, curve.gRight, predictor)
        val p = DpEqPlanner.fromCurve(curve.gLeft, curve.gRight, plan.commonPreampDb, FS)
        val f = DpEqPlanner.flat(FS)
        return Expected(
            plan.commonPreampDb,
            EqConfig(plan.commonPreampDb, f.cutoffHz.toList(), listOf(f.gainsLeft.toList(), f.gainsRight.toList())),
            EqConfig(plan.commonPreampDb, p.cutoffHz.toList(), listOf(p.gainsLeft.toList(), p.gainsRight.toList())),
            -plan.options[1].deltaLDb.toFloat(),
        )
    }

    private val settings = CurveSettings(0.8f.toDouble(), 2.0, -1.0, 3.0)

    private fun contentOf(r: Rig) = (r.vm.ui.value as ResultsUiState.Content).content

    // ---- tests ---------------------------------------------------------------------------------------------------

    @Test
    fun loadsContentAndPersonalizedCurveFromProfileSettings() = runTest(dispatcher) {
        val r = rig()
        settle()
        val c = contentOf(r)
        assertThat(c.profileId).isEqualTo(1)
        assertThat(c.testId).isEqualTo(1)
        val g = CurveBuilder.build(c.hlLeft, c.hlRight, 0.8f.toDouble(), 2.0, -1.0, 3.0)
        assertThat(r.vm.ab.value.gLeft).isEqualTo(g.gLeft)
        assertThat(r.vm.ab.value.gRight).isEqualTo(g.gRight)
        // Strength changes the curve: it is the profile's, not the default 0.5.
        assertThat(g.gLeft).isNotEqualTo(CurveBuilder.build(c.hlLeft, c.hlRight, 0.5, 2.0, -1.0, 3.0).gLeft)
    }

    @Test
    fun startAb_loadsDiscipline_swapsToPersonalizedWithLoudnessMatchedGains_andDoesNotAutoplay() = runTest(dispatcher) {
        val r = rig()
        settle()
        r.vm.startAb()
        settle()
        assertThat(r.fake.source).isEqualTo(AudioSource.Asset(DemoClip.DISCIPLINE.assetPath))
        assertThat(r.fake.loop).isNull()
        assertThat(r.fake.isPlaying).isFalse()
        assertThat(r.backend.predictorRequests).containsExactly(DemoClip.DISCIPLINE)
        val e = expected(contentOf(r), settings, r.backend.clipPredictor)
        val swap = r.fake.lastSwap!!
        // cfg.preampDb = P_common, inputGainDb = -DeltaL(G), DP layout planned for the REAL mixer rate.
        assertThat(swap.cfg).isEqualTo(e.cfgPersonalized)
        assertThat(swap.cfg.preampDb).isEqualTo(e.commonPreamp)
        assertThat(swap.inputGainDb).isEqualTo(e.gainPersonalized)
        assertThat(swap.cfg.bandCutoffsHz.last()).isEqualTo(FS / 2f)
        assertThat(r.vm.ab.value.status).isEqualTo(AbStatus.READY)
    }

    @Test
    fun toggle_swapsBetweenFlatOriginalAndPersonalized_sharingTheCommonPreamp() = runTest(dispatcher) {
        val r = rig()
        settle()
        r.vm.startAb()
        settle()
        val e = expected(contentOf(r), settings, r.backend.clipPredictor)

        r.vm.setPersonalized(false)
        val orig = r.fake.lastSwap!!
        assertThat(orig.cfg).isEqualTo(e.cfgOriginal)
        assertThat(orig.cfg.channelGainsDb.flatten().all { it == 0f }).isTrue()
        assertThat(orig.cfg.preampDb).isEqualTo(e.commonPreamp) // shared P_common, so neither option can clip
        assertThat(orig.inputGainDb).isEqualTo(0f) // DeltaL(flat) = 0

        r.vm.setPersonalized(true)
        val pers = r.fake.lastSwap!!
        assertThat(pers.cfg).isEqualTo(e.cfgPersonalized)
        assertThat(pers.inputGainDb).isEqualTo(e.gainPersonalized)
        assertThat(pers.cfg.channelGainsDb.flatten().any { it != 0f }).isTrue()
        // Same player, no reload: same playhead.
        assertThat(r.backend.created).isEqualTo(1)
        assertThat(r.probed.bypassOpenAtLoad).hasSize(1)
    }

    @Test
    fun ownFile_usesPinkLtas_andTheUserUriSource() = runTest(dispatcher) {
        val r = rig()
        settle()
        r.vm.startAb()
        settle()
        val clipGain = r.fake.lastSwap!!.inputGainDb

        r.vm.useOwnFile("content://media/song", "song.flac")
        settle()
        assertThat(r.fake.source).isEqualTo(AudioSource.Uri("content://media/song"))
        assertThat(r.backend.predictorRequests).containsExactly(DemoClip.DISCIPLINE, null).inOrder()
        val e = expected(contentOf(r), settings, LoudnessPredictor.pink())
        val swap = r.fake.lastSwap!!
        assertThat(swap.cfg).isEqualTo(e.cfgPersonalized)
        assertThat(swap.inputGainDb).isEqualTo(e.gainPersonalized)
        assertThat(swap.inputGainDb).isNotEqualTo(clipGain) // pink vs clip LTAS give different matching gains
        assertThat(r.vm.ab.value.sourceKind).isEqualTo(AbSourceKind.USER_FILE)
        assertThat(r.vm.ab.value.fileName).isEqualTo("song.flac")
        assertThat(r.backend.created).isEqualTo(1) // one player for the whole screen

        r.vm.useDemoClip()
        settle()
        assertThat(r.fake.source).isEqualTo(AudioSource.Asset(DemoClip.DISCIPLINE.assetPath))
        assertThat(r.vm.ab.value.sourceKind).isEqualTo(AbSourceKind.DEMO_CLIP)
    }

    @Test
    fun unreadableOwnFile_fallsBackToTheClip_andSaysSo() = runTest(dispatcher) {
        val r = rig()
        settle()
        r.vm.startAb()
        settle()
        r.backend.failUserFiles = true
        r.vm.useOwnFile("content://media/bad", null)
        settle()
        val ab = r.vm.ab.value
        assertThat(ab.fileError).isTrue()
        assertThat(ab.sourceKind).isEqualTo(AbSourceKind.DEMO_CLIP)
        assertThat(ab.status).isEqualTo(AbStatus.READY)
        assertThat(r.fake.source).isEqualTo(AudioSource.Asset(DemoClip.DISCIPLINE.assetPath))
    }

    @Test
    fun p0_bypassTokenIsAcquiredBeforeThePlayerExistsAndLoads_andHeldUntilStop() = runTest(dispatcher) {
        val r = rig()
        settle()
        assertThat(r.bypass.openCount).isEqualTo(0) // nothing before the screen is STARTED
        r.vm.startAb()
        settle()
        assertThat(r.bypass.acquiredReasons).contains(BypassReason.RESULTS_AB)
        assertThat(r.backend.bypassHeldAtCreate).isTrue()
        assertThat(r.probed.bypassOpenAtLoad.all { it >= 1 }).isTrue()
        assertThat(r.bypass.openCount).isEqualTo(1)

        r.vm.stopAb()
        assertThat(r.fake.released).isTrue()
        assertThat(r.bypass.openCount).isEqualTo(0)
        assertThat(r.bypass.bypassed.value).isFalse()
        assertThat(r.vm.ab.value.status).isEqualTo(AbStatus.IDLE)
    }

    @Test
    fun backgroundingPausesAndReleases_andForegroundingPreparesAgainWithoutAutoplay() = runTest(dispatcher) {
        val r = rig()
        settle()
        r.vm.startAb()
        settle()
        r.vm.togglePlay()
        assertThat(r.fake.isPlaying).isTrue()
        assertThat(r.vm.ab.value.playing).isTrue()

        r.vm.stopAb() // ON_STOP
        assertThat(r.fake.isPlaying).isFalse()
        assertThat(r.fake.released).isTrue()
        assertThat(r.vm.ab.value.playing).isFalse()
        assertThat(r.bypass.openCount).isEqualTo(0)

        // ON_START again: a fresh player (and a fresh token), paused.
        r.vm.startAb()
        settle()
        assertThat(r.backend.players).hasSize(2)
        assertThat(r.fake.isPlaying).isFalse()
        assertThat(r.vm.ab.value.playing).isFalse()
        assertThat(r.bypass.openCount).isEqualTo(1)
        r.vm.stopAb()
        assertThat(r.bypass.openCount).isEqualTo(0)
    }

    @Test
    fun pause_stopsPlaybackWithoutReleasing() = runTest(dispatcher) {
        val r = rig()
        settle()
        r.vm.startAb()
        settle()
        r.vm.togglePlay()
        assertThat(r.fake.isPlaying).isTrue()
        r.vm.pause()
        assertThat(r.fake.isPlaying).isFalse()
        assertThat(r.fake.released).isFalse()
    }

    @Test
    fun onCleared_releasesThePlayerAndTheToken() = runTest(dispatcher) {
        val r = rig()
        settle()
        r.vm.startAb()
        settle()
        assertThat(r.bypass.openCount).isEqualTo(1)
        ResultsViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(r.vm)
        assertThat(r.fake.released).isTrue()
        assertThat(r.bypass.openCount).isEqualTo(0)
    }

    @Test
    fun startThenImmediateStop_neverLeavesATokenOrPlayerBehind() = runTest(dispatcher) {
        val r = rig()
        r.vm.startAb()
        r.vm.stopAb() // before content/plan finished loading
        settle()
        assertThat(r.bypass.openCount).isEqualTo(0)
        assertThat(r.backend.created).isEqualTo(0)
    }

    @Test
    fun clipFailure_releasesTheTokenAndPlayer_andRetryReacquiresIt() = runTest(dispatcher) {
        val r = rig()
        settle()
        r.backend.failClips = true
        r.vm.startAb()
        settle()
        assertThat(r.vm.ab.value.status).isEqualTo(AbStatus.ERROR)
        assertThat(r.bypass.openCount).isEqualTo(0)
        assertThat(r.bypass.bypassed.value).isFalse()
        assertThat(r.fake.released).isTrue()

        r.backend.failClips = false
        r.vm.retryAb()
        settle()
        assertThat(r.vm.ab.value.status).isEqualTo(AbStatus.READY)
        assertThat(r.bypass.openCount).isEqualTo(1)
        r.vm.stopAb()
        assertThat(r.bypass.openCount).isEqualTo(0)
    }

    @Test
    fun seedColour_usesTheSharedRawHlFunction_soItAgreesWithTheAppLevelSync() = runTest(dispatcher) {
        val r = rig()
        settle()
        val c = contentOf(r)
        val expected = my.umarh.paudio.designsystem.ProfileSeedColor
            .fromRawHl(c.hlLeft.toList(), c.hlRight.toList(), c.frequenciesHz).toArgb()
        assertThat(r.profiles.seedWrites.single().color).isEqualTo(expected)
    }

    @Test
    fun seedColour_appliedForTheLatestTest_unlessUserOverrode() = runTest(dispatcher) {
        val r = rig()
        settle()
        assertThat(r.profiles.seedWrites).hasSize(1)
        assertThat(r.profiles.seedWrites[0].profileId).isEqualTo(1)
        assertThat(r.profiles.seedWrites[0].overridden).isFalse()
        assertThat(r.profiles.seedWrites[0].color).isNotNull()

        val overridden = rig(profile = profileOf(overridden = true))
        settle()
        assertThat(overridden.profiles.seedWrites).isEmpty()

        // Opening an older test must not recolour the profile from stale data.
        val older = rig(tests = listOf(test(1, startedAt = 1_000), test(2, startedAt = 2_000)), th = mapOf(1L to thresholds(1), 2L to thresholds(2)), testId = 1)
        settle()
        assertThat(older.profiles.seedWrites).isEmpty()
    }

    @Test
    fun unavailableStates() = runTest(dispatcher) {
        val missing = rig(tests = emptyList(), testId = 7)
        val incomplete = rig(th = mapOf(1L to thresholds(1).drop(2)))
        val noProfile = rig(profile = null)
        settle()
        assertThat(missing.vm.ui.value).isEqualTo(ResultsUiState.Unavailable(UnavailableReason.TEST_MISSING))
        assertThat(incomplete.vm.ui.value).isEqualTo(ResultsUiState.Unavailable(UnavailableReason.INCOMPLETE))
        assertThat(noProfile.vm.ui.value).isEqualTo(ResultsUiState.Unavailable(UnavailableReason.PROFILE_MISSING))
        // Unavailable results never create a player or hold the bypass.
        missing.vm.startAb()
        settle()
        assertThat(missing.backend.created).isEqualTo(0)
        assertThat(missing.bypass.openCount).isEqualTo(0)
    }
}
