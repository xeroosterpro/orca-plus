package com.wholphinplus.sources.cinema

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Text
import com.wholphinplus.sources.ui.SourcesEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * The player's "Play from another server" button: the action, or null when there's nothing to
 * switch to (no extra servers and one version). Provided by the playback screen, read by its
 * control row (owner, 2026-10-11: "if it stops playing I can quick switch server").
 */
val LocalSwitchCopy = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Whether a playback screen is kept, paused, while Orca+ is in the background (see VM `orcaBackground`). */
object PlayerAway {
    @Volatile
    var kept: Boolean = false
}

/**
 * Back in the player asks "Stop watching?" (owner, 2026-10-11: confirm before closing). Keep
 * watching is focused, so a second stray Back or OK changes nothing; Back on the question keeps
 * watching too. Off in Settings → Playback → Ask before closing. [enabled] is false while the
 * player's own menus or Next Up handle Back.
 */
@Composable
fun LeavePlayerConfirm(
    title: String?,
    enabled: Boolean,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java).sourceHook().store }
    val on by store.confirmLeave.collectAsState()
    var asking by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = on && enabled && !asking) { asking = true }
    if (asking) {
        LeaveQuestion(
            title = title,
            onStay = { asking = false },
            onLeave = {
                asking = false
                onLeave()
            },
        )
    }
}

@Composable
private fun LeaveQuestion(
    title: String?,
    onStay: () -> Unit,
    onLeave: () -> Unit,
) {
    val first = remember { FocusRequester() }
    // The Back that opened the question was released before it showed; an OK must go down here first
    val okDown = remember { booleanArrayOf(false) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // Tried each frame until the popup's window has the button attached
        for (attempt in 0 until 20) {
            withFrameNanos {}
            if (runCatching { first.requestFocus() }.getOrDefault(false)) break
        }
    }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(280, easing = CinemaEase)) }
    // Back arrives as a key on the Shield and as a callback with predictive back (Android 13+)
    BackHandler(onBack = onStay)
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onStay,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .graphicsLayer {
                    alpha = enter.value
                    translationY = (1f - enter.value) * 10.dp.toPx()
                }.onPreviewKeyEvent {
                    val ok = it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter
                    when {
                        it.key == Key.Back || it.key == Key.Escape -> {
                            if (it.type == KeyEventType.KeyUp) onStay()
                            true
                        }
                        ok && it.type == KeyEventType.KeyDown -> {
                            okDown[0] = true
                            false
                        }
                        ok && it.type == KeyEventType.KeyUp && !okDown[0] -> true
                        else -> false
                    }
                }.width(340.dp)
                .background(Color(0xF2181818), RoundedCornerShape(14.dp))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Stop watching?",
                color = Ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp, end = 12.dp),
            )
            if (!title.isNullOrBlank()) {
                Text(
                    title,
                    color = InkDim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                )
            }
            MenuRow("Keep watching", PlayIcon, Modifier.focusRequester(first), onClick = onStay)
            MenuRow("Stop and go back", CloseIcon, onClick = onLeave)
        }
    }
}

private val PlayIcon: ImageVector =
    ImageVector
        .Builder("Play", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString("M8 5v14l11-7z").toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.White),
        ).build()
