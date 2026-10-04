package com.wholphinplus.sources.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun JsonObject.string(name: String): String = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content.orEmpty()

internal fun JsonObject.int(name: String): Int? = string(name).toIntOrNull()

internal fun JsonObject.long(name: String): Long? = string(name).toLongOrNull()

internal fun JsonObject.boolean(name: String): Boolean? = string(name).toBooleanStrictOrNull()

internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

internal fun JsonObject.array(name: String): List<JsonElement> = (this[name] as? JsonArray).orEmpty()

internal fun JsonObject.array(
    parent: String,
    name: String,
): List<JsonElement> = obj(parent)?.array(name).orEmpty()

internal fun JsonObject.objects(name: String): List<JsonObject> = array(name).filterIsInstance<JsonObject>()
