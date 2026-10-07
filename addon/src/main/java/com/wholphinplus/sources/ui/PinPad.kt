package com.wholphinplus.sources.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

/** Sync PINs are six digits: quick on a remote, and the cloud locks after ten wrong tries. */
const val PIN_LENGTH = 6

private val Ink = Color(0xFFF2F2F2)
private val InkDim = Color(0xFFB3B3B3)
private val Problem = Color(0xFFFF8A80)

/**
 * A six-digit PIN pad for the remote: a keypad to move around (the remote's number keys and
 * Delete work too) and six dots. It hands over the PIN as soon as the sixth digit is in.
 * [resetKey] changing (a wrong PIN, the next stage) clears it.
 */
@Composable
internal fun PinPad(
    title: String,
    subtitle: String,
    error: String?,
    busy: Boolean,
    resetKey: Any,
    onComplete: (String) -> Unit,
    modifier: Modifier = Modifier,
    busyText: String = "One moment…",
) {
    var digits by remember(resetKey) { mutableStateOf("") }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(resetKey) { runCatching { first.requestFocus() } }
    // A wrong PIN shakes the dots
    LaunchedEffect(error, resetKey) {
        if (error != null) {
            shake.animateTo(0f, keyframes {
                durationMillis = 360
                -14f at 60
                12f at 130
                -8f at 200
                5f at 270
            })
        }
    }

    fun type(d: Char) {
        if (busy || digits.length >= PIN_LENGTH) return
        digits += d
        if (digits.length == PIN_LENGTH) {
            val pin = digits
            scope.launch { onComplete(pin) }
        }
    }

    fun back() {
        if (!busy) digits = digits.dropLast(1)
    }
    Column(
        modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val n = DIGIT_KEYS.indexOf(e.key).takeIf { it >= 0 } ?: NUMPAD_KEYS.indexOf(e.key).takeIf { it >= 0 }
            when {
                n != null -> {
                    type('0' + n)
                    true
                }
                e.key == Key.Backspace || e.key == Key.Delete -> {
                    back()
                    true
                }
                else -> false
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = Ink, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        Text(subtitle, color = InkDim, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center, modifier = Modifier.size(width = 620.dp, height = 44.dp))
        Row(Modifier.offset(x = shake.value.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(PIN_LENGTH) { i ->
                val filled = i < digits.length
                Box(
                    Modifier
                        .size(18.dp)
                        .background(if (filled) Ink else Color.Transparent, CircleShape)
                        .border(2.dp, if (error != null && digits.isEmpty()) Problem else Ink.copy(alpha = 0.7f), CircleShape),
                )
            }
        }
        Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
            when {
                busy -> Text(busyText, color = Ink, fontSize = 15.sp)
                error != null -> Text(error, color = Problem, fontSize = 15.sp)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("123", "456", "789", "<0").forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.forEach { c ->
                        when (c) {
                            '<' -> Key("⌫", busy) { back() }
                            else -> Key(c.toString(), busy, if (c == '1') Modifier.focusRequester(first) else Modifier) { type(c) }
                        }
                    }
                    // Keeps the bottom row centred under the grid: ⌫ 0 and a blank
                    if (line == "<0") Box(Modifier.size(width = 78.dp, height = 48.dp))
                }
            }
        }
    }
}

@Composable
private fun Key(
    label: String,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val f = com.wholphinplus.sources.cinema.rememberFocusFade(Color.White.copy(alpha = 0.08f), Ink, Ink, Color(0xFF111111), quick = true)
    Surface(
        onClick = onClick,
        enabled = !busy,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = f.fill,
                contentColor = f.content,
                focusedContainerColor = f.fill,
                focusedContentColor = f.content,
                disabledContainerColor = Color.White.copy(alpha = 0.04f),
                disabledContentColor = InkDim,
            ),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        interactionSource = f.source,
        modifier = with(com.wholphinplus.sources.cinema.TapHelper) { modifier.size(width = 78.dp, height = 48.dp).glideFocus(1.08f) },
    ) {
        Box(Modifier.size(width = 78.dp, height = 48.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * A new PIN: typed, then typed again. Calls [onPin] once both match; a mismatch starts over.
 */
@Composable
internal fun NewPinPad(
    title: String,
    subtitle: String,
    error: String?,
    busy: Boolean,
    onPin: (String) -> Unit,
    modifier: Modifier = Modifier,
    busyText: String = "Saving your setup…",
) {
    var firstPin by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableStateOf(0) }
    PinPad(
        title = if (firstPin == null) title else "Enter it once more",
        subtitle = if (firstPin == null) subtitle else "The same six digits, to be sure.",
        error = mismatch ?: error,
        busy = busy,
        resetKey = round,
        busyText = busyText,
        onComplete = { pin ->
            val earlier = firstPin
            when {
                earlier == null -> {
                    firstPin = pin
                    mismatch = null
                }
                earlier != pin -> {
                    firstPin = null
                    mismatch = "Those didn't match. Pick your PIN again."
                }
                else -> {
                    mismatch = null
                    onPin(pin)
                }
            }
            round++
        },
        modifier = modifier,
    )
}

private val DIGIT_KEYS = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
private val NUMPAD_KEYS = listOf(Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9)
