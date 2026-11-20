package my.umarh.paudio.core.dsp

import kotlin.math.abs
import kotlin.math.sign

/**
 * Monotone piecewise-cubic Hermite interpolation (Fritsch–Carlson), reproducing
 * `scipy.interpolate.PchipInterpolator`: interior derivatives are the weighted harmonic mean of the
 * adjacent secant slopes (zero at local extrema/plateaus), end derivatives use the scipy
 * three-point shape-preserving rule (SPEC §2.1.3; golden set 3). [x] must be strictly increasing
 * with at least two points; the caller works in log2(f). Outside [x] the end cubics extrapolate.
 */
class Pchip(private val x: DoubleArray, private val y: DoubleArray) {
    private val d: DoubleArray

    init {
        require(x.size == y.size && x.size >= 2) { "need >= 2 matching points" }
        for (i in 1 until x.size) require(x[i] > x[i - 1]) { "x must be strictly increasing" }
        val n = x.size
        val h = DoubleArray(n - 1) { x[it + 1] - x[it] }
        val m = DoubleArray(n - 1) { (y[it + 1] - y[it]) / h[it] }
        d = DoubleArray(n)
        if (n == 2) {
            d[0] = m[0]
            d[1] = m[0]
        } else {
            for (k in 1 until n - 1) {
                val m0 = m[k - 1]
                val m1 = m[k]
                d[k] = if (m0 == 0.0 || m1 == 0.0 || sign(m0) != sign(m1)) {
                    0.0
                } else {
                    val w1 = 2.0 * h[k] + h[k - 1]
                    val w2 = h[k] + 2.0 * h[k - 1]
                    (w1 + w2) / (w1 / m0 + w2 / m1)
                }
            }
            d[0] = edge(h[0], h[1], m[0], m[1])
            d[n - 1] = edge(h[n - 2], h[n - 3], m[n - 2], m[n - 3])
        }
    }

    /** Evaluate at [xq]. */
    fun eval(xq: Double): Double {
        val n = x.size
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xq >= x[mid]) lo = mid else hi = mid
        }
        val i = lo
        val h = x[i + 1] - x[i]
        val t = (xq - x[i]) / h
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2 * t3 - 3 * t2 + 1
        val h10 = t3 - 2 * t2 + t
        val h01 = -2 * t3 + 3 * t2
        val h11 = t3 - t2
        return h00 * y[i] + h10 * h * d[i] + h01 * y[i + 1] + h11 * h * d[i + 1]
    }

    fun eval(xq: DoubleArray): DoubleArray = DoubleArray(xq.size) { eval(xq[it]) }

    private fun edge(h0: Double, h1: Double, m0: Double, m1: Double): Double {
        val dd = ((2.0 * h0 + h1) * m0 - h0 * m1) / (h0 + h1)
        return when {
            sign(dd) != sign(m0) -> 0.0
            sign(m0) != sign(m1) && abs(dd) > 3.0 * abs(m0) -> 3.0 * m0
            else -> dd
        }
    }
}
