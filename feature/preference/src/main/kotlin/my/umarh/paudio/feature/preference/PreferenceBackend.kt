package my.umarh.paudio.feature.preference

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import my.umarh.paudio.audio.playback.AbPlayerFactory
import my.umarh.paudio.audio.playback.ListenTick
import my.umarh.paudio.audio.playback.LtasRepository
import my.umarh.paudio.core.dsp.LoudnessPredictor
import my.umarh.paudio.core.model.AbPlayer
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.core.model.FrequencyGrid
import my.umarh.paudio.core.preference.AbSide
import my.umarh.paudio.core.preference.DemoClip
import my.umarh.paudio.data.db.PreferenceTestEntity
import my.umarh.paudio.data.repo.PreferenceTestRepository
import my.umarh.paudio.data.repo.ProfileRepository
import my.umarh.paudio.data.repo.ProfileSettings
import my.umarh.paudio.data.settings.SettingsRepository
import my.umarh.paudio.service.engine.ActiveProfile
import my.umarh.paudio.service.engine.ActiveProfileResolver
import my.umarh.paudio.service.engine.ProfileCurveService

/**
 * An A/B player plus its optional real state. [swap] switches the audible option (the real player also records which
 * A/B [AbSide] becomes active so [ticks] are attributed correctly); [playing] / [ticks] are null for players that do
 * not report them (test doubles drive the gate through their own flows).
 */
class PrefAudio(
    val player: AbPlayer,
    val playing: StateFlow<Boolean>? = null,
    val ticks: Flow<ListenTick>? = null,
    val swap: (EqConfig, Float, AbSide?) -> Unit = { cfg, gain, _ -> player.swapTo(cfg, gain) },
)

/** Everything the preference test needs from the audio layer, behind one seam so the ViewModel is JVM-testable. */
interface PreferenceAudioBackend {
    /** Create on the main thread; the caller owns [AbPlayer.release]. */
    fun createPlayer(): PrefAudio

    /** Real mixer rate; the DP band layout is planned for it (never assume 48 kHz). */
    fun outputSampleRate(): Int

    suspend fun predictor(clip: DemoClip): LoudnessPredictor
}

/** Hearing-compensation baseline of the profile the test runs for (SPEC 2.1.3: HL of its latest complete test). */
class PreferenceBaseline(
    val profileId: Long,
    /** HL_est per test frequency; all zeros when the profile has no complete test ([hasTest] = false). */
    val hlLeft: DoubleArray,
    val hlRight: DoubleArray,
    val hasTest: Boolean,
    val strength: Double,
    val gB: Double,
    val gP: Double,
    val gT: Double,
    val personalizationOn: Boolean,
)

interface PreferenceData {
    /** [profileId] null = the active listener's profile for the current headset. Null result = no profile available. */
    suspend fun load(profileId: Long?): PreferenceBaseline?

    suspend fun save(profileId: Long, startedAt: Long, test: Triple<Float, Float, Float>, roundsJson: String, settings: ProfileSettings)

    fun nowMs(): Long

    fun random(): Random
}

internal class AndroidPreferenceAudioBackend @Inject constructor(
    private val factory: AbPlayerFactory,
    private val ltas: LtasRepository,
) : PreferenceAudioBackend {
    override fun createPlayer(): PrefAudio {
        val p = factory.create(BypassReason.PREFERENCE_AB)
        return PrefAudio(p, p.playing, p.listenTicks) { cfg, gain, side -> p.swapTo(cfg, gain, side) }
    }

    override fun outputSampleRate(): Int = factory.outputSampleRate()

    override suspend fun predictor(clip: DemoClip): LoudnessPredictor = LoudnessPredictor.fromLtas(ltas.forClip(clip))
}

internal class AndroidPreferenceData @Inject constructor(
    private val profiles: ProfileRepository,
    private val prefTests: PreferenceTestRepository,
    private val curves: ProfileCurveService,
    private val resolver: ActiveProfileResolver,
    private val settings: SettingsRepository,
) : PreferenceData {
    override suspend fun load(profileId: Long?): PreferenceBaseline? {
        val id = profileId ?: resolveActiveProfileId() ?: return null
        val profile = profiles.get(id)?.profile ?: return null
        val source = curves.loadSource(id)
        val n = FrequencyGrid.TEST_FREQUENCIES_HZ.size
        return PreferenceBaseline(
            profileId = id,
            hlLeft = source?.hlLeft?.toDoubleArray() ?: DoubleArray(n),
            hlRight = source?.hlRight?.toDoubleArray() ?: DoubleArray(n),
            hasTest = source != null,
            strength = profile.compStrength.toDouble().coerceIn(0.0, 1.0),
            gB = profile.gB.toDouble(),
            gP = profile.gP.toDouble(),
            gT = profile.gT.toDouble(),
            personalizationOn = profile.personalizationOn,
        )
    }

    /** Active listener's profile for the current headset; falls back to that listener's "Any headphones" profile. */
    private suspend fun resolveActiveProfileId(): Long? {
        val active = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolver.activeProfile.first { it !is ActiveProfile.Loading } }
        when (active) {
            is ActiveProfile.Resolved -> return active.profile.id
            is ActiveProfile.NoProfile -> active.anyHeadphones?.let { return it.id }
            else -> Unit
        }
        val listenerId = settings.activeListenerId.first()
            ?: profiles.observeAll().first().firstOrNull()?.listener?.id
            ?: return null
        return profiles.ensureAnyHeadphonesProfile(listenerId)
    }

    override suspend fun save(
        profileId: Long,
        startedAt: Long,
        test: Triple<Float, Float, Float>,
        roundsJson: String,
        settings: ProfileSettings,
    ) {
        prefTests.save(
            PreferenceTestEntity(
                profileId = profileId,
                startedAt = startedAt,
                gB = test.first,
                gP = test.second,
                gT = test.third,
                roundsJson = roundsJson,
            ),
        )
        profiles.updateSettings(profileId, settings)
    }

    override fun nowMs(): Long = System.currentTimeMillis()

    override fun random(): Random = Random.Default

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 3_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PreferenceModule {
    @Binds abstract fun audio(impl: AndroidPreferenceAudioBackend): PreferenceAudioBackend

    @Binds abstract fun data(impl: AndroidPreferenceData): PreferenceData
}
