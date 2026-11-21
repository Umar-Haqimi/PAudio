package my.umarh.paudio.feature.results

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import my.umarh.paudio.audio.playback.AbPlayerFactory
import my.umarh.paudio.audio.playback.LtasRepository
import my.umarh.paudio.core.dsp.LoudnessPredictor
import my.umarh.paudio.core.model.AbPlayer
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.preference.DemoClip

/** An A/B player plus (optionally) its real "audible" state; [playing] is null for players that do not report it. */
class AbHandle(val player: AbPlayer, val playing: StateFlow<Boolean>? = null)

/**
 * Everything the Results A/B needs from the audio layer, behind one seam so the ViewModel is JVM-testable.
 * The Android implementation wraps `AbPlayerFactory` (which acquires the P0 bypass token in `load`) and `LtasRepository`.
 */
interface ResultsAbBackend {
    /** Create on the main thread; the caller owns [AbPlayer.release]. */
    fun createPlayer(): AbHandle

    /** Real mixer rate; the DP band layout is planned for it (DECISIONS: never assume 48 kHz). */
    fun outputSampleRate(): Int

    /** Loudness predictor for [clip]'s LTAS, or pink noise (equal power per 1/3 octave) when [clip] is null (user file). */
    suspend fun predictor(clip: DemoClip?): LoudnessPredictor
}

internal class AndroidResultsAbBackend @Inject constructor(
    private val factory: AbPlayerFactory,
    private val ltas: LtasRepository,
) : ResultsAbBackend {
    override fun createPlayer(): AbHandle {
        val p = factory.create(BypassReason.RESULTS_AB)
        return AbHandle(p, p.playing)
    }

    override fun outputSampleRate(): Int = factory.outputSampleRate()

    override suspend fun predictor(clip: DemoClip?): LoudnessPredictor =
        LoudnessPredictor.fromLtas(if (clip != null) ltas.forClip(clip) else ltas.pink())
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ResultsModule {
    @Binds
    abstract fun abBackend(impl: AndroidResultsAbBackend): ResultsAbBackend
}
