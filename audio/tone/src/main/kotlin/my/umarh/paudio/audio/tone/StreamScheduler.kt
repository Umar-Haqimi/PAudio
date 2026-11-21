package my.umarh.paudio.audio.tone

import my.umarh.paudio.core.model.StereoBuffer
import kotlin.math.PI
import kotlin.math.cos

/**
 * One queued stimulus. Frame positions are absolute stream positions (frames since the stream started).
 * [audible] is false for an explicit silent segment (a timed gap): it is scheduled like any stimulus but never
 * counts as "playing".
 */
class Stimulus(val buffer: StereoBuffer, val audible: Boolean = true) {
    /** Stream frame at which the first sample was emitted, or -1 while still queued. */
    @Volatile var startFrame: Long = -1L
        internal set

    /** Stream frame just after the last emitted sample, or -1 while not finished. */
    @Volatile var endFrame: Long = -1L
        internal set
}

/**
 * Pure, thread-safe decision logic of the continuous stream (SPEC §2.2 trap 5): for every chunk the writer asks
 * [fill] and gets either queued stimulus samples or digital silence, so the stream never has a gap.
 *
 * Early stop ([requestStop]) multiplies the *continuing* stimulus samples by a raised-cosine ramp 1 -> 0 of
 * [rampMs] (both ends inclusive), so the output is continuous at the moment of the request (gain 1) and ends on
 * an exact 0 gain: no click.
 */
class StreamScheduler(val sampleRate: Int, rampMs: Int = 25) {
    private val rampLen = maxOf(1, sampleRate * rampMs / 1000)
    private val lock = Any()
    private val queue = ArrayDeque<Stimulus>()
    private var current: Stimulus? = null
    private var pos = 0
    private var ramp = -1
    private var framesOut = 0L

    /** Frames handed out so far. */
    val framesProduced: Long get() = synchronized(lock) { framesOut }

    fun enqueue(s: Stimulus) {
        require(s.buffer.sampleRate == sampleRate) { "sample rate ${s.buffer.sampleRate} != stream $sampleRate" }
        synchronized(lock) { queue.addLast(s) }
    }

    /** Ramps the stimulus currently emitting down; false if none is emitting (or already ramping). */
    fun requestStop(): Boolean = synchronized(lock) {
        if (current == null || ramp >= 0) return false
        ramp = 0
        true
    }

    /**
     * Drops [s] if it is still queued (it never starts; its frames stay -1) or ramps it down if it is the one
     * emitting. Returns false when [s] is unknown or already finished.
     */
    fun cancel(s: Stimulus): Boolean = synchronized(lock) {
        if (queue.remove(s)) return true
        if (current === s && ramp < 0) {
            ramp = 0
            return true
        }
        false
    }

    /** Fills [out] (interleaved L,R; size >= 2*[frames]) with the next [frames] frames. */
    fun fill(out: FloatArray, frames: Int) {
        synchronized(lock) {
            var i = 0
            while (i < frames) {
                var cur = current
                if (cur == null) {
                    cur = queue.removeFirstOrNull()
                    if (cur == null) {
                        out.fill(0f, 2 * i, 2 * frames)
                        break
                    }
                    cur.startFrame = framesOut + i
                    current = cur
                    pos = 0
                    ramp = -1
                }
                val b = cur.buffer
                var ended = false
                while (i < frames) {
                    if (pos >= b.frames) {
                        ended = true
                        break
                    }
                    var g = 1f
                    if (ramp >= 0) g = (0.5 * (1.0 + cos(PI * ramp / rampLen))).toFloat()
                    out[2 * i] = b.left[pos] * g
                    out[2 * i + 1] = b.right[pos] * g
                    pos++
                    i++
                    if (ramp >= 0) {
                        ramp++
                        if (ramp > rampLen) {
                            ended = true
                            break
                        }
                    }
                }
                if (!ended && pos >= b.frames) ended = true
                if (ended) {
                    cur.endFrame = framesOut + i
                    current = null
                }
            }
            framesOut += frames
        }
    }
}
