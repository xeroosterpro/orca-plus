package com.wholphinplus.sources.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.wholphinplus.sources.SourceHook
import com.wholphinplus.sources.sync.CloudException
import com.wholphinplus.sources.sync.ProfileSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Turning cloud sync on with the PIN pad. With a profile in the cloud ([exists]) it asks for the
 * PIN and brings the setup here; without one it has a new PIN picked and saves this TV's setup.
 * Ends on a short "all set"; [onSkip] (shown as [skipLabel]) leaves it for later.
 */
@Composable
internal fun CloudPinFlow(
    hook: SourceHook,
    exists: Boolean,
    onDone: () -> Unit,
    onSkip: () -> Unit,
    skipLabel: String,
    modifier: Modifier = Modifier,
    greeting: String? = null,
) {
    val sync = hook.profileSync
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var wrong by remember { mutableIntStateOf(0) }
    var done by remember { mutableStateOf(false) }

    fun go(pin: String) {
        busy = true
        error = null
        scope.launch {
            try {
                sync.turnOn(hook, pin)
                done = true
                delay(1_800)
                onDone()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = ProfileSync.describe(e)
                if (e is CloudException && (e.error == "wrong_pin" || e.error == "locked")) wrong++
            } finally {
                busy = false
            }
        }
    }
    AnimatedContent(
        targetState = done,
        transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) },
        label = "cloud",
        modifier = modifier,
    ) { finished ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (finished) {
                AllSet(
                    if (exists) "Welcome back" else "Your setup is saved",
                    if (exists) "Everything from your other TV is here: settings, rows, lists and where you left off." else "On another TV, sign in and enter this PIN to bring it all along.",
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (exists) {
                        PinPad(
                            title = greeting ?: "Welcome back",
                            subtitle = "Your Orca+ setup is saved in the cloud. Enter your 6-digit sync PIN to bring it to this TV.",
                            error = error,
                            busy = busy,
                            resetKey = wrong,
                            busyText = "Bringing your setup here…",
                            onComplete = ::go,
                        )
                    } else {
                        NewPinPad(
                            title = "Keep your setup safe",
                            subtitle = "Pick a 6-digit sync PIN. On any other TV, sign in and enter it to get your settings, rows, lists and progress back.",
                            error = error,
                            busy = busy,
                            onPin = ::go,
                        )
                    }
                    SkipButton(skipLabel, busy, onSkip)
                    if (exists) {
                        Text(
                            "Forgot it? On a TV that still syncs, open Settings → Profile & Cloud → Cloud sync → Change PIN. No old PIN needed.",
                            color = Color(0xFF8C8C8C),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 80.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SkipButton(
    label: String,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = !busy,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Color(0xFFB3B3B3),
                focusedContainerColor = Color(0x33FFFFFF),
                focusedContentColor = Color.White,
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) }
}

@Composable
private fun AllSet(
    title: String,
    subtitle: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(84.dp).background(Color(0xFF2E7D32), CircleShape), contentAlignment = Alignment.Center) {
            Text("✓", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black)
        }
        Text(title, color = Color(0xFFF2F2F2), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, color = Color(0xFFB3B3B3), fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 120.dp))
    }
}

/**
 * The one-time offer, a few seconds after the app opens on a TV that doesn't sync yet: "welcome
 * back" when the signed-in account has a cloud profile, else "keep your setup safe". Either
 * answer (or "Not now") is remembered; Settings → Orca+ → Cloud sync is always there.
 */
@Composable
fun CloudPrompt() {
    val hook = LocalContext.current.sourceHook()
    val sync = hook.profileSync
    val status by sync.status.collectAsState()
    var exists by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        if (sync.prompted || status.on) return@LaunchedEffect
        delay(4_000)
        if (sync.prompted || sync.status.value.on || hook.mainConnection() == null) return@LaunchedEffect
        exists = sync.cloudHasProfile(hook)
    }
    val e = exists ?: return
    val close = {
        sync.markPrompted()
        exists = null
    }
    PadDialog(onClose = close) { CloudPinFlow(hook, e, onDone = close, onSkip = close, skipLabel = "Not now", modifier = Modifier.fillMaxSize()) }
}

/** The PIN pad over everything, on a dark screen; Back closes it. */
@Composable
internal fun PadDialog(
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
        androidx.activity.compose.BackHandler { onClose() }
        Box(Modifier.fillMaxSize().background(Color(0xF20A0A0A))) { content() }
    }
}
