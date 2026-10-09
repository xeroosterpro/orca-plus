package com.wholphinplus.sources.welcome

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.wholphinplus.sources.cinema.CinemaEase
import com.wholphinplus.sources.cinema.CinemaFade
import com.wholphinplus.sources.cinema.InkDim
import com.wholphinplus.sources.sync.CloudException
import com.wholphinplus.sources.sync.MainLogin
import com.wholphinplus.sources.sync.ProfileSync
import com.wholphinplus.sources.ui.PinPad
import com.wholphinplus.sources.ui.SkipButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "I have an Orca+ account": the Orca+ name and sync PIN, and the TV signs in to the main
 * server and brings the setup back by itself (owner, 2026-10-08: a returning user typed the
 * server, user and password, then the PIN). [onSignIn] signs Wholphin in with the sign-in the
 * profile holds and throws when it can't; [onServer] is the way in without a name.
 */
@Composable
internal fun NameSignInStep(
    onSignIn: suspend (MainLogin) -> Unit,
    onServer: () -> Unit,
    onBack: () -> Unit,
) {
    val hook = LocalContext.current.welcomeHook()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var askPin by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var wrong by remember { mutableIntStateOf(0) }
    val field = remember { FocusRequester() }
    BackHandler {
        if (busy) return@BackHandler
        error = null
        if (askPin) askPin = false else onBack()
    }
    val softKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(askPin) {
        if (askPin) return@LaunchedEffect
        runCatching { field.requestFocus() }
        // Focusing a text box opens the keyboard on Android TV; it waits for OK
        kotlinx.coroutines.delay(120)
        softKeyboard?.hide()
    }

    fun signIn(pin: String) {
        busy = true
        error = null
        scope.launch {
            try {
                onSignIn(hook.profileSync.signInWithName(name.trim(), pin))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                when {
                    // The name was wrong, not the PIN: back to the name
                    e is CloudException && e.error == "no_name" -> {
                        askPin = false
                        error = "No Orca+ account is called \"${name.trim()}\". Check the name, or sign in with your server."
                    }
                    else -> {
                        error = ProfileSync.describe(e)
                        wrong++
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    AnimatedContent(
        targetState = askPin,
        transitionSpec = { fadeIn(tween(420, easing = CinemaEase)) togetherWith fadeOut(tween(220, easing = CinemaFade)) },
        label = "nameSignIn",
    ) { pinStage ->
        if (!pinStage) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepHeader(
                    "YOUR ORCA+ ACCOUNT",
                    "Welcome back",
                    "Type your Orca+ name, then your sync PIN. Orca+ signs in to your server and brings back your rows, servers and settings.",
                    Modifier.weight(0.42f),
                )
                GlassPanel(Modifier.weight(0.58f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        WelcomeField(
                            name,
                            { name = it.replace(" ", "") },
                            "Orca+ name",
                            keyboard = KeyboardType.Ascii,
                            imeAction = ImeAction.Next,
                            focusRequester = field,
                            onDone = { if (name.isNotBlank()) askPin = true },
                        )
                        error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp, lineHeight = 19.sp) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            PillButton("Next", enabled = name.isNotBlank(), onClick = {
                                error = null
                                askPin = true
                            })
                            PillButton("Use my server instead", primary = false, onClick = onServer)
                        }
                        Text(
                            "No Orca+ name yet? Sign in with your server and PIN this time, then pick one in Settings → Account & Cloud → Cloud sync.",
                            color = InkDim,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    PinPad(
                        title = "Hi, ${name.trim()}",
                        subtitle = "Enter your 6-digit sync PIN.",
                        error = error,
                        busy = busy,
                        resetKey = wrong,
                        busyText = "Signing you in…",
                        onComplete = ::signIn,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        SkipButton("Back", busy) {
                            error = null
                            askPin = false
                        }
                        SkipButton("Use my server instead", busy, onServer)
                    }
                }
            }
        }
    }
}
