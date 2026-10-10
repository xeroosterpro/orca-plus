package com.wholphinplus.sources

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Opening the saved sign-in when the server can't be reached at start (the TV came up before
 * its Wi-Fi, a slow link). Wholphin's session restore asks the server who's signed in first; on
 * a dead or slow network that took 30 s and then landed on "Pick your server · Can't reach it",
 * and pressing OK there led to a password sign-in that dropped the saved session.
 *
 * Now: the server check gets [WAIT_MS]; when it fails for want of a network, or takes longer,
 * the saved server, user and token open as they are (Home shows its saved pages and fills in by
 * itself), and the check carries on in the background, again whenever it fails, until the
 * server answers. A real "no" from the server at once (a sign-in that expired) is left to
 * Wholphin. The same "no" arriving later, once the saved session is already open (a slow server,
 * or the network coming back), goes to [restore]'s `onRefused`: the app sends the viewer to sign
 * in on the saved server (before, Home stayed open with every request failing). A later check
 * that gets through loads the user's server profile (audio and subtitle defaults, admin), which
 * the offline session doesn't have.
 */
object OfflineStart {
    private const val WAIT_MS = 8_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [online] (Wholphin's restore), or [offline] (the saved session without the network) when
     * [online] fails for the network or isn't done within [WAIT_MS]. Null as [online] gives it.
     */
    suspend fun <T : Any> restore(
        online: suspend () -> T?,
        offline: suspend () -> T?,
        // Checks the opened session with the server (online may shortcut once a session is open)
        recheck: suspend () -> Any? = online,
        // The server refused the saved sign-in after the saved session opened (401/403)
        onRefused: suspend (Throwable) -> Unit = {},
        waitMs: Long = WAIT_MS,
    ): T? {
        val check = scope.async { runCatching { online() } }
        val first = withTimeoutOrNull(waitMs) { check.await() }
        if (first != null) {
            val ex = first.exceptionOrNull() ?: return first.getOrNull()
            if (!isNetwork(ex)) throw ex
            Timber.w(ex, "Server unreachable at start: opening the saved session")
            lost()
            return offline()?.also { checkLater(recheck, onRefused) } ?: throw ex
        }
        Timber.w("Server slow to answer at start: opening the saved session, checking in the background")
        val saved = offline() ?: return check.await().getOrThrow()
        scope.launch {
            val ex = check.await().exceptionOrNull() ?: return@launch
            if (!settled(ex, onRefused)) checkLater(recheck, onRefused)
        }
        return saved
    }

    /** For the Message Center: the main server didn't answer at start, and when it came back. */
    private fun lost() =
        runCatching {
            Inbox.post(Inbox.Kind.SERVERS, "${ServerBrands.mainName()} didn't answer", "Orca+ opened your saved Home and keeps trying; rows fill in once it's back.", Inbox.Level.WARN, key = "main-server")
        }

    private fun back() {
        Timber.i("Server reached: session checked")
        runCatching { Inbox.post(Inbox.Kind.SERVERS, "${ServerBrands.mainName()} is back", "Connected again.", Inbox.Level.GOOD, key = "main-server", popUp = false, unread = false) }
    }

    /** First wait of [checkLater] (tests shorten it). */
    internal var retryStartMs = 5_000L

    /** Tries [online] again, waiting longer each time (5 s … 1 min), until the server answers. */
    private fun checkLater(
        online: suspend () -> Any?,
        onRefused: suspend (Throwable) -> Unit,
    ) {
        scope.launch {
            var wait = retryStartMs
            repeat(60) {
                delay(wait)
                val ex = runCatching { online() }.exceptionOrNull() ?: return@launch back()
                if (settled(ex, onRefused)) return@launch
                wait = (wait * 2).coerceAtMost(60_000L)
            }
        }
    }

    /**
     * True when a background check's failure [ex] ends the checking: a refused sign-in (handed
     * to [onRefused]). The network, or a server error (500, 503 while it starts up), is checked
     * again later.
     */
    private suspend fun settled(
        ex: Throwable,
        onRefused: suspend (Throwable) -> Unit,
    ): Boolean {
        if (isNetwork(ex)) return false
        if (!isRefused(ex)) {
            Timber.w("Session check failed (%s), trying again later", ex.javaClass.simpleName)
            return false
        }
        Timber.w("Session check: the server refused the saved sign-in, asking to sign in again")
        runCatching { Inbox.post(Inbox.Kind.SERVERS, "${ServerBrands.mainName()} asked you to sign in again", "Its saved sign-in ran out or was removed on the server.", Inbox.Level.WARN, key = "main-server") }
        runCatching { onRefused(ex) }.onFailure { Timber.w(it, "Could not open the sign-in") }
        return true
    }

    /** The server said no to the sign-in itself (an expired or revoked token). */
    internal fun isRefused(e: Throwable): Boolean =
        generateSequence(e) { it.cause.takeIf { c -> c !== it } }.take(8).any {
            (it as? org.jellyfin.sdk.api.client.exception.InvalidStatusException)?.status.let { s -> s == 401 || s == 403 }
        }

    /**
     * [block], waiting out a network that isn't back yet (a TV waking from sleep asks its server
     * before the Wi-Fi is up: "Error during appResume", "Exception in coroutine", a failed worker,
     * all within a second). Network failures are tried again for about [QUIET_MS] (1, 2, 4, 8 s),
     * then given up with one quiet line: null. Anything the server itself answered is thrown as
     * before.
     */
    suspend fun <T : Any> retryQuietly(
        what: String,
        block: suspend () -> T,
    ): T? {
        var wait = 1_000L
        var spent = 0L
        while (true) {
            try {
                return block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isNetwork(e)) throw e
                if (spent >= QUIET_MS) {
                    Timber.i("%s: the server isn't reachable yet (%s), left for later", what, e.javaClass.simpleName)
                    return null
                }
            }
            delay(wait)
            spent += wait
            wait = (wait * 2).coerceAtMost(8_000L)
        }
    }

    private const val QUIET_MS = 15_000L

    /** A failure of the network (no route, timeout, DNS), not an answer from the server. */
    internal fun isNetwork(e: Throwable): Boolean {
        var t: Throwable? = e
        var network = false
        while (t != null) {
            val name = t.javaClass.name
            // The server answered (401, 500…): not a network problem
            if (name.endsWith("InvalidStatusException")) return false
            if (t is java.io.IOException || name.contains("Timeout", ignoreCase = true) || name.endsWith("ConnectException")) network = true
            t = t.cause.takeIf { it !== t }
        }
        return network
    }
}
