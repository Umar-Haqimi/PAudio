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
 * The writer loop: one thread that writes [chunkFrames] frames at a time to the sink for as long as the engine
 * is open: stimulus samples or digital silence as decided by [StreamScheduler]. Hardware-free, so it is unit
 * tested with a fake [PcmSink].
 *
 * [play] suspends until the playback head has passed the stimulus end (not merely until it is written).
 * Cancelling [play] ramps the stimulus down and leaves the stream in silence.
 *
 * Failure handling (SPEC §2.2 trap 5, headset swap): on `ERROR_DEAD_OBJECT` the sink is re-created once via
 * [sinkFactory] (pending stimuli fail with [IllegalStateException] so the test layer redoes that trial, the
 * stream lives on). Any other write error, a failed re-creation, or a second dead object right after a retry marks
 * the engine failed: [isClosed] becomes true, pending and later [play] calls throw, and the owner must re-open.
 * A playback head that moves backwards is treated as a stream reset: pending stimuli fail and the scheduler is
 * re-based.
 *
 * [playingFlow] is owned by the caller so collectors never bind to a stale engine.
 */
class StreamEngine(
    initialSink: PcmSink,
    val sampleRate: Int,
    private val chunkFrames: Int = sampleRate / 100,
    private val rampMs: Int = 25,
    private val threadInit: () -> Unit = {},
    private val sinkFactory: (() -> PcmSink?)? = null,
    private val playingFlow: MutableStateFlow<Boolean> = MutableStateFlow(false),
) {
    @Volatile private var sink: PcmSink = initialSink
    @Volatile private var scheduler = StreamScheduler(sampleRate, rampMs)
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val waiters = CopyOnWriteArrayList<Pair<Stimulus, CompletableDeferred<Unit>>>()
    private var thread: Thread? = null

    /** Number of times the sink was re-created after a dead object (for tests/diagnostics). */
    @Volatile var sinkRecreations: Int = 0
        private set

    /** True while a stimulus is audible (playback head inside it). Updated once per chunk (~10 ms). */
    val playing: StateFlow<Boolean> get() = playingFlow

    /** True once closed or failed: the engine can no longer play and must be replaced. */
    val isClosed: Boolean get() = closed.get() || failed.get()

    fun start() {
        check(!isClosed) { "closed" }
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
        var lastHead = 0L
        var retried = false
        var framesSinceRetry = 0L
        try {
            while (!closed.get()) {
                scheduler.fill(buf, chunkFrames)
                var off = 0
                while (off < chunkFrames && !closed.get()) {
                    val src = if (off == 0) buf else buf.copyOfRange(2 * off, 2 * chunkFrames)
                    val n = try {
                        sink.write(src, chunkFrames - off)
                    } catch (e: RuntimeException) {
                        ERROR_FATAL
                    }
                    if (n < 0) {
                        if (closed.get()) return
                        if (n == ERROR_DEAD_OBJECT && !retried && recreateSink()) {
                            retried = true
                            framesSinceRetry = 0
                            lastHead = 0
                            break
                        }
                        fail(IllegalStateException("sink write error $n"))
                        return
                    }
                    off += n
                    framesSinceRetry += n
                }
                if (retried && framesSinceRetry > sampleRate) retried = false
                val head = sink.playedFrames
                if (head < lastHead) rebase()
                lastHead = head
                poll(head)
            }
        } finally {
            if (failed.get() || closed.get()) failAll(CancellationException("tone output closed"))
        }
    }

    /** Dead AudioTrack: replace it, drop everything in flight. */
    private fun recreateSink(): Boolean {
        val factory = sinkFactory ?: return false
        val old = sink
        runCatching { old.stop() }
        runCatching { old.release() }
        val fresh = try { factory() } catch (e: RuntimeException) { null } ?: return false
        try {
            fresh.start()
        } catch (e: RuntimeException) {
            runCatching { fresh.release() }
            return false
        }
        sink = fresh
        sinkRecreations++
        rebase()
        return true
    }

    /** New stream timeline: pending stimuli are lost (their frames can no longer be tracked). */
    private fun rebase() {
        scheduler = StreamScheduler(sampleRate, rampMs)
        failAll(IllegalStateException("stream restarted; stimulus lost"))
    }

    private fun fail(t: Throwable) {
        failed.set(true)
        failAll(t)
        runCatching { sink.stop() }
        runCatching { sink.release() }
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
        playingFlow.value = audible
    }

    private fun failAll(t: Throwable) {
        playingFlow.value = false
        for (w in waiters) {
            waiters.remove(w)
            w.second.completeExceptionally(t)
        }
    }

    /** Suspends until [stimulus] has been fully played out. Cancellation ramps it down to silence. */
    suspend fun play(stimulus: StereoBuffer) {
        check(started.get() && !isClosed) { "ToneOutput not open" }
        require(stimulus.sampleRate == sampleRate) { "sample rate ${stimulus.sampleRate} != stream $sampleRate" }
        val s = Stimulus(stimulus)
        val done = CompletableDeferred<Unit>()
        val entry = s to done
        waiters += entry
        scheduler.enqueue(s)
        // Close/failure may have swept the waiters just before we added ours.
        if (isClosed) {
            waiters.remove(entry)
            throw IllegalStateException("ToneOutput closed")
        }
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

    /** Idempotent; safe from finally blocks. Stops the sink (wakes a blocked write), joins the thread, releases. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { sink.stop() }
        val t = thread
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(500) }
        failAll(CancellationException("tone output closed"))
        runCatching { sink.release() }
    }

    private companion object {
        const val ERROR_DEAD_OBJECT = -6
        const val ERROR_FATAL = -1
    }
}
