package com.wholphinplus.sources.welcome

import com.wholphinplus.sources.SourceHook
import com.wholphinplus.sources.sync.SetupCrypto
import com.wholphinplus.sources.sync.SetupStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import kotlin.io.encoding.Base64

/**
 * "Set up with your phone" (owner, 2026-10-08: setting up a relative's Fire TV meant typing a
 * server, user and password with the remote). The first screen shows a QR code; the phone fills
 * in the server address, username, password and, for a cloud setup, the Orca+ PIN, encrypted for
 * this TV (see [SetupCrypto]). The welcome then runs as if typed here, Wholphin's own add-server
 * and sign-in included: [details] tells each step what to do, each step claims an [Details.attempt]
 * once, and how it went goes back to the phone, which shows "wrong password" and lets it be sent
 * again without scanning anew.
 */
object PhoneSetup {
    data class Details(
        val server: String,
        val username: String,
        val password: String,
        val pin: String,
        val attempt: Int,
    )

    /** The code on screen. */
    data class Code(
        val code: String,
        val url: String,
        val qr: String,
    )

    enum class Phase { WAITING, CONNECTING, SIGNING_IN, RESTORING, DONE }

    private val _code = MutableStateFlow<Code?>(null)
    val code: StateFlow<Code?> = _code.asStateFlow()

    private val _details = MutableStateFlow<Details?>(null)
    val details: StateFlow<Details?> = _details.asStateFlow()

    private val _phase = MutableStateFlow(Phase.WAITING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** What went wrong last (shown on the TV and the phone), null when going well. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** The cloud couldn't be reached for a code (the QR shows a note instead). */
    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    private var hook: SourceHook? = null
    private var job: Job? = null
    private var session: SetupStart? = null
    private var claimedServer = 0
    private var claimedLogin = 0
    private var claimedPin = 0

    val active: Boolean get() = _details.value != null && _phase.value != Phase.DONE

    /** Keeps a code on screen and waits for the phone (one at a time; codes renew every ~19 min). */
    fun ensureStarted(hook: SourceHook) {
        if (job?.isActive == true || _phase.value == Phase.DONE) return
        if (!com.wholphinplus.sources.sync.ProfileSync.AVAILABLE) return
        this.hook = hook
        job =
            hook.background.launch {
                while (_phase.value != Phase.DONE) {
                    val keys = SetupCrypto.newKeys()
                    val s =
                        runCatching { hook.profileSync.setupStart(Base64.Default.encode(SetupCrypto.rawPublic(keys.public as ECPublicKey))) }
                            .onFailure { Timber.w(it, "Phone setup: no code") }
                            .getOrNull()
                    if (s == null) {
                        _offline.value = true
                        delay(10_000)
                        continue
                    }
                    _offline.value = false
                    session = s
                    _code.value = Code(s.code, s.url, s.qr)
                    val until = System.currentTimeMillis() + 19 * 60 * 1000
                    while (System.currentTimeMillis() < until && _phase.value != Phase.DONE) {
                        delay(2_000)
                        val box = runCatching { hook.profileSync.setupCollect(s) }.getOrElse { e ->
                            // The code ran out or was refused: a fresh one
                            if (e is com.wholphinplus.sources.sync.CloudException && e.code in 400..499) break
                            null
                        } ?: continue
                        val got = runCatching { decode(SetupCrypto.open(keys.private as ECPrivateKey, s.code, Base64.Default.decode(box.epk), Base64.Default.decode(box.iv), Base64.Default.decode(box.ct))) }.getOrNull()
                        if (got == null) {
                            report(false, "Your TV couldn't read that. Send it again.", final = false)
                            continue
                        }
                        val attempt = (_details.value?.attempt ?: 0) + 1
                        Timber.i("Phone setup: details arrived (attempt %d)", attempt)
                        _problem.value = null
                        _phase.value = Phase.CONNECTING
                        _details.value = got.copy(attempt = attempt)
                        report(true, "Connecting to ${got.server}…", final = false)
                    }
                }
            }
    }

    private fun decode(bytes: ByteArray): Details {
        val o = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
        fun f(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
        require(f("server").isNotBlank() && f("username").isNotBlank())
        return Details(f("server").trim(), f("username").trim(), f("password"), f("pin").trim(), 0)
    }

    /** The server step takes [attempt] once (true: add the server now). */
    fun claimServer(attempt: Int): Boolean = (attempt > claimedServer).also { if (it) claimedServer = attempt }

    /** The sign-in takes [attempt] once (true: sign in now). */
    fun claimLogin(attempt: Int): Boolean =
        (attempt > claimedLogin).also {
            if (it) {
                claimedLogin = attempt
                _phase.value = Phase.SIGNING_IN
                report(true, "Signing in as ${_details.value?.username}…", final = false)
            }
        }

    /** The PIN to try once for this attempt (null: none sent, or already tried). */
    fun claimPin(): String? {
        val d = _details.value ?: return null
        if (d.pin.isBlank() || d.attempt <= claimedPin) return null
        claimedPin = d.attempt
        _phase.value = Phase.RESTORING
        report(true, "Signed in ✓ Bringing back your Orca+ setup…", final = false)
        return d.pin
    }

    /** A step failed: shown on the TV, and the phone asks for the details again. */
    fun fail(message: String) {
        if (!active) return
        Timber.i("Phone setup: %s", message)
        _problem.value = message
        _phase.value = Phase.WAITING
        report(false, message, final = false)
    }

    /** Done (signed in, and the setup restored when a PIN came): the phone says all set. */
    fun finish(message: String) {
        if (_details.value == null || _phase.value == Phase.DONE) return
        _phase.value = Phase.DONE
        _problem.value = null
        report(true, message, final = true)
        job?.cancel()
    }

    private fun report(
        ok: Boolean,
        message: String,
        final: Boolean,
    ) {
        val h = hook ?: return
        val s = session ?: return
        h.background.launch { runCatching { h.profileSync.setupResult(s, ok, message, final) }.onFailure { Timber.w(it, "Phone setup: report") } }
    }

    /** Same server? (the phone may type it without the scheme or port Wholphin saved). */
    fun sameServer(
        typed: String,
        saved: String,
    ): Boolean {
        fun host(s: String) =
            s
                .trim()
                .lowercase()
                .substringAfter("://")
                .substringBefore("/")
                .substringBefore(":")
        return host(typed) == host(saved)
    }

    /** Wholphin's sign-in error in words for the phone. */
    fun loginProblem(error: String): String =
        when {
            error.contains("401") || error.contains("unauthor", true) || error.contains("invalid", true) -> "Wrong username or password"
            else -> "Couldn't sign in: $error"
        }
}
