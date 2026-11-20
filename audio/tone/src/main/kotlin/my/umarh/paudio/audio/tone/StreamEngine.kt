package my.umarh.paudio.audio.tone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import my.umarh.paudio.core.model.StereoBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The writer loop: one thread that writes [chunkFrames] frames at a time to [sink] for as long as the engine is
 * open: stimulus samples or digital silence as decided by [StreamScheduler]. Hardware-free, so it is unit
 * tested with a fake [PcmSink].
 *
 * [play] suspends until the playback head has passed the stimulus end (not merely until it is written).
 * Cancelling [play] ramps the stimulus down and leaves the stream in silence.
 */
class StreamEngine(
    private val sink: PcmSink,
    val sampleRate: Int,
    private val chunkFrames: Int = sampleRate / 100,
    rampMs: Int = 25,
    private val threadInit: () -> Unit = {},
) {
    private val scheduler = StreamScheduler(sampleRate, rampMs)
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val waiters = CopyOnWriteArrayList<Pair<Stimulus, CompletableDeferred<Unit>>>()
    private val _playing = MutableStateFlow(false)
    private var thread: Thread? = null

    /** True while a stimulus is audible (playback head inside it). Updated once per chunk (~10 ms). */
    val playing: StateFlow<Boolean> = _playing

    val isClosed: Boolean get() = closed.get()

    fun start() {
        check(!closed.get()) { "closed" }
        if (!started.compareAndSet(false, true)) return
        sink.start()
        thread = Thread({ loop() }, "paudio-tone-writer").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop() {
        threadInit()
        val buf = FloatArray(2 * chunkFrames)
        try {
            while (!closed.get()) {
                scheduler.fill(buf, chunkFrames)
                var off = 0
                while (off < chunkFrames && !closed.get()) {
                    val src = if (off == 0) buf else buf.copyOfRange(2 * off, 2 * chunkFrames)
                    val n = sink.write(src, chunkFrames - off)
                    if (n < 0) {
                        failAll(IllegalStateException("sink write error $n"))
                        return
                    }
                    off += n
                }
                poll(sink.playedFrames)
            }
        } finally {
            failAll(CancellationException("tone output closed"))
        }
    }

    private fun poll(head: Long) {
        var audible = false
        for (w in waiters) {
            val s = w.first
            val end = s.endFrame
            if (end >= 0 && head >= end) {
                waiters.remove(w)
                w.second.complete(Unit)
            } else if (s.startFrame in 0..head) {
                audible = true
            }
        }
        _playing.value = audible
    }

    private fun failAll(t: Throwable) {
        _playing.value = false
        for (w in waiters) {
            waiters.remove(w)
            w.second.completeExceptionally(t)
        }
    }

    /** Suspends until [stimulus] has been fully played out. Cancellation ramps it down to silence. */
    suspend fun play(stimulus: StereoBuffer) {
        check(started.get() && !closed.get()) { "ToneOutput not open" }
        val s = Stimulus(stimulus)
        val done = CompletableDeferred<Unit>()
        val entry = s to done
        waiters += entry
        scheduler.enqueue(s)
        try {
            done.await()
        } catch (e: CancellationException) {
            if (scheduler.cancel(s) && s.startFrame < 0) waiters.remove(entry)
            throw e
        }
    }

    /** Ramps the stimulus that is currently emitting to silence; returns when the ramp has been played out. */
    suspend fun stopCurrent() {
        if (!scheduler.requestStop()) return
        val pending = waiters.filter { it.first.startFrame >= 0 }.map { it.second }
        withContext(NonCancellable) { pending.forEach { runCatching { it.await() } } }
    }

    /** Idempotent; safe from finally blocks. Stops the thread and releases the sink. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val t = thread
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(500) }
        failAll(CancellationException("tone output closed"))
        runCatching { sink.stop() }
        runCatching { sink.release() }
    }
}
