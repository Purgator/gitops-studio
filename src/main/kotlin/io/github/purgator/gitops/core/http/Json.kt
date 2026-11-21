package io.github.purgator.gitops.core.http

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime

/** Null-tolerant navigation helpers over Gson trees: a missing key never throws. */

fun parseJson(text: String): JsonElement =
    if (text.isBlank()) JsonNull.INSTANCE else runCatching { JsonParser.parseString(text) }.getOrDefault(JsonNull.INSTANCE)

operator fun JsonElement?.get(key: String): JsonElement? =
    (this as? JsonObject)?.get(key)?.takeUnless { it.isJsonNull }

fun JsonElement?.at(vararg path: String): JsonElement? = path.fold(this) { node, key -> node[key] }

val JsonElement?.string: String?
    get() = (this as? JsonPrimitive)?.asString

fun JsonElement?.str(vararg path: String): String = at(*path).string.orEmpty()

fun JsonElement?.int(vararg path: String): Int? =
    (at(*path) as? JsonPrimitive)?.let { p -> runCatching { p.asInt }.getOrNull() }

fun JsonElement?.long(vararg path: String): Long? =
    (at(*path) as? JsonPrimitive)?.let { p -> runCatching { p.asLong }.getOrNull() }

fun JsonElement?.bool(vararg path: String): Boolean =
    (at(*path) as? JsonPrimitive)?.let { p -> runCatching { p.asBoolean }.getOrNull() } ?: false

fun JsonElement?.list(vararg path: String): List<JsonElement> =
    (at(*path) as? JsonArray)?.filterNot { it.isJsonNull } ?: emptyList()

fun JsonElement?.instant(vararg path: String): Instant? = parseInstant(str(*path))

fun parseInstant(text: String): Instant? {
    if (text.isBlank()) return null
    return runCatching { Instant.parse(text) }
        .recoverCatching { OffsetDateTime.parse(text).toInstant() }
        .getOrNull()
}

/** Builds a JSON object, skipping null values. Accepts primitives, [JsonElement]s, maps and iterables. */
fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject {
    val obj = JsonObject()
    for ((key, value) in pairs) if (value != null) obj.add(key, toJson(value))
    return obj
}

fun jsonArrayOf(values: Iterable<Any?>): JsonArray {
    val array = JsonArray()
    values.forEach { array.add(toJson(it)) }
    return array
}

private fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull.INSTANCE
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject().also { o -> value.forEach { (k, v) -> if (v != null) o.add(k.toString(), toJson(v)) } }
    is Iterable<*> -> jsonArrayOf(value)
    else -> JsonPrimitive(value.toString())
}
