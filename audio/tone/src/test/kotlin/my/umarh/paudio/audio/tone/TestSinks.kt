package my.umarh.paudio.audio.tone

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Sink that "plays" at 2x real time: each write of N frames takes ~N/rate/2 seconds. The playback head lags the
 * writes by [lagFrames]. After [failAfterWrites] writes, every write returns [failCode] (e.g. -6 DEAD_OBJECT).
 */
internal class FakeSink(
    val rate: Int,
    private val lagFrames: Int = 0,
    private val failAfterWrites: Int = Int.MAX_VALUE,
    private val failCode: Int = -6,
) : PcmSink {
    private val written = AtomicLong()
    private val headBase = AtomicLong()
    private val writes = AtomicInteger()
    val samples: MutableList<Float> = java.util.Collections.synchronizedList(ArrayList())
    @Volatile var released = 0
    @Volatile var stopped = 0

    override fun write(interleaved: FloatArray, frames: Int): Int {
        if (writes.incrementAndGet() > failAfterWrites) return failCode
        Thread.sleep(maxOf(1L, frames * 1000L / rate / 2))
        for (i in 0 until frames) samples += interleaved[2 * i]
        written.addAndGet(frames.toLong())
        return frames
    }

    /** Simulates the playback head restarting from 0 (reroute / track restart). */
    fun resetHead() = headBase.set(written.get())

    val framesWritten: Long get() = written.get()
    override val playedFrames: Long get() = maxOf(0L, written.get() - headBase.get() - lagFrames)
    override fun start() {}
    override fun stop() { stopped++ }
    override fun release() { released++ }
}
