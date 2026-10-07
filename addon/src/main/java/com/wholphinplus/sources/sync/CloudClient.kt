package com.wholphinplus.sources.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/** The cloud said no: [error] is its reason ("wrong_pin", "locked", "conflict", "no_profile"…). */
class CloudException(
    val code: Int,
    val error: String,
    val triesLeft: Int? = null,
    val retryAfterSec: Int? = null,
    val version: Int? = null,
) : Exception(error)

/**
 * The cloud's copy: its version, the wrap for the key, the encrypted profile (null when empty),
 * and when a "forgot PIN" reset will delete it (0: none asked for).
 */
class CloudCopy(
    val version: Int,
    val wrap: String,
    val blob: ByteArray?,
    val resetAt: Long = 0,
)

/** Whether the cloud has a profile for an account, and when a "forgot PIN" reset deletes it (0: none). */
class CloudState(
    val exists: Boolean,
    val resetAt: Long,
)

/** A pairing for adding keys from a phone: show [qr] (or [url] and [code]), collect with [secret]. */
class PairStart(
    val code: String,
    val secret: String,
    val url: String,
    val qr: String,
)

/** Talks to the Orca+ cloud (cloud/server.js). Blocking: call from Dispatchers.IO. */
internal class CloudClient(
    private val base: String,
) {
    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val b64 = Base64.getEncoder()

    fun state(id: String): CloudState {
        val o = call("GET", id, null)
        return CloudState((o["exists"] as? JsonPrimitive)?.content == "true", (o["resetAt"] as? JsonPrimitive)?.longOrNull ?: 0)
    }

    fun exists(id: String): Boolean = state(id).exists

    /** "Forgot PIN": asks the cloud to delete the profile a day from now; when it will. Needs no PIN. */
    fun requestReset(id: String): Long = (call("POST", "$id/reset", JsonObject(emptyMap()))["resetAt"] as? JsonPrimitive)?.longOrNull ?: error("The cloud left out resetAt")

    /** Cancels a pending reset: only a TV that syncs (it has the keys) can. */
    fun keep(
        id: String,
        auth: String,
    ) {
        call("POST", "$id/keep", buildJsonObject { put("auth", auth) })
    }

    /** Claims [id] with this PIN's [auth]; the wrap for its first upload. */
    fun create(
        id: String,
        auth: String,
    ): String = text(call("POST", "$id/create", buildJsonObject { put("auth", auth) }), "wrap")

    fun open(
        id: String,
        auth: String,
    ): CloudCopy {
        val o = call("POST", "$id/open", buildJsonObject { put("auth", auth) })
        return CloudCopy(
            version = (o["version"] as? JsonPrimitive)?.intOrNull ?: 0,
            wrap = text(o, "wrap"),
            blob = (o["blob"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { Base64.getDecoder().decode(it) },
            resetAt = (o["resetAt"] as? JsonPrimitive)?.longOrNull ?: 0,
        )
    }

    /** Writes over [version]; the new version, or [CloudException] "conflict" when it moved on. */
    fun put(
        id: String,
        auth: String,
        version: Int,
        blob: ByteArray,
    ): Int =
        (call("PUT", id, buildJsonObject {
            put("auth", auth)
            put("version", version)
            put("blob", b64.encodeToString(blob))
        })["version"] as JsonPrimitive).intOrNull ?: error("No version")

    fun changePin(
        id: String,
        auth: String,
        newAuth: String,
        version: Int,
        blob: ByteArray,
    ): Int =
        (call("POST", "$id/pin", buildJsonObject {
            put("auth", auth)
            put("newAuth", newAuth)
            put("version", version)
            put("blob", b64.encodeToString(blob))
        })["version"] as JsonPrimitive).intOrNull ?: error("No version")

    fun delete(
        id: String,
        auth: String,
    ) {
        call("DELETE", id, buildJsonObject { put("auth", auth) })
    }

    fun pairStart(): PairStart {
        val o = request(Request.Builder().url("$base/v1/pair").post("{}".toRequestBody("application/json".toMediaType())))
        return PairStart(text(o, "code"), text(o, "secret"), text(o, "url"), text(o, "qr"))
    }

    /** What the phone sent (field → value), or null while it hasn't. Throws "expired" after 15 minutes. */
    fun pairCollect(p: PairStart): Map<String, String>? {
        val o = request(Request.Builder().url("$base/v1/pair/${p.code}").header("x-pair-secret", p.secret))
        if ((o["ready"] as? JsonPrimitive)?.content != "true") return null
        return (o["data"] as? JsonObject).orEmpty().mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }
    }

    private fun request(b: Request.Builder): JsonObject =
        http.newCall(b.header("User-Agent", "Orca+").build()).execute().use { r ->
            val o = runCatching { json.parseToJsonElement(r.body.string()) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
            if (!r.isSuccessful) throw CloudException(r.code, (o["error"] as? JsonPrimitive)?.content ?: "http_${r.code}")
            o
        }

    private fun text(
        o: JsonObject,
        name: String,
    ) = (o[name] as? JsonPrimitive)?.content ?: error("The cloud left out $name")

    private fun call(
        method: String,
        path: String,
        body: JsonObject?,
    ): JsonObject {
        val req =
            Request
                .Builder()
                .url("$base/v1/profile/$path")
                .header("User-Agent", "Orca+")
                .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
                .build()
        http.newCall(req).execute().use { r ->
            val o = runCatching { json.parseToJsonElement(r.body.string()) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
            if (!r.isSuccessful) {
                fun int(n: String) = (o[n] as? JsonPrimitive)?.intOrNull
                throw CloudException(r.code, (o["error"] as? JsonPrimitive)?.content ?: "http_${r.code}", int("triesLeft"), int("retryAfterSec"), int("version"))
            }
            return o
        }
    }
}
