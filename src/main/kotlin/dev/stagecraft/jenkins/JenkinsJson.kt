package dev.stagecraft.jenkins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Accessors over Jenkins' JSON that distinguish "absent" from "present but null".
 *
 * Jenkins answers `"result": null` for a build that has not finished, and omits keys it was not
 * asked for. Both mean "no value" here, but `"result": null` must not be confused with the string
 * `"null"`, so the string accessor insists on `isString`.
 */
internal val stagecraftJson: Json = Json {
    isLenient = true
    ignoreUnknownKeys = true
    allowTrailingComma = true
}

internal fun parseJsonObject(body: String): JsonObject {
    val element: JsonElement = try {
        stagecraftJson.parseToJsonElement(body)
    } catch (e: Exception) {
        throw JenkinsException.Malformed("<body>", "not valid JSON: ${e.message}")
    }
    return element as? JsonObject
        ?: throw JenkinsException.Malformed("<body>", "expected a JSON object but found ${element::class.simpleName}")
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString && it !is JsonNull }?.content

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.objects(key: String): List<JsonObject> =
    arr(key)?.mapNotNull { it as? JsonObject }.orEmpty()
