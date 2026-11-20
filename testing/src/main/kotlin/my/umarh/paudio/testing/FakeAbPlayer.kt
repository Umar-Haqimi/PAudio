package my.umarh.paudio.testing

import my.umarh.paudio.core.model.AbPlayer
import my.umarh.paudio.core.model.AudioSource
import my.umarh.paudio.core.model.EqConfig
import kotlin.time.Duration

/** Records everything the A/B player is asked to do; no audio. */
class FakeAbPlayer : AbPlayer {
    data class Swap(val cfg: EqConfig, val inputGainDb: Float)

    var source: AudioSource? = null
        private set
    var loop: ClosedRange<Duration>? = null
        private set
    val swaps: MutableList<Swap> = mutableListOf()

    var isPlaying: Boolean = false
        private set
    var released: Boolean = false
        private set

    val lastSwap: Swap? get() = swaps.lastOrNull()

    override suspend fun load(src: AudioSource, loop: ClosedRange<Duration>?) {
        check(!released) { "released" }
        source = src
        this.loop = loop
    }

    override fun swapTo(cfg: EqConfig, inputGainDb: Float) {
        check(!released) { "released" }
        swaps += Swap(cfg, inputGainDb)
    }

    override fun play() {
        check(!released) { "released" }
        check(source != null) { "nothing loaded" }
        isPlaying = true
    }

    override fun pause() {
        isPlaying = false
    }

    override fun release() {
        released = true
        isPlaying = false
    }
}
