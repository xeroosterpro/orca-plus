package com.wholphinplus.sources

import androidx.media3.common.C
import androidx.media3.common.Player
import com.wholphinplus.sources.cinema.Conductor
import com.wholphinplus.sources.core.ExternalSource
import com.wholphinplus.sources.core.PlayEvent
import com.wholphinplus.sources.core.ServerClient
import com.wholphinplus.sources.core.ServerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * Follows one play. While a stream from an extra server plays, reports start/progress/stop to
 * that server the way its own clients do, so its Continue Watching (and other apps') matches;
 * Wholphin keeps reporting to the main Jellyfin server itself. Either way the position goes into
 * the [ProgressOverlay] for the main-server item ([mainItem]): some main servers (Silo) ignore
 * every progress write, so without it a play from the main server left no trace.
 *
 * [connection] and [source] are null for a play from the main server (nothing to report).
 */
internal class PlaybackReporter(
    private val client: ServerClient?,
    private val connection: ServerConnection?,
    private val source: ExternalSource?,
    private val player: Player,
    private val overlay: ProgressOverlay?,
    private val mainItem: MainItem?,
    /** The server it plays from, for the Message Center. */
    private val from: String? = null,
) : Player.Listener {
    private val session = UUID.randomUUID().toString().replace("-", "")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var positionMs = 0L
    private var durationMs = (source?.runTimeTicks ?: 0L) / 10_000L
    private var stopped = false
    private var started = false

    // Reports go out one at a time, in order: a PROGRESS that overtook the STOP re-opened the
    // session on the server or put back an older position
    private val outbox = Channel<() -> Unit>(Channel.UNLIMITED)

    /** What this reporter follows: the main item and the stream it plays from. */
    val key: String = key(mainItem?.id, source)

    fun start() {
        started = true
        Conductor.playbackStarted()
        player.addListener(this)
        if (client != null && connection != null && source != null) {
            SENDER.launch {
                for (job in outbox) runCatching { job() }
            }
        }
        send(PlayEvent.START, paused = false)
        scope.launch {
            while (isActive) {
                delay(10_000)
                snapshot()
                if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
                    send(PlayEvent.PROGRESS, paused = !player.playWhenReady)
                }
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        snapshot()
        if (!stopped) send(PlayEvent.PROGRESS, paused = !player.playWhenReady)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            snapshot()
            stop()
        }
    }

    /** Safe to call more than once; also used when the player is already gone. */
    fun stop() {
        if (stopped) return
        stopped = true
        if (started) Conductor.playbackEnded()
        runCatching { snapshot() }
        runCatching { player.removeListener(this) }
        scope.cancel()
        send(PlayEvent.STOP, paused = true)
        outbox.close()
        mainItem?.let { m -> runCatching { Inbox.played(m.id, m.seriesId, m.title, m.episode, positionMs, durationMs, from) } }
    }

    private fun snapshot() {
        runCatching {
            positionMs = player.currentPosition.coerceAtLeast(0)
            player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { durationMs = it }
        }
    }

    private fun send(
        event: PlayEvent,
        paused: Boolean,
    ) {
        val pos = positionMs
        val dur = durationMs
        // The main server may not keep this (see ProgressOverlay): keep the position locally
        if (overlay != null && mainItem != null && event != PlayEvent.START && pos > 0) {
            val played = dur > 0 && pos >= dur * 9 / 10
            overlay.record(
                mainItem.id,
                ProgressOverlay.Entry(pos * 10_000L, played, System.currentTimeMillis(), mainItem.seriesId, mainItem.title),
            )
        }
        val c = client ?: return
        val conn = connection ?: return
        val src = source ?: return
        // Not tied to the player's scope: the final STOP must go out after the screen closes.
        outbox.trySend {
            runCatching { c.reportPlayback(conn, src, event, pos, dur, paused, session) }
                .onFailure { Timber.w(it, "Report %s to %s failed", event, conn.label) }
        }
    }

    companion object {
        private val SENDER = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun key(
            mainItemId: String?,
            source: ExternalSource?,
        ): String = mainItemId.orEmpty() + "|" + source?.url.orEmpty()
    }
}
