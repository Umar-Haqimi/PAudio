package my.umarh.paudio.audio.tone

import kotlin.random.Random

/** Where the writer thread sends interleaved stereo float frames. Implemented by AudioTrack; faked in tests. */
interface PcmSink {
    /** Blocking write of [frames] interleaved frames; returns frames written (<0 on error). */
    fun write(interleaved: FloatArray, frames: Int): Int

    /** Frames actually played out so far (playback head). */
    val playedFrames: Long

    fun start()
    fun stop()
    fun release()
}

/** Float -> 16-bit conversion with TPDF dither (fallback when float AudioTrack is unsupported). */
object Pcm16 {
    fun convert(src: FloatArray, dst: ShortArray, samples: Int, rnd: Random = Random.Default) {
        for (i in 0 until samples) {
            // Digital silence must stay exactly 0 (hearing-test purity): never dither exact zeros.
            if (src[i] == 0f) {
                dst[i] = 0
                continue
            }
            // TPDF: sum of two uniform variables in [-0.5, 0.5) LSB.
            val d = (rnd.nextFloat() - 0.5f) + (rnd.nextFloat() - 0.5f)
            val v = (src[i] * 32767f + d).coerceIn(-32768f, 32767f)
            dst[i] = Math.round(v).toShort()
        }
    }
}
