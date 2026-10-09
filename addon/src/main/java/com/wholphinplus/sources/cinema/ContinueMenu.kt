package com.wholphinplus.sources.cinema

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.wholphinplus.sources.ProgressOverlay
import com.wholphinplus.sources.SourceHook
import com.wholphinplus.sources.ui.SourcesEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * Titles taken out of Continue Watching with its long-press menu this session, by card key, so
 * every copy of the row (other tabs, the page kept for Back, the one saved on the device) leaves
 * them out at once. Each stays out until something of it is played after [Gone.at]; a fresh row
 * from the server has them out anyway (the progress overlay filters it).
 */
internal object ContinueEdits {
    class Gone(
        val id: String,
        val series: String?,
        val at: Long,
        /** Marked watched (not removed): out while it stays watched here. */
        val watched: Boolean = false,
    )

    val gone = mutableStateMapOf<String, Gone>()

    fun hides(
        item: CinemaItem,
        overlay: ProgressOverlay,
    ): Boolean {
        val g = gone[item.key] ?: return false
        // The server's copy of the mark arrives as a fresh "watched" too: not a new play
        if (g.watched) return overlay.isWatched(g.id)
        val again = maxOf(overlay.lastPlayed(g.id) ?: 0L, g.series?.let { overlay.seriesLastPlayed(it) } ?: 0L)
        return again <= g.at
    }
}

/** What the menu asks for. */
internal enum class ContinueAction { REMOVE, WATCHED }

/**
 * Continue Watching's menu, held OK on a card: Remove from Continue Watching, Mark as watched,
 * Cancel. A small panel over the card, fading and rising in; Back closes it.
 */
@Composable
internal fun ContinueMenu(
    item: CinemaItem,
    onClose: () -> Unit,
    onAction: (ContinueAction) -> Unit,
) {
    val first = remember { FocusRequester() }
    // Whether an OK went down inside the menu (the one that opened it was pressed on the card)
    val okDown = remember { booleanArrayOf(false) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // Tried each frame until the popup's window has the button attached
        for (attempt in 0 until 20) {
            withFrameNanos {}
            if (runCatching { first.requestFocus() }.getOrDefault(false)) break
        }
    }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(320, easing = CinemaEase)) }
    // Back arrives as a key on the Shield and as a callback with predictive back (Android 13+)
    BackHandler(onBack = onClose)
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onClose,
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
                            if (it.type == KeyEventType.KeyUp) onClose()
                            true
                        }
                        // The OK held to open the menu is still down: its repeats and its release
                        // must not press the first button
                        ok && it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount > 0 -> true
                        ok && it.type == KeyEventType.KeyDown -> {
                            okDown[0] = true
                            false
                        }
                        ok && it.type == KeyEventType.KeyUp && !okDown[0] -> true
                        else -> false
                    }
                }.width(300.dp)
                .background(Color(0xF2181818), RoundedCornerShape(14.dp))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val episode = item.kind == BaseItemKind.EPISODE
            Text(
                item.title,
                color = InkDim,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 12.dp),
            )
            MenuRow("Remove from Continue Watching", CloseIcon, Modifier.focusRequester(first)) { onAction(ContinueAction.REMOVE) }
            MenuRow(if (episode) "Mark episode as watched" else "Mark as watched", CheckIcon) { onAction(ContinueAction.WATCHED) }
            MenuRow("Cancel", null, onClick = onClose)
        }
    }
}

@Composable
private fun MenuRow(
    text: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val f = rememberFocusFade(Color.White.copy(alpha = 0f), Ink, Ink, Stage)
    Surface(
        onClick = onClick,
        interactionSource = f.source,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = f.fill, contentColor = f.content, focusedContainerColor = f.fill, focusedContentColor = f.content),
        modifier = modifier.fillMaxWidth().glideLift(scale = 1.02f, edge = false).tapToClick(onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
            } else {
                Spacer(Modifier.width(30.dp))
            }
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** The hook and a repository for the menu's actions (the rows don't carry one). */
@Composable
internal fun rememberContinueRepo(): Pair<SourceHook, CinemaRepository> {
    val context = LocalContext.current
    return remember {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java)
        val hook = entry.sourceHook()
        hook to CinemaRepository(hook, hook.collections, entry.searchService().tmdb)
    }
}

/**
 * Carries out a menu choice on [item] in Continue Watching's [state]: focus moves to the next card
 * (else the one before) and the card glides out; an episode marked watched gives way, in its
 * place, to the show's next one. The overlay keeps the change at once; the server is told after.
 */
internal suspend fun applyContinueAction(
    action: ContinueAction,
    item: CinemaItem,
    state: RowState,
    shown: List<CinemaItem>,
    hook: SourceHook,
    repo: CinemaRepository,
) {
    val episode = item.kind == BaseItemKind.EPISODE && item.detailsId != item.id
    val series = if (episode) item.detailsId.toString() else null
    if (action == ContinueAction.WATCHED && episode) {
        // Kept here first, so the show's Next Up already moves on
        hook.overlay.markWatched(item.id.toString(), series, item.title)
        val next = kotlinx.coroutines.withTimeoutOrNull(NEXT_WAIT_MS) { repo.nextUpOf(item.detailsId) }?.takeIf { it.key != item.key && shown.none { s -> s.key == it.key } }
        val at = state.items.indexOfFirst { it.key == item.key }
        if (next != null && at >= 0) {
            state.returnKey = next.key
            state.items[at] = next
            focusCard(state)
            sendWatched(repo, item)
            return
        }
    }
    // Out of the row: the neighbour takes focus first, so focus never falls out of the row
    val i = shown.indexOfFirst { it.key == item.key }
    val neighbour = shown.getOrNull(i + 1) ?: shown.getOrNull(i - 1)
    if (neighbour != null) {
        state.returnKey = neighbour.key
        focusCard(state)
    }
    when (action) {
        ContinueAction.REMOVE -> hook.overlay.dismiss(item.id.toString(), series)
        ContinueAction.WATCHED -> if (!episode) hook.overlay.markWatched(item.id.toString(), null, item.title)
    }
    val at = maxOf(hook.overlay.lastPlayed(item.id.toString()) ?: 0L, series?.let { hook.overlay.seriesLastPlayed(it) } ?: 0L, System.currentTimeMillis())
    ContinueEdits.gone[item.key] = ContinueEdits.Gone(item.id.toString(), series, at, watched = action == ContinueAction.WATCHED)
    when (action) {
        ContinueAction.REMOVE -> runCatching { repo.sendRemoveFromContinue(item) }
        ContinueAction.WATCHED -> sendWatched(repo, item)
    }
}

/** The server told too (Silo ignores it; other servers keep it); the overlay already has it. */
private suspend fun sendWatched(
    repo: CinemaRepository,
    item: CinemaItem,
) {
    runCatching { repo.sendMarkWatched(item) }
}

private suspend fun focusCard(state: RowState) {
    for (attempt in 0 until 20) {
        withFrameNanos {}
        if (runCatching { state.cardFocus.requestFocus() }.getOrDefault(false)) return
    }
}

/** How long an episode marked watched waits for the show's next one before it just leaves. */
private const val NEXT_WAIT_MS = 4_000L

private val CloseIcon: ImageVector =
    ImageVector
        .Builder("Close", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString("M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z").toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.White),
        ).build()

private val CheckIcon: ImageVector =
    ImageVector
        .Builder("Check", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString("M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z").toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.White),
        ).build()
