package com.wholphinplus.sources.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.wholphinplus.sources.PlaybackReporter
import com.wholphinplus.sources.core.ExternalSource
import com.wholphinplus.sources.core.ServerClient
import com.wholphinplus.sources.core.ServerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * A plain full-screen player for titles that only exist on an extra server (Wholphin's own
 * player needs a main-server item). Resumes where that server left off and reports progress
 * back to it, like its own apps do.
 */
class ExternalPlayerActivity : Activity() {
    internal class Request(
        val source: ExternalSource,
        val connection: ServerConnection,
        val client: ServerClient,
        val title: String,
    )

    companion object {
        // In-process hand-off: a source carries server tokens, which don't belong in an Intent.
        @Volatile private var pending: Request? = null

        internal fun start(
            context: Context,
            request: Request,
        ) {
            pending = request
            context.startActivity(Intent(context, ExternalPlayerActivity::class.java))
        }
    }

    private var player: ExoPlayer? = null
    private var reporter: PlaybackReporter? = null
    private lateinit var view: PlayerView
    private val scope: CoroutineScope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = pending ?: return finish()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = PlayerView(this).apply { keepScreenOn = true }
        setContentView(view)
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        view.player = exo
        view.requestFocus()
        exo.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Timber.e(error, "External playback failed")
                    Toast.makeText(this@ExternalPlayerActivity, "Can't play this copy: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                    finish()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) finish()
                }
            },
        )
        scope.launch {
            // Resume where this server left off
            val resumeMs =
                withContext(Dispatchers.IO) {
                    runCatching { request.client.userData(request.connection, request.source.itemId) }
                        .getOrNull()
                        ?.takeIf { !it.played }
                        ?.positionTicks
                        ?.div(10_000L) ?: 0L
                }
            exo.setMediaItem(
                MediaItem
                    .Builder()
                    .setUri(request.source.url)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(request.title).build())
                    .build(),
                resumeMs,
            )
            exo.prepare()
            exo.play()
            reporter =
                PlaybackReporter(request.client, request.connection, request.source, exo, null, null).also { it.start() }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Recreated with nothing to play (the app was killed meanwhile): it's closing, no view
        if (!::view.isInitialized) return super.dispatchKeyEvent(event)
        // OK/D-pad: show the controls first, then let them handle the key
        if (event.action == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible &&
            event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            view.showController()
            return true
        }
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        reporter?.stop()
        player?.release()
        scope.cancel()
        super.onDestroy()
    }
}
