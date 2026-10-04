package com.wholphinplus.sources

import androidx.media3.common.C
import androidx.media3.common.Player
import com.wholphinplus.sources.core.ExternalSource
import com.wholphinplus.sources.core.PlayEvent
import com.wholphinplus.sources.core.ServerClient
import com.wholphinplus.sources.core.ServerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * While a stream from an extra server plays, report start/progress/stop to that server the way
 * its own clients do, so its Continue Watching (and other apps') matches. Wholphin keeps reporting
 * to the main Jellyfin server itself.
 */
internal class PlaybackReporter(
    private val client: ServerClient,
    private val connection: ServerConnection,
    private val source: ExternalSource,
    private val player: Player,
    private val overlay: ProgressOverlay?,
    private val mainItem: MainItem?,
) : Player.Listener {
    private val session = UUID.randomUUID().toString().replace("-", "")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var positionMs = 0L
    private var durationMs = source.runTimeTicks / 10_000L
    private var stopped = false

    fun isFor(other: ExternalSource): Boolean = other.url == source.url

    fun start() {
        player.addListener(this)
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
        runCatching { snapshot() }
        runCatching { player.removeListener(this) }
        scope.cancel()
        send(PlayEvent.STOP, paused = true)
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
        // The main server doesn't see this stream, so keep its progress locally (see ProgressOverlay)
        if (overlay != null && mainItem != null && event != PlayEvent.START && pos > 0) {
            val played = dur > 0 && pos >= dur * 9 / 10
            overlay.record(
                mainItem.id,
                ProgressOverlay.Entry(pos * 10_000L, played, System.currentTimeMillis(), mainItem.seriesId, mainItem.title),
            )
        }
        // Not tied to the player's scope: the final STOP must go out after the screen closes.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { client.reportPlayback(connection, source, event, pos, dur, paused, session) }
                .onFailure { Timber.w(it, "Report %s to %s failed", event, connection.label) }
        }
    }
}
