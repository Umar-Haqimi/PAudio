package my.umarh.paudio.core.dsp

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * FFT block size the platform DynamicsProcessing engine ends up with (DECISIONS §1.2; mirrors
 * `EffectDynamicsProcessing.cpp:234-245` + `DPFrequency.cpp:137-150`): float math, truncation,
 * `< 8 -> 8`, otherwise the next power of two, clamped to 8..16384. 16 ms @ 48 kHz -> 1024.
 */
fun dpBlockSize(fs: Int, frameMs: Float): Int {
    val d = (frameMs * fs / 1000.0f).toInt()
    val n = when {
        d < 8 -> 8
        d and (d - 1) == 0 -> d
        else -> 1 shl (32 - Integer.numberOfLeadingZeros(d))
    }
    return n.coerceIn(8, 16384)
}

/** `numpy.geomspace(start, stop, num)` (log10/linspace/pow construction, exact end points). */
internal fun geomspace(start: Double, stop: Double, num: Int): DoubleArray {
    require(num >= 2) { "geomspace needs num >= 2" }
    require(start > 0.0 && stop > 0.0) { "geomspace needs positive bounds" }
    val ls = log10(start)
    val step = (log10(stop) - ls) / (num - 1)
    val r = DoubleArray(num) { 10.0.pow(it * step + ls) }
    r[0] = start
    r[num - 1] = stop
    return r
}

/** Native bin stop of a cutoff: `(int)(0.5f + cutoff·N/fs)` in float32 (DPFrequency.cpp:91-95). */
internal fun nativeBinStop(cutoffHz: Double, blockSize: Int, fs: Int): Int =
    (0.5f + cutoffHz.toFloat() * blockSize / fs).toInt()

/**
 * PreEQ band layout, shared by both ears (DECISIONS §1.5 "Decision", SPEC §2.1.4 as overridden).
 * Band `b` covers FFT bins `(stops[b-1], stops[b]]`, band 0 is bin 0; `cutoffHz[b] = stops[b]·fs/N`
 * exactly, so the last cutoff of the main layouts is exactly `fs/2`. Build via the factories only.
 *
 * Invariants (checked at construction): every stop equals the native float32 bin stop of its cutoff,
 * the last cutoff is ≥ fs/2, and unless [degenerate] the stops are strictly increasing.
 */
class DpBandLayout private constructor(
    val fs: Int,
    val blockSize: Int,
    /** Native bin stop of every band (`(int)(0.5f + cutoff·N/fs)`). */
    val stops: IntArray,
    /** Band top edges in Hz, what is passed to `EqBand.cutoffFrequency`. */
    val cutoffHz: DoubleArray,
    /** True only for [spec40], whose sub-bin bands may repeat stops (DECISIONS §1.5 pitfall). */
    val degenerate: Boolean,
) {
    init {
        require(stops.size == cutoffHz.size && stops.isNotEmpty()) { "stops/cutoffs mismatch" }
        for (i in stops.indices) {
            require(stops[i] == nativeBinStop(cutoffHz[i], blockSize, fs)) { "stop $i does not match its cutoff" }
            if (!degenerate && i > 0) require(stops[i] > stops[i - 1]) { "stops must be strictly increasing" }
        }
        require(cutoffHz.last() >= fs / 2.0) { "last cutoff must be >= fs/2" }
    }

    val bandCount: Int get() = stops.size

    /** Number of bands that actually receive at least one bin in the native loop. */
    val effectiveBandCount: Int
        get() {
            var next = 0
            var n = 0
            for (s in stops) {
                if (s >= next && next <= blockSize / 2) n++
                next = s + 1
            }
            return n
        }

    companion object {
        /** Preferred layout: `[0] + unique(round(geomspace(1, N/2, target)))`, 87 bands at N = 1024. */
        fun of(fs: Int, blockSize: Int, target: Int = 128): DpBandLayout {
            val g = geomspace(1.0, (blockSize / 2).toDouble(), target)
            val set = sortedSetOf<Int>()
            for (x in g) set.add(Math.rint(x).toInt()) // half-to-even like numpy.round
            val stops = IntArray(set.size + 1)
            set.forEachIndexed { i, s -> stops[i + 1] = s }
            return DpBandLayout(fs, blockSize, stops, DoubleArray(stops.size) { stops[it] * fs.toDouble() / blockSize }, false)
        }

        /** First fallback: 51 bands at N = 1024 (`geomspace(…, 64)`). */
        fun fallback51(fs: Int, blockSize: Int): DpBandLayout = of(fs, blockSize, 64)

        /**
         * Last-resort fallback: the spec 40-band layout `c_k = 20·2^((k+1)/4)` (k = 0..38),
         * `c_39 = fs/2`. Bands below ~490 Hz are narrower than a bin, so stops may repeat
         * (degenerate) — reduced precision, kept only as a ladder step.
         */
        fun spec40(fs: Int, blockSize: Int): DpBandLayout {
            val cut = DoubleArray(40) { if (it < 39) 20.0 * 2.0.pow((it + 1) / 4.0) else fs / 2.0 }
            val stops = IntArray(40) { nativeBinStop(cut[it], blockSize, fs) }
            return DpBandLayout(fs, blockSize, stops, cut, true)
        }
    }
}

/** Maps a target curve onto PreEQ band gains (DECISIONS §1 "Band layout" step 3). */
object DpBandMapper {
    private const val MIN_F_HZ = 20.0

    /**
     * Band gain = mean (dB) over bins `k ∈ (stops[b-1], stops[b]]` of `G(max(k·fs/N, 20 Hz))`, with
     * [gDb] the target in dB at a frequency. Band 0 (bin 0, DC) uses `G(20 Hz)`. A degenerate band
     * (only possible in [DpBandLayout.spec40]) uses the gain at its stop bin.
     */
    fun map(layout: DpBandLayout, gDb: (Double) -> Double): FloatArray {
        val out = FloatArray(layout.bandCount)
        for (b in 0 until layout.bandCount) {
            val stop = layout.stops[b]
            val start = if (b == 0) 0 else layout.stops[b - 1] + 1
            val lo = if (start <= stop) start else stop
            var sum = 0.0
            for (k in lo..stop) {
                sum += gDb(maxOf(k * layout.fs.toDouble() / layout.blockSize, MIN_F_HZ))
            }
            out[b] = (sum / (stop - lo + 1)).toFloat()
        }
        return out
    }

    /** As above with the target given on the 1/24-octave [DspGrid] (log2-linear, ends held). */
    fun map(layout: DpBandLayout, gDbOnGrid: DoubleArray): FloatArray =
        map(layout) { DspGrid.interpolate(gDbOnGrid, it) }
}

/**
 * What the ear sees from a DP PreEQ configuration (DECISIONS §1.5–1.6): per-bin gains written by the
 * native staircase loop, smoothed by the sqrt-Hann analysis/synthesis windows into a
 * **linear-in-dB interpolation over bin-centre knots** `f_k = k·fs/N`, constant beyond the ends.
 */
object DpReconstruction {
    /** Replica of `DPFrequency::updateParameters` (PreEQ part) in dB; unwritten bins stay at 0 dB. */
    fun binGainsDb(layout: DpBandLayout, bandGainsDb: FloatArray): DoubleArray {
        val half = layout.blockSize / 2
        val bins = DoubleArray(half + 1)
        var binNext = 0
        for (b in 0 until layout.bandCount) {
            val start = binNext
            val stop = nativeBinStop(layout.cutoffHz[b], layout.blockSize, layout.fs)
            binNext = stop + 1
            for (k in start..minOf(stop, half)) bins[k] = bandGainsDb[b].toDouble()
        }
        return bins
    }

    /** Reconstructed response in dB at [fHz] from precomputed [bins] (see [binGainsDb]). */
    fun responseDb(layout: DpBandLayout, bins: DoubleArray, fHz: Double): Double {
        val pos = fHz * layout.blockSize / layout.fs
        if (pos <= 0.0) return bins[0]
        if (pos >= bins.size - 1) return bins[bins.size - 1]
        val k = floor(pos).toInt()
        val t = pos - k
        return bins[k] + t * (bins[k + 1] - bins[k])
    }

    fun responseDb(layout: DpBandLayout, bandGainsDb: FloatArray, fHz: Double): Double =
        responseDb(layout, binGainsDb(layout, bandGainsDb), fHz)

    fun responseDb(layout: DpBandLayout, bandGainsDb: FloatArray, fHz: DoubleArray): DoubleArray {
        val bins = binGainsDb(layout, bandGainsDb)
        return DoubleArray(fHz.size) { responseDb(layout, bins, fHz[it]) }
    }

    /** RMS of `reconstruction − G` over the grid points with 50 Hz ≤ f ≤ 16 kHz (SPEC §2.1.4 acceptance). */
    fun rmsErrorDb(layout: DpBandLayout, bandGainsDb: FloatArray, gDbOnGrid: DoubleArray): Double {
        val bins = binGainsDb(layout, bandGainsDb)
        var acc = 0.0
        var n = 0
        for (i in 0 until DspGrid.POINTS) {
            val f = DspGrid.gridHz[i]
            if (f < 50.0 || f > 16000.0) continue
            val e = responseDb(layout, bins, f) - gDbOnGrid[i]
            acc += e * e
            n++
        }
        return sqrt(acc / n)
    }
}
