package com.wholphinplus.sources.cinema

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

// The billboard panel's heights (full, buttons hidden, rolled up) and the gap below it. The rows
// sit at RowsTopRolled and slide down by the panel's extra height (see CinemaScreen).
private val PanelTop = 16.dp
internal val PanelFull = 262.dp
internal val PanelNoButtons = 210.dp
internal val PanelRolled = 52.dp
internal val GapFull = 18.dp
internal val GapRolled = 8.dp
internal val RowsTopRolled = TopNavHeight + PanelTop + PanelRolled + GapRolled

private val RolledLogoHeight = 44.dp
private val RolledShadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.75f), blurRadius = 10f)

/** A top bar entered with Up (from Play) lands on [selected], the pill of the page being shown. */
internal fun Modifier.upToSelected(selected: FocusRequester): Modifier =
    focusProperties {
        onEnter = {
            if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Up) runCatching { selected.requestFocus() }
        }
    }.focusGroup()

/** Height eased between [full] and [rolled] by [roll] (0..1), in the layout pass only. */
private fun Modifier.rollHeight(
    full: Dp,
    rolled: Dp,
    roll: () -> Float,
): Modifier = animatedHeight { lerp(full, rolled, roll()) }

/** Width eased between [full] and [rolled] by [roll] (0..1), in the layout pass only. */
private fun Modifier.rollWidth(
    full: Dp,
    rolled: Dp,
    roll: () -> Float,
): Modifier =
    layout { measurable, constraints ->
        val w = lerp(full, rolled, roll()).roundToPx().coerceAtMost(constraints.maxWidth)
        val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
        layout(w, placeable.height) { placeable.place(0, 0) }
    }

/**
 * A height read in the layout pass only, so animating it never recomposes. Clips its content,
 * allowing [slack] past the left, right and bottom edges (a focused button's zoom).
 */
private fun Modifier.animatedHeight(
    slack: Dp = 0.dp,
    height: () -> Dp,
): Modifier =
    (if (slack == 0.dp) clipToBounds() else drawWithContent {
        val s = slack.toPx()
        clipRect(-s, 0f, size.width + s, size.height + s) { this@drawWithContent.drawContent() }
    }).layout { measurable, constraints ->
        val h = height().roundToPx()
        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
        layout(placeable.width, h) { placeable.place(0, 0) }
    }

/**
 * Fixed-height panel: the focused title changing or the buttons fading never change its size,
 * so the rows below don't move. Only rolling up (the setting) shrinks it, to a quarter: a small
 * logo and the episode or Top 10 line stay, the rest fades out.
 */
/** Back while browsing rows brings the billboard back (its own scope: flips don't recompose the screen). */
@Composable
internal fun BillboardBack(
    focus: HomeFocus,
    toBillboard: () -> Unit,
) {
    BackHandler(enabled = !focus.billboard) { toBillboard() }
}

/** The server's backdrop, else TMDB's clean picture (some servers have none for a title). */
@Composable
internal fun ShownBackdrop(
    shown: androidx.compose.runtime.State<CinemaItem?>,
    focus: HomeFocus,
) {
    val item = shown.value
    val art = rememberArt(item)
    StableBackdrop(item?.backdropUrl ?: art?.cleanBackdrop?.let { "https://image.tmdb.org/t/p/w1280$it" }, drift = focus.billboard)
}

@Composable
internal fun InfoPanel(
    item: () -> CinemaItem?,
    billboard: () -> Boolean,
    roll: () -> Float,
    noButtons: () -> Float,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    onMoreInfo: () -> Unit,
    onButtonsFocused: () -> Unit,
) {
    // Full: details + buttons (262). Buttons hidden: details only (210). Rolled up: a quarter (72).
    // Wide enough for the rolled-up details beside the logo; the text below keeps 480 dp
    Box(Modifier.padding(start = 48.dp, top = PanelTop).animatedHeight(slack = 12.dp) { lerp(lerp(PanelFull, PanelNoButtons, noButtons()), PanelRolled, roll()) }.widthIn(max = 1000.dp)) {
        AnimatedContent(
            targetState = item(),
            // The fade is drawn below with the alpha: AnimatedContent's own fade put each panel in
            // a layer of its own for every frame of it (the Shield's GPU draws that twice)
            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            contentKey = { it?.key },
            label = "info",
        ) { shownItem ->
            val simple = com.wholphinplus.sources.DeviceClass.simpleMotion
            val shownAlpha by transition.animateFloat(
                // Overlapping, not out-then-in: the old text eases away as the new one comes up.
                // Light boxes: a quick plain fade
                transitionSpec = {
                    if (simple) {
                        tween(if (targetState == EnterExitState.Visible) 180 else 120, easing = CinemaFade)
                    } else if (targetState == EnterExitState.Visible) {
                        tween(520, delayMillis = 60, easing = CinemaEase)
                    } else {
                        tween(260, easing = CinemaFade)
                    }
                },
                label = "infoAlpha",
            ) { if (it == EnterExitState.Visible) 1f else 0f }
            // The new title rises the last few dp into place as it fades in; the old one stays put (not on Light)
            val rising = transition.targetState == EnterExitState.Visible && !simple
            // The rolled-up details and the full ones are never seen together (each is invisible past
            // half the roll), so only one is built: a change measured both (~16 texts, 9 ms on the
            // Shield). They swap at the halfway point, where both are transparent.
            val rolledNow by remember { derivedStateOf { roll() > 0.5f } }
            Column(
                Modifier.graphicsLayer {
                    alpha = shownAlpha
                    if (rising) translationY = (1f - shownAlpha) * 14.dp.toPx()
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                },
            ) {
                if (shownItem == null) return@Column
                val logo = rememberArt(shownItem)?.logoUrl() ?: shownItem.logoUrl
                // The logo's shape once loaded: rolled up, its box hugs it, so the details sit
                // right beside it instead of after a fixed 220 dp slot (a narrow logo left a gap)
                var logoAspect by remember(logo) { mutableFloatStateOf(0f) }
                val rolledLogo = if (logo != null && logoAspect > 0f) (RolledLogoHeight * logoAspect).coerceIn(48.dp, 220.dp) else 220.dp
                Row(Modifier.rollHeight(70.dp, RolledLogoHeight, roll), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.fillMaxHeight().rollWidth(340.dp, rolledLogo, roll), contentAlignment = Alignment.BottomStart) {
                    // A logo that arrives a moment late crossfades over the title text
                    Crossfade(targetState = logo, animationSpec = if (simple) androidx.compose.animation.core.snap() else tween(300, easing = CinemaEase), label = "logo") { l ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                            if (l != null) {
                                AsyncImage(
                                    model = rememberPlain(l),
                                    contentDescription = shownItem.title,
                                    contentScale = ContentScale.Fit,
                                    alignment = Alignment.BottomStart,
                                    onSuccess = { r -> r.painter.intrinsicSize.let { if (it.height > 0f) logoAspect = it.width / it.height } },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                // No logo: the name shrinks to fit two lines of the logo's box rather than
                                // being cut off ("Echoes of the Ice A…" at one line of 34 sp)
                                androidx.compose.foundation.text.BasicText(
                                    shownItem.title,
                                    style = TextStyle(color = Ink, fontWeight = FontWeight.ExtraBold, lineHeight = 1.05.em),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 34.sp, stepSize = 2.sp),
                                )
                            }
                        }
                    }
                }
                // Rolled up, the details sit beside the logo, after a hairline: still there while
                // browsing rows. A soft shadow keeps them legible over a bright picture.
                if (rolledNow) Row(
                    Modifier.graphicsLayer {
                        alpha = ((roll() - 0.5f) * 2f).coerceIn(0f, 1f)
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.padding(horizontal = 18.dp).width(1.dp).height(30.dp).background(Ink.copy(alpha = 0.3f)))
                    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(shadow = RolledShadow)) {
                        Column(Modifier.widthIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                // The Top 10 place joins the details (its own line folds away rolled up)
                                shownItem.rank?.takeIf { shownItem.subtitle == null }?.let { RankLine(it, null) }
                                MetaLine(Meta(shownItem.meta, shownItem.rating))
                            }
                            // An episode's name says more than the show's story here
                            Text(
                                shownItem.subtitle ?: shownItem.overview,
                                color = if (shownItem.subtitle != null) Ink else Ink.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                fontWeight = if (shownItem.subtitle != null) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                }
                Box(Modifier.rollHeight(8.dp, 0.dp, roll))
                // One fixed line: the episode, else the title's Top 10 place, else blank. Rolled up it
                // folds away (the details beside the logo carry it), so the rows sit 20 dp higher
                Box(Modifier.rollHeight(20.dp, 0.dp, roll), contentAlignment = Alignment.CenterStart) {
                    val rank = shownItem.rank
                    when {
                        shownItem.subtitle != null ->
                            Text(shownItem.subtitle, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        rank != null -> RankLine(rank, shownItem.rankLabel)
                    }
                }
                Box(Modifier.rollHeight(8.dp, 0.dp, roll))
                if (!rolledNow) Column(Modifier.widthIn(max = 480.dp).graphicsLayer {
                    alpha = (1f - roll() * 2f).coerceIn(0f, 1f)
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetaLine(Meta(shownItem.meta, shownItem.rating))
                    Text(shownItem.overview, color = Ink.copy(alpha = 0.86f), fontSize = 14.sp, lineHeight = 20.sp, minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        AnimatedVisibility(
            visible = billboard(),
            // Waits for the panel to mostly grow back (~0.4 s on CinemaGlide), so the buttons never fade in
            // over the description while there's no room for them yet
            enter = fadeIn(tween(320, delayMillis = 360, easing = CinemaEase)),
            exit = fadeOut(tween(200, easing = CinemaFade)),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroButton("Play", Icons.Filled.PlayArrow, primary = true, modifier = Modifier.focusRequester(playFocus), onFocused = onButtonsFocused, onClick = onPlay)
                HeroButton("More Info", Icons.Filled.Info, primary = false, onFocused = onButtonsFocused, onClick = onMoreInfo)
            }
        }
    }
}
