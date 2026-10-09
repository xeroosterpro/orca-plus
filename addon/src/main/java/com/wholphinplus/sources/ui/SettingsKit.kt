package com.wholphinplus.sources.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.wholphinplus.sources.cinema.CinemaFade
import com.wholphinplus.sources.cinema.Ink
import com.wholphinplus.sources.cinema.InkDim
import com.wholphinplus.sources.cinema.Plus
import com.wholphinplus.sources.cinema.glideLift

/*
 * Settings in Orca+'s look (owner, 2026-10-08: "needs a face lift… easy to navigate"). One row for
 * every setting, Wholphin's and Orca+'s alike: clear at rest, and on focus a soft glass fill with a
 * white ring that ease in together (TV Material's white fill snapped on, and Orca+'s own rows were
 * grey cards with an outline: two looks on one screen). Section headings and the topic's header
 * match the rest of the app (the "+ CHOOSE A COPY" eyebrow).
 */

private val RowShape = RoundedCornerShape(12.dp)
private val RowTitle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)
private val RowSummary = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
private val RowValue = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)
private val FocusFill = Color.White.copy(alpha = 0.10f)

/**
 * A settings row. The same parameters as TV Material's ListItem, so Wholphin's settings use it by
 * name ([selected] is ignored: settings rows have no selected state).
 */
@Composable
fun SettingsRow(
    onClick: () -> Unit,
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    overlineContent: (@Composable () -> Unit)? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fill by animateColorAsState(if (focused) FocusFill else Color.Transparent, tween(if (focused) 240 else 200, easing = CinemaFade), label = "row")
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RowShape),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                pressedContainerColor = Color.Transparent,
                contentColor = Ink,
                focusedContentColor = Ink,
                pressedContentColor = Ink,
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        interactionSource = source,
        modifier =
            modifier
                .fillMaxWidth()
                .glideLift(scale = 1.012f, corner = 12.dp, edgeWidth = 2.dp)
                .drawBehind { drawRoundRect(fill, cornerRadius = CornerRadius(12.dp.toPx())) },
    ) {
        Row(Modifier.heightIn(min = 60.dp).padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            leadingContent?.let {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) { CompositionLocalProvider(LocalContentColor provides Ink) { it() } }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                overlineContent?.let { CompositionLocalProvider(LocalContentColor provides InkDim, androidx.tv.material3.LocalTextStyle provides RowSummary) { it() } }
                CompositionLocalProvider(LocalContentColor provides Ink, androidx.tv.material3.LocalTextStyle provides RowTitle) { headlineContent() }
                supportingContent?.let {
                    Spacer(Modifier.padding(top = 2.dp))
                    CompositionLocalProvider(LocalContentColor provides InkDim, androidx.tv.material3.LocalTextStyle provides RowSummary) { it() }
                }
            }
            trailingContent?.let {
                Spacer(Modifier.width(16.dp))
                CompositionLocalProvider(LocalContentColor provides Ink.copy(alpha = 0.88f), androidx.tv.material3.LocalTextStyle provides RowValue) { it() }
            }
        }
    }
}

/** A section's heading: small caps, quiet, with room above (the eyebrow used across Orca+). */
@Composable
fun SettingsHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text.uppercase(),
        color = InkDim,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.2.sp,
        modifier = modifier.fillMaxWidth().padding(start = 20.dp, top = 18.dp, bottom = 4.dp),
    )
}

/** A settings page of its own (Extra servers, Poster tags…): its name and what it's for. */
@Composable
fun SettingsPageHeader(
    title: String,
    about: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("+", color = Plus, fontSize = 15.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(6.dp))
            Text("SETTINGS", color = InkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.4.sp)
        }
        Text(title, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 2.dp))
        Text(about, color = InkDim, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp).widthIn(max = 980.dp))
    }
}

/** Pop-ups' panel (choices, menus): a raised shade of the stage with a hairline edge. */
val SettingsDialogColor = Color(0xFF1A1A1F)

/** The open topic's name and what's in it, above its settings. */
@Composable
fun SettingsPaneHeader(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(start = 36.dp, top = 28.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("+", color = Plus, fontSize = 15.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(6.dp))
            Text(hint.uppercase(), color = InkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.4.sp, maxLines = 1)
        }
        Text(title, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * Summaries that only repeat what the switch beside them shows ("Enabled", "Yes") are left out.
 * The ones that say what the setting does stay.
 */
fun usefulSummary(summary: String?): String? = summary?.takeUnless { it.trim().lowercase() in REDUNDANT }

private val REDUNDANT = setOf("enabled", "disabled", "yes", "no", "on", "off", "show", "hide", "true", "false")

/** A slider row's focus: the same fill and ring as [SettingsRow], for Wholphin's slider (its own layout). */
@Composable
fun Modifier.settingsFocusFrame(source: MutableInteractionSource): Modifier {
    // The focus is on the slider bar inside, so the frame follows [source], not its own focus
    val focused by source.collectIsFocusedAsState()
    val fill by animateColorAsState(if (focused) FocusFill else Color.Transparent, tween(if (focused) 240 else 200, easing = CinemaFade), label = "row")
    val ring by androidx.compose.animation.core.animateFloatAsState(if (focused) 1f else 0f, tween(if (focused) 320 else 220, easing = CinemaFade), label = "ring")
    return this.drawBehind {
        val r = CornerRadius(12.dp.toPx())
        drawRoundRect(fill, cornerRadius = r)
        if (ring > 0f) {
            val w = 2.dp.toPx()
            drawRoundRect(
                Ink.copy(alpha = ring),
                topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius(r.x - w / 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(w),
            )
        }
    }
}

/** [settingsFocusFrame] for a caller in another package (Wholphin's slider). */
@Composable
fun settingsFrame(source: MutableInteractionSource): Modifier = Modifier.settingsFocusFrame(source)

/** The settings screen's backdrop: Orca+'s dark stage with a faint wash of the accent at the top left. */
val SettingsBackdrop: androidx.compose.ui.graphics.Brush =
    androidx.compose.ui.graphics.Brush.radialGradient(
        0f to Plus.copy(alpha = 0.10f),
        1f to Color.Transparent,
        center = androidx.compose.ui.geometry.Offset(0f, 0f),
        radius = 1400f,
    )

/** The topic list's own panel, a shade darker than the settings beside it. */
val SettingsRailColor = Color(0xFF09090B)

/** The page under it all (Orca+'s stage). */
val SettingsPageColor = Color(0xFF111114)

/**
 * A topic in the settings list. The open topic carries an accent bar and its description; the
 * focused one gets the row's glass fill and ring, eased in.
 */
@Composable
fun SettingsRailItem(
    title: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fill by animateColorAsState(if (focused) FocusFill else Color.Transparent, tween(if (focused) 240 else 200, easing = CinemaFade), label = "rail")
    val bar by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0f, tween(260, easing = CinemaFade), label = "bar")
    val ink by animateColorAsState(if (selected || focused) Ink else Ink.copy(alpha = 0.62f), tween(220, easing = CinemaFade), label = "ink")
    // Every topic keeps its line (a list that grew and shrank under the remote moved as you went)
    val hintInk by animateColorAsState(if (selected || focused) InkDim else InkDim.copy(alpha = 0.55f), tween(220, easing = CinemaFade), label = "hint")
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                pressedContainerColor = Color.Transparent,
                contentColor = Ink,
                focusedContentColor = Ink,
                pressedContentColor = Ink,
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        interactionSource = source,
        modifier =
            modifier
                .fillMaxWidth()
                .glideLift(scale = 1.02f, corner = 10.dp, edgeWidth = 2.dp)
                .drawBehind {
                    drawRoundRect(fill, cornerRadius = CornerRadius(10.dp.toPx()))
                    if (bar > 0f) {
                        val h = size.height * 0.46f * bar
                        drawRoundRect(
                            Plus,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, (size.height - h) / 2),
                            size = androidx.compose.ui.geometry.Size(3.dp.toPx(), h),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    }
                },
    ) {
        Column(Modifier.padding(start = 18.dp, end = 12.dp, top = 7.dp, bottom = 7.dp)) {
            Text(title, color = ink, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
            Text(hint, color = hintInk, fontSize = 11.5.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/** Orca+'s ink, accent and easings for the settings page in Wholphin's module. */
val SettingsInk: Color get() = Ink
val SettingsAccent: Color get() = Plus
val SettingsEase: androidx.compose.animation.core.Easing get() = com.wholphinplus.sources.cinema.CinemaEase
val SettingsFade: androidx.compose.animation.core.Easing get() = CinemaFade

/** A list's top edge fading out (rows scrolling up under a header), drawn as an alpha mask. */
val SettingsListFade: Modifier =
    Modifier
        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, 36.dp.toPx() / size.height.coerceAtLeast(1f) to Color.Black),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }
