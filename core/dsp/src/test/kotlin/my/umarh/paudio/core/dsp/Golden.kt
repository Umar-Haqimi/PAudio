package my.umarh.paudio.core.dsp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Loads golden vector files from `src/test/resources/golden` (SPEC §7.1). */
object Golden {
    fun load(name: String): JsonObject {
        val stream = Golden::class.java.getResourceAsStream("/golden/$name.json")
            ?: error("missing golden file $name.json")
        return Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }
}

fun JsonElement.doubles(): DoubleArray = jsonArray.let { a -> DoubleArray(a.size) { a[it].jsonPrimitive.double } }
fun JsonElement.ints(): IntArray = jsonArray.let { a -> IntArray(a.size) { a[it].jsonPrimitive.int } }
fun JsonElement.dbl(): Double = jsonPrimitive.double
fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray
fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
fun JsonObject.tol(key: String): Double = obj("tolerance").getValue(key).dbl()
fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

fun maxAbsDiff(a: DoubleArray, b: DoubleArray): Double {
    require(a.size == b.size) { "size ${a.size} vs ${b.size}" }
    var m = 0.0
    for (i in a.indices) m = maxOf(m, kotlin.math.abs(a[i] - b[i]))
    return m
}
