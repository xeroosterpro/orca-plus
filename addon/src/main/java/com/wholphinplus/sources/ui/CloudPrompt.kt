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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    // "Forgot PIN": PIN pad → "start over?" → the reset asked for (also shown when one already is)
    var forgot by remember { mutableStateOf(Forgot.NO) }
    var resetAt by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    LaunchedEffect(exists) {
        if (exists) sync.cloudState(hook)?.resetAt?.takeIf { it > 0 }?.let {
            resetAt = it
            forgot = Forgot.ASKED
        }
    }

    fun askReset() {
        busy = true
        error = null
        scope.launch {
            try {
                resetAt = sync.forgotPin(hook)
                forgot = Forgot.ASKED
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = ProfileSync.describe(e)
            } finally {
                busy = false
            }
        }
    }

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
        transitionSpec = { fadeIn(tween(420, easing = com.wholphinplus.sources.cinema.CinemaEase)) togetherWith fadeOut(tween(220, easing = com.wholphinplus.sources.cinema.CinemaFade)) },
        label = "cloud",
        modifier = modifier,
    ) { finished ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!finished && exists && forgot != Forgot.NO) {
                ForgotPin(
                    asked = forgot == Forgot.ASKED,
                    whenText = resetTime(context, resetAt),
                    busy = busy,
                    error = error,
                    onConfirm = ::askReset,
                    onBack = {
                        error = null
                        forgot = Forgot.NO
                    },
                    onContinue = onSkip,
                    continueLabel = skipLabel,
                )
            } else if (finished) {
                AllSet(
                    if (exists) "Welcome back" else "Your setup is saved",
                    if (exists) "Everything from your other TV is here: settings, rows, lists and where you left off." else "On another TV, sign in and enter this PIN to bring it all along.",
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                    // Side by side: stacked, the screen ran into the edge band TVs crop (overscan)
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        SkipButton(skipLabel, busy, onSkip)
                        if (exists) {
                            SkipButton("Forgot PIN?", busy) {
                                error = null
                                forgot = Forgot.CONFIRM
                            }
                        }
                    }
                    if (exists) {
                        Text(
                            "A TV that still syncs can change the PIN without the old one: Settings → Profile & Cloud → Cloud sync → Change PIN.",
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

private enum class Forgot { NO, CONFIRM, ASKED }

/** "Forgot PIN": the offer to start over ([asked] false), then when the old setup goes ([asked] true). */
@Composable
private fun ForgotPin(
    asked: Boolean,
    whenText: String,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    continueLabel: String,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(asked) { runCatching { first.requestFocus() } }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(horizontal = 140.dp),
    ) {
        Text(
            if (asked) "Your old setup will be deleted" else "Start over with a new PIN?",
            color = Color(0xFFF2F2F2),
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Text(
            if (asked) {
                "The setup saved for this account goes $whenText. Any TV that still syncs can stop it until then. " +
                    "After that, set a new PIN in Settings → Profile & Cloud → Cloud sync. This TV works as normal meanwhile."
            } else {
                "Without the PIN, the saved setup can't be opened: it's encrypted with it. Orca+ can delete it so you can start " +
                    "fresh with a new PIN. To keep anyone else from doing this to you, it waits 24 hours, and any TV that still " +
                    "syncs can stop it."
            },
            color = Color(0xFFB3B3B3),
            fontSize = 16.sp,
            lineHeight = 23.sp,
            textAlign = TextAlign.Center,
        )
        error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 15.sp) }
        if (asked) {
            PillButton(continueLabel, primary = true, busy = busy, modifier = Modifier.focusRequester(first), onClick = onContinue)
            SkipButton("I remember my PIN", busy, onBack)
        } else {
            PillButton(if (busy) "Asking the cloud…" else "Delete it in 24 hours", primary = true, busy = busy, modifier = Modifier.focusRequester(first), onClick = onConfirm)
            SkipButton("Back", busy, onBack)
        }
    }
}

/** When a reset happens, in the TV's own date and time format ("on Wed, Oct 7, 6:10 AM"). */
internal fun resetTime(
    context: android.content.Context,
    at: Long,
): String =
    "on " +
        android.text.format.DateUtils.formatDateTime(
            context,
            at,
            android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_SHOW_DATE or
                android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_ALL,
        )

@Composable
internal fun PillButton(
    label: String,
    primary: Boolean,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val f = com.wholphinplus.sources.cinema.rememberFocusFade(if (primary) Color(0x33FFFFFF) else Color(0x00FFFFFF), Color.White, Color(0xFFE6E6E6), Color.Black)
    Surface(
        onClick = onClick,
        enabled = !busy,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(containerColor = f.fill, contentColor = f.content, focusedContainerColor = f.fill, focusedContentColor = f.content),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        interactionSource = f.source,
        modifier = with(com.wholphinplus.sources.cinema.TapHelper) { modifier.glideFocus(1.05f) },
    ) { Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 26.dp, vertical = 11.dp)) }
}

@Composable
internal fun SkipButton(
    label: String,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val f = com.wholphinplus.sources.cinema.rememberFocusFade(Color(0x00FFFFFF), Color(0x33FFFFFF), Color(0xFFB3B3B3), Color.White)
    Surface(
        onClick = onClick,
        enabled = !busy,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(containerColor = f.fill, contentColor = f.content, focusedContainerColor = f.fill, focusedContentColor = f.content),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        interactionSource = f.source,
        modifier = with(com.wholphinplus.sources.cinema.TapHelper) { Modifier.glideFocus(1.05f) },
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
    ResetWarning(hook, status)
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

/**
 * On a TV that syncs, when someone used "Forgot PIN" on another TV: keep the setup, or let the
 * reset go ahead. Asked once per request (the answer is remembered with its time).
 */
@Composable
private fun ResetWarning(
    hook: SourceHook,
    status: ProfileSync.Status,
) {
    val sync = hook.profileSync
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dismissed by remember(status.resetAt) { mutableStateOf(sync.resetSeen == status.resetAt) }
    if (!status.on || status.resetAt <= 0 || dismissed) return
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val close = {
        sync.resetSeen = status.resetAt
        dismissed = true
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(300)
        runCatching { first.requestFocus() }
    }
    PadDialog(onClose = close) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 140.dp),
            ) {
                Text("Someone wants to reset your Orca+ cloud setup", color = Color(0xFFF2F2F2), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                Text(
                    "\"Forgot PIN\" was used on another TV. The setup saved for this account will be deleted ${resetTime(context, status.resetAt)} " +
                        "unless you keep it. If that was you, you can change the PIN here instead: Settings → Profile & Cloud → Cloud sync → Change PIN.",
                    color = Color(0xFFB3B3B3),
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    textAlign = TextAlign.Center,
                )
                error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 15.sp) }
                PillButton(if (busy) "Keeping it…" else "Keep my setup", primary = true, busy = busy, modifier = Modifier.focusRequester(first)) {
                    busy = true
                    scope.launch {
                        try {
                            sync.keepSetup(hook)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            error = ProfileSync.describe(e)
                        } finally {
                            busy = false
                        }
                    }
                }
                SkipButton("Let it reset", busy, close)
            }
        }
    }
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
