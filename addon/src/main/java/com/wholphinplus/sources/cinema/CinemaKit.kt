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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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

/**
 * How many rows (and pages of rows) are visibly moving right now. The billboard waits for none before it changes: a
 * change builds a new panel, a 20-30 ms frame on the Shield, and mid-scroll that was a stutter.
 */
internal object RowMotion {
    val moving = androidx.compose.runtime.mutableIntStateOf(0)
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
                style = androidx.compose.ui.graphics.drawscope.Stroke(w),
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
internal fun ReportMotion(state: androidx.compose.foundation.lazy.LazyListState) {
    LaunchedEffect(state) {
        var counted = false
        fun count(m: Boolean) {
            if (m == counted) return
            counted = m
            RowMotion.moving.intValue += if (m) 1 else -1
            if (android.os.Build.VERSION.SDK_INT >= 29) android.os.Trace.setCounter("Orca:rows moving", RowMotion.moving.intValue.toLong())
        }
        try {
            snapshotFlow { state.isScrollInProgress }.collectLatest { scrolling ->
                if (!scrolling) return@collectLatest count(false)
                count(true)
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
                    count(step > STILL_PX)
                }
            }
        } finally {
            if (counted) RowMotion.moving.intValue -= 1
        }
    }
}

/** Movement per frame, in pixels, that reads as standing still (a spring's soft landing). */
private const val STILL_PX = 2

/** What had focus on a tab when it opened a page over itself; it takes focus back on the way back. */
internal object ReturnFocus {
    var target: androidx.compose.ui.focus.FocusRequester? = null
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
    val zoom = remember { Animatable(1f) }
    LaunchedEffect(drift) { if (drift) zoom.animateTo(1.06f, tween(12_000, easing = LinearOutSlowInEasing)) }
    // Each new picture eases back from a touch closer as it fades in: it arrives, not just appears
    // (Not one shown at once: it matches the picture the screen came from)
    val arrive = remember { Animatable(if (settle) 1.035f else 1f) }
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

/** Fixed-size requests so a preloaded image is a memory-cache hit when it's shown. */
internal fun request(
    context: android.content.Context,
    url: String,
    width: Int = 1280,
    height: Int = 720,
) = ImageRequest.Builder(context).data(url).size(width, height).build()

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

/** The streaming-app kind tag above a title's logo: a purple plus and "SERIES" or "FILM". */
@Composable
internal fun KindTag(
    kind: org.jellyfin.sdk.model.api.BaseItemKind,
    modifier: Modifier = Modifier,
) {
    val series = kind == org.jellyfin.sdk.model.api.BaseItemKind.SERIES || kind == org.jellyfin.sdk.model.api.BaseItemKind.EPISODE
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("+", color = Plus, fontSize = 15.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(5.dp))
        Text(if (series) "SERIES" else "FILM", color = InkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
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
