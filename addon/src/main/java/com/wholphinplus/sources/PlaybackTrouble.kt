package com.wholphinplus.sources

import androidx.annotation.OptIn
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.Loader

/**
 * Why a play failed, in words a viewer can act on. Wholphin's own screen showed the last
 * internal message ("Unable to get media URL from the server. Do you have permission to view
 * and/or transcode?") even when the server's storage was failing (owner, 2026-10-08: "the user
 * will have no idea"). Here the player's error, what the server answered and how often it
 * failed lately become: whose problem it is, what happened, and what to do.
 */
object PlaybackTrouble {
    /** What the failure tells us, pulled out of the player's error and the screen's message. */
    data class Signals(
        val httpCode: Int? = null,
        val network: Boolean = false,
        val parse: Boolean = false,
        val decoder: Boolean = false,
        // The server offered nothing to play (Wholphin's "Unable to get media URL")
        val noStream: Boolean = false,
        // The server's "not found" answers to stream opens in the last couple of minutes, since the
        // last time something played (see StreamHealth)
        val serverFailures: Int = 0,
    )

    enum class Culprit(
        val label: String,
    ) {
        SERVER("SERVER PROBLEM"),
        CONNECTION("CONNECTION PROBLEM"),
        FILE("PROBLEM WITH THIS COPY"),
        TV("FORMAT PROBLEM"),
        UNKNOWN("PLAYBACK PROBLEM"),
    }

    data class Trouble(
        val culprit: Culprit,
        val headline: String,
        val explanation: String,
        // One small line for whoever helps: what the server said, how often it failed
        val technical: String,
    )

    /** Failed opens in this window count as "the server is failing right now". */
    const val WINDOW_MS = 120_000L

    /** [server] names where it played from: "Your server" or an extra server's name. */
    fun explain(
        s: Signals,
        server: String,
    ): Trouble {
        val it = if (server == MAIN) "your server" else server
        val failing = s.serverFailures >= 3 || (s.httpCode != null && RetryingDataSource.isTransientCode(s.httpCode))
        // The player's own verdict comes before the server's recent misses: a decoder failure is
        // the TV's whatever the server did, and unreadable data is the file's unless the server
        // kept missing since the last time something played (2026-10-08: data broke while Silo
        // answered 404 again and again; StreamHealth forgets misses once a play gets going)
        val (culprit, headline, explanation) =
            when {
                s.httpCode == 401 || s.httpCode == 403 ->
                    Triple(
                        Culprit.SERVER,
                        "${it.cap()} turned this video away",
                        "It refused to send it. Your sign-in may have expired, or the server's owner has limited this title. " +
                            "Signing out and back in can help; if not, ask the server's owner.",
                    )
                s.decoder ->
                    Triple(
                        Culprit.TV,
                        "This TV couldn't play this copy's format",
                        "Its picture or sound wouldn't decode here. Another copy may use a format this TV plays.",
                    )
                s.parse && s.serverFailures < 3 ->
                    Triple(
                        Culprit.FILE,
                        "This copy won't play",
                        "The file stopped making sense to the player at this point; it may be damaged on the server" +
                            (if (s.noStream) ", and the server can't convert it into one that plays" else "") +
                            ". Another copy should work.",
                    )
                failing ->
                    Triple(
                        Culprit.SERVER,
                        "${it.cap()} isn't sending this video right now",
                        "The title is listed, but the server kept failing to hand over the file. " +
                            "That's a fault on the server's side, not your TV or your internet. It often clears up on its own; " +
                            "a copy from another server should play now.",
                    )
                s.network ->
                    Triple(
                        Culprit.CONNECTION,
                        "Can't reach $it",
                        "The connection dropped or the server didn't answer. Check that the TV is online; " +
                            "if other apps work, the server may be down for a while.",
                    )
                s.noStream ->
                    Triple(
                        Culprit.SERVER,
                        "${it.cap()} won't stream this copy",
                        "It didn't offer a version this TV can play (the server can't convert this copy). Another copy should work.",
                    )
                else ->
                    Triple(
                        Culprit.UNKNOWN,
                        "This video didn't play",
                        "Something went wrong starting it. Try again, or play another copy.",
                    )
            }
        val technical =
            listOfNotNull(
                s.httpCode?.let { c -> "server answered $c" },
                s.serverFailures.takeIf { n -> n > 0 }?.let { n -> "$n \"not found\" ${if (n == 1) "answer" else "answers"} in the last 2 min" },
                "no connection".takeIf { s.network },
                "file data unreadable".takeIf { s.parse },
                "decoder error".takeIf { s.decoder },
                "no conversion offered".takeIf { s.noStream },
            ).joinToString("  ·  ")
        return Trouble(culprit, headline, explanation, technical)
    }

    /** The main server, as the picker names it. */
    const val MAIN = "Your server"

    /** Reads the player's errors (and the screen's [message]) into [Signals]. */
    @OptIn(UnstableApi::class)
    fun signalsOf(
        errors: List<Throwable>,
        message: String?,
        serverFailures: Int,
    ): Signals {
        var http: Int? = null
        var network = false
        var parse = false
        var decoder = false
        errors.flatMap { error -> generateSequence(error) { it.cause }.take(12).toList() }.forEach { e ->
            when (e) {
                is HttpDataSource.InvalidResponseCodeException -> http = http ?: e.responseCode
                is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException,
                is java.net.NoRouteToHostException, is javax.net.ssl.SSLException,
                -> network = true
                is ParserException -> parse = true
                // Media3 wraps a parser's crash (Matroska's "No valid varint length mask found") this way
                is Loader.UnexpectedLoaderException -> parse = true
                is PlaybackException ->
                    when (e.errorCode) {
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        -> network = true
                        in 3000..3999 -> parse = true
                        in 4000..4999 -> decoder = true
                    }
            }
        }
        val noStream = message?.contains("Unable to get media URL", ignoreCase = true) == true
        return Signals(http, network, parse, decoder, noStream, serverFailures)
    }

    private fun String.cap() = replaceFirstChar { it.uppercase() }
}

/**
 * The server's recent "not found" (404) answers to stream opens (any server: one plays at a
 * time), so a failure that reached the screen in another shape (the fallback's "no URL") still
 * reads as the server's. Only 404s count: that's how a server with failing storage answers (Silo,
 * 2026-10-08); a 5xx that reaches the screen is the server's anyway. A play that gets going clears
 * them ([played]), so misses that a retry rode out earlier don't blame the server for a later
 * failure of the TV or the file.
 */
object StreamHealth {
    private val failures = ArrayDeque<Long>()

    /** A stream open got HTTP [code] (null: no HTTP answer). */
    @Synchronized
    fun failed(
        code: Int?,
        now: Long = System.currentTimeMillis(),
    ) {
        if (code != 404) return
        failures.addLast(now)
        while (failures.size > 200) failures.removeFirst()
    }

    @Synchronized
    fun recent(
        now: Long = System.currentTimeMillis(),
        windowMs: Long = PlaybackTrouble.WINDOW_MS,
    ): Int {
        while (failures.isNotEmpty() && now - failures.first() > windowMs) failures.removeFirst()
        return failures.size
    }

    /** Something is playing: the server delivered. */
    @Synchronized
    fun played() = failures.clear()
}
