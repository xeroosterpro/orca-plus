package com.wholphinplus.sources.cinema

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.jellyfin.sdk.model.api.BaseItemKind

internal val BadgeText =
    TextStyle(
        fontWeight = FontWeight.Black,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )

internal val NumeralWidth = 84.dp

/** The Top 10 numbers' style: filled, and the outline drawn over it. */
@Composable
private fun rememberNumeral(): Pair<TextStyle, Stroke> {
    val density = LocalDensity.current
    return remember(density) {
        TextStyle(
            fontSize = 136.sp,
            lineHeight = 136.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-14).sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.Both),
        ) to Stroke(width = with(density) { 2.5.dp.toPx() })
    }
}

/** Set once the Top 10 numbers have been drawn in this process ([NumeralWarmup]). */
private var numeralsWarm = false

/**
 * Draws the Top 10 numbers once, out of sight, while the page is still. The outlined 136 sp
 * digits are built as paths the first time each is drawn: 40 ms on the Shield, which landed on
 * the move that first brought a Top 10 row into view. Drawn under an opaque box, then removed.
 */
@Composable
internal fun NumeralWarmup() {
    var on by remember { mutableStateOf(!numeralsWarm) }
    if (!on) return
    val (numeral, stroke) = rememberNumeral()
    Box(Modifier.size(NumeralWidth * 6, PosterHeight)) {
        Text("1234567890", color = Stage, style = numeral, maxLines = 1, softWrap = false)
        Text("1234567890", color = Color(0xFF8A8A8A), style = numeral.copy(drawStyle = stroke), maxLines = 1, softWrap = false)
        Box(Modifier.matchParentSize().background(Stage))
    }
    LaunchedEffect(Unit) {
        // A few frames on screen, after Home's own first frames
        delay(1_200)
        numeralsWarm = true
        on = false
    }
}
internal val PosterWidth = 112.dp
internal val PosterHeight = 168.dp

/**
 * A Top 10 tile: a big outlined number with the poster tucked over its right edge, the way the
 * streaming charts row looks.
 */
@Composable
internal fun Top10Card(
    item: CinemaItem,
    rank: Int,
    onFocused: (CinemaItem) -> Unit,
    onClick: (CinemaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(4.dp)
    val (numeral, stroke) = rememberNumeral()
    Row(verticalAlignment = Alignment.Bottom) {
        Box(Modifier.width(if (rank >= 10) NumeralWidth + 56.dp else NumeralWidth).height(PosterHeight), contentAlignment = Alignment.BottomEnd) {
            // Filled in stage black, outlined in grey, nudged under the poster
            Text("$rank", color = Stage, style = numeral, maxLines = 1, softWrap = false, modifier = Modifier.offset(x = 16.dp, y = 6.dp))
            Text("$rank", color = Color(0xFF8A8A8A), style = numeral.copy(drawStyle = stroke), maxLines = 1, softWrap = false, modifier = Modifier.offset(x = 16.dp, y = 6.dp))
        }
        Card(
            onClick = { onClick(item) },
            shape = CardDefaults.shape(shape),
            border = CardDefaults.border(focusedBorder = Border.None),
            scale = CardDefaults.scale(focusedScale = 1f),
            glow = CardDefaults.glow(),
            colors = CardDefaults.colors(containerColor = Color(0xFF1F1F1F)),
            modifier = modifier.width(PosterWidth).height(PosterHeight).glideLift(corner = 4.dp) { onFocused(item) }.tapToClick { onClick(item) },
        ) {
            if (rememberCardFilled(item.key)) PosterFace(item)
        }
    }
}

/** A Top 10 poster's picture and tags; shared with the settings preview. */
@Composable
internal fun PosterFace(item: CinemaItem) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        val url = item.posterUrl ?: item.cardUrl
        if (url != null) {
            CardPicture(url, 224, 336, contentDescription = item.title, modifier = Modifier.fillMaxSize())
        }
        if (item.posterUrl == null) {
            Text(item.title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        if (LocalOverlays.current.progress) item.progress?.let { p -> ProgressBar(p, Modifier.align(Alignment.BottomStart)) }
        PosterBadges(item, tall = true)
    }
}

@Composable
internal fun CinemaCard(
    item: CinemaItem,
    onFocused: (CinemaItem) -> Unit,
    onClick: (CinemaItem) -> Unit,
    width: Dp = 208.dp,
    /** On the card itself (the focusable part): a focus requester to come back to it. */
    modifier: Modifier = Modifier,
    /** OK held on the card (Continue Watching's menu); null: a long press is just a press. */
    onLongClick: ((CinemaItem) -> Unit)? = null,
) {
    if (com.wholphinplus.sources.DeviceClass.plainCards) return LightCard(item, onFocused, onClick, width, modifier, onLongClick)
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.width(width)) {
        // Built while the remote moves: the shell now, the face once still ([CardFill])
        val filled = rememberCardFilled(item.key)
        // One art lookup for the picture, logo, scores and caption, with the face (Home's warm-up
        // looks up the art of each row's first cards ahead; a lookup per shell made every key
        // press that brought in a row twice as heavy)
        val art = if (filled) rememberArtState(item) else null
        Card(
            onClick = { onClick(item) },
            onLongClick = onLongClick?.let { l -> { l(item) } },
            shape = CardDefaults.shape(shape),
            border = CardDefaults.border(focusedBorder = Border.None),
            scale = CardDefaults.scale(focusedScale = 1f),
            glow = CardDefaults.glow(),
            colors = CardDefaults.colors(containerColor = Color(0xFF1F1F1F)),
            modifier = modifier.width(width).aspectRatio(16f / 9f).glideLift { onFocused(item) }.tapToClick { onClick(item) },
        ) {
            if (art != null) CinemaCardFace(item, width, art)
        }
        if (LocalOverlays.current.captions) {
            if (com.wholphinplus.sources.DeviceClass.simpleMotion) {
                // Light boxes: the caption comes with the face. Laid out with the shell, its three
                // texts were ~90 text layouts in each frame that brought a row in (Fire Stick bench
                // 2026-10-11); its room is kept so the row doesn't grow when it fills
                Box(Modifier.fillMaxWidth().height(captionHeight())) { if (filled) CardCaption(item, art?.art) }
            } else {
                CardCaption(item, art?.art)
            }
        }
    }
}

/**
 * Light boxes' card (Fire Sticks): a plain focusable box instead of the TV [Card]. The TV card
 * keeps an interaction source, a focus state that recomposes it, and scale, border and glow
 * layers for every card, all switched off here anyway; on a stick that was part of each frame that
 * brought a row in. Here focus is one value read only where the white edge is drawn, so moving
 * along a row redraws two cards and recomposes none. Same face and caption as [CinemaCard].
 */
@Composable
internal fun LightCard(
    item: CinemaItem,
    onFocused: (CinemaItem) -> Unit,
    onClick: (CinemaItem) -> Unit,
    width: Dp,
    modifier: Modifier,
    onLongClick: ((CinemaItem) -> Unit)?,
) {
    Column(Modifier.width(width)) {
        val filled = rememberCardFilled(item.key)
        val art = if (filled) rememberArtState(item) else null
        val press = remember { PlainPress() }
        Box(
            modifier
                .width(width)
                .aspectRatio(16f / 9f)
                .clip(LightCardShape)
                .background(CardShell)
                .drawWithContent {
                    drawContent()
                    if (press.focused.value) {
                        val w = 3.dp.toPx()
                        val r = (6.dp.toPx() - w / 2).coerceAtLeast(0f)
                        drawRoundRect(
                            color = Ink,
                            topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                            size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                            style = press.stroke(w),
                        )
                    }
                }.onFocusChanged {
                    press.focused.value = it.isFocused
                    if (it.isFocused) onFocused(item)
                }.onKeyEvent { press.key(it, { onClick(item) }, onLongClick?.let { l -> { l(item) } }) }
                .focusable()
                .tapToClick { onClick(item) },
        ) {
            if (art != null) CinemaCardFace(item, width, art)
        }
        if (LocalOverlays.current.captions) {
            Box(Modifier.fillMaxWidth().height(captionHeight())) { if (filled) CardCaption(item, art?.art) }
        }
    }
}

private val LightCardShape = RoundedCornerShape(6.dp)
private val CardShell = Color(0xFF1F1F1F)

/** [LightCard]'s focus and OK key: a press counts only when it went down on this card. */
private class PlainPress {
    val focused = mutableStateOf(false)
    private var down = false
    private var long = false
    private var edge: Stroke? = null

    fun stroke(width: Float): Stroke = edge?.takeIf { it.width == width } ?: Stroke(width).also { edge = it }

    fun key(
        e: androidx.compose.ui.input.key.KeyEvent,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)?,
    ): Boolean {
        val k = e.key
        if (k != androidx.compose.ui.input.key.Key.DirectionCenter && k != androidx.compose.ui.input.key.Key.Enter && k != androidx.compose.ui.input.key.Key.NumPadEnter) return false
        when (e.type) {
            androidx.compose.ui.input.key.KeyEventType.KeyDown -> {
                if (e.nativeKeyEvent.repeatCount == 0) {
                    down = true
                    long = false
                } else if (down && !long && onLongClick != null) {
                    // Held OK: the menu, once, and no click when it's let go
                    long = true
                    onLongClick()
                }
            }
            androidx.compose.ui.input.key.KeyEventType.KeyUp -> {
                if (down && !long) onClick()
                down = false
                long = false
            }
            else -> return false
        }
        return true
    }
}

/** [CardCaption]'s height, measured once (Light boxes keep its room while the card is a shell). */
@Composable
private fun captionHeight(): Dp {
    val density = LocalDensity.current
    CaptionRoom.at?.takeIf { it.first == density.density * density.fontScale }?.let { return it.second }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 0)
    val base = LocalTextStyle.current
    val title = measurer.measure("Ag", base.merge(TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)), maxLines = 1).size.height
    val line = measurer.measure("Ag", base.merge(TextStyle(fontSize = 11.sp)), maxLines = 1).size.height
    val room = with(density) { 9.dp + 2.dp + (title + line).toDp() + 1.dp }
    CaptionRoom.at = density.density * density.fontScale to room
    return room
}

private object CaptionRoom {
    var at: Pair<Float, Dp>? = null
}

/** "Apr 24, 2019", in the device's language. */
private val CAPTION_DATE = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")

/** Under a card (Poster tags → Title and date): the name, then its date and length. */
@Composable
internal fun CardCaption(
    item: CinemaItem,
    art: com.wholphinplus.sources.core.TitleArt? = rememberArt(item),
) {
    Column(Modifier.fillMaxWidth().padding(top = 9.dp, start = 2.dp, end = 2.dp)) {
        Text(item.title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            // The full release date from TMDB when the server only knows the year
            val released = art?.released?.takeIf { item.kind != BaseItemKind.EPISODE }
            val date = released?.let { runCatching { CAPTION_DATE.format(java.time.LocalDate.parse(it)) }.getOrNull() } ?: item.captionDate.orEmpty()
            Text(date, color = InkDim, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
            item.captionLength?.let { Text(it, color = InkDim, fontSize = 11.sp, maxLines = 1) }
        }
    }
}

/**
 * A wide card's picture and every tag on it (Settings → Orca+ → Poster tags). Shared by the
 * rows and the settings preview, so the preview is exactly what the rows draw.
 */
@Composable
internal fun CinemaCardFace(
    item: CinemaItem,
    width: Dp,
    artState: ArtState = rememberArtState(item),
) {
    val context = LocalContext.current
    val o = LocalOverlays.current
    val art = artState.art
    // TMDB's art is on its way (for a moment at most): the picture and the logo or name wait for
    // it, rather than the server's picture loading and then being swapped
    val waiting = artState.waiting
    val choice = cardChoice(item, art, o)
    val logo = choice.logo
    val clean = choice.clean
    val titled = choice.titled
    val url = choice.url
    // No logo: the name is written on the picture only when it isn't already under the card
    val drawName = clean != null || (titled == null && !item.cardHasTitleArt && !o.captions)
    Box(Modifier.fillMaxSize()) {
        if (waiting) {
            Unit
        } else if (url != null) {
            // Decoded at card size: small, fast, and cached for the next visit
            CardPicture(url, 416, 234, contentDescription = item.title, modifier = Modifier.fillMaxSize())
        } else {
            // No wide art on the server or TMDB: the poster filling the card, or a plain title
            // card, instead of an empty grey box
            NoWideArt(item.posterUrl, context)
        }
        if (drawName && !waiting) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
            // Clear of the New label (~15 dp tall) with a little air, also when the card is lifted
            val bottom = if (item.badge != null && o.newLabels) 26.dp else 10.dp
            if (logo != null && clean != null) {
                FittedLogo(
                    logo,
                    height = width * 0.23f,
                    // Up to 60% of the card: the small score in the opposite corner still clears it
                    maxWidth = width * 0.6f,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = bottom),
                )
            } else {
                Text(
                    item.title,
                    color = Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = bottom),
                )
            }
        }
        item.badge?.takeIf { o.newLabels }?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).background(Label, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        if (o.progress) item.progress?.let { p -> ProgressBar(p, Modifier.align(Alignment.BottomStart)) }
        // A second pass: the streaming logos, quality tags and scores are ~35% of a card's cost,
        // built a frame after the picture and logo so no frame builds a whole card
        if (rememberCardFilled(CardExtras(item.key))) {
            PosterBadges(item)
            val own = LocalPageService.current
            ServiceLogos(item, art?.serviceUrls().orEmpty().filterNot { own != null && it.endsWith(own) }.take(o.maxServices))
            CardRatings(item, art)
        }
    }
}

/** [CinemaCardFace]'s second pass, by title ([rememberCardFilled]). */
private data class CardExtras(
    val key: Any,
)

/** Which pictures a wide card draws ([CinemaCardFace]); the start-up warm-up asks for the same. */
internal class CardChoice(
    val logo: String?,
    val clean: String?,
    val titled: String?,
    val url: String?,
)

internal fun cardChoice(
    item: CinemaItem,
    art: com.wholphinplus.sources.core.TitleArt?,
    o: PosterOverlays,
): CardChoice {
    // Card-size logos: drawn at most ~250 px wide ([PosterSize.cardLogo])
    val logo = (art?.logoUrl() ?: item.logoUrl)?.let(PosterSize::cardLogo)
    // A title with a logo: its clean picture with the logo laid over the bottom corner, the same
    // on every card (art with the name baked in puts it anywhere, at any size). Without one, or
    // with title logos off: art with the name baked in, else the picture with the name on it
    // TMDB's picture first: its CDN answers in ~0.1 s on a connection of its own, where the
    // server's (often the same TMDB picture) took 0.4-0.7 s and queued behind the rows' requests
    val clean = if (logo != null && o.titleLogos) art?.cleanCardUrl() ?: item.cleanCardUrl else null
    val titled = if (clean == null) art?.cardUrl() else null
    return CardChoice(logo, clean, titled, clean ?: titled ?: item.cardUrl)
}

/**
 * A wide card's picture for a title that only has a poster, or nothing at all. The poster fills
 * the card, cropped to its upper middle: the key art sits there on most posters, and the title
 * printed lower down would only repeat the name the card shows anyway.
 */
@Composable
private fun NoWideArt(
    poster: String?,
    context: android.content.Context,
) {
    if (poster != null) {
        CardPicture(
            poster,
            416,
            624,
            contentDescription = null,
            alignment = androidx.compose.ui.BiasAlignment(0f, -0.35f),
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2B2734), Color(0xFF141318))))) {
            Text("+", color = Plus.copy(alpha = 0.35f), fontSize = 44.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp))
        }
    }
}
