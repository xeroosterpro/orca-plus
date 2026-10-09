package com.wholphinplus.sources.cinema

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.wholphinplus.sources.PickSession
import com.wholphinplus.sources.Retry
import com.wholphinplus.sources.SourceHook
import com.wholphinplus.sources.core.ExternalSource
import kotlinx.coroutines.CancellationException
import java.util.UUID

/** Seconds before the screen plays the other copy by itself. */
private const val SWITCH_SECONDS = 10

/**
 * In place of Wholphin's error page when a play fails: whose problem it is (the server, the
 * connection, the copy, the TV's formats), what happened in plain words, and what to do. When
 * another server has the title, its best copy is offered and plays by itself after a countdown,
 * from the same spot; any move on the remote stops the countdown. A small line keeps the
 * technical facts for whoever helps.
 */
@Composable
fun PlaybackTroubleScreen(
    session: PickSession,
    itemId: UUID?,
    message: String?,
    exception: Throwable?,
    onRetry: (Retry) -> Unit,
    onBack: () -> Unit,
) {
    // Opened from Wholphin's player screen: bring the title art along, as the picker does
    val context = androidx.compose.ui.platform.LocalContext.current
    val cinemaArt = remember { dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, com.wholphinplus.sources.ui.SourcesEntryPoint::class.java).cinemaArt() }
    CompositionLocalProvider(LocalArt provides (LocalArt.current ?: cinemaArt)) {
        TroubleScreen(
        session,
        itemId,
        message,
        exception,
        onRetry = { r ->
            // Never the Copy itself: its source carries the stream URL and the server's token
            timber.log.Timber.i("Playback trouble: %s", (r as? com.wholphinplus.sources.Retry.Copy)?.let { "Copy from ${it.source.serverLabel}" } ?: r.javaClass.simpleName)
            onRetry(r)
        },
        onBack = {
            timber.log.Timber.i("Playback trouble: Back")
            onBack()
        },
    )
    }
}

@Composable
private fun TroubleScreen(
    session: PickSession,
    itemId: UUID?,
    message: String?,
    exception: Throwable?,
    onRetry: (Retry) -> Unit,
    onBack: () -> Unit,
) {
    val trouble = remember(message, exception) { session.trouble(itemId, message, exception).also { timber.log.Timber.i("Playback trouble shown: %s (%s)", it.headline, it.technical) } }
    val art = remember(itemId) { itemId?.let { StageArt.forPick(it.toString(), null) } }
    val titleArt = rememberArt(art)

    // Another copy, looked up while the screen shows (the picker's lookups are cached)
    var looking by remember { mutableStateOf(itemId != null && session.hasOtherServers()) }
    var other by remember { mutableStateOf<ExternalSource?>(null) }
    // Any key before the copy turns up, or any move during the countdown: no switching by itself
    var touched by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableStateOf<Int?>(null) }
    var pressedHere by remember { mutableStateOf(false) }
    val drain = remember { Animatable(1f) }

    val switchButton = remember { FocusRequester() }
    val retryButton = remember { FocusRequester() }

    LaunchedEffect(itemId) {
        if (itemId != null && looking) {
            other =
                try {
                    session.otherCopy(itemId)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
            looking = false
        }
    }
    LaunchedEffect(Unit) { runCatching { retryButton.requestFocus() } }
    LaunchedEffect(other) {
        val copy = other ?: return@LaunchedEffect
        if (touched) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos {}
        runCatching { switchButton.requestFocus() }
        for (s in SWITCH_SECONDS downTo 1) {
            secondsLeft = s
            drain.snapTo(s / SWITCH_SECONDS.toFloat())
            drain.animateTo((s - 1) / SWITCH_SECONDS.toFloat(), tween(1_000, easing = LinearEasing))
            if (touched) {
                secondsLeft = null
                return@LaunchedEffect
            }
        }
        secondsLeft = null
        onRetry(Retry.Copy(copy))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Stage)
            .onPreviewKeyEvent { e ->
                val ok = e.key.nativeKeyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
                // The OK that picked a copy (or started the play) can be let go after this screen is
                // up: its release clicked whatever had focus here (it pressed Wholphin's "Send app
                // logs" on the Shield, 2026-10-08). Only an OK pressed on this screen counts.
                if (e.type == KeyEventType.KeyDown) pressedHere = true
                if (ok && e.type == KeyEventType.KeyUp && !pressedHere) return@onPreviewKeyEvent true
                // OK on the countdown's own button just plays it sooner; anything else stops the clock
                if (!ok || secondsLeft == null) touched = true
                false
            },
    ) {
        StableBackdrop(art?.backdropUrl ?: titleArt?.cleanBackdrop?.let { "https://image.tmdb.org/t/p/w1280$it" }, drift = false)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Stage.copy(alpha = 0.96f), 0.55f to Stage.copy(alpha = 0.84f), 1f to Stage.copy(alpha = 0.35f))))
        Column(Modifier.fillMaxHeight().width(940.dp).padding(start = 64.dp, top = 56.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("+", color = Plus, fontSize = 15.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(5.dp))
                Text(trouble.culprit.label, color = InkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            }
            Spacer(Modifier.height(12.dp))
            val logo = titleArt?.logoUrl() ?: art?.logoUrl
            if (logo != null) {
                AsyncImage(model = logo, contentDescription = art?.title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.height(60.dp).width(300.dp))
            } else if (art != null) {
                Text(art.title, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(26.dp))
            Text(trouble.headline, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 36.sp)
            Spacer(Modifier.height(10.dp))
            Text(trouble.explanation, color = Ink.copy(alpha = 0.78f), fontSize = 17.sp, lineHeight = 25.sp, modifier = Modifier.widthIn(max = 760.dp))
            Spacer(Modifier.height(26.dp))
            // What the countdown will do, or that a copy is being looked for
            val copy = other
            val where = copy?.let { if (it.connectionId == SourceHook.JELLYFIN_ROW) "your server" else it.serverLabel }
            Text(
                when {
                    copy != null && secondsLeft != null -> "Playing the ${copy.quality.takeIf { it != "?" }?.let { "$it " }.orEmpty()}copy from $where in $secondsLeft…"
                    copy != null -> "Another copy is ready on $where (${listOf(copy.quality, copy.size).filter { it.isNotBlank() && it != "?" }.joinToString(" · ")})."
                    looking -> "Looking for another copy on your other servers…"
                    session.hasOtherServers() -> "No other copy of this title on your servers right now."
                    else -> ""
                },
                color = InkDim,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.width(320.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Ink.copy(alpha = if (secondsLeft != null) 0.12f else 0f))) {
                if (secondsLeft != null) Box(Modifier.fillMaxHeight().width(320.dp).graphicsLayer { scaleX = drain.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }.background(Plus))
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (copy != null) {
                    HeroButton(
                        "Play from ${if (copy.connectionId == SourceHook.JELLYFIN_ROW) "your server" else copy.serverLabel}",
                        Icons.Filled.PlayArrow,
                        primary = true,
                        modifier = Modifier.focusRequester(switchButton),
                        onClick = { onRetry(Retry.Copy(copy)) },
                    )
                }
                HeroButton("Try again", Icons.Filled.Refresh, primary = copy == null, modifier = Modifier.focusRequester(retryButton), onClick = { onRetry(Retry.Same) })
                if (session.hasOtherServers()) HeroButton("Choose a copy", Icons.Filled.List, primary = false, onClick = { onRetry(Retry.Choose) })
                HeroButton("Back", Icons.Filled.ArrowBack, primary = false, onClick = onBack)
            }
            Spacer(Modifier.height(28.dp))
            if (trouble.technical.isNotBlank()) Text(trouble.technical, color = InkDim.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}
