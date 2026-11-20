package my.umarh.paudio.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import my.umarh.paudio.audio.effect.EffectEngine
import my.umarh.paudio.audio.effect.EngineMode
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.HeadsetKey
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.OutputDeviceType
import my.umarh.paudio.data.db.HeadsetEntity
import my.umarh.paudio.data.db.HearingTestEntity
import my.umarh.paudio.data.db.ListenerEntity
import my.umarh.paudio.data.db.ProfileEntity
import my.umarh.paudio.data.db.ThresholdEntity
import my.umarh.paudio.data.db.TrialEntity
import my.umarh.paudio.data.repo.HearingTestRepository
import my.umarh.paudio.data.repo.ListenerProfiles
import my.umarh.paudio.data.repo.ProfileRepository
import my.umarh.paudio.data.repo.ProfileSettings
import my.umarh.paudio.data.repo.ProfileSlot
import my.umarh.paudio.data.repo.ProfileWithHeadset
import my.umarh.paudio.data.repo.ProfileWriteResult
import my.umarh.paudio.data.settings.SettingsRepository
import my.umarh.paudio.core.model.FrequencyGrid
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.service.engine.ActiveProfileResolver
import my.umarh.paudio.service.engine.EngineController
import my.umarh.paudio.service.engine.ProfileCurveService
import my.umarh.paudio.testing.FakeEffectBackend
import my.umarh.paudio.testing.FakeOutputDevices
import my.umarh.paudio.testing.FakeSessionSource
import my.umarh.paudio.data.settings.EngineMode as StoredMode

class FakeSettings : SettingsRepository {
    val listener = MutableStateFlow<Long?>(1L)
    val map = MutableStateFlow<Map<String, Long>>(emptyMap())
    val mode = MutableStateFlow(StoredMode.SESSION)
    val boot = MutableStateFlow(false)
    val onboarding = MutableStateFlow(true)
    override val activeListenerId: Flow<Long?> = listener
    override val headsetProfiles: Flow<Map<String, Long>> = map
    override val engineMode: Flow<StoredMode> = mode
    override val startOnBoot: Flow<Boolean> = boot
    override val onboardingDone: Flow<Boolean> = onboarding
    override val defaultCompStrength: Flow<Float> = MutableStateFlow(0.5f)
    override val personalizationEnabled: Flow<Boolean> = MutableStateFlow(true)
    override suspend fun setActiveListenerId(id: Long?) { listener.value = id }
    override suspend fun setHeadsetProfile(headsetKey: String, profileId: Long?) {
        map.value = if (profileId == null) map.value - headsetKey else map.value + (headsetKey to profileId)
    }
    override suspend fun removeProfile(profileId: Long) { map.value = map.value.filterValues { it != profileId } }
    override suspend fun setEngineMode(mode: StoredMode) { this.mode.value = mode }
    override suspend fun setStartOnBoot(enabled: Boolean) { boot.value = enabled }
    override suspend fun setOnboardingDone(done: Boolean) { onboarding.value = done }
    override suspend fun setDefaultCompStrength(strength: Float) {}
    override suspend fun setPersonalizationEnabled(enabled: Boolean) {}
}

class FakeProfiles : ProfileRepository {
    val listeners = MutableStateFlow<List<ListenerProfiles>>(emptyList())

    fun profile(id: Long): ProfileEntity? =
        listeners.value.flatMap { it.profiles }.firstOrNull { it.profile.id == id }?.profile

    override fun observeAll(): Flow<List<ListenerProfiles>> = listeners
    override fun observe(profileId: Long): Flow<ProfileWithHeadset?> = listeners.map { l ->
        l.flatMap { it.profiles }.firstOrNull { it.profile.id == profileId }
    }
    override suspend fun get(profileId: Long): ProfileWithHeadset? =
        listeners.value.flatMap { it.profiles }.firstOrNull { it.profile.id == profileId }

    var setPersonalizationCalls = 0
    override suspend fun setPersonalization(profileId: Long, on: Boolean) {
        setPersonalizationCalls++
        val p = profile(profileId) ?: return
        updateSettings(profileId, ProfileSettings(p.compStrength, p.gB, p.gP, p.gT, on))
    }

    override suspend fun updateSettings(profileId: Long, settings: ProfileSettings) {
        listeners.value = listeners.value.map { lp ->
            lp.copy(
                profiles = lp.profiles.map { pw ->
                    if (pw.profile.id != profileId) pw else pw.copy(
                        profile = pw.profile.copy(
                            compStrength = settings.compStrength, gB = settings.gB, gP = settings.gP, gT = settings.gT,
                            personalizationOn = settings.personalizationOn,
                        ),
                    )
                },
            )
        }
    }

    override suspend fun create(listenerId: Long, headsetId: Long?, name: String, settings: ProfileSettings): ProfileWriteResult = TODO()
    override suspend fun rename(profileId: Long, name: String) = TODO()
    override suspend fun setSeedColor(profileId: Long, seedColor: Int?, overridden: Boolean) = TODO()
    override suspend fun delete(profileId: Long) = TODO()
    override suspend fun duplicate(profileId: Long, newName: String, target: ProfileSlot): ProfileWriteResult = TODO()
    override suspend fun moveToHeadset(profileId: Long, headsetId: Long?): ProfileWriteResult = TODO()
    override suspend fun ensureAnyHeadphonesProfile(listenerId: Long): Long = TODO()
    override suspend fun resolveForHeadset(listenerId: Long, headsetKey: HeadsetKey): ProfileEntity? = TODO()
}

/** Tests keyed by profile id; [gates] lets a test hold back a profile's data (to model a slow DB read). */
class FakeTests : HearingTestRepository {
    private val latest = HashMap<Long, MutableStateFlow<HearingTestEntity?>>()
    private val all = MutableStateFlow<List<HearingTestEntity>>(emptyList())
    private val thresholds = HashMap<Long, List<ThresholdEntity>>()
    val gates = HashMap<Long, CompletableDeferred<Unit>>()

    private fun stateFor(profileId: Long) = latest.getOrPut(profileId) { MutableStateFlow(null) }

    fun put(test: HearingTestEntity, th: List<ThresholdEntity>) {
        thresholds[test.id] = th
        all.value = all.value.filter { it.id != test.id } + test
        stateFor(test.profileId).value = test
    }

    override fun observeLatest(profileId: Long): Flow<HearingTestEntity?> = flow {
        gates[profileId]?.await()
        emitAll(stateFor(profileId))
    }
    override fun observeThresholds(testId: Long): Flow<List<ThresholdEntity>> = flow { emit(thresholds[testId].orEmpty()) }
    override suspend fun latest(profileId: Long): HearingTestEntity? = stateFor(profileId).value
    override suspend fun get(testId: Long): HearingTestEntity? = latest.values.firstNotNullOfOrNull { it.value?.takeIf { t -> t.id == testId } }
    override suspend fun thresholds(testId: Long): List<ThresholdEntity> = thresholds[testId].orEmpty()
    override suspend fun save(test: HearingTestEntity, thresholds: List<ThresholdEntity>, trials: List<TrialEntity>, headsetKey: HeadsetKey?): Long = TODO()
    override fun observeForProfile(profileId: Long): Flow<List<HearingTestEntity>> = flow {
        gates[profileId]?.await()
        emitAll(all.map { l -> l.filter { it.profileId == profileId } })
    }
    override suspend fun trials(testId: Long): List<TrialEntity> = TODO()
    override suspend fun delete(testId: Long) = TODO()
    override fun observeForTrend(profileId: Long, headsetKey: HeadsetKey): Flow<List<HearingTestEntity>> = TODO()
}

class FakeLauncher : ServiceLauncher {
    var starts = 0
    var stops = 0
    var failWith: RuntimeException? = null
    override fun start() {
        failWith?.let { throw it }
        starts++
    }
    override fun stop() { stops++ }
}

class FakeFlags(override var userTurnedOff: Boolean = false) : ServiceFlags {
    override var ignoredHeadsetKeys: Set<String> = emptySet()
}

class FakeSink : HeadsetPromptSink, BootFallback {
    val shown = mutableListOf<Pair<String, Long?>>()
    var cleared = 0
    var tapToStart = 0
    override fun showUnknown(headsetKey: String, anyHeadphones: ProfileEntity?) { shown += headsetKey to anyHeadphones?.id }
    override fun clear() { cleared++ }
    override fun postTapToStart() { tapToStart++ }
}

val BT = FakeOutputDevices.BLUETOOTH_BUDS
val WIRED = FakeOutputDevices.WIRED_HEADPHONES
val SPEAKER = FakeOutputDevices.SPEAKER

fun headset(id: Long, device: OutputDevice) = HeadsetEntity(
    id, device.headsetKey.value, device.productName, device.type, device.productName, device.addressSuffix, 0,
)

fun profile(id: Long, headsetId: Long?, name: String, strength: Float = 0.5f, gB: Float = 0f, on: Boolean = true) =
    ProfileEntity(
        id = id, listenerId = 1, headsetId = headsetId, name = name, compStrength = strength, gB = gB,
        personalizationOn = on, createdAt = 0, updatedAt = 0,
    )

fun listener(id: Long = 1) = ListenerEntity(id, "L$id", 0)

/** Complete 9x2 thresholds; [hfLoss] raises the levels (higher = worse) from 6 kHz up. */
fun thresholdsFor(testId: Long, hfLoss: Float): List<ThresholdEntity> =
    Ear.entries.flatMap { ear ->
        FrequencyGrid.TEST_FREQUENCIES_HZ.map { f ->
            ThresholdEntity(testId, ear, f, -60f + (if (f >= 6000) hfLoss else 0f), censored = false, lowConfidence = false, trials = 5)
        }
    }

fun testEntity(id: Long, profileId: Long) = HearingTestEntity(
    id = id, profileId = profileId, mode = TestMode.QUICK, startedAt = id, durationMs = 1, volIndex = 5, volMax = 15,
    lRef = -60f, deviceSnapshotJson = null, ancOn = false, appVersion = "t", algoVersion = 1, fpRate = 0f, reliable = true,
)

/**
 * Persisted state shared by two [Rig]s, to model "the process died and a fresh engine/controller started".
 */
class Store {
    val settings = FakeSettings()
    val profiles = FakeProfiles()
    val tests = FakeTests()
    val devices = FakeOutputDevices(BT)
    val flags = FakeFlags()

    /** L1 owns P1 (BT, strong HF loss) and P2 (wired, different settings) and P3 = Any headphones. */
    fun seedStandard() {
        val h1 = headset(1, BT)
        val h2 = headset(2, WIRED)
        profiles.listeners.value = listOf(
            ListenerProfiles(
                listener(),
                listOf(
                    ProfileWithHeadset(profile(1, 1, "Buds", strength = 0.5f), h1),
                    ProfileWithHeadset(profile(2, 2, "Wired", strength = 1.0f, gB = 4f), h2),
                    ProfileWithHeadset(profile(3, null, "Any"), null),
                ),
            ),
        )
        tests.put(testEntity(11, 1), thresholdsFor(11, 20f))
        tests.put(testEntity(12, 2), thresholdsFor(12, 5f))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class Rig(testScope: TestScope, val store: Store = Store(), val rate: Int = 48_000) {
    val backend = FakeEffectBackend()
    val session = FakeSessionSource()
    val legacy = FakeSessionSource()
    private val unconfined = testScope.backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScope.testScheduler)
    val scope: CoroutineScope = CoroutineScope(unconfined)
    val engine = EffectEngine(
        backend,
        { mode -> if (mode == EngineMode.SESSION) session else legacy },
        { false },
        scope,
        { testScope.testScheduler.currentTime },
    )
    val sink = FakeSink()
    val launcher = FakeLauncher()
    val curves = ProfileCurveService(store.tests)
    val resolver = ActiveProfileResolver(store.devices, store.settings, store.profiles, scope)
    val controller = EngineController(
        engine, resolver, curves, store.settings, store.profiles, launcher, store.flags,
        { rate }, { true }, sink, ServiceFaults(), scope,
    )

    /** What the curve service would produce for profile [id] right now (the expected backend config). */
    suspend fun expectedConfig(id: Long, ladder: my.umarh.paudio.core.dsp.DpLadderStep = my.umarh.paudio.core.dsp.DpLadderStep.MAIN) =
        curves.config(store.profiles.profile(id)!!, curves.loadSource(id), rate, ladder)
}
