package my.umarh.paudio

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.FrequencyGrid
import my.umarh.paudio.core.model.HeadsetKey
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.OutputDeviceType
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.data.db.HeadsetEntity
import my.umarh.paudio.data.db.HearingTestEntity
import my.umarh.paudio.data.db.ListenerEntity
import my.umarh.paudio.data.db.ProfileEntity
import my.umarh.paudio.data.db.ThresholdEntity
import my.umarh.paudio.data.db.TrialEntity
import my.umarh.paudio.data.repo.HearingTestRepository
import my.umarh.paudio.data.repo.ListenerProfiles
import my.umarh.paudio.data.repo.ProfileWithHeadset
import my.umarh.paudio.feature.profiles.ProfileMeasure
import my.umarh.paudio.service.engine.ActiveProfile
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** F25: the app-level seed-colour sync (`seedSync`) and the theme-profile pick (`pickThemeProfile`). */
@RunWith(RobolectricTestRunner::class)
class AppViewModelSeedSyncTest {
    private fun profile(id: Long, seed: Int? = null, overridden: Boolean = false) = ProfileEntity(
        id = id, listenerId = 1, headsetId = null, name = "p$id", seedColor = seed, seedOverridden = overridden,
        createdAt = 0, updatedAt = 0,
    )

    private fun test(id: Long, profileId: Long, startedAt: Long) = HearingTestEntity(
        id = id, profileId = profileId, mode = TestMode.QUICK, startedAt = startedAt,
        durationMs = 1, volIndex = 7, volMax = 15, lRef = -40f, deviceSnapshotJson = null, ancOn = null,
        appVersion = "t", algoVersion = 1, fpRate = null, reliable = true,
    )

    private fun complete(testId: Long, level: Float) = Ear.entries.flatMap { ear ->
        FrequencyGrid.TEST_FREQUENCIES_HZ.map { f ->
            ThresholdEntity(testId, ear, f, level, censored = false, lowConfidence = false, trials = 6)
        }
    }

    private open class FakeTests(
        val tests: List<HearingTestEntity>,
        val thresholds: Map<Long, List<ThresholdEntity>>,
    ) : HearingTestRepository {
        override suspend fun save(
            test: HearingTestEntity,
            thresholds: List<ThresholdEntity>,
            trials: List<TrialEntity>,
            headsetKey: HeadsetKey?,
        ): Long = TODO()
        override fun observeForProfile(profileId: Long): Flow<List<HearingTestEntity>> =
            flowOf(tests.filter { it.profileId == profileId })
        override fun observeLatest(profileId: Long): Flow<HearingTestEntity?> = TODO()
        override suspend fun latest(profileId: Long): HearingTestEntity? = TODO()
        override suspend fun get(testId: Long): HearingTestEntity? = TODO()
        override suspend fun thresholds(testId: Long): List<ThresholdEntity> = TODO()
        override fun observeThresholds(testId: Long): Flow<List<ThresholdEntity>> = flowOf(thresholds[testId].orEmpty())
        override suspend fun trials(testId: Long): List<TrialEntity> = TODO()
        override suspend fun delete(testId: Long): Unit = TODO()
        override fun observeForTrend(profileId: Long, headsetKey: HeadsetKey): Flow<List<HearingTestEntity>> = TODO()
    }

    @Test fun emitsSeedDerivedFromLatestCompleteTest() = runTest {
        val old = complete(1, -20f)
        val latest = complete(2, -60f)
        val repo = FakeTests(listOf(test(1, 7, 100), test(2, 7, 200)), mapOf(1L to old, 2L to latest))
        val expected = ProfileMeasure.seedColor(latest)
        assertThat(expected).isNotNull()
        val out = seedSync(flowOf(profile(7)), repo).toList()
        assertThat(out).containsExactly(7L to expected!!)
    }

    @Test fun nothingEmittedWhenStoredSeedIsCurrent() = runTest {
        val th = complete(1, -40f)
        val seed = ProfileMeasure.seedColor(th)!!
        val repo = FakeTests(listOf(test(1, 7, 100)), mapOf(1L to th))
        assertThat(seedSync(flowOf(profile(7, seed = seed)), repo).toList()).isEmpty()
    }

    @Test fun userPickedColourIsNeverOverwritten() = runTest {
        val repo = FakeTests(listOf(test(1, 7, 100)), mapOf(1L to complete(1, -40f)))
        assertThat(seedSync(flowOf(profile(7, seed = 0x123456, overridden = true)), repo).toList()).isEmpty()
    }

    @Test fun noProfileNoTestOrIncompleteTestEmitNothing() = runTest {
        val repo = FakeTests(listOf(test(1, 7, 100)), mapOf(1L to complete(1, -40f).drop(3)))
        assertThat(seedSync(flowOf(null), repo).toList()).isEmpty()
        assertThat(seedSync(flowOf(profile(8)), repo).toList()).isEmpty() // no tests for profile 8
        assertThat(seedSync(flowOf(profile(7)), repo).toList()).isEmpty() // incomplete thresholds
    }

    @Test fun observeFailureEndsTheFlowWithoutThrowing() = runTest {
        val repo = object : FakeTests(emptyList(), emptyMap()) {
            override fun observeForProfile(profileId: Long): Flow<List<HearingTestEntity>> = flow { error("db") }
        }
        assertThat(seedSync(flowOf(profile(7)), repo).toList()).isEmpty()
    }

    @Test fun themeProfilePrefersResolvedThenActiveListenersAnyProfile() {
        val listener = ListenerEntity(1, "A", 0)
        val any = ProfileWithHeadset(profile(1), null)
        val headset = HeadsetEntity(1, "k", "n", OutputDeviceType.WIRED, "", "", 0)
        val bound = ProfileWithHeadset(profile(2).copy(headsetId = 1), headset)
        val groups = listOf(ListenerProfiles(listener, listOf(bound, any)))
        assertThat(pickThemeProfile(ActiveProfile.Loading, 1, groups)!!.id).isEqualTo(1)
        assertThat(pickThemeProfile(ActiveProfile.Loading, 99, groups)!!.id).isEqualTo(1) // falls back to first group
        assertThat(pickThemeProfile(ActiveProfile.Loading, 1, emptyList())).isNull()
        val resolved = ActiveProfile.Resolved(OutputDevice(OutputDeviceType.WIRED, "x"), bound.profile, listener)
        assertThat(pickThemeProfile(resolved, 1, groups)!!.id).isEqualTo(2)
    }
}
