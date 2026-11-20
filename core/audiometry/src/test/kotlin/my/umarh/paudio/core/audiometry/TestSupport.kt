package my.umarh.paudio.core.audiometry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

internal object Golden {
    fun load(name: String): JsonObject {
        val stream = checkNotNull(Golden::class.java.getResourceAsStream("/golden/$name.json")) { "missing golden/$name.json" }
        return Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }
}

internal fun JsonElement.dbl(): Double = jsonPrimitive.double
internal fun JsonElement.str(): String = jsonPrimitive.content
internal fun JsonElement.bool(): Boolean = jsonPrimitive.boolean
internal fun JsonElement.int(): Int = jsonPrimitive.int
internal fun JsonElement.doubles(): List<Double> = jsonArray.map { it.dbl() }
internal fun JsonElement.isNull(): Boolean = this is JsonPrimitive && this.content == "null"
internal fun JsonObject.cases(): JsonArray = getValue("cases").jsonArray

/** Minimal in-place radix-2 FFT (test only). */
internal fun fft(re: DoubleArray, im: DoubleArray) {
    val n = re.size
    require(n and (n - 1) == 0)
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            val tr = re[i]; re[i] = re[j]; re[j] = tr
            val ti = im[i]; im[i] = im[j]; im[j] = ti
        }
    }
    var len = 2
    while (len <= n) {
        val ang = -2.0 * PI / len
        val wr = cos(ang)
        val wi = sin(ang)
        var i = 0
        while (i < n) {
            var cr = 1.0
            var ci = 0.0
            for (k in 0 until len / 2) {
                val ur = re[i + k]
                val ui = im[i + k]
                val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                re[i + k] = ur + vr
                im[i + k] = ui + vi
                re[i + k + len / 2] = ur - vr
                im[i + k + len / 2] = ui - vi
                val ncr = cr * wr - ci * wi
                ci = cr * wi + ci * wr
                cr = ncr
            }
            i += len
        }
        len = len shl 1
    }
}

/** 10·log10(E_out / E_in) of a rectangular-window, zero-padded FFT; in-band = |f_k − f| ≤ 12 % of f. */
internal fun outOfBandRelDb(x: FloatArray, freqHz: Int, sampleRate: Int, nfft: Int = 65536): Double {
    val re = DoubleArray(nfft)
    val im = DoubleArray(nfft)
    for (i in x.indices) re[i] = x[i].toDouble()
    fft(re, im)
    var eIn = 0.0
    var eOut = 0.0
    for (k in 0..nfft / 2) {
        val fk = k.toDouble() * sampleRate / nfft
        val e = re[k] * re[k] + im[k] * im[k]
        if (kotlin.math.abs(fk - freqHz) <= 0.12 * freqHz) eIn += e else eOut += e
    }
    return 10.0 * log10(eOut / eIn)
}
