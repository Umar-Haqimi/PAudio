package my.umarh.paudio.testing

import my.umarh.paudio.core.model.StereoBuffer
import my.umarh.paudio.core.model.ToneOutput
import java.util.concurrent.CopyOnWriteArrayList

/** Records everything played. No real audio, no delays. */
class FakeToneOutput : ToneOutput {
    sealed interface Event {
        data class Opened(val sampleRate: Int) : Event
        data class Played(val buffer: StereoBuffer) : Event
        data class Silence(val ms: Int) : Event
        data object Closed : Event
    }

    val events: MutableList<Event> = CopyOnWriteArrayList()

    @Volatile var isOpen: Boolean = false
        private set
    @Volatile var sampleRate: Int = 0
        private set

    val played: List<StereoBuffer> get() = events.filterIsInstance<Event.Played>().map { it.buffer }
    val silenceMs: Int get() = events.filterIsInstance<Event.Silence>().sumOf { it.ms }

    /** Optional hook, invoked on every [play]; e.g. let a test assert on other fakes' state during playback. */
    var onPlay: (StereoBuffer) -> Unit = {}

    override suspend fun open(sampleRate: Int) {
        this.sampleRate = sampleRate
        isOpen = true
        events += Event.Opened(sampleRate)
    }

    override suspend fun play(stimulus: StereoBuffer) {
        check(isOpen) { "ToneOutput not open" }
        events += Event.Played(stimulus)
        onPlay(stimulus)
    }

    override suspend fun playSilence(ms: Int) {
        check(isOpen) { "ToneOutput not open" }
        events += Event.Silence(ms)
    }

    override fun close() {
        isOpen = false
        events += Event.Closed
    }
}
