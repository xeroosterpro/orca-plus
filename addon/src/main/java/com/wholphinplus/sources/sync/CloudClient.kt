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
    /** The profile's Orca+ name (null: none chosen). */
    val name: String? = null,
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

/** A phone setup: show [qr] (or [url]), collect with [secret]. */
class SetupStart(
    val code: String,
    val secret: String,
    val url: String,
    val qr: String,
)

/** What the phone sealed for this TV (base64): its ephemeral key, nonce and ciphertext. */
class SetupBox(
    val epk: String,
    val iv: String,
    val ct: String,
)

/**
 * This TV's device token per profile: the cloud trusts a TV that showed the right PIN before, so
 * guesses run up by others (a lock) don't reach it. "new" asks for one.
 */
internal interface DeviceTokens {
    fun get(id: String): String?

    fun set(
        id: String,
        token: String,
    )
}

/** Talks to the Orca+ cloud (cloud/server.js). Blocking: call from Dispatchers.IO. */
internal class CloudClient(
    private val base: String,
    private val devices: DeviceTokens? = null,
) {
    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    private val json = Json { ignoreUnknownKeys = true }

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
        call("POST", "$id/keep", authed(id, auth))
    }

    /** Claims [id] with this PIN's [auth]; the wrap for its first upload. */
    fun create(
        id: String,
        auth: String,
    ): String = text(call("POST", "$id/create", authed(id, auth)), "wrap")

    fun open(
        id: String,
        auth: String,
    ): CloudCopy {
        val o = call("POST", "$id/open", authed(id, auth))
        return CloudCopy(
            version = (o["version"] as? JsonPrimitive)?.intOrNull ?: 0,
            wrap = text(o, "wrap"),
            blob = (o["blob"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { kotlin.io.encoding.Base64.Default.decode(it) },
            resetAt = (o["resetAt"] as? JsonPrimitive)?.longOrNull ?: 0,
            name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )
    }

    /** The profile id an Orca+ name belongs to; [CloudException] "no_name" when none. */
    fun nameLookup(name: String): String {
        val o = request(Request.Builder().url("$base/v1/name/" + java.net.URLEncoder.encode(name.trim().lowercase(), "UTF-8")))
        return text(o, "id")
    }

    /** Gives the profile the Orca+ [name] ("" takes it away); the name now. "name_taken", "bad_name". */
    fun setName(
        id: String,
        auth: String,
        name: String,
    ): String? = (call("POST", "$id/name", authed(id, auth) { put("name", name) })["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Writes over [version]; the new version, or [CloudException] "conflict" when it moved on. */
    fun put(
        id: String,
        auth: String,
        version: Int,
        blob: ByteArray,
    ): Int =
        version(call("PUT", id, authed(id, auth) {
            put("version", version)
            put("blob", kotlin.io.encoding.Base64.Default.encode(blob))
        }))

    fun changePin(
        id: String,
        auth: String,
        newAuth: String,
        version: Int,
        blob: ByteArray,
    ): Int =
        version(call("POST", "$id/pin", authed(id, auth) {
            put("newAuth", newAuth)
            put("version", version)
            put("blob", kotlin.io.encoding.Base64.Default.encode(blob))
        }))

    fun delete(
        id: String,
        auth: String,
    ) {
        call("DELETE", id, authed(id, auth))
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

    /** Starts a phone setup for this TV's public key (base64, 65 bytes). */
    /** A phone setup code; [mode] "server": for an extra server (the phone's page asks no PIN). */
    fun setupStart(
        pub: String,
        mode: String? = null,
    ): SetupStart {
        val body =
            buildJsonObject {
                put("pub", pub)
                mode?.let { put("mode", it) }
            }.toString()
        val o = request(Request.Builder().url("$base/v1/setup").post(body.toRequestBody("application/json".toMediaType())))
        return SetupStart(text(o, "code"), text(o, "secret"), text(o, "url"), text(o, "qr"))
    }

    /** The phone's sealed box once sent (null while waiting). Throws "expired" when the code ran out. */
    fun setupCollect(s: SetupStart): SetupBox? {
        val o = request(Request.Builder().url("$base/v1/setup/${s.code}").header("x-pair-secret", s.secret))
        if ((o["ready"] as? JsonPrimitive)?.content != "true") return null
        val b = o["box"] as? JsonObject ?: return null
        fun f(k: String) = (b[k] as? JsonPrimitive)?.content.orEmpty()
        return SetupBox(f("epk"), f("iv"), f("ct"))
    }

    /** Tells the phone how it went ([final] false: still going, the phone keeps waiting). */
    fun setupResult(
        s: SetupStart,
        ok: Boolean,
        message: String,
        final: Boolean,
    ) {
        val body =
            buildJsonObject {
                put("ok", ok)
                put("message", message)
                put("final", final)
            }.toString()
        request(Request.Builder().url("$base/v1/setup/${s.code}/result").header("x-pair-secret", s.secret).post(body.toRequestBody("application/json".toMediaType())))
    }

    private fun request(b: Request.Builder): JsonObject =
        http.newCall(b.header("User-Agent", "Orca+").build()).execute().use { r ->
            val o = runCatching { json.parseToJsonElement(r.body.string()) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
            if (!r.isSuccessful) throw CloudException(r.code, (o["error"] as? JsonPrimitive)?.content ?: "http_${r.code}")
            o
        }

    /** A request body with the PIN's [auth] and this TV's device token (or "new" to get one). */
    private fun authed(
        id: String,
        auth: String,
        more: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): JsonObject =
        buildJsonObject {
            put("auth", auth)
            if (devices != null) put("device", devices.get(id) ?: "new")
            more()
        }

    private fun version(o: JsonObject): Int = (o["version"] as? JsonPrimitive)?.intOrNull ?: error("The cloud left out the version")

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
            // A device token the cloud made for this TV (it showed the right PIN): kept for this profile
            val device = (o["device"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (device != null && devices != null) devices.set(path.substringBefore('/'), device)
            return o
        }
    }
}
