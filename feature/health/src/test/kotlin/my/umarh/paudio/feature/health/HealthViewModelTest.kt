package my.umarh.paudio.feature.health

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import my.umarh.paudio.core.model.HeadsetKey
import my.umarh.paudio.core.model.OutputDeviceType
import my.umarh.paudio.data.db.DeviceSnapshot
import my.umarh.paudio.data.db.HeadsetEntity
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HealthViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun listenerOf(id: Long) = ListenerEntity(id = id, name = "L$id", createdAt = 0)

    private fun profileOf(id: Long, listenerId: Long = 1, headsetId: Long? = null) =
        ProfileEntity(id = id, listenerId = listenerId, headsetId = headsetId, name = "P$id", createdAt = 0, updatedAt = 0)

    private fun headset(id: Long, key: String) = HeadsetEntity(
        id = id,
        key = key,
        displayName = key,
        type = OutputDeviceType.WIRED,
        productName = "",
        addressSuffix = "",
        createdAt = 0,
    )

    private class FakeProfiles(initial: List<ListenerProfiles>) : ProfileRepository {
        val groups = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<ListenerProfiles>> = groups
        override fun observe(profileId: Long): Flow<ProfileWithHeadset?> =
            groups.map { gs -> gs.flatMap { it.profiles }.firstOrNull { it.profile.id == profileId } }
        override suspend fun get(profileId: Long) = TODO()
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

    private class FakeSettings(initialActive: Long?) : SettingsRepository {
        val active = MutableStateFlow<Long?>(initialActive)
        override val activeListenerId: Flow<Long?> get() = active
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

    /** Mirrors RoomHearingTestRepository.observeForTrend, including the `?: currentKey` fallback to the profile's headset. */
    private class FakeTests(
        initial: List<HearingTestEntity>,
        val th: Map<Long, List<ThresholdEntity>>,
        private val currentKey: String? = null,
        private val failObserveForProfile: Boolean = false,
    ) : HearingTestRepository {
        val all = MutableStateFlow(initial)
        val trendKeys = mutableListOf<HeadsetKey>()
        override suspend fun save(test: HearingTestEntity, thresholds: List<ThresholdEntity>, trials: List<my.umarh.paudio.data.db.TrialEntity>, headsetKey: HeadsetKey?) = TODO()
        override fun observeForProfile(profileId: Long): Flow<List<HearingTestEntity>> =
            if (failObserveForProfile) {
                flow { throw IllegalStateException("boom") }
            } else {
                all.map { l -> l.filter { it.profileId == profileId } }
            }
        override fun observeLatest(profileId: Long) =
            all.map { l -> l.filter { it.profileId == profileId }.maxByOrNull { it.startedAt } }
        override suspend fun latest(profileId: Long) = all.value.filter { it.profileId == profileId }.maxByOrNull { it.startedAt }
        override suspend fun get(testId: Long) = all.value.firstOrNull { it.id == testId }
        override suspend fun thresholds(testId: Long) = th[testId].orEmpty()
        override fun observeThresholds(testId: Long) = flowOf(th[testId].orEmpty())
        override suspend fun trials(testId: Long) = emptyList<my.umarh.paudio.data.db.TrialEntity>()
        override suspend fun delete(testId: Long) = TODO()
        override fun observeForTrend(profileId: Long, headsetKey: HeadsetKey): Flow<List<HearingTestEntity>> {
            trendKeys += headsetKey
            return all.map { l ->
                l.filter { it.profileId == profileId && (DeviceSnapshot.headsetKeyOf(it.deviceSnapshotJson) ?: currentKey) == headsetKey.value }
                    .sortedByDescending { t -> t.startedAt }
            }
        }
    }

    private fun defaultProfiles() =
        FakeProfiles(listOf(ListenerProfiles(listenerOf(1), listOf(ProfileWithHeadset(profileOf(1), null)))))

    private fun twoListeners() = FakeProfiles(
        listOf(
            ListenerProfiles(listenerOf(1), listOf(ProfileWithHeadset(profileOf(1, listenerId = 1), null))),
            ListenerProfiles(listenerOf(2), listOf(ProfileWithHeadset(profileOf(2, listenerId = 2), null))),
        ),
    )

    private fun vm(
        route: HealthRoute,
        tests: FakeTests,
        profiles: FakeProfiles = defaultProfiles(),
        settings: FakeSettings = FakeSettings(1),
    ) = HealthViewModel(
        SavedStateHandle(
            buildMap {
                route.profileId?.let { put("profileId", it) }
                route.testId?.let { put("testId", it) }
            },
        ),
        profiles,
        tests,
        settings,
    )

    /** Thresholds are pre-populated for test ids 1..5 so that tests added later are complete. */
    private fun fakeTests(tests: List<HearingTestEntity>, currentKey: String? = null) =
        FakeTests(tests, (1L..5L).associateWith { thresholds(it) } + (99L to thresholds(99)), currentKey)

    private fun TestScope.settle() {
        // advanceUntilIdle ignores background work (the collector lives in backgroundScope), so also runCurrent.
        repeat(3) {
            advanceUntilIdle()
            runCurrent()
        }
    }

    private fun TestScope.collect(vm: HealthViewModel): MutableStateFlow<HealthUiState> {
        val out = MutableStateFlow<HealthUiState>(HealthUiState.Loading)
        backgroundScope.launch { vm.uiState.collect { out.value = it } }
        return out
    }

    @Test
    fun noTests_isEmpty() = runTest(dispatcher) {
        val out = collect(vm(HealthRoute(), FakeTests(emptyList(), emptyMap())))
        settle()
        assertThat(out.value).isEqualTo(HealthUiState.Empty)
    }

    @Test
    fun trendOnlyIncludesTestsFromTheSameHeadset_andFallbackProfileIsMostRecentlyTested() = runTest(dispatcher) {
        val tests = listOf(test(1, headsetKey = "A"), test(2, headsetKey = "B"), test(3, headsetKey = "A"))
        val fake = fakeTests(tests)
        val out = collect(vm(HealthRoute(), fake)) // no profileId: resolves to the profile with the latest test
        settle()
        val c = (out.value as HealthUiState.Content).content
        assertThat(c.testId).isEqualTo(3)
        assertThat(fake.trendKeys).containsExactly(HeadsetKey("A"))
        assertThat(c.trend.map { it.dateMillis }).containsExactly(tests[0].startedAt, tests[2].startedAt).inOrder()
        assertThat(c.showTrend).isTrue()
    }

    @Test
    fun selectedTestId_isHonoured() = runTest(dispatcher) {
        val fake = fakeTests(listOf(test(1), test(2)))
        val out = collect(vm(HealthRoute(profileId = 1, testId = 1), fake))
        settle()
        assertThat((out.value as HealthUiState.Content).content.testId).isEqualTo(1)
    }

    @Test
    fun noSnapshotKey_andProfileWithoutHeadset_hasNoTrend_neverMixingHeadsets() = runTest(dispatcher) {
        val fake = fakeTests(listOf(test(1, headsetKey = null), test(2, headsetKey = null), test(3, headsetKey = "A")))
        val out = collect(vm(HealthRoute(profileId = 1, testId = 2), fake))
        settle()
        val c = (out.value as HealthUiState.Content).content
        assertThat(c.testId).isEqualTo(2)
        assertThat(fake.trendKeys).isEmpty()
        assertThat(c.trend).hasSize(1)
        assertThat(c.showTrend).isFalse()
    }

    @Test
    fun missingSnapshotKey_fallsBackToProfileHeadsetKey() = runTest(dispatcher) {
        val profiles = FakeProfiles(
            listOf(ListenerProfiles(listenerOf(1), listOf(ProfileWithHeadset(profileOf(1, headsetId = 7), headset(7, "H"))))),
        )
        val fake = fakeTests(
            listOf(test(1, headsetKey = null), test(2, headsetKey = null), test(3, headsetKey = "A")),
            currentKey = "H",
        )
        val out = collect(vm(HealthRoute(profileId = 1, testId = 2), fake, profiles))
        settle()
        val c = (out.value as HealthUiState.Content).content
        assertThat(fake.trendKeys).containsExactly(HeadsetKey("H"))
        assertThat(c.trend).hasSize(2)
        assertThat(c.showTrend).isTrue()
    }

    @Test
    fun foreignTestId_isUnavailable_andShowLatestSwitchesToLatest() = runTest(dispatcher) {
        // Test 99 exists, but belongs to profile 2, not the requested profile 1.
        val fake = fakeTests(listOf(test(1), test(2), test(99, profileId = 2)))
        val model = vm(HealthRoute(profileId = 1, testId = 99), fake)
        val out = collect(model)
        settle()
        assertThat(out.value).isEqualTo(HealthUiState.Unavailable(UnavailableReason.TEST_MISSING, canShowLatest = true))
        model.showLatest()
        settle()
        assertThat((out.value as HealthUiState.Content).content.testId).isEqualTo(2)
    }

    @Test
    fun deletedProfile_isUnavailable() = runTest(dispatcher) {
        val out = collect(vm(HealthRoute(profileId = 42), fakeTests(listOf(test(1)))))
        settle()
        assertThat(out.value).isEqualTo(HealthUiState.Unavailable(UnavailableReason.PROFILE_MISSING))
    }

    @Test
    fun profileDeletedWhileShowing_becomesUnavailable() = runTest(dispatcher) {
        val profiles = defaultProfiles()
        val out = collect(vm(HealthRoute(profileId = 1), fakeTests(listOf(test(1))), profiles))
        settle()
        assertThat(out.value).isInstanceOf(HealthUiState.Content::class.java)
        profiles.groups.value = emptyList()
        settle()
        assertThat(out.value).isEqualTo(HealthUiState.Unavailable(UnavailableReason.PROFILE_MISSING))
    }

    @Test
    fun incompleteThresholds_areUnavailable_notEmpty() = runTest(dispatcher) {
        val fake = FakeTests(listOf(test(1)), emptyMap())
        val out = collect(vm(HealthRoute(profileId = 1), fake))
        settle()
        assertThat(out.value).isEqualTo(HealthUiState.Unavailable(UnavailableReason.INCOMPLETE, canShowLatest = false))
    }

    @Test
    fun repositoryError_isUnavailable_notACrash() = runTest(dispatcher) {
        val fake = FakeTests(listOf(test(1)), emptyMap(), failObserveForProfile = true)
        val out = collect(vm(HealthRoute(profileId = 1), fake))
        settle()
        assertThat(out.value).isEqualTo(HealthUiState.Unavailable(UnavailableReason.ERROR))
    }

    @Test
    fun resolveProfile_prefersActiveListener_evenWhenOtherHasNewerTest() = runTest(dispatcher) {
        // Profile 1 (listener 1) was tested earlier than profile 2 (listener 2).
        val tests = listOf(test(1, profileId = 1, startedAt = 1_000), test(2, profileId = 2, startedAt = 2_000))
        val first = collect(vm(HealthRoute(), fakeTests(tests), twoListeners(), FakeSettings(1)))
        val second = collect(vm(HealthRoute(), fakeTests(tests), twoListeners(), FakeSettings(2)))
        settle()
        assertThat((first.value as HealthUiState.Content).content.profileId).isEqualTo(1)
        assertThat((second.value as HealthUiState.Content).content.profileId).isEqualTo(2)
    }

    @Test
    fun resolveProfile_fallsBackToOtherListener_whenActiveHasNoTests() = runTest(dispatcher) {
        val fake = fakeTests(listOf(test(2, profileId = 2)))
        val out = collect(vm(HealthRoute(), fake, twoListeners(), FakeSettings(1)))
        settle()
        assertThat((out.value as HealthUiState.Content).content.profileId).isEqualTo(2)
    }

    @Test
    fun withoutProfileId_followsNewTestsAndActiveListenerChanges() = runTest(dispatcher) {
        val fake = fakeTests(listOf(test(1, profileId = 1, startedAt = 1_000)))
        val settings = FakeSettings(1)
        val out = collect(vm(HealthRoute(), fake, twoListeners(), settings))
        settle()
        assertThat((out.value as HealthUiState.Content).content.testId).isEqualTo(1)
        // A newer test for the other listener's profile: the active listener still wins.
        fake.all.value += test(2, profileId = 2, startedAt = 2_000)
        settle()
        assertThat((out.value as HealthUiState.Content).content.profileId).isEqualTo(1)
        // A newer test for the active listener's profile is picked up without recreating the view model.
        fake.all.value += test(3, profileId = 1, startedAt = 3_000)
        settle()
        assertThat((out.value as HealthUiState.Content).content.testId).isEqualTo(3)
        settings.active.value = 2
        settle()
        assertThat((out.value as HealthUiState.Content).content.profileId).isEqualTo(2)
    }
}
