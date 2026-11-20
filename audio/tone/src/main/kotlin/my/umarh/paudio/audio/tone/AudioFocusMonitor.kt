package my.umarh.paudio.audio.tone

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Audio focus changes relevant to a running test (calls, other media). */
enum class FocusEvent {
    GAINED,

    /** Another app took focus for good. */
    LOST,

    /** Temporary loss (e.g. an incoming call); the test must pause. */
    LOST_TRANSIENT,

    /** Others may duck; for a hearing test this is as bad as a loss. */
    LOST_CAN_DUCK,
    ;

    companion object {
        fun from(change: Int): FocusEvent? = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> GAINED
            AudioManager.AUDIOFOCUS_LOSS -> LOST
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> LOST_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> LOST_CAN_DUCK
            else -> null
        }
    }
}

/**
 * Holds transient audio focus for test / A-B playback and reports changes via [events].
 * [request] returns whether focus was granted; [abandon] is idempotent (call it from finally).
 */
@Singleton
class AudioFocusMonitor @Inject constructor(@ApplicationContext context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _events = MutableSharedFlow<FocusEvent>(extraBufferCapacity = 16)
    private val lock = Any()
    private var request: AudioFocusRequest? = null

    private val _latest = MutableStateFlow<FocusEvent?>(null)

    /** Live stream of focus changes (no replay). */
    val events: SharedFlow<FocusEvent> = _events

    /** The most recent focus change since [request], so a change before collection is not lost. */
    val latest: StateFlow<FocusEvent?> = _latest

    private fun publish(e: FocusEvent) {
        _latest.value = e
        _events.tryEmit(e)
    }

    fun request(exclusive: Boolean = true): Boolean = synchronized(lock) {
        if (request != null) return true
        _latest.value = null
        val gain = if (exclusive) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE else AudioManager.AUDIOFOCUS_GAIN
        val r = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(AudioTrackSink.MEDIA_ATTRIBUTES)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(
                { c -> FocusEvent.from(c)?.let(::publish) },
                Handler(Looper.getMainLooper()),
            )
            .build()
        val granted = am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) request = r
        granted
    }

    fun abandon() {
        val r = synchronized(lock) { request.also { request = null } } ?: return
        am.abandonAudioFocusRequest(r)
    }
}
