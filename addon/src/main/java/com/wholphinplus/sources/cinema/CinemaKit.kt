package com.wholphinplus.sources.cinema

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
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

/** The streaming-app ease: quick start, long soft landing. Used for every Cinema motion. */
internal val CinemaEase = CubicBezierEasing(0.2f, 0f, 0f, 1f)

internal val LocalArt = staticCompositionLocalOf<CinemaArt?> { null }

/** TMDB title art: known art on the first frame, otherwise fetched once and then cached. */
@Composable
internal fun rememberArt(item: CinemaItem?): TitleArt? {
    val art = LocalArt.current
    val tmdb = item?.tmdbId
    return produceState(tmdb?.let { art?.cached(item.tmdbTv, it) }, item?.key) {
        if (value == null && tmdb != null) value = art?.art(item.tmdbTv, tmdb)
    }.value
}

/**
 * Scroll so the focused child lands [offsetPx] from the start, with one smooth ease (the default
 * is a springy animation that overshoots on a remote's quick repeats).
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun pivot(offsetPx: Float): BringIntoViewSpec =
    object : BringIntoViewSpec {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override val scrollAnimationSpec: AnimationSpec<Float> = tween(320, easing = CinemaEase)

        override fun calculateScrollDistance(
            offset: Float,
            size: Float,
            containerSize: Float,
        ): Float = offset - offsetPx
    }

/**
 * Full-screen backdrop that only crossfades once the next image is decoded, so it never fades
 * through black, with an optional slow drift. Faded into the stage from the left and bottom.
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
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val ok = SingletonImageLoader.get(context).execute(request(context, url)) is SuccessResult
        if (ok) shown = url
    }
    val t = rememberInfiniteTransition(label = "drift")
    val scale by t.animateFloat(1f, 1.06f, infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Reverse), label = "scale")
    Box(modifier.fillMaxSize()) {
        Crossfade(
            targetState = shown,
            animationSpec = tween(650, easing = LinearEasing),
            label = "backdrop",
            modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(widthFraction).fillMaxHeight(heightFraction),
        ) { u ->
            if (u != null) {
                AsyncImage(
                    model = request(context, u),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier =
                        Modifier.fillMaxSize().graphicsLayer {
                            val s = if (drift) scale else 1f
                            scaleX = s
                            scaleY = s
                        },
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Stage, 0.22f to Stage.copy(alpha = 0.92f), 0.5f to Stage.copy(alpha = 0.35f), 0.75f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Stage.copy(alpha = 0.55f), 0.12f to Color.Transparent, 0.5f to Color.Transparent, 0.8f to Stage)))
    }
}

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
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        colors =
            ButtonDefaults.colors(
                containerColor = if (primary) Ink else Color.White.copy(alpha = 0.22f),
                contentColor = if (primary) Stage else Ink,
                focusedContainerColor = Ink,
                focusedContentColor = Stage,
            ),
        border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Ink), inset = (-4).dp, shape = RoundedCornerShape(50))),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
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
