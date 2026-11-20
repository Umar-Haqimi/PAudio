package my.umarh.paudio.core.dsp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Long-term average spectrum of a clip on the 30 one-third-octave bands of [ThirdOctaveBands]
 * (SPEC §2.1.5). JSON form:
 * `{"clip": "...", "sampleRate": 48000, "bandCentersHz": [30 doubles], "bandPower": [30 doubles]}`;
 * `bandPower` is linear and is renormalised to sum 1 on load.
 */
class Ltas(val clip: String, val sampleRate: Int, val bandPower: DoubleArray) {
    init {
        require(bandPower.size == ThirdOctaveBands.COUNT) { "LTAS needs ${ThirdOctaveBands.COUNT} bands" }
    }

    companion object {
        fun parse(json: String): Ltas = fromJson(Json.parseToJsonElement(json).jsonObject)

        fun fromJson(o: JsonObject): Ltas {
            val centers = o.getValue("bandCentersHz").jsonArray.map { it.jsonPrimitive.double }
            val power = o.getValue("bandPower").jsonArray.map { it.jsonPrimitive.double }
            require(centers.size == ThirdOctaveBands.COUNT && power.size == ThirdOctaveBands.COUNT) {
                "LTAS must have ${ThirdOctaveBands.COUNT} bands"
            }
            for (i in centers.indices) {
                require(abs(centers[i] / ThirdOctaveBands.centerHz(i) - 1.0) < 0.01) {
                    "LTAS band $i centre ${centers[i]} Hz does not match ${ThirdOctaveBands.centerHz(i)} Hz"
                }
            }
            require(power.all { it.isFinite() && it >= 0.0 }) { "LTAS powers must be finite and >= 0" }
            val sum = power.sum()
            require(sum > 0.0) { "LTAS power sums to zero" }
            val sr = o["sampleRate"]?.jsonPrimitive?.double?.toInt() ?: 48_000
            val clip = o["clip"]?.jsonPrimitive?.content ?: ""
            return Ltas(clip, sr, DoubleArray(power.size) { power[it] / sum })
        }
    }
}

/**
 * Predicted loudness change of a curve (SPEC §2.1.5):
 *
 * `ΔL(G) = 10·log10( Σ_b P_b·|K(f_b)|²·10^(Ḡ_b/10) / Σ_b P_b·|K(f_b)|² )`
 *
 * over the 30 one-third-octave bands b (centres `1000·2^(b/3)`, b = −16..13; edges `f_b·2^(±1/6)`),
 * with `K` the BS.1770 K-weighting ([KWeighting]) and `P_b` the clip LTAS (or equal power per band =
 * pink noise for user files).
 *
 * Band averaging `Ḡ_b` (exact definition): for each ear, `Ḡ_b,ear` is the arithmetic mean **in dB**
 * of G over the 1/24-octave [DspGrid] points with `lo_b <= f < hi_b`; if a band contains no grid
 * point, G is evaluated at the band centre by linear-in-log2(f) interpolation ([DspGrid.interpolate]).
 * The ears are then power-averaged: `Ḡ_b = 10·log10( mean over the two ears of 10^(Ḡ_b,ear/10) )`.
 * The result is applied as `−ΔL` through inputGain for in-app A/B only.
 */
class LoudnessPredictor(bandPower: DoubleArray) {
    private val weights: DoubleArray
    private val weightSum: Double

    init {
        require(bandPower.size == ThirdOctaveBands.COUNT) { "need ${ThirdOctaveBands.COUNT} band powers" }
        weights = DoubleArray(ThirdOctaveBands.COUNT) {
            bandPower[it] * KWeighting.powerGain(ThirdOctaveBands.centerHz(it))
        }
        weightSum = weights.sum()
        require(weightSum > 0.0) { "band powers must not all be zero" }
    }

    /** `ΔL` in dB for left/right curves on the [DspGrid]. */
    fun deltaLDb(gLeft: DoubleArray, gRight: DoubleArray): Double {
        val bl = bandMeanDb(gLeft)
        val br = bandMeanDb(gRight)
        var acc = 0.0
        for (b in 0 until ThirdOctaveBands.COUNT) {
            val gbarDb = 10.0 * log10(0.5 * (10.0.pow(bl[b] / 10.0) + 10.0.pow(br[b] / 10.0)))
            acc += weights[b] * 10.0.pow(gbarDb / 10.0)
        }
        return 10.0 * log10(acc / weightSum)
    }

    companion object {
        /** Equal power per band (pink noise). */
        fun pink(): LoudnessPredictor =
            LoudnessPredictor(DoubleArray(ThirdOctaveBands.COUNT) { 1.0 / ThirdOctaveBands.COUNT })

        fun fromLtas(ltas: Ltas): LoudnessPredictor = LoudnessPredictor(ltas.bandPower)

        /** `Ḡ_b,ear` for one ear: per-band mean in dB over grid points with `lo <= f < hi`. */
        fun bandMeanDb(g: DoubleArray): DoubleArray {
            require(g.size == DspGrid.POINTS) { "expected ${DspGrid.POINTS} grid values" }
            return DoubleArray(ThirdOctaveBands.COUNT) { b ->
                val lo = ThirdOctaveBands.lowEdgeHz(b)
                val hi = ThirdOctaveBands.highEdgeHz(b)
                var sum = 0.0
                var n = 0
                for (i in 0 until DspGrid.POINTS) {
                    val f = DspGrid.gridHz[i]
                    if (f >= lo && f < hi) {
                        sum += g[i]
                        n++
                    }
                }
                if (n > 0) sum / n else DspGrid.interpolate(g, ThirdOctaveBands.centerHz(b))
            }
        }
    }
}
