package com.wholphinplus.sources.cinema

import androidx.compose.foundation.gestures.detectTapGestures

import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.wholphinplus.sources.core.TitleArt

// Palette shared by every Cinema screen: true black stage, off-white type, red only for labels
internal val Stage = Color(0xFF0B0B0B)
internal val Ink = Color(0xFFF2F2F2)
internal val InkDim = Color(0xFFB3B3B3)
internal val Label = Color(0xFFD62839)
internal val Plus = Color(0xFFA78BFA)

/**
 * The Cinema ease: a soft start and a long, gentle landing (owner, 2026-10-06: "not another
 * Android motion… slow but smooth"). Material's (0.2, 0, 0, 1) leapt off the mark; this one eases in.
 */
internal val CinemaEase = CubicBezierEasing(0.32f, 0f, 0.12f, 1f)

/** For crossfades: even at both ends, so neither picture lingers or vanishes. */
internal val CinemaFade = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * How browsing moves (owner, 2026-10-06: "like Apple TV… floaty, not a snap", then "less jumpy,
 * a little more slow"): a spring with no bounce, half way in ~0.16 s and settled in ~0.55 s
 * (it was 260: there in ~0.25 s, which read as a jump). Unlike a fixed-length ease it keeps its
 * speed when the next press lands mid-move, so quick presses glide on instead of restarting.
 */
internal val CinemaGlide = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 110f)

/** Whether the row a card sits in is the one the remote rests on (its pictures start at once). */
internal val LocalRowRested = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Boolean>?> { null }

/**
 * A held Up/Down driven by the app, at a steady pace, from [HOLD_START_MS] after the press. The
 * remote's own repeats start ~0.4 s after the press, and by then the first press's glide had
 * landed (the page counted as still at ~380 ms in Shield traces): every hold stopped, then took
 * off again (owner, 2026-10-09: "as soon as I hold down or up it hits a stutter off the jump").
 * Now the moves keep coming before the glide slows, one every [STEP_MS], each as a key event
 * through the page (so every key handler still applies); the remote's repeats are ignored while
 * it drives, and letting go (or any other key) stops it.
 */
internal class HoldRepeat(
    private val view: android.view.View,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private var job: kotlinx.coroutines.Job? = null
    private var held = 0

    // Our own key events pass straight through
    private var injecting = false

    fun on(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (injecting) return false
        val n = e.nativeKeyEvent
        val upDown = n.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || n.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
        if (!upDown) {
            if (n.action == android.view.KeyEvent.ACTION_DOWN) stop()
            return false
        }
        return when (n.action) {
            android.view.KeyEvent.ACTION_DOWN ->
                if (n.repeatCount == 0) {
                    // The press itself moves as always; a hold takes over shortly after
                    stop()
                    held = n.keyCode
                    val down = n.downTime
                    job = scope.launch {
                        kotlinx.coroutines.delay(HOLD_START_MS)
                        drive(n.keyCode, down)
                    }
                    false
                } else {
                    // The remote's repeats: ours set the pace while we drive
                    job?.isActive == true
                }
            android.view.KeyEvent.ACTION_UP -> {
                if (n.keyCode == held) stop()
                false
            }
            else -> false
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        held = 0
    }

    private suspend fun drive(
        code: Int,
        downTime: Long,
    ) {
        var repeat = 1
        while (true) {
            val started = android.os.SystemClock.uptimeMillis()
            val event = android.view.KeyEvent(downTime, started, android.view.KeyEvent.ACTION_DOWN, code, repeat++)
            injecting = true
            val moved = try {
                view.dispatchKeyEvent(event)
            } finally {
                injecting = false
            }
            if (!moved) return
            // Each move drawn before the next (as [HeldKeys] asks of the remote's repeats)
            androidx.compose.runtime.withFrameNanos {}
            androidx.compose.runtime.withFrameNanos {}
            val left = STEP_MS - (android.os.SystemClock.uptimeMillis() - started)
            if (left > 0) kotlinx.coroutines.delay(left)
        }
    }

    companion object {
        /** A press held this long becomes a hold (a tap is let go well before). */
        const val HOLD_START_MS = 220L

        /** One row per step while held: about the pace the remote's repeats managed through [HeldKeys]. */
        const val STEP_MS = 105L
    }
}

/** [HoldRepeat] on a page: put it before the page's other key handling. */
@Composable
internal fun Modifier.holdRepeat(): Modifier {
    val view = androidx.compose.ui.platform.LocalView.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val hold = remember(view, scope) { HoldRepeat(view, scope) }
    androidx.compose.runtime.DisposableEffect(hold) { onDispose { hold.stop() } }
    return onPreviewKeyEvent { hold.on(it) }
}

/** Held D-pad keys: a repeat waits for the last move to be drawn ([behind]). */
internal object HeldKeys {
    /** Frames that must start after a move before a held key's next repeat is taken. */
    private const val FRAMES = 2

    private var framesSince = FRAMES
    private var lastEvent: android.view.KeyEvent? = null
    private var lastDropped = false
    private val count: android.view.Choreographer.FrameCallback =
        android.view.Choreographer.FrameCallback {
            framesSince++
            if (framesSince < FRAMES) post()
        }

    private fun post(): Unit = android.view.Choreographer.getInstance().postFrameCallback(count)

    /**
     * True for a held D-pad key's repeat that comes before the last move has been drawn: skip it.
     * Each Up/Down can build a new row (40-120 ms on the Shield's Tegra) and a held key repeats as
     * soon as the app takes the last one, so moves ran back to back with frames only squeezed in
     * between: 150-260 ms freezes, then a jump (owner, 2026-10-09: "stutter in scrolling up and
     * down rows fast"). Now every move gets its frames; single presses are never dropped.
     */
    fun behind(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val n = e.nativeKeyEvent
        if (n.keyCode !in DPAD_CODES) return false
        if (n.action == android.view.KeyEvent.ACTION_UP) {
            if (n !== lastEvent) CardFill.released()
            lastEvent = n
            return false
        }
        if (n.action != android.view.KeyEvent.ACTION_DOWN) return false
        // The same event seen by an outer and an inner page: the same answer
        if (n === lastEvent) return lastDropped
        lastEvent = n
        lastDropped = n.repeatCount > 0 && framesSince < FRAMES
        // Held or pressed, the remote is in use: cards coming in wait to fill
        CardFill.keyed(upDown = n.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || n.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        if (!lastDropped) {
            framesSince = 0
            android.view.Choreographer.getInstance().removeFrameCallback(count)
            post()
        }
        return lastDropped
    }

    private val DPAD_CODES =
        setOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
}

/**
 * Cards built while the remote is moving show only their shell (size, focus, lift, caption);
 * the picture, logo and tags fill in once everything is still, [PER_FRAME] cards a frame.
 * Building a card's face is ~5 ms on the Shield and a row brings ~8: done inside a move, that
 * stopped the glide (owner, 2026-10-09: "preserve the motion and movement with scrolling at all
 * costs… even if there is no data yet").
 */
internal object CardFill {
    /** Card faces built per frame, however they come: one face is 7-9 ms on the Shield's Tegra. */
    private const val PER_FRAME = 1

    /**
     * After the last D-pad press, this long counts as still pressing: the remote's wait before a
     * held key starts repeating, and a margin. It was 220 ms, so in the half second before the
     * repeats began the app took the remote as let go and started filling cards and changing the
     * billboard, right as the repeats took off (owner, 2026-10-09: "when you hold scroll it hits
     * then lets up and takes off").
     */
    private val KEY_QUIET_MS = android.view.ViewConfiguration.getKeyRepeatTimeout().toLong() + 150L


    private val keyActive = androidx.compose.runtime.mutableStateOf(false)
    private val upDownActive = androidx.compose.runtime.mutableStateOf(false)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val quiet = Runnable { keyActive.value = false }
    private val upDownQuiet = Runnable { upDownActive.value = false }

    /** A D-pad press was taken ([HeldKeys.behind]); [upDown]: Up or Down. Busy while it's held. */
    fun keyed(upDown: Boolean) {
        keyActive.value = true
        handler.removeCallbacks(quiet)
        // A fallback only: the key's release normally ends it ([released])
        handler.postDelayed(quiet, KEY_QUIET_MS)
        if (upDown) {
            upDownActive.value = true
            handler.removeCallbacks(upDownQuiet)
            handler.postDelayed(upDownQuiet, KEY_QUIET_MS)
        }
    }

    /**
     * A D-pad key let go: a single press is over at once, so its cards fill as soon as the glide
     * lands (they waited out the remote's repeat delay, ~0.65 s, after every press). A held key
     * stays busy until this, through its repeat delay too.
     */
    fun released() {
        handler.removeCallbacks(quiet)
        handler.removeCallbacks(upDownQuiet)
        handler.postDelayed(quiet, RELEASE_QUIET_MS)
        handler.postDelayed(upDownQuiet, RELEASE_QUIET_MS)
    }

    private const val RELEASE_QUIET_MS = 120L

    /** Titles whose card face was built this run ([rememberCardFilled]). */
    val seen: MutableSet<Any> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /**
     * Cards wait to fill while the page moves up or down: each step brings a whole row. Along a
     * row they fill as they come (one card a step, smooth on the Shield; owner, 2026-10-09:
     * sideways "never seen it that fast… but posters don't show").
     */
    val busy: Boolean get() = upDownActive.value || RowMotion.pageMoving.intValue > 0

    /** The remote is in use or anything still glides (the billboard waits for this). */
    val anyBusy: Boolean get() = keyActive.value || RowMotion.moving.intValue > 0

    // This frame's face budget, reset at the next frame
    private var used = 0
    private var resetPosted = false
    private val reset =
        android.view.Choreographer.FrameCallback {
            used = 0
            resetPosted = false
        }

    /** Until then (uptime) every face builds at once: the app's first screen, before anyone moves ([StartWarmup]). */
    @Volatile var openUntil = 0L

    /** One face's room in this frame, if there's any left (main thread). */
    fun claim(): Boolean {
        if (android.os.SystemClock.uptimeMillis() < openUntil) return true
        if (used >= PER_FRAME) return false
        used++
        if (!resetPosted) {
            resetPosted = true
            android.view.Choreographer.getInstance().postFrameCallback(reset)
        }
        return true
    }

    private var frame = 0L
    private var stillFrames = 0

    private var pictureFrame = 0L
    private var pictures = 0

    /** Waits for a frame with room for one more picture to start. */
    suspend fun pictureSlot() {
        while (true) {
            val t = androidx.compose.runtime.withFrameNanos { it }
            if (t != pictureFrame) {
                pictureFrame = t
                pictures = 0
            }
            if (pictures < com.wholphinplus.sources.DeviceClass.picturesPerFrame) {
                pictures++
                return
            }
        }
    }

    /**
     * Waits for a frame with room for one face: after a few still frames, or for a card shown
     * before ([urgent]: its picture is in memory, so it comes back without waiting for stillness).
     */
    suspend fun ticket(urgent: Boolean) {
        while (true) {
            val t = androidx.compose.runtime.withFrameNanos { it }
            if (t != frame) {
                frame = t
                stillFrames = if (busy) 0 else stillFrames + 1
            }
            if ((urgent || stillFrames > com.wholphinplus.sources.DeviceClass.settleFrames) && claim()) return
        }
    }
}

/**
 * Whether this card may build its face now ([CardFill]): at once when nothing moves and the frame
 * has room, or when this title's card was shown before (its picture is in memory: scrolling back
 * up showed the rows above as blank cards filling in again, owner 2026-10-09: "why can't it
 * remember the loaded posters"); otherwise in a later frame, one face a frame.
 */
@Composable
internal fun rememberCardFilled(key: Any): Boolean {
    val filled =
        remember(key) {
            // Read without subscribing: a read here made every built card recompose each time motion
            // started or stopped (30-50 cards, a 10-25 ms frame on the Shield right as a glide began)
            val idle = !androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { CardFill.busy }
            // A card shown before doesn't build here either: built inside a key press (a row coming
            // back into view), its face cost the press ~8 ms a card. It fills in the next frames
            androidx.compose.runtime.mutableStateOf(idle && CardFill.claim())
        }
    if (!filled.value) {
        LaunchedEffect(key) {
            // Shown before, or its picture loaded ahead: no need to wait for the page to stop
            val inMemory = key in CardFill.seen || key in PosterPrefetch.ready
            CardFill.ticket(urgent = inMemory && com.wholphinplus.sources.DeviceClass.fillWhileMoving)
            filled.value = true
        }
    }
    if (filled.value) CardFill.seen += key
    return filled.value
}

/**
 * How many rows (and pages of rows) are visibly moving right now. The billboard waits for none before it changes: a
 * change builds a new panel, a 20-30 ms frame on the Shield, and mid-scroll that was a stutter.
 */
internal object RowMotion {
    val moving = androidx.compose.runtime.mutableIntStateOf(0)

    /** Of those, pages moving up or down ([ReportMotion] with `page`). */
    val pageMoving = androidx.compose.runtime.mutableIntStateOf(0)
}

/**
 * A focused card's lift ([scale]) on [CinemaGlide], with its white edge ([corner] rounded) fading
 * in alongside instead of popping on. The TV cards' own scale and border are switched off (theirs
 * snap). Read only in the draw layer: the animation never recomposes the card.
 */
@Composable
internal fun Modifier.glideLift(
    scale: Float = 1.08f,
    corner: Dp = 6.dp,
    // The white edge: off for pills and buttons whose fill already shows focus; [edgeInset] < 0
    // draws it as a ring outside the shape
    edge: Boolean = true,
    edgeWidth: Dp = 3.dp,
    edgeInset: Dp = 0.dp,
    onFocused: () -> Unit = {},
): Modifier {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val lift = androidx.compose.animation.core.animateFloatAsState(if (focused) scale else 1f, CinemaGlide, label = "lift")
    val edgeAlpha = androidx.compose.animation.core.animateFloatAsState(if (focused && edge) 1f else 0f, tween(if (focused) 320 else 220, easing = CinemaFade), label = "edge")
    // The edge's stroke, made once (not on every frame of the fade)
    val strokes = remember { arrayOfNulls<androidx.compose.ui.graphics.drawscope.Stroke>(1) }
    return graphicsLayer {
        scaleX = lift.value
        scaleY = lift.value
    }.drawWithContent {
        drawContent()
        val a = if (edge) edgeAlpha.value else 0f
        if (a > 0f) {
            val w = edgeWidth.toPx()
            val at = edgeInset.toPx() + w / 2
            val r = (corner.toPx() - at).coerceAtLeast(0f)
            drawRoundRect(
                color = Ink.copy(alpha = a),
                topLeft = androidx.compose.ui.geometry.Offset(at, at),
                size = androidx.compose.ui.geometry.Size(size.width - 2 * at, size.height - 2 * at),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                style = strokes[0]?.takeIf { it.width == w } ?: androidx.compose.ui.graphics.drawscope.Stroke(w).also { strokes[0] = it },
            )
        }
    }.onFocusChanged {
        focused = it.isFocused
        if (it.isFocused) onFocused()
    }
}

/** [glideLift] with its white edge, as a Modifier to chain from other packages. */
@Composable
internal fun glideEdge(
    scale: Float,
    corner: Dp,
): Modifier = Modifier.glideLift(scale = scale, corner = corner)

/** [glideLift] with a ring just outside a pill (the billboard's Play). */
@Composable
internal fun glideRing(scale: Float): Modifier = Modifier.glideLift(scale = scale, corner = 100.dp, edgeWidth = 2.dp, edgeInset = (-4).dp)

/** A control's colours, eased between rest and focus ([rememberFocusFade]). */
internal class FocusFade(
    val source: androidx.compose.foundation.interaction.MutableInteractionSource,
    private val container: androidx.compose.runtime.State<Color>,
    private val ink: androidx.compose.runtime.State<Color>,
) {
    val fill: Color get() = container.value
    val content: Color get() = ink.value
}

/**
 * TV Material switches a control's colours the instant it gains focus (the white fill popped on
 * while the zoom eased). This eases them instead: pass [FocusFade.source] as the control's
 * interaction source and the same [FocusFade.fill]/[FocusFade.content] for both its resting and
 * focused colours, and give it `scale = 1` with [glideLift] for the zoom.
 */
@Composable
internal fun rememberFocusFade(
    rest: Color,
    focused: Color,
    restInk: Color,
    focusedInk: Color,
    // Keyboard keys and suggestion lists: typing must feel instant, so a much shorter ease
    quick: Boolean = false,
): FocusFade {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val on by source.collectIsFocusedAsState()
    val spec = tween<Color>(if (quick) 130 else if (on) 240 else 200, easing = CinemaFade)
    val fill = androidx.compose.animation.animateColorAsState(if (on) focused else rest, spec, label = "fill")
    val ink = androidx.compose.animation.animateColorAsState(if (on) focusedInk else restInk, spec, label = "ink")
    return remember(source) { FocusFade(source, fill, ink) }
}

/**
 * Counts [state] in [RowMotion] while it visibly moves. A spring's last few hundred ms creep a
 * pixel or less a frame, which nobody sees as motion: the row counts as still once it moves under
 * [STILL_PX] a frame, so the billboard needn't wait out the whole soft landing.
 */
@Composable
internal fun ReportMotion(
    state: androidx.compose.foundation.lazy.LazyListState,
    page: Boolean = false,
) {
    LaunchedEffect(state) {
        var counted = false
        fun count(m: Boolean) {
            if (m == counted) return
            counted = m
            RowMotion.moving.intValue += if (m) 1 else -1
            if (page) RowMotion.pageMoving.intValue += if (m) 1 else -1
            if (android.os.Build.VERSION.SDK_INT >= 29) android.os.Trace.setCounter("Orca:rows moving", RowMotion.moving.intValue.toLong())
        }
        try {
            snapshotFlow { state.isScrollInProgress }.collectLatest { scrolling ->
                if (!scrolling) return@collectLatest count(false)
                count(true)
                var calm = 0
                var index = state.firstVisibleItemIndex
                var offset = state.firstVisibleItemScrollOffset
                while (true) {
                    androidx.compose.runtime.withFrameNanos {}
                    val i = state.firstVisibleItemIndex
                    val o = state.firstVisibleItemScrollOffset
                    // A new first item: the offsets aren't comparable, so it's still moving
                    val step = if (i == index) kotlin.math.abs(o - offset) else Int.MAX_VALUE
                    index = i
                    offset = o
                    // Still only after a few calm frames: a spring's tail dips under the mark and back,
                    // and each flip woke everything waiting on [RowMotion]
                    calm = if (step > STILL_PX) 0 else calm + 1
                    if (step > STILL_PX) count(true) else if (calm >= CALM_FRAMES) count(false)
                }
            }
        } finally {
            if (counted) RowMotion.moving.intValue -= 1
        }
    }
}

/** Movement per frame, in pixels, that reads as standing still (a spring's soft landing). */
private const val STILL_PX = 2

/** Calm frames in a row before a list counts as still ([ReportMotion]). */
private const val CALM_FRAMES = 3

/** What had focus on a tab when it opened a page over itself; it takes focus back on the way back. */
internal object ReturnFocus {
    var target: androidx.compose.ui.focus.FocusRequester? = null

    /** A row's See all, for Back from its grid (kept apart: [target] holds the tile a page was opened from). */
    var grid: androidx.compose.ui.focus.FocusRequester? = null
}

/**
 * On a row (a LazyRow, a focus group of its own): Right past its last card or Left past its first
 * stays put. Focus search went on to whatever lay that way, the top menu or the Settings gear
 * (Shield walk 2026-10-08: Right after a Top 10's #10 landed on the Kids tab). Up and Down leave as usual.
 */
internal fun Modifier.staysInRow(): Modifier =
    focusProperties {
        onExit = {
            if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Left ||
                requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Right
            ) {
                cancelFocusChange()
            }
        }
    }

internal val LocalArt = staticCompositionLocalOf<CinemaArt?> { null }

/** TMDB title art: known art on the first frame, otherwise fetched once and then cached. */
@Composable
internal fun rememberArt(item: CinemaItem?): TitleArt? {
    val art = LocalArt.current
    val tmdb = item?.tmdbId
    // Keyed on the TMDB id too: an episode's card can draw before its series' id is known (the
    // first rows don't wait for it), and would otherwise never look its art up
    return produceState(tmdb?.let { art?.cached(item.tmdbTv, it) }, item?.key, tmdb) {
        if (value == null && tmdb != null) value = art?.art(item.tmdbTv, tmdb)
    }.value
}

/** A card's TMDB art: known, or still being looked up ([waiting]: for at most [ART_WAIT_MS]). */
@Stable
internal class ArtState(
    art: TitleArt?,
    waiting: Boolean,
) {
    var art by mutableStateOf(art)
    var waiting by mutableStateOf(waiting)
}

/**
 * How long a card holds its picture for TMDB's art before it shows the server's. It used to start
 * the server's picture at once and switch when the art came (0.2-1 s later): two downloads per
 * card (the first one is never cancelled in time on HTTP/2) and a visible swap of picture and
 * name for logo.
 */
internal const val ART_WAIT_MS = 1_500L

/**
 * [rememberArt] for a card: one lookup shared by the card's picture, logo and caption, and
 * whether it's still worth waiting for.
 */
@Composable
internal fun rememberArtState(item: CinemaItem?): ArtState {
    val art = LocalArt.current
    val tmdb = item?.tmdbId
    val state =
        remember(item?.key, tmdb) {
            val known = tmdb?.let { art?.cached(item.tmdbTv, it) }
            ArtState(known, waiting = known == null && tmdb != null && art?.enabled == true)
        }
    if (state.art == null && tmdb != null && art != null) {
        LaunchedEffect(state) {
            val timer =
                launch {
                    kotlinx.coroutines.delay(ART_WAIT_MS)
                    state.waiting = false
                }
            val got = art.art(item.tmdbTv, tmdb)
            if (got != null) state.art = got
            state.waiting = false
            timer.cancel()
        }
    }
    return state
}

/**
 * Scroll so the focused child lands [offsetPx] from the start. Only says where: [gliding] makes
 * it move on [CinemaGlide].
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun pivot(offsetPx: Float): BringIntoViewSpec =
    object : BringIntoViewSpec {
        override fun calculateScrollDistance(
            offset: Float,
            size: Float,
            containerSize: Float,
        ): Float = offset - offsetPx
    }

/**
 * [this] spec's scrolling, done on [CinemaGlide] by [state] itself. Compose 1.12's lazy lists
 * wrap the spec for sticky headers and drop its animation (they always used the stock spring,
 * stiffness 1500: every move snapped over in ~0.15 s whatever was asked). So Compose is told the
 * child needs no scrolling, and the glide runs here, keeping its speed when a new press retargets.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BringIntoViewSpec.gliding(state: androidx.compose.foundation.gestures.ScrollableState): BringIntoViewSpec {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val inner = this
    return remember(inner, state, scope) { Glider(inner, state, scope) }
}

@OptIn(ExperimentalFoundationApi::class)
private class Glider(
    private val inner: BringIntoViewSpec,
    private val state: androidx.compose.foundation.gestures.ScrollableState,
    private val scope: kotlinx.coroutines.CoroutineScope,
) : BringIntoViewSpec {
    /** Where the glide is, and is headed, in pixels scrolled since it began; and its speed. */
    private var at = 0f
    private var target = 0f
    private var velocity = 0f
    private var job: kotlinx.coroutines.Job? = null

    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val wanted = inner.calculateScrollDistance(offset, size, containerSize)
        // At an end the list can't go further: asking again each layout would loop
        if ((wanted > 0f && !state.canScrollForward) || (wanted < 0f && !state.canScrollBackward)) return 0f
        val goal = at + wanted
        if (kotlin.math.abs(goal - target) >= 1f || job?.isActive != true && kotlin.math.abs(wanted) >= 1f) aim(goal)
        return 0f
    }

    private fun aim(goal: Float) {
        // The other way: start from rest, not by braking the old glide to a stop first (a reversal
        // read as a stall, then a take-off)
        if (velocity != 0f && (goal - at) * velocity < 0f) velocity = 0f
        target = goal
        job?.cancel()
        job =
            scope.launch {
                state.scroll {
                    androidx.compose.animation.core.animate(at, goal, velocity, CinemaGlide) { value, v ->
                        val step = value - at
                        val used = scrollBy(step)
                        at += used
                        velocity = v
                        // Hit an end: stop here
                        if (kotlin.math.abs(used - step) > 0.5f) throw kotlinx.coroutines.CancellationException()
                    }
                }
                velocity = 0f
                target = at
            }
        job?.invokeOnCompletion { if (it != null && job?.isActive != true) { velocity = 0f; target = at } }
    }
}

/**
 * Full-screen backdrop that only crossfades once the next image is decoded, so it never fades
 * through black, with an optional slow drift.
 *
 * The bitmap is full-bleed. A narrower frame left a hard vertical edge behind the title: the
 * scrim's first stop sat on that edge and was not solid, so the cut showed through on every
 * banner. The scrim stays solid black past where that edge used to be, then eases out.
 */
@Composable
internal fun StableBackdrop(
    url: String?,
    drift: Boolean,
    modifier: Modifier = Modifier,
    widthFraction: Float = 0.80f,
    heightFraction: Float = 0.82f,
) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<String?>(null) }
    // The first picture, when it was already in memory (the title page opened from a card whose
    // picture the billboard just showed): there at once, not faded up from black. The screen
    // change fades it in already; a second fade from nothing was the black dip opening a title.
    var instant by remember { mutableStateOf(false) }
    LaunchedEffect(url) {
        // No backdrop: clear to the stage rather than keep the previous title's picture
        if (url == null) {
            shown = null
            return@LaunchedEffect
        }
        val started = android.os.SystemClock.uptimeMillis()
        val ok = SingletonImageLoader.get(context).execute(request(context, url)) is SuccessResult
        if (ok) {
            instant = shown == null && android.os.SystemClock.uptimeMillis() - started < INSTANT_MS
            shown = url
        }
    }
    // Where the old inset bitmap began. Solid black covers that line, then the picture eases in.
    val seam = (1f - widthFraction).coerceIn(0f, 0.5f)
    val solid = (seam + 0.16f).coerceAtMost(0.46f)
    val fadeMid = (solid + 0.26f).coerceAtMost(0.76f)
    // The picture before and the one fading in over it (900 ms, eased at both ends). Not Crossfade: its fade drew
    // each full-screen picture into a layer of its own and then the layer on screen, every frame
    // of every change (302 of 506 frames while browsing, on the Shield's GPU). One picture fades
    // just as well drawn with the alpha.
    var before by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf<String?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(shown) {
        if (shown == current) return@LaunchedEffect
        // Changed again mid-fade: the more visible of the two is what fades out
        before = if (fade.value >= 0.5f) current else before
        current = shown
        if (before == null && instant) {
            fade.snapTo(1f)
        } else {
            fade.snapTo(0f)
            fade.animateTo(1f, tween(900, easing = CinemaFade))
        }
        before = null
    }
    Box(modifier.fillMaxSize()) {
        before?.let { u -> key(u) { BackdropPicture(u, drift, alpha = { 1f - fade.value }) } }
        current?.let { u -> key(u) { BackdropPicture(u, drift, alpha = { fade.value }, settle = !(instant && before == null)) } }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Stage,
                    solid to Stage,
                    fadeMid to Stage.copy(alpha = 0.35f),
                    0.88f to Color.Transparent,
                ),
            ),
        )
        val bottomSolid = (heightFraction - 0.02f).coerceIn(0.72f, 0.92f)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Stage.copy(alpha = 0.55f),
                    0.12f to Color.Transparent,
                    0.5f to Color.Transparent,
                    bottomSolid to Stage,
                ),
            ),
        )
    }
}

/** One backdrop picture: a slow zoom once (while [drift]), faded by [alpha] without a layer of its own. */
@Composable
private fun BackdropPicture(
    url: String,
    drift: Boolean,
    alpha: () -> Float,
    settle: Boolean = true,
) {
    val context = LocalContext.current
    // One slow zoom per picture, then it holds. (It used to drift back and forth for ever,
    // redrawing the whole screen 60 times a second even with nobody watching.)
    // A Light box (DeviceClass) skips both moves: each redraws the whole screen for seconds
    val still = com.wholphinplus.sources.DeviceClass.light
    val zoom = remember { Animatable(1f) }
    LaunchedEffect(drift) { if (drift && !still) zoom.animateTo(1.06f, tween(12_000, easing = LinearOutSlowInEasing)) }
    // Each new picture eases back from a touch closer as it fades in: it arrives, not just appears
    // (Not one shown at once: it matches the picture the screen came from)
    val arrive = remember { Animatable(if (settle && !still) 1.035f else 1f) }
    LaunchedEffect(Unit) { if (arrive.value != 1f) arrive.animateTo(1f, tween(1_400, easing = CinemaEase)) }
    AsyncImage(
        model = request(context, url),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier =
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom.value * arrive.value
                scaleY = zoom.value * arrive.value
                this.alpha = alpha()
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
            },
    )
}

/**
 * Loads a backdrop into the memory cache (same size as [StableBackdrop] asks for), so when it's
 * shown it crossfades in the same frame as the title text instead of half a second later.
 */
internal suspend fun preloadBackdrop(
    context: android.content.Context,
    url: String?,
    timeoutMs: Long,
) {
    if (url == null) return
    kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { SingletonImageLoader.get(context).execute(request(context, url)) }
}

/** A picture that loads this fast came from memory: it can be shown without a fade. */
private const val INSTANT_MS = 120L

/**
 * Fixed-size requests so a preloaded image is a memory-cache hit when it's shown. Plain bitmaps
 * (see rememberCardPicture): the billboard fetches the next backdrop as soon as focus moves, and
 * as a hardware bitmap its upload landed mid-glide and held the frame 50-200 ms.
 */
internal fun request(
    context: android.content.Context,
    url: String,
    width: Int = 1280,
    height: Int = 720,
) = ImageRequest.Builder(context).data(url).size(width, height).allowHardware(false).build()

@Composable
internal fun Wordmark(
    modifier: Modifier = Modifier,
    size: Int = 22,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("ORCA", color = Ink, fontSize = size.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (size * 0.06).sp)
        Text("+", color = Plus, fontSize = size.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Immutable
internal data class Meta(
    val parts: List<String>,
    val rating: String?,
    val quality: String? = null,
)

@Composable
internal fun MetaLine(meta: Meta) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        meta.parts.forEachIndexed { i, m ->
            if (i > 0) Text("•", color = InkDim, fontSize = 13.sp)
            Text(m, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        meta.rating?.let { Boxed(it) }
        meta.quality?.let { Boxed(it) }
    }
}

@Composable
private fun Boxed(text: String) {
    Text(
        text,
        color = Ink,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.border(1.dp, InkDim, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
internal fun HeroButton(
    text: String,
    icon: ImageVector,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    val f = rememberFocusFade(if (primary) Ink else Color.White.copy(alpha = 0.22f), Ink, if (primary) Stage else Ink, Stage)
    Button(
        onClick = onClick,
        // The ring fades in a little outside the pill as it grows
        modifier = modifier.glideLift(scale = 1.06f, corner = 100.dp, edgeWidth = 2.dp, edgeInset = (-4).dp, onFocused = onFocused).tapToClick(onClick),
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        colors = ButtonDefaults.colors(containerColor = f.fill, contentColor = f.content, focusedContainerColor = f.fill, focusedContentColor = f.content),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        interactionSource = f.source,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** Thin red progress bar along a card's bottom edge. */
@Composable
internal fun ProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
) {
    Box(modifier.fillMaxWidth().height(height).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Label))
    }
}

/**
 * Mouse clicks (and touch) reach a TV app as taps, which only move the focus on TV components.
 * This makes one tap focus the element and act on it, like OK on the remote.
 */
internal fun Modifier.tapToClick(onClick: () -> Unit): Modifier =
    this.pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }

/** Lets other packages reach [tapToClick]. */
internal object TapHelper {
    fun Modifier.tap(onClick: () -> Unit): Modifier = tapToClick(onClick)

    /** [glideLift] without the white edge, for controls whose fill shows focus. */
    @Composable
    fun Modifier.glideFocus(scale: Float): Modifier = glideLift(scale = scale, edge = false)
}

/**
 * Wholphin's navigation pushes a page per call, so a quick double OK (or a held key) opened the
 * same title page twice, or two players, and Back then showed it again. One push per press.
 */
internal object NavGuard {
    private var last = 0L

    fun allow(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - last < WINDOW_MS) return false
        last = now
        return true
    }

    private const val WINDOW_MS = 800L
}

/** [f] behind [NavGuard], for a screen's onOpen and onPlay. */
@Composable
internal fun <A, B> guarded(f: (A, B) -> Unit): (A, B) -> Unit = remember(f) { { a, b -> if (NavGuard.allow()) f(a, b) } }
