package my.umarh.paudio.feature.health

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import my.umarh.paudio.core.model.HeadsetKey
import my.umarh.paudio.data.db.DeviceSnapshot
import my.umarh.paudio.data.db.HearingTestEntity
import my.umarh.paudio.data.db.ListenerEntity
import my.umarh.paudio.data.db.ProfileEntity
import my.umarh.paudio.data.db.ThresholdEntity
import my.umarh.paudio.data.repo.HearingTestRepository
import my.umarh.paudio.data.repo.ListenerProfiles
import my.umarh.paudio.data.repo.ProfileRepository
import my.umarh.paudio.data.repo.ProfileWithHeadset
import my.umarh.paudio.data.settings.EngineMode
import my.umarh.paudio.data.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HealthViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val profile = ProfileEntity(id = 1, listenerId = 1, headsetId = null, name = "Me", createdAt = 0, updatedAt = 0)
    private val listener = ListenerEntity(id = 1, name = "Me", createdAt = 0)

    private val profiles = object : ProfileRepository {
        override fun observeAll(): Flow<List<ListenerProfiles>> =
            flowOf(listOf(ListenerProfiles(listener, listOf(ProfileWithHeadset(profile, null)))))
        override fun observe(profileId: Long): Flow<ProfileWithHeadset?> = flowOf(ProfileWithHeadset(profile, null))
        override suspend fun get(profileId: Long) = ProfileWithHeadset(profile, null)
        override suspend fun create(listenerId: Long, headsetId: Long?, name: String, settings: my.umarh.paudio.data.repo.ProfileSettings) = TODO()
        override suspend fun rename(profileId: Long, name: String) = TODO()
        override suspend fun updateSettings(profileId: Long, settings: my.umarh.paudio.data.repo.ProfileSettings) = TODO()
        override suspend fun setSeedColor(profileId: Long, seedColor: Int?, overridden: Boolean) = TODO()
        override suspend fun delete(profileId: Long) = TODO()
        override suspend fun duplicate(profileId: Long, newName: String, target: my.umarh.paudio.data.repo.ProfileSlot) = TODO()
        override suspend fun moveToHeadset(profileId: Long, headsetId: Long?) = TODO()
        override suspend fun ensureAnyHeadphonesProfile(listenerId: Long) = TODO()
        override suspend fun resolveForHeadset(listenerId: Long, headsetKey: HeadsetKey) = TODO()
    }

    private val settings = object : SettingsRepository {
        override val activeListenerId = flowOf<Long?>(1)
        override val headsetProfiles = flowOf(emptyMap<String, Long>())
        override val engineMode = flowOf(EngineMode.SESSION)
        override val startOnBoot = flowOf(false)
        override val onboardingDone = flowOf(true)
        override val defaultCompStrength = flowOf(0.5f)
        override val personalizationEnabled = flowOf(true)
        override suspend fun setActiveListenerId(id: Long?) = TODO()
        override suspend fun setHeadsetProfile(headsetKey: String, profileId: Long?) = TODO()
        override suspend fun removeProfile(profileId: Long) = TODO()
        override suspend fun setEngineMode(mode: EngineMode) = TODO()
        override suspend fun setStartOnBoot(enabled: Boolean) = TODO()
        override suspend fun setOnboardingDone(done: Boolean) = TODO()
        override suspend fun setDefaultCompStrength(strength: Float) = TODO()
        override suspend fun setPersonalizationEnabled(enabled: Boolean) = TODO()
    }

    private class FakeTests(val all: List<HearingTestEntity>, val th: Map<Long, List<ThresholdEntity>>) : HearingTestRepository {
        val trendKeys = mutableListOf<HeadsetKey>()
        override suspend fun save(test: HearingTestEntity, thresholds: List<ThresholdEntity>, trials: List<my.umarh.paudio.data.db.TrialEntity>, headsetKey: HeadsetKey?) = TODO()
        override fun observeForProfile(profileId: Long) = flowOf(all.filter { it.profileId == profileId })
        override fun observeLatest(profileId: Long) = flowOf(all.maxByOrNull { it.startedAt })
        override suspend fun latest(profileId: Long) = all.filter { it.profileId == profileId }.maxByOrNull { it.startedAt }
        override suspend fun get(testId: Long) = all.firstOrNull { it.id == testId }
        override suspend fun thresholds(testId: Long) = th[testId].orEmpty()
        override fun observeThresholds(testId: Long) = flowOf(th[testId].orEmpty())
        override suspend fun trials(testId: Long) = emptyList<my.umarh.paudio.data.db.TrialEntity>()
        override suspend fun delete(testId: Long) = TODO()
        override fun observeForTrend(profileId: Long, headsetKey: HeadsetKey): Flow<List<HearingTestEntity>> {
            trendKeys += headsetKey
            return flowOf(all.filter { it.profileId == profileId && DeviceSnapshot.headsetKeyOf(it.deviceSnapshotJson) == headsetKey.value })
                .map { it.sortedByDescending { t -> t.startedAt } }
        }
    }

    private fun vm(route: HealthRoute, tests: FakeTests) = HealthViewModel(
        SavedStateHandle(buildMap {
            route.profileId?.let { put("profileId", it) }
            route.testId?.let { put("testId", it) }
        }),
        profiles,
        tests,
        settings,
    )

    private fun TestScope.collect(vm: HealthViewModel): MutableStateFlow<HealthUiState> {
        val out = MutableStateFlow<HealthUiState>(HealthUiState.Loading)
        backgroundScope.launch { vm.uiState.collect { out.value = it } }
        return out
    }

    @Test
    fun noTests_isEmpty() = runTest(dispatcher) {
        val out = collect(vm(HealthRoute(), FakeTests(emptyList(), emptyMap())))
        advanceUntilIdle()
        assertThat(out.value).isEqualTo(HealthUiState.Empty)
    }

    @Test
    fun trendOnlyIncludesTestsFromTheSameHeadset_andFallbackProfileIsMostRecentlyTested() = runTest(dispatcher) {
        val tests = listOf(
            test(1, headsetKey = "A"),
            test(2, headsetKey = "B"),
            test(3, headsetKey = "A"),
        )
        val fake = FakeTests(tests, tests.associate { it.id to thresholds(it.id) })
        val out = collect(vm(HealthRoute(), fake)) // no profileId: resolves to the profile with the latest test
        advanceUntilIdle()
        val c = (out.value as HealthUiState.Content).content
        assertThat(c.testId).isEqualTo(3)
        assertThat(fake.trendKeys).containsExactly(HeadsetKey("A"))
        assertThat(c.trend.map { it.dateMillis }).containsExactly(tests[0].startedAt, tests[2].startedAt).inOrder()
        assertThat(c.showTrend).isTrue()
    }

    @Test
    fun selectedTestId_isHonoured() = runTest(dispatcher) {
        val tests = listOf(test(1), test(2))
        val fake = FakeTests(tests, tests.associate { it.id to thresholds(it.id) })
        val out = collect(vm(HealthRoute(profileId = 1, testId = 1), fake))
        advanceUntilIdle()
        assertThat((out.value as HealthUiState.Content).content.testId).isEqualTo(1)
    }
}
