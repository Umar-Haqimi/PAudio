package my.umarh.paudio.audio.playback

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import my.umarh.paudio.core.model.BypassController
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.EffectBackend
import javax.inject.Inject

/**
 * Hilt-injectable factory (unscoped): each screen creates its own [ExoAbPlayer] and releases it.
 * Requires the app module to bind [EffectBackend] and [BypassController].
 */
class AbPlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val effectBackend: EffectBackend,
    private val bypass: BypassController,
) {
    /** Create on the main thread. [reason] is [BypassReason.RESULTS_AB] or [BypassReason.PREFERENCE_AB]. */
    fun create(reason: BypassReason): ExoAbPlayer = ExoAbPlayer(context, effectBackend, bypass, reason)
}
