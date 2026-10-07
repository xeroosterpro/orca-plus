package com.wholphinplus.sources.welcome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.wholphinplus.sources.cinema.CinemaEase
import com.wholphinplus.sources.cinema.CinemaFade
import com.wholphinplus.sources.cinema.Ink
import com.wholphinplus.sources.cinema.InkDim
import com.wholphinplus.sources.cinema.Plus
import com.wholphinplus.sources.cinema.Stage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// The welcome's own accents, from the Orca+ mark: violet plus, deep indigo, a warm rose.
internal val Violet = Color(0xFF8B6CFF)
internal val Indigo = Color(0xFF2E2470)
internal val Rose = Color(0xFFB0366B)
internal val Glass = Color(0x16FFFFFF)
internal val GlassLine = Color(0x26FFFFFF)

/**
 * The welcome's living background. Before sign-in: a slow aurora in the Orca+ colours with
 * drifting plus sparks. Once there are [posters] (the person's own library), three rows of them
 * glide past behind the aurora, dimmed.
 */
@Composable
internal fun WelcomeBackdrop(
    posters: List<String>,
    modifier: Modifier = Modifier,
) {
    val t = rememberInfiniteTransition(label = "aurora")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(26_000, easing = LinearEasing)), label = "phase")
    val rise by t.animateFloat(0f, 1f, infiniteRepeatable(tween(18_000, easing = LinearEasing)), label = "rise")
    Box(modifier.fillMaxSize().background(Stage)) {
        if (posters.isNotEmpty()) PosterWall(posters)
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            fun blob(
                color: Color,
                cx: Float,
                cy: Float,
                r: Float,
                a: Float,
            ) = drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a), Color.Transparent), center = Offset(cx, cy), radius = r), radius = r, center = Offset(cx, cy))
            val strength = if (posters.isEmpty()) 1f else 0.6f
            blob(Violet, w * (0.30f + 0.12f * cos(phase)), h * (0.35f + 0.10f * sin(phase * 1.3f)), w * 0.55f, 0.42f * strength)
            blob(Indigo, w * (0.75f + 0.10f * sin(phase * 0.8f)), h * (0.70f + 0.12f * cos(phase)), w * 0.65f, 0.55f * strength)
            blob(Rose, w * (0.62f + 0.14f * cos(phase * 1.1f + 1f)), h * (0.18f + 0.08f * sin(phase)), w * 0.40f, 0.26f * strength)
            // Plus sparks rising slowly
            for (i in 0 until 26) {
                val seed = i * 7919
                val x = ((seed % 997) / 997f) * w
                val speed = 0.4f + (seed % 13) / 13f
                val y = h - ((rise * speed + (seed % 389) / 389f) % 1f) * (h * 1.1f)
                val tw = 0.35f + 0.65f * ((sin(phase * 2 + i) + 1f) / 2f)
                val s = 3f + (seed % 5)
                val c = Color.White.copy(alpha = 0.10f * tw)
                drawLine(c, Offset(x - s, y), Offset(x + s, y), strokeWidth = 1.6f)
                drawLine(c, Offset(x, y - s), Offset(x, y + s), strokeWidth = 1.6f)
            }
        }
        // With posters behind, the text side (left) sinks into the stage so it stays readable
        if (posters.isNotEmpty()) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Stage.copy(alpha = 0.92f), 0.45f to Stage.copy(alpha = 0.55f), 0.75f to Color.Transparent)))
        // Vignette so type stays legible at the edges
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Stage.copy(alpha = 0.75f)), radius = 1600f)))
    }
}

/** Three rows of posters gliding sideways at different speeds. */
@Composable
private fun BoxScope.PosterWall(posters: List<String>) {
    val context = LocalContext.current
    val t = rememberInfiniteTransition(label = "wall")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(90_000, easing = LinearEasing)), label = "drift")
    val fade = remember { Animatable(0f) }
    LaunchedEffect(posters) { fade.animateTo(1f, tween(1600, easing = CinemaEase)) }
    Column(
        Modifier.fillMaxSize().graphicsLayer {
            alpha = 0.32f * fade.value
            rotationZ = -6f
            scaleX = 1.25f
            scaleY = 1.25f
        },
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        for (row in 0 until 3) {
            val items = posters.drop(row * 12).take(12).ifEmpty { posters }
            val dir = if (row % 2 == 0) -1f else 1f
            Row(
                Modifier.graphicsLayer { translationX = dir * drift * 1400f - (if (dir > 0) 1400f else 0f) },
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                (items + items).forEach { url ->
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(url).size(240, 360).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(120.dp).height(180.dp).clip(RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
    }
}

/** "STEP 2 OF 3" over a big headline and a line of help, for the left side of a step. */
@Composable
internal fun StepHeader(
    step: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(step, color = Plus, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Text(title, color = Ink, fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold)
        Text(body, color = InkDim, fontSize = 16.sp, lineHeight = 23.sp)
    }
}

/** A frosted panel for a step's content. */
@Composable
internal fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Glass)
            .border(1.dp, GlassLine, RoundedCornerShape(22.dp))
            .padding(24.dp),
    ) { content() }
}

/** A focusable choice in a step: a big icon, a title and a line under it. */
@Composable
internal fun ChoiceCard(
    title: String,
    subtitle: String,
    glyph: String,
    accent: Color,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val f = com.wholphinplus.sources.cinema.rememberFocusFade(Glass, Ink, Ink, Stage)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = f.fill, contentColor = f.content, focusedContainerColor = f.fill, focusedContentColor = f.content),
        border = ClickableSurfaceDefaults.border(border = androidx.tv.material3.Border(BorderStroke(1.dp, GlassLine), shape = RoundedCornerShape(16.dp))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        interactionSource = f.source,
        modifier = with(com.wholphinplus.sources.cinema.TapHelper) { modifier.fillMaxWidth().tapClick(onClick).glideFocus(1.03f) },
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = if (compact) 9.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (compact) 36.dp else 44.dp).clip(CircleShape).background(accent.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                Text(glyph, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(subtitle, fontSize = 13.sp, maxLines = if (compact) 1 else 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.graphicsLayer { alpha = 0.75f })
            }
            if (trailing != null) Text(trailing, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The welcome's main button: a white pill, or a quiet glass one. */
@Composable
internal fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val f = com.wholphinplus.sources.cinema.rememberFocusFade(if (primary) Ink else Glass, if (primary) Color.White else Ink, if (primary) Stage else Ink, Stage)
    Surface(
        onClick = { if (enabled) onClick() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(containerColor = f.fill, contentColor = f.content, focusedContainerColor = f.fill, focusedContentColor = f.content),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        interactionSource = f.source,
        // Like Play on the billboard: a ring fades in around the pill as it grows
        modifier = modifier.graphicsLayer { alpha = if (enabled) 1f else 0.45f }.then(com.wholphinplus.sources.cinema.glideRing(1.06f)).tapClick { if (enabled) onClick() },
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 30.dp, vertical = 13.dp))
    }
}

/**
 * A text box for the remote: OK opens the device keyboard, the keyboard's Next/Done moves on,
 * and the arrows leave it like any other control.
 */
@Composable
internal fun WelcomeField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onDone: () -> Unit = {},
) {
    val softKeyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = InkDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 18.sp),
            cursorBrush = SolidColor(Plus),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = imeAction, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { softKeyboard?.hide(); onDone() }, onDone = { softKeyboard?.hide(); onDone() }, onGo = { softKeyboard?.hide(); onDone() }),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyUp && (it.key == Key.DirectionCenter || it.key == Key.Enter)) {
                            softKeyboard?.show()
                            true
                        } else {
                            false
                        }
                    }.clip(RoundedCornerShape(12.dp))
                    .background(if (focused) Color(0x2EFFFFFF) else Glass)
                    .border(if (focused) 2.dp else 1.dp, if (focused) Ink else GlassLine, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

/** A sign-in code, one big tile per character, with where to type it. */
@Composable
internal fun CodeDisplay(
    code: String,
    where: String,
    modifier: Modifier = Modifier,
) {
    val t = rememberInfiniteTransition(label = "wait")
    val pulse by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100, easing = CinemaFade), RepeatMode.Reverse), label = "pulse")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.Start) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            code.forEach { c ->
                Box(
                    Modifier.size(width = 56.dp, height = 72.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x22FFFFFF)).border(1.dp, GlassLine, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(c.toString(), color = Ink, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        Text(where, color = InkDim, fontSize = 15.sp, lineHeight = 21.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).graphicsLayer { alpha = pulse }.clip(CircleShape).background(Plus))
            Spacer(Modifier.width(10.dp))
            Text("Waiting for you to approve it…", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The step dots along the bottom. */
@Composable
internal fun StepDots(
    current: Int,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until count) {
            Box(Modifier.size(width = if (i == current) 28.dp else 8.dp, height = 8.dp).clip(RoundedCornerShape(50)).background(if (i == current) Ink else GlassLine))
        }
    }
}

/** One tap (mouse or touch) acts, like OK on the remote. */
internal fun Modifier.tapClick(onClick: () -> Unit): Modifier = with(com.wholphinplus.sources.cinema.TapHelper) { tap(onClick) }

internal val StepGutter: Dp = 72.dp

/** Orca+'s services, for the welcome screens. */
internal fun android.content.Context.welcomeHook(): com.wholphinplus.sources.SourceHook =
    dagger.hilt.android.EntryPointAccessors.fromApplication(applicationContext, com.wholphinplus.sources.ui.SourcesEntryPoint::class.java).sourceHook()
