package my.umarh.paudio.audio.playback

import my.umarh.paudio.core.model.AudioSource
import my.umarh.paudio.core.preference.AbRoundGate
import my.umarh.paudio.core.preference.DemoClip
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What to hand to `AbPlayer.load`. */
data class ClipPlayback(val source: AudioSource, val loop: ClosedRange<Duration>?)

/** Maps [DemoClip] to a player source and loop region. */
object DemoClipSource {
    /** Preference rounds loop the first [AbRoundGate.LOOP_SECONDS] s of the (~30 s) clip. */
    val PREFERENCE_LOOP: ClosedRange<Duration> = Duration.ZERO..AbRoundGate.LOOP_SECONDS.seconds

    /** Results A/B: whole clip looped. */
    fun results(clip: DemoClip) = ClipPlayback(AudioSource.Asset(clip.assetPath), null)

    /** Preference A/B: 12 s loop region. */
    fun preference(clip: DemoClip) = ClipPlayback(AudioSource.Asset(clip.assetPath), PREFERENCE_LOOP)

    /** User-picked file (SAF content:// uri); region optional. */
    fun userFile(uri: String, loop: ClosedRange<Duration>? = null) = ClipPlayback(AudioSource.Uri(uri), loop)
}
