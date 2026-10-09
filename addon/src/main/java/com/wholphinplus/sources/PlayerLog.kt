package com.wholphinplus.sources

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import timber.log.Timber
import java.io.IOException

/**
 * What the player waits on, in the log (tag Wholphin, "Player:"): buffering and how long it
 * lasted, the first picture, load errors, audio output trouble. Added when the owner's Shield
 * loaded a movie (direct play, decoder ready in 5 s) and then sat ~2 min before playing with
 * nothing in the log to say why (2026-10-09). A few lines per playback, nothing per frame.
 */
@OptIn(UnstableApi::class)
class PlayerLog : AnalyticsListener {
    private val created = System.currentTimeMillis()
    private var bufferingSince = 0L

    private fun at() = "+${(System.currentTimeMillis() - created) / 100 / 10.0}s"

    override fun onPlaybackStateChanged(
        eventTime: AnalyticsListener.EventTime,
        state: Int,
    ) {
        when (state) {
            Player.STATE_BUFFERING -> {
                bufferingSince = System.currentTimeMillis()
                Timber.i("Player: buffering at %d s (%s)", eventTime.currentPlaybackPositionMs / 1000, at())
            }
            Player.STATE_READY -> {
                val waited = if (bufferingSince > 0) System.currentTimeMillis() - bufferingSince else 0
                bufferingSince = 0
                Timber.i("Player: ready after %d ms of buffering (%s)", waited, at())
            }
            Player.STATE_ENDED -> Timber.i("Player: ended (%s)", at())
            Player.STATE_IDLE -> Timber.i("Player: idle (%s)", at())
        }
    }

    override fun onRenderedFirstFrame(
        eventTime: AnalyticsListener.EventTime,
        output: Any,
        renderTimeMs: Long,
    ) {
        Timber.i("Player: first picture (%s)", at())
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        Timber.w("Player: load error after %d ms, %d bytes: %s (%s)", loadEventInfo.loadDurationMs, loadEventInfo.bytesLoaded, LogScrub.text(error.toString()), at())
    }

    override fun onPlayerError(
        eventTime: AnalyticsListener.EventTime,
        error: PlaybackException,
    ) {
        Timber.w("Player: error %s: %s (%s)", error.errorCodeName, LogScrub.text(error.message), at())
    }

    override fun onAudioSinkError(
        eventTime: AnalyticsListener.EventTime,
        audioSinkError: Exception,
    ) {
        Timber.w("Player: audio output trouble: %s (%s)", LogScrub.text(audioSinkError.toString()), at())
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        Timber.w("Player: audio ran dry (%d ms since last fed, %s)", elapsedSinceLastFeedMs, at())
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        if (droppedFrames >= 10) Timber.w("Player: %d frames dropped in %d ms", droppedFrames, elapsedMs)
    }
}
