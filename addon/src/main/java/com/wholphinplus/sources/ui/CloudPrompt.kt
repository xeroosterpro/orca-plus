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
import androidx.compose.foundation.layout.widthIn
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
    // A PIN sent from a phone setup: tried at once, and the phone told how it went
    autoPin: String? = null,
    // First setup: the Orca+ name comes first, then the PIN (an account: name + PIN on any TV)
    nameFirst: Boolean = false,
) {
    val sync = hook.profileSync
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var wrong by remember { mutableIntStateOf(0) }
    var done by remember { mutableStateOf(false) }
    // A new PIN set: offer an Orca+ name before "all set" (a new TV then needs only name + PIN)
    var naming by remember { mutableStateOf(false) }
    // The name picked before the PIN ([nameFirst]); saved once the PIN has made the account
    var askingName by remember { mutableStateOf(nameFirst && !exists) }
    var chosenName by remember { mutableStateOf<String?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }
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
                if (pin == autoPin) com.wholphinplus.sources.welcome.PhoneSetup.finish("All set ✓ Your Orca+ setup is back on the TV.")
                chosenName?.let { picked ->
                    try {
                        sync.setName(hook, picked)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Taken in the meantime, or offline: the PIN is set, pick the name again
                        nameError = ProfileSync.describe(e)
                    }
                }
                if (!exists && sync.status.value.name == null) {
                    naming = true
                    return@launch
                }
                done = true
                delay(1_800)
                onDone()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = ProfileSync.describe(e)
                if (e is CloudException && (e.error == "wrong_pin" || e.error == "locked")) wrong++
                if (pin == autoPin) com.wholphinplus.sources.welcome.PhoneSetup.finish("Signed in ✓ That PIN didn't open your setup: enter it on the TV.")
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(autoPin) { if (autoPin != null && exists) go(autoPin) }
    AnimatedContent(
        targetState = done,
        transitionSpec = { fadeIn(tween(420, easing = com.wholphinplus.sources.cinema.CinemaEase)) togetherWith fadeOut(tween(220, easing = com.wholphinplus.sources.cinema.CinemaFade)) },
        label = "cloud",
        modifier = modifier,
    ) { finished ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!finished && askingName) {
                AccountName(
                    hook,
                    onNext = { picked ->
                        chosenName = picked
                        askingName = false
                    },
                    onSkip = onSkip,
                    skipLabel = skipLabel,
                )
            } else if (!finished && naming) {
                NamePick(hook, startName = chosenName.orEmpty(), startError = nameError, onDone = {
                    naming = false
                    done = true
                    scope.launch {
                        delay(1_800)
                        onDone()
                    }
                })
            } else if (!finished && exists && forgot != Forgot.NO) {
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
                            title = chosenName?.let { "A PIN for $it" } ?: "Keep your setup safe",
                            subtitle =
                                if (chosenName != null) {
                                    "Pick a 6-digit PIN. On any other TV, choose \"I have an Orca+ account\" and enter ${chosenName} and this PIN: it all comes back."
                                } else {
                                    "Pick a 6-digit sync PIN. On any other TV, sign in and enter it to get your settings, rows, lists and progress back."
                                },
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
                            "A TV that still syncs can change the PIN without the old one: Settings → Account & Cloud → Cloud sync → Change PIN.",
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

/** An Orca+ name as the cloud takes it: 3-24 of a-z 0-9 . _ - , starting with a letter or digit. */
private val NAME_RULE = Regex("^[a-z0-9][a-z0-9._-]{2,23}$")

/**
 * First setup: the account's Orca+ name, checked free, before its PIN (owner, 2026-10-09: "when
 * user sets up for the first time allow them to set account name… used if they ever need to log in
 * on another device, plus their PIN").
 */
@Composable
private fun AccountName(
    hook: SourceHook,
    onNext: (String) -> Unit,
    onSkip: () -> Unit,
    skipLabel: String,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val field = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }

    fun next() {
        val n = name.trim().lowercase()
        if (busy || n.isEmpty()) return
        if (!NAME_RULE.matches(n)) {
            error = "3 to 24 letters or numbers (. _ - allowed), starting with a letter or number"
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                if (hook.profileSync.nameFree(n)) onNext(n) else error = "$n is taken. Try another one."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = ProfileSync.describe(e)
            } finally {
                busy = false
            }
        }
    }
    Column(Modifier.widthIn(max = 660.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Create your Orca+ account", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Pick a name, then a PIN. On any other TV, choose \"I have an Orca+ account\" and enter both: your servers, rows, lists and where you left off come with you.",
            color = Color(0xFFB3B3B3),
            fontSize = 16.sp,
            lineHeight = 23.sp,
            textAlign = TextAlign.Center,
        )
        com.wholphinplus.sources.welcome.WelcomeField(
            name,
            { name = it.replace(" ", "").lowercase() },
            "Orca+ name (like sam or the.smiths)",
            keyboard = androidx.compose.ui.text.input.KeyboardType.Ascii,
            imeAction = androidx.compose.ui.text.input.ImeAction.Next,
            focusRequester = field,
            onDone = ::next,
        )
        error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp, textAlign = TextAlign.Center) }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            com.wholphinplus.sources.welcome.PillButton(if (busy) "Checking…" else "Next", enabled = name.isNotBlank() && !busy, onClick = ::next)
            SkipButton(skipLabel, busy, onSkip)
        }
    }
}

/** Right after a new PIN: an optional Orca+ name, so a new TV signs in with the name and PIN alone. */
@Composable
private fun NamePick(
    hook: SourceHook,
    onDone: () -> Unit,
    startName: String = "",
    startError: String? = null,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(startName) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(startError) }
    val field = remember { androidx.compose.ui.focus.FocusRequester() }
    val softKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { field.requestFocus() }
        delay(120)
        softKeyboard?.hide()
    }

    fun save() {
        if (busy || name.isBlank()) return
        busy = true
        error = null
        scope.launch {
            try {
                hook.profileSync.setName(hook, name.trim())
                onDone()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = ProfileSync.describe(e)
            } finally {
                busy = false
            }
        }
    }
    Column(Modifier.widthIn(max = 640.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Pick an Orca+ name", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Your PIN is set. With a name too, a new TV needs only the name and the PIN: it signs in to your server by itself. You can pick one later in Settings → Account & Cloud → Cloud sync.",
            color = Color(0xFFB3B3B3),
            fontSize = 16.sp,
            lineHeight = 23.sp,
            textAlign = TextAlign.Center,
        )
        com.wholphinplus.sources.welcome.WelcomeField(
            name,
            { name = it.replace(" ", "") },
            "Orca+ name (letters and numbers, like sam)",
            keyboard = androidx.compose.ui.text.input.KeyboardType.Ascii,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            focusRequester = field,
            onDone = ::save,
        )
        error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            com.wholphinplus.sources.welcome.PillButton(if (busy) "Saving…" else "Save name", enabled = name.isNotBlank() && !busy, onClick = ::save)
            SkipButton("Not now", busy, onDone)
        }
    }
}

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
                    "After that, set a new PIN in Settings → Account & Cloud → Cloud sync. This TV works as normal meanwhile."
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
 * answer (or "Not now") is remembered; Settings → Account & Cloud → Cloud sync is always there.
 */
@Composable
fun CloudPrompt() {
    // A build without the cloud (built from source with no -PorcaCloudUrl) never asks
    if (!com.wholphinplus.sources.sync.ProfileSync.AVAILABLE) return
    val hook = LocalContext.current.sourceHook()
    val sync = hook.profileSync
    val status by sync.status.collectAsState()
    ResetWarning(hook, status)
    var exists by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        if (sync.prompted || status.on) return@LaunchedEffect
        delay(4_000)
        // mainConnection may ask the server who's signed in: never on the main thread
        val signedIn = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { hook.mainConnection() != null }
        if (sync.prompted || sync.status.value.on || !signedIn) return@LaunchedEffect
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
    // Asked once per reset: the cloud moves a reset's time later while someone runs out the
    // profile's PIN guesses, so the same reset can come back with a later time
    var dismissed by remember(status.resetAt) { mutableStateOf(ProfileSync.sameReset(sync.resetSeen, status.resetAt)) }
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
                        "unless you keep it. If that was you, you can change the PIN here instead: Settings → Account & Cloud → Cloud sync → Change PIN.",
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
