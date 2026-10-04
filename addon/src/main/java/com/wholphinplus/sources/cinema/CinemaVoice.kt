package com.wholphinplus.sources.cinema

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import timber.log.Timber

/** Material's microphone (not in the core icon set). */
internal val MicIcon: ImageVector by lazy {
    ImageVector
        .Builder("Mic", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            addPathNodes(
                "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
                    "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
            ),
            fill = SolidColor(Color.Black),
        ).build()
}

/** Where voice search is: Idle → Starting → Listening → Processing → (result | Error). */
internal sealed interface VoiceState {
    data object Idle : VoiceState

    data object Starting : VoiceState

    data object Listening : VoiceState

    data object Processing : VoiceState

    data class Error(
        val message: String,
    ) : VoiceState
}

/**
 * Cinema search's own speech recognizer (the system service, the same one Wholphin's mic uses).
 * Lives as long as the search screen; all calls on the main thread.
 */
internal class CinemaVoice(
    private val context: Context,
    private val onResult: (String) -> Unit,
) {
    var state by mutableStateOf<VoiceState>(VoiceState.Idle)
        private set

    /** 0..1, from the recognizer's input level; drives the pulse. */
    var level by mutableFloatStateOf(0f)
        private set

    /** What it has heard so far. */
    var partial by mutableStateOf("")
        private set

    val available: Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val active: Boolean get() = state != VoiceState.Idle

    private var recognizer: SpeechRecognizer? = null

    fun start() {
        destroy()
        partial = ""
        level = 0f
        state = VoiceState.Starting
        val rec = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (rec == null) {
            state = VoiceState.Error("Voice search isn't available on this device")
            return
        }
        recognizer = rec
        rec.setRecognitionListener(listener(rec))
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            Timber.w(e, "Voice search failed to start")
            destroy()
            state = VoiceState.Error("Couldn't start the microphone")
        }
    }

    fun denied() {
        state = VoiceState.Error("Allow microphone access to search by voice")
    }

    /** Stop listening and close the overlay. */
    fun cancel() {
        destroy()
        state = VoiceState.Idle
        level = 0f
        partial = ""
    }

    private fun destroy() {
        // Forget it first so its late callbacks are ignored
        val rec = recognizer
        recognizer = null
        rec?.let {
            runCatching {
                it.cancel()
                it.destroy()
            }
        }
    }

    private fun listener(rec: SpeechRecognizer) =
        object : RecognitionListener {
            private fun valid() = recognizer === rec

            override fun onReadyForSpeech(params: Bundle?) {
                if (valid()) state = VoiceState.Listening
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                if (valid()) level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (valid()) {
                    state = VoiceState.Processing
                    level = 0f
                }
            }

            override fun onError(error: Int) {
                if (!valid()) return
                destroy()
                level = 0f
                state =
                    VoiceState.Error(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> "Voice search needs an internet connection"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone access to search by voice"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "The microphone is busy, try again"
                            else -> "Something went wrong, try again"
                        },
                    )
            }

            override fun onResults(results: Bundle?) {
                if (!valid()) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                destroy()
                if (text.isNullOrBlank()) {
                    state = VoiceState.Error("Didn't catch that")
                } else {
                    state = VoiceState.Idle
                    partial = ""
                    onResult(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!valid()) return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { partial = it }
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?,
            ) = Unit
        }
}

/** A [CinemaVoice] tied to the screen, plus the permission prompt it needs the first time. */
@Composable
internal fun rememberCinemaVoice(onResult: (String) -> Unit): Pair<CinemaVoice, () -> Unit> {
    val context = LocalContext.current
    val voice = remember { CinemaVoice(context.applicationContext, onResult) }
    DisposableEffect(Unit) { onDispose { voice.cancel() } }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) voice.start() else voice.denied()
        }
    val toggle = {
        when {
            voice.state == VoiceState.Starting || voice.state == VoiceState.Listening -> voice.cancel()
            voice.hasPermission -> voice.start()
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    return voice to toggle
}

/**
 * The listening screen: the page dims, a big red mic in the middle breathes with your voice inside
 * rings that keep spreading while it listens, and what it hears is written out underneath.
 */
@Composable
internal fun VoiceOverlay(
    voice: CinemaVoice,
    onRetry: () -> Unit,
) {
    AnimatedVisibility(voice.active, enter = fadeIn(tween(200, easing = CinemaEase)), exit = fadeOut(tween(160))) {
        // The overlay holds focus and eats Back itself: a BackHandler here loses to the app's
        // navigation, which would leave search instead of just closing the mic
        val overlayFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { overlayFocus.requestFocus() } }
        val state = voice.state
        val listening = state == VoiceState.Listening
        val error = state as? VoiceState.Error
        val t = rememberInfiniteTransition(label = "voice")
        val ripple by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "ripple")
        val breathe by t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breathe")
        val level by animateFloatAsState(voice.level, tween(90), label = "level")
        val retry = remember { FocusRequester() }
        LaunchedEffect(error) { if (error != null) runCatching { retry.requestFocus() } }

        Box(
            Modifier
                .fillMaxSize()
                .background(Stage.copy(alpha = 0.92f))
                .onPreviewKeyEvent { e ->
                    when (e.key) {
                        Key.Back, Key.Escape -> {
                            if (e.type == KeyEventType.KeyUp) voice.cancel()
                            true
                        }
                        // Keep focus inside: the page behind is covered
                        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> true
                        else -> false
                    }
                }.focusRequester(overlayFocus)
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                    // Spreading rings: only while it is really listening
                    if (listening) {
                        Canvas(Modifier.fillMaxSize()) {
                            val base = 56.dp.toPx()
                            val reach = size.minDimension / 2 - base
                            for (i in 0..2) {
                                val p = (ripple + i / 3f) % 1f
                                drawCircle(Label.copy(alpha = (1f - p) * 0.55f), radius = base + reach * p, style = Stroke(width = 3.dp.toPx()))
                            }
                            // A soft glow that swells with your voice
                            drawCircle(Label.copy(alpha = 0.22f), radius = base + reach * 0.45f * level)
                        }
                    }
                    val scale = if (listening) 1f + level * 0.22f else if (error == null) breathe else 1f
                    Box(
                        Modifier
                            .size(112.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }.background(if (error != null) Color(0xFF3A3A3A) else Label, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MicIcon, contentDescription = "Voice search", tint = Color.White, modifier = Modifier.size(54.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                val headline =
                    when {
                        error != null -> error.message
                        voice.partial.isNotBlank() -> "“${voice.partial}”"
                        state == VoiceState.Starting -> "Getting ready…"
                        state == VoiceState.Processing -> "Searching…"
                        else -> "Listening…"
                    }
                Text(headline, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.widthIn(max = 760.dp))
                Spacer(Modifier.height(10.dp))
                if (error != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HeroButton("Try Again", MicIcon, primary = true, modifier = Modifier.focusRequester(retry), onClick = onRetry)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Press Back to type instead", color = InkDim, fontSize = 14.sp)
                } else {
                    Text("Say a title, actor or genre", color = InkDim, fontSize = 16.sp)
                    Spacer(Modifier.height(28.dp))
                    Text("Press Back to cancel", color = InkDim.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
