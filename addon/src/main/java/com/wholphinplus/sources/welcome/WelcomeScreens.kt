package com.wholphinplus.sources.welcome

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.wholphinplus.sources.core.CodeLogin
import com.wholphinplus.sources.core.EmbyConnectAccount
import com.wholphinplus.sources.core.EmbyConnectServer
import com.wholphinplus.sources.core.ServerConnection
import com.wholphinplus.sources.core.ServerKind
import com.wholphinplus.sources.cinema.CinemaEase
import com.wholphinplus.sources.cinema.CinemaFade
import com.wholphinplus.sources.cinema.CinemaRepository
import com.wholphinplus.sources.cinema.Ink
import com.wholphinplus.sources.cinema.InkDim
import com.wholphinplus.sources.cinema.Label
import com.wholphinplus.sources.cinema.Plus
import com.wholphinplus.sources.cinema.Stage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A server found on the network (or saved), for the first step. */
data class FoundServer(
    val key: String,
    val name: String,
    val address: String,
)

/** A user the server lists publicly, for the sign-in step. */
data class WelcomeUser(
    val name: String,
    val imageUrl: String?,
)

// ======================================================================== intro + server

/**
 * First launch, before any server: the grand entrance, then choosing the Jellyfin server.
 * Wholphin's own server code does the work through [onPick] / [onAddress].
 */
@Composable
fun WelcomeServerFlow(
    found: List<FoundServer>,
    connecting: Boolean,
    error: String?,
    onPick: (FoundServer) -> Unit,
    onAddress: (String) -> Unit,
    onSearchAgain: () -> Unit,
    modifier: Modifier = Modifier,
    // Signing in again ("Add a server"): straight to the server step, no first-run intro (whose
    // doors also rewrote "returning"); Back there leaves through [onBack]
    startAtServer: Boolean = false,
    onBack: () -> Unit = {},
    // "I have an Orca+ account" with an Orca+ name: signs Wholphin in with the cloud's sign-in
    // (throws when it can't); null: that door goes to the server step as before
    onCloudSignIn: (suspend (com.wholphinplus.sources.sync.MainLogin) -> Unit)? = null,
) {
    var intro by rememberSaveable { mutableStateOf(!startAtServer) }
    var account by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val hook = remember { context.welcomeHook() }
    // The first run offers setting up from a phone; what it sends takes the welcome forward
    LaunchedEffect(Unit) { if (!startAtServer) PhoneSetup.ensureStarted(hook) }
    val phone by PhoneSetup.details.collectAsState()
    LaunchedEffect(phone?.attempt) {
        val d = phone ?: return@LaunchedEffect
        if (intro || account) {
            hook.profileSync.welcomeReturning = d.pin.isNotBlank()
            intro = false
            account = false
        }
    }
    Box(modifier.fillMaxSize()) {
        WelcomeBackdrop(emptyList())
        AnimatedContent(
            targetState = if (intro) 0 else if (account) 1 else 2,
            transitionSpec = { (fadeIn(tween(700, delayMillis = 200, easing = CinemaFade)) + slideInHorizontally(tween(700, easing = CinemaEase)) { it / 8 }) togetherWith fadeOut(tween(400, easing = CinemaFade)) },
            label = "welcome",
        ) { stage ->
            when (stage) {
                0 ->
                    Intro(onStart = { returning ->
                        hook.profileSync.welcomeReturning = returning
                        intro = false
                        // A returning user: the Orca+ name and PIN first (owner, 2026-10-08)
                        account = returning && onCloudSignIn != null
                    })
                1 ->
                    NameSignInStep(
                        onSignIn = { login -> onCloudSignIn?.invoke(login) },
                        onServer = { account = false },
                        onBack = {
                            account = false
                            intro = true
                        },
                    )
                else -> ServerStep(found, connecting, error, onPick, onAddress, onSearchAgain, onBack = { if (startAtServer) onBack() else intro = true })
            }
        }
        PhoneSetupBanner(Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 40.dp))
    }
}

@Composable
private fun Intro(onStart: (returning: Boolean) -> Unit) {
    val letters = "ORCA"
    val reveal = remember { letters.map { Animatable(0f) } }
    val plus = remember { Animatable(0f) }
    val rest = remember { Animatable(0f) }
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(450)
        reveal.forEachIndexed { i, a ->
            launch {
                delay(i * 110L)
                a.animateTo(1f, tween(700, easing = CinemaEase))
            }
        }
        delay(letters.length * 110L + 250)
        // The one bounce in the app: the logo's plus lands with a small overshoot (a brand moment, once)
        launch { plus.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 260f)) }
        delay(450)
        rest.animateTo(1f, tween(800, easing = CinemaEase))
        runCatching { start.requestFocus() }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            letters.forEachIndexed { i, c ->
                Text(
                    c.toString(),
                    color = Ink,
                    fontSize = 108.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 6.sp,
                    modifier =
                        Modifier.graphicsLayer {
                            alpha = reveal[i].value
                            translationY = (1f - reveal[i].value) * 40.dp.toPx()
                        },
                )
            }
            Box(contentAlignment = Alignment.Center) {
                // The plus arrives with a glow, drawn behind it so it takes no room beside the word
                Text(
                    "+",
                    color = Plus,
                    fontSize = 108.sp,
                    fontWeight = FontWeight.Black,
                    modifier =
                        Modifier.drawBehind {
                            val r = size.minDimension * 0.9f * (0.6f + plus.value * 0.6f)
                            drawCircle(Brush.radialGradient(listOf(Violet.copy(alpha = 0.55f * plus.value.coerceIn(0f, 1f)), Color.Transparent), center = center, radius = r), radius = r)
                        }.graphicsLayer {
                            alpha = plus.value.coerceIn(0f, 1f)
                            scaleX = 0.2f + plus.value * 0.8f
                            scaleY = 0.2f + plus.value * 0.8f
                        },
                )
            }
        }
        Column(Modifier.graphicsLayer { alpha = rest.value; translationY = (1f - rest.value) * 16.dp.toPx() }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Every library you love. One beautiful home.", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text("STARTS WITH YOUR JELLYFIN OR SILO SERVER  ·  ADD EMBY, PLEX AND MORE", color = InkDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(36.dp))
            // The fastest way in: the phone (owner, 2026-10-08), beside the two doors on this TV: a
            // guided setup, or bringing a saved setup back with the sync PIN
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                if (com.wholphinplus.sources.sync.ProfileSync.AVAILABLE) PhoneSetupCard()
                Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (com.wholphinplus.sources.sync.ProfileSync.AVAILABLE) {
                        Text("OR SET UP ON THIS TV", color = InkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                    ChoiceCard("I'm new to Orca+", "A quick tour sets up your server, look and pages", "+", Violet, modifier = Modifier.focusRequester(start)) { onStart(false) }
                    if (com.wholphinplus.sources.sync.ProfileSync.AVAILABLE) {
                        ChoiceCard("I have an Orca+ account", "Sign in, enter your sync PIN, and it's all back", "↺", Rose) { onStart(true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerStep(
    found: List<FoundServer>,
    connecting: Boolean,
    error: String?,
    onPick: (FoundServer) -> Unit,
    onAddress: (String) -> Unit,
    onSearchAgain: () -> Unit,
    onBack: () -> Unit,
) {
    var typing by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    val softKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val hook = LocalContext.current.welcomeHook()
    // When the main-server connection fails, check whether the address is an Emby or Plex
    // server, to say so instead of a plain connection error
    var wrongKind by remember { mutableStateOf<ServerKind?>(null) }
    LaunchedEffect(error) {
        wrongKind = null
        if (error != null && address.isNotBlank()) {
            val kind = withContext(Dispatchers.IO) { runCatching { hook.client.fetchPublicInfo(com.wholphinplus.sources.core.normalizeServerUrl(address.trim())).serverKind }.getOrNull() }
            wrongKind = kind?.takeIf { it == ServerKind.EMBY || it == ServerKind.PLEX }
        }
    }
    val connect = { url: String ->
        softKeyboard?.hide()
        onAddress(url)
    }
    var searching by remember { mutableStateOf(true) }
    val firstCard = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    BackHandler { if (typing) typing = false else onBack() }
    LaunchedEffect(Unit) {
        delay(4_500)
        searching = false
    }
    LaunchedEffect(typing) { runCatching { if (typing) field.requestFocus() else firstCard.requestFocus() } }
    // A server found after the screen opened takes the focus, unless the remote has been used
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(found.isNotEmpty()) { if (found.isNotEmpty() && !typing && !touched) runCatching { firstCard.requestFocus() } }

    Row(
        Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 56.dp).onPreviewKeyEvent {
            touched = true
            false
        },
        horizontalArrangement = Arrangement.spacedBy(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(0.42f), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            StepHeader(
                "SIGN IN  ·  1 OF 2",
                "Connect your main server",
                "Orca+ runs on one main server. Pick it below, or type its address. Your other servers come next.",
            )
            ServerRoles()
        }
        GlassPanel(Modifier.weight(0.58f)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!typing) {
                    found.forEachIndexed { i, s ->
                        ChoiceCard(
                            title = s.name.ifBlank { "Jellyfin server" },
                            subtitle = s.address + "  ·  found on your network",
                            glyph = "J",
                            accent = Violet,
                            trailing = if (connecting) "Connecting…" else "›",
                            modifier = if (i == 0) Modifier.focusRequester(firstCard) else Modifier,
                            onClick = { onPick(s) },
                        )
                    }
                    if (found.isEmpty()) {
                        Text(if (searching) "Searching your network…" else "No servers found automatically. Type its address below.", color = InkDim, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp))
                    }
                    ChoiceCard(
                        title = "Enter an address",
                        subtitle = "For example 10.0.0.20:8096 or jellyfin.example.com",
                        glyph = "+",
                        accent = Indigo,
                        modifier = if (found.isEmpty()) Modifier.focusRequester(firstCard) else Modifier,
                        onClick = { typing = true },
                    )
                    if (!searching && found.isEmpty()) {
                        PillButton("Search again", primary = false, onClick = {
                            searching = true
                            onSearchAgain()
                        })
                    }
                } else {
                    WelcomeField(address, { address = it }, "Server address", keyboard = KeyboardType.Uri, imeAction = ImeAction.Go, focusRequester = field, onDone = { if (address.isNotBlank()) connect(address.trim()) })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillButton(if (connecting) "Connecting…" else "Connect", enabled = address.isNotBlank() && !connecting, onClick = { connect(address.trim()) })
                        PillButton("Back", primary = false, onClick = { typing = false })
                    }
                }
                if (error != null) {
                    val kind = wrongKind
                    if (kind != null) {
                        Text("That's ${if (kind == ServerKind.PLEX) "a Plex" else "an Emby"} server", color = Color(0xFFFFC46B), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Your main server needs to be Jellyfin or Silo. Connect that here, then add your ${if (kind == ServerKind.PLEX) "Plex" else "Emby"} server once you're signed in.", color = InkDim, fontSize = 13.sp, lineHeight = 18.sp)
                    } else {
                        Text("Couldn't connect to that server", color = Color(0xFFFF8A80), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Check the address and that the server is switched on. Orca+ tried it with and without https and the usual ports.", color = InkDim, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
    }
    StepDotsAtBottom(0)
}

// ======================================================================== sign-in

/**
 * Signing in to the chosen server: a Quick Connect code first (approve it on a phone, nothing to
 * type), or a password. Wholphin's own sign-in does the work through the callbacks.
 */
@Composable
fun WelcomeSignIn(
    serverName: String,
    quickEnabled: Boolean,
    quickCode: String?,
    users: List<WelcomeUser>,
    busy: Boolean,
    error: String?,
    onQuickConnect: () -> Unit,
    onPassword: (user: String, password: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Over the headline: the first run's step, or a plain "SIGN IN" when signing in again. */
    eyebrow: String = "SIGN IN  ·  2 OF 2",
    /** A user picked from the list (a user already signed in on this TV can switch straight in). */
    onPickUser: (String) -> Unit = {},
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val userField = remember { FocusRequester() }
    val passField = remember { FocusRequester() }
    val softKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    BackHandler { onBack() }
    LaunchedEffect(quickEnabled) { if (quickEnabled && quickCode == null) onQuickConnect() }
    LaunchedEffect(Unit) { softKeyboard?.hide() }
    LaunchedEffect(Unit) {
        delay(300)
        // The first listed user, or the username box when the server lists none
        runCatching { userField.requestFocus() }
        // Focusing a text box opens the keyboard on Android TV; keep it closed until OK is pressed
        delay(120)
        softKeyboard?.hide()
    }
    Box(modifier.fillMaxSize()) {
        WelcomeBackdrop(emptyList())
        Row(Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 56.dp), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(0.42f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                StepHeader(
                    eyebrow,
                    "Sign in to ${serverName.ifBlank { "your server" }}",
                    if (quickEnabled) "Approve the code from your phone and Orca+ carries on by itself. Or sign in with your password." else "Sign in with your Jellyfin username and password.",
                )
                if (quickEnabled && quickCode != null) {
                    CodeDisplay(quickCode, "On your phone or computer open Jellyfin, go to your profile, then Quick Connect, and enter this code.")
                }
            }
            GlassPanel(Modifier.weight(0.58f)) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(if (quickEnabled) "Or use your password" else "Sign in with your password", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (users.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            users.take(5).forEachIndexed { i, u ->
                                UserChip(u, selected = u.name == username, modifier = if (i == 0) Modifier.focusRequester(userField) else Modifier) {
                                    username = u.name
                                    runCatching { passField.requestFocus() }
                                    onPickUser(u.name)
                                }
                            }
                        }
                    }
                    if (users.isEmpty()) {
                        WelcomeField(username, { username = it }, "Username", focusRequester = userField, onDone = { runCatching { passField.requestFocus() } })
                    }
                    WelcomeField(password, { password = it }, "Password", password = true, imeAction = ImeAction.Done, focusRequester = passField, onDone = { if (username.isNotBlank()) onPassword(username, password) })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillButton(if (busy) "Signing in…" else "Sign in", enabled = username.isNotBlank() && !busy, onClick = { onPassword(username, password) })
                        PillButton("Change server", primary = false, onClick = onBack)
                    }
                    error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp) }
                }
            }
        }
        StepDotsAtBottom(1)
        // Signing in with what the phone sent: what is happening, or what to fix there
        PhoneSetupBanner(Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 40.dp))
    }
}

@Composable
private fun UserChip(
    user: WelcomeUser,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val f = com.wholphinplus.sources.cinema.rememberFocusFade(if (selected) Color(0x33FFFFFF) else Color.White.copy(alpha = 0f), Ink, Ink, Stage)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = f.fill, contentColor = f.content, focusedContainerColor = f.fill, focusedContentColor = f.content),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        interactionSource = f.source,
        modifier = with(com.wholphinplus.sources.cinema.TapHelper) { modifier.tapClick(onClick).glideFocus(1.06f) },
    ) {
        Column(Modifier.padding(10.dp).width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Violet, Rose))), contentAlignment = Alignment.Center) {
                if (user.imageUrl != null) {
                    AsyncImage(model = user.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(user.name.take(1).uppercase(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(user.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun StepDotsAtBottom(current: Int) {
    Box(Modifier.fillMaxSize().padding(bottom = 28.dp), contentAlignment = Alignment.BottomCenter) { StepDots(current, 2) }
}

// ======================================================================== after sign-in

private enum class FinishStep { CHECKING, RESTORE, NO_PROFILE, TAGS, PAGES, POWERUPS, SAVE, LIBRARIES, EMBY_CHOOSE, EMBY, EMBY_PICK, EMBY_ADDRESS, PLEX, JELLYFIN_CHOOSE, SILO_CHOOSE, JELLYFIN, OTHER_ADDRESS, LOOK, OUTRO }

/**
 * Signed in: bring in other libraries (Emby Connect, Plex, more Jellyfin) and choose the look,
 * over the app with the person's own posters gliding behind. Ends with a flourish into Home.
 */
@Composable
fun WelcomeFinish(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hook = remember { context.welcomeHook() }
    val scope = rememberCoroutineScope()
    val connections by hook.store.connections.collectAsState()
    val cinemaMode by hook.store.cinemaMode.collectAsState()
    // Until a look is picked the tour counts Cinema's stops (it's the one offered first)
    var lookPicked by rememberSaveable { mutableStateOf(false) }
    val cinema = cinemaMode || !lookPicked
    var step by rememberSaveable { mutableStateOf(FinishStep.CHECKING) }
    // Whether the account already has a cloud profile (null: the cloud couldn't be asked)
    var cloudHas by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        if (step != FinishStep.CHECKING) return@LaunchedEffect
        // The default rows start loading now, so they're matched by the time the tour ends
        hook.collections.refreshStale(hook)
        cloudHas = kotlinx.coroutines.withTimeoutOrNull(8_000) { hook.profileSync.cloudHasProfile(hook) }
        step =
            when {
                cloudHas == true && !hook.profileSync.status.value.on -> FinishStep.RESTORE
                hook.profileSync.welcomeReturning -> FinishStep.NO_PROFILE
                else -> FinishStep.LIBRARIES
            }
        // Set up from a phone: signed in; the phone hears how it ends (the PIN step tells it itself)
        if (PhoneSetup.active) {
            when {
                step == FinishStep.RESTORE && PhoneSetup.details.value?.pin.isNullOrBlank() ->
                    PhoneSetup.finish("Signed in ✓ Enter your Orca+ PIN on the TV to bring back your setup.")
                step == FinishStep.NO_PROFILE -> PhoneSetup.finish("Signed in ✓ No Orca+ setup was found for this account. Finish on the TV.")
                step == FinishStep.LIBRARIES -> PhoneSetup.finish("Signed in ✓ Finish the short tour on your TV.")
            }
        }
    }
    var posters by remember { mutableStateOf<List<String>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var otherIsSilo by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { posters = runCatching { CinemaRepository(hook, hook.collections).posterWall() }.getOrDefault(emptyList()) }

    fun save(c: ServerConnection) {
        hook.store.save(c)
        hook.clearCache()
        message = "Added ${c.label}"
    }

    Box(modifier.fillMaxSize()) {
        WelcomeBackdrop(posters)
        // Back on the tour's first stop (and while checking or leaving) stays put: leaving the app
        // mid-welcome would only start it again next time
        BackHandler(enabled = step != FinishStep.OUTRO) {
            step =
                when (step) {
                    FinishStep.CHECKING, FinishStep.LIBRARIES -> step
                    FinishStep.SAVE -> FinishStep.POWERUPS
                    // One look now: no "pick your home screen" stop between libraries and tags
                    FinishStep.POWERUPS -> FinishStep.PAGES
                    FinishStep.PAGES -> FinishStep.TAGS
                    FinishStep.TAGS -> FinishStep.LIBRARIES
                    FinishStep.LOOK -> FinishStep.LIBRARIES
                    FinishStep.EMBY_PICK -> FinishStep.EMBY
                    FinishStep.EMBY, FinishStep.EMBY_ADDRESS -> FinishStep.EMBY_CHOOSE
                    FinishStep.JELLYFIN, FinishStep.OTHER_ADDRESS -> if (otherIsSilo) FinishStep.SILO_CHOOSE else FinishStep.JELLYFIN_CHOOSE
                    else -> FinishStep.LIBRARIES
                }
        }
        val stops = TourStop.entries - TourStop.LOOK
        androidx.compose.runtime.CompositionLocalProvider(LocalTourStops provides stops) {
            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn(tween(500, delayMillis = 120, easing = CinemaEase)) togetherWith fadeOut(tween(250, easing = CinemaFade)) },
                label = "finish",
            ) { s ->
                when (s) {
                    FinishStep.CHECKING ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("One moment…", color = InkDim, fontSize = 18.sp) }
                    // The account has a cloud profile: the PIN brings everything, so the rest is skipped
                    FinishStep.RESTORE ->
                        com.wholphinplus.sources.ui.CloudPinFlow(
                            hook,
                            exists = true,
                            // The PIN the phone sent, or the one typed with the Orca+ name: tried once
                            autoPin = remember { PhoneSetup.claimPin() ?: hook.profileSync.takePendingPin() },
                            onDone = { step = FinishStep.OUTRO },
                            onSkip = {
                                hook.profileSync.markPrompted()
                                step = FinishStep.LIBRARIES
                            },
                            skipLabel = "Start fresh instead",
                            // Picked "I'm new", but this account already saved a setup from another TV
                            greeting = if (hook.profileSync.welcomeReturning) null else "You already have a setup",
                            modifier = Modifier.fillMaxSize().background(Stage.copy(alpha = 0.82f)),
                        )
                    FinishStep.NO_PROFILE -> NoProfileStep(offline = cloudHas == null, onNew = { step = FinishStep.LIBRARIES })
                    FinishStep.TAGS -> TagsStep(hook, onNext = { step = FinishStep.PAGES })
                    FinishStep.PAGES -> PagesStep(hook, onNext = { step = FinishStep.POWERUPS })
                    FinishStep.POWERUPS -> PowerUpsStep(hook, onNext = { step = if (cloudHas == false && !hook.profileSync.status.value.on) FinishStep.SAVE else FinishStep.OUTRO })
                    FinishStep.SAVE ->
                        com.wholphinplus.sources.ui.CloudPinFlow(
                            hook,
                            exists = false,
                            onDone = { step = FinishStep.OUTRO },
                            onSkip = {
                                hook.profileSync.markPrompted()
                                step = FinishStep.OUTRO
                            },
                            skipLabel = "Skip for now",
                            nameFirst = true,
                            modifier = Modifier.fillMaxSize().background(Stage.copy(alpha = 0.82f)),
                        )
                    FinishStep.LIBRARIES -> LibrariesStep(connections, message, onPick = { step = it }, onNext = { step = FinishStep.TAGS })
                    FinishStep.EMBY_CHOOSE ->
                        ChooseStep(
                            "Add your Emby server",
                            "Use your Emby Connect account to pick from your servers, or enter one server's details yourself.",
                            codeTitle = "Emby Connect",
                            codeSubtitle = "Enter a code at emby.media/pin on your phone, then pick your servers",
                            glyph = "E",
                            accent = Color(0xFF52B54B),
                            onCode = { step = FinishStep.EMBY },
                            onAddress = { step = FinishStep.EMBY_ADDRESS },
                            onCancel = { step = FinishStep.LIBRARIES },
                        )
                    FinishStep.JELLYFIN_CHOOSE, FinishStep.SILO_CHOOSE -> {
                        val silo = s == FinishStep.SILO_CHOOSE
                        otherIsSilo = silo
                        ChooseStep(
                            if (silo) "Add a Silo server" else "Add a Jellyfin server",
                            if (silo) "Silo works like Jellyfin. Sign in with its address and your account, or approve a Quick Connect code if the server has it on." else "Approve a Quick Connect code from your phone, or sign in with the server's address and your account.",
                            codeTitle = "Quick Connect",
                            codeSubtitle = "Type the address, then approve a code on your phone",
                            glyph = if (silo) "S" else "J",
                            accent = if (silo) Color(0xFF3D8BD8) else Violet,
                            onCode = { step = FinishStep.JELLYFIN },
                            onAddress = { step = FinishStep.OTHER_ADDRESS },
                            onCancel = { step = FinishStep.LIBRARIES },
                            addressFirst = silo,
                        )
                    }
                    FinishStep.OTHER_ADDRESS ->
                        AddressStep(
                            title = if (otherIsSilo) "Your Silo server" else "Your Jellyfin server",
                            example = if (otherIsSilo) "silo.example.com" else "10.0.0.20:8096",
                            defaultKind = ServerKind.JELLYFIN,
                            onConnected = {
                                save(it)
                                step = FinishStep.LIBRARIES
                            },
                            onCancel = { step = if (otherIsSilo) FinishStep.SILO_CHOOSE else FinishStep.JELLYFIN_CHOOSE },
                        )
                    FinishStep.EMBY -> EmbyStep(onAccount = { step = FinishStep.EMBY_PICK }, onCancel = { step = FinishStep.EMBY_CHOOSE }, onError = { message = it })
                    FinishStep.EMBY_ADDRESS -> AddressStep(title = "Your Emby server", example = "10.0.0.30:8096", defaultKind = ServerKind.EMBY, onConnected = {
                        save(it)
                        step = FinishStep.LIBRARIES
                    }, onCancel = { step = FinishStep.EMBY_CHOOSE })
                    FinishStep.EMBY_PICK -> EmbyPickStep(onAdded = {
                        it.forEach(::save)
                        step = FinishStep.LIBRARIES
                    }, onCancel = { step = FinishStep.LIBRARIES })
                    FinishStep.PLEX ->
                        CodeStep(
                            title = "Sign in to Plex",
                            start = { hook.client.startPlexPin("") },
                            poll = { hook.client.pollPlexPin(it, "") },
                            where = "On your phone or computer go to plex.tv/link and enter this code.",
                            onConnected = {
                                save(it)
                                step = FinishStep.LIBRARIES
                            },
                            onCancel = { step = FinishStep.LIBRARIES },
                        )
                    FinishStep.JELLYFIN -> JellyfinStep(silo = otherIsSilo, onConnected = {
                        save(it)
                        step = FinishStep.LIBRARIES
                    }, onCancel = { step = if (otherIsSilo) FinishStep.SILO_CHOOSE else FinishStep.JELLYFIN_CHOOSE })
                    FinishStep.LOOK -> LookStep(onChoose = { cinema ->
                        hook.store.setCinemaMode(cinema)
                        lookPicked = true
                        // Cinema shows poster tags and its pages; both looks then see power-ups
                        step = if (cinema) FinishStep.TAGS else FinishStep.POWERUPS
                    })
                    FinishStep.OUTRO -> Outro(onFinished = {
                        hook.store.setOnboarding(Onboarding.DONE)
                        onDone()
                    })
                }
            }
        }
        PhoneSetupBanner(Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 40.dp))
    }
}

@Composable
private fun LibrariesStep(
    connections: List<ServerConnection>,
    message: String?,
    onPick: (FinishStep) -> Unit,
    onNext: () -> Unit,
) {
    // Most people have one server: Skip is where the remote starts
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    fun added(kind: ServerKind) = connections.count { it.serverKind == kind }
    Row(Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 56.dp), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
        StepHeader(
            tourStep(TourStop.SERVERS, "OPTIONAL"),
            "Add more servers",
            "You're in. Add Emby, Plex, or another Jellyfin or Silo server, and Orca+ finds every title wherever it lives: press Play and pick the best copy. You can do this later in Settings too.",
            Modifier.weight(0.42f),
        )
        GlassPanel(Modifier.weight(0.58f)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceCard("Emby", "Emby Connect, or address and sign-in", "E", Color(0xFF52B54B), compact = true, trailing = if (added(ServerKind.EMBY) > 0) "${added(ServerKind.EMBY)} added ✓" else "›") { onPick(FinishStep.EMBY_CHOOSE) }
                ChoiceCard("Plex", "A code at plex.tv/link, nothing to type", "P", Color(0xFFE5A00D), compact = true, trailing = if (added(ServerKind.PLEX) > 0) "${added(ServerKind.PLEX)} added ✓" else "›") { onPick(FinishStep.PLEX) }
                // Extra Jellyfin and Silo servers count together (Silo speaks Jellyfin's API)
                ChoiceCard("Jellyfin", "Quick Connect, or address and sign-in", "J", Violet, compact = true, trailing = if (added(ServerKind.JELLYFIN) > 0) "${added(ServerKind.JELLYFIN)} added ✓" else "›") { onPick(FinishStep.JELLYFIN_CHOOSE) }
                ChoiceCard("Silo", "Address and sign-in, or a Quick Connect code", "S", Color(0xFF3D8BD8), compact = true, trailing = "›") { onPick(FinishStep.SILO_CHOOSE) }
                message?.let { Text(it, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(if (connections.isEmpty()) "Skip for now" else "Continue", modifier = Modifier.focusRequester(first), onClick = onNext)
                }
            }
        }
    }
}

/** A code to approve elsewhere (Plex, Emby, Quick Connect), polled until it's approved. */
@Composable
private fun <T> CodeStep(
    title: String,
    start: suspend () -> CodeLogin,
    poll: suspend (CodeLogin) -> T?,
    where: String,
    onConnected: (T) -> Unit,
    onCancel: () -> Unit,
) {
    var login by remember { mutableStateOf<CodeLogin?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { cancel.requestFocus() }
        try {
            val l = withContext(Dispatchers.IO) { start() }
            login = l
            val deadline = System.currentTimeMillis() + 10 * 60_000L
            while (System.currentTimeMillis() < deadline) {
                delay(l.intervalSeconds.coerceAtLeast(2) * 1000L)
                val result = withContext(Dispatchers.IO) { runCatching { poll(l) } }
                // A blip shows while it lasts; the next good answer clears it
                error = result.exceptionOrNull()?.let { if (it is CancellationException) throw it else it.message }
                result.getOrNull()?.let {
                    onConnected(it)
                    return@LaunchedEffect
                }
            }
            error = "The code expired. Go back and try again."
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = e.message ?: "Couldn't start the sign-in"
        }
    }
    Row(Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 56.dp), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
        StepHeader("ADD A SERVER", title, "Nothing to type on the TV. Approve the code and Orca+ adds it by itself.", Modifier.weight(0.42f))
        GlassPanel(Modifier.weight(0.58f)) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                val l = login
                if (l != null) CodeDisplay(l.code, where) else Text("Getting a code…", color = InkDim, fontSize = 16.sp)
                error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp) }
                PillButton("Cancel", primary = false, modifier = Modifier.focusRequester(cancel), onClick = onCancel)
            }
        }
    }
}

// Emby Connect keeps its account between the PIN and the server picker
private object EmbySession {
    var account: EmbyConnectAccount? = null
}

@Composable
private fun EmbyStep(
    onAccount: () -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit,
) {
    val hook = LocalContext.current.welcomeHook()
    CodeStep(
        title = "Sign in with Emby Connect",
        start = { hook.client.startEmbyConnectPin() },
        poll = { hook.client.pollEmbyConnectPin(it) },
        where = "On your phone or computer go to emby.media/pin, sign in to your Emby account and enter this code.",
        onConnected = {
            EmbySession.account = it
            onAccount()
        },
        onCancel = onCancel,
    )
}

@Composable
private fun EmbyPickStep(
    onAdded: (List<ServerConnection>) -> Unit,
    onCancel: () -> Unit,
) {
    val hook = LocalContext.current.welcomeHook()
    val scope = rememberCoroutineScope()
    val account = EmbySession.account
    var servers by remember { mutableStateOf<List<EmbyConnectServer>?>(null) }
    val chosen = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(account) {
        if (account == null) return@LaunchedEffect onCancel()
        servers = runCatching { withContext(Dispatchers.IO) { hook.client.embyConnectServers(account) } }.onFailure { error = it.message }.getOrDefault(emptyList())
        // A few: all ticked. An account with many (owner, 2026-10-08: too many to scroll): pick
        servers?.takeIf { it.size <= 3 }?.forEach { chosen += it.systemId.ifBlank { it.name } }
        delay(100)
        runCatching { first.requestFocus() }
    }
    Row(Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 56.dp), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
        StepHeader("EMBY CONNECT", "Choose your Emby servers", "These are linked to your Emby account. Orca+ signs in to each one for you.", Modifier.weight(0.42f))
        GlassPanel(Modifier.weight(0.58f)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val list = servers
                when {
                    list == null -> Text("Finding your servers…", color = InkDim, fontSize = 16.sp)
                    list.isEmpty() -> Text("No servers are linked to this Emby account.", color = InkDim, fontSize = 16.sp)
                    else -> {
                        if (list.size > 3) Text("${list.size} servers on this account  ·  OK ticks the ones to add", color = InkDim, fontSize = 14.sp)
                        // Scrolls: an account can have dozens, and the Add button sat below them out of reach
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                            modifier = Modifier.heightIn(max = 470.dp),
                        ) {
                            items(list.size) { i ->
                                val s = list[i]
                                val key = s.systemId.ifBlank { s.name }
                                ChoiceCard(
                                    title = s.name.ifBlank { "Emby server" },
                                    subtitle = s.localUrl.ifBlank { s.remoteUrl },
                                    glyph = "E",
                                    accent = Color(0xFF52B54B),
                                    trailing = if (key in chosen) "✓" else "",
                                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                                ) { if (key in chosen) chosen -= key else chosen += key }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(if (busy) "Adding…" else "Add ${chosen.size} server${if (chosen.size == 1) "" else "s"}", enabled = chosen.isNotEmpty() && !busy && account != null, onClick = {
                        val acc = account ?: return@PillButton
                        busy = true
                        scope.launch {
                            val picked = list.orEmpty().filter { (it.systemId.ifBlank { it.name }) in chosen }
                            val results = withContext(Dispatchers.IO) { picked.map { s -> runCatching { hook.client.connectEmbyServer(acc, s, "") } } }
                            busy = false
                            val ok = results.mapNotNull { it.getOrNull() }
                            results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { error = it.message }
                            if (ok.isNotEmpty()) onAdded(ok)
                        }
                    })
                    PillButton("Back", primary = false, onClick = onCancel)
                }
            }
        }
    }
}

/** Two ways to add a server: a code to approve elsewhere, or its address and sign-in. */
@Composable
private fun ChooseStep(
    title: String,
    body: String,
    codeTitle: String,
    codeSubtitle: String,
    glyph: String,
    accent: Color,
    onCode: () -> Unit,
    onAddress: () -> Unit,
    onCancel: () -> Unit,
    addressFirst: Boolean = false,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Row(Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 56.dp), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
        StepHeader("ADD A SERVER", title, body, Modifier.weight(0.42f))
        GlassPanel(Modifier.weight(0.58f)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val code: @Composable (Modifier) -> Unit = { m -> ChoiceCard(codeTitle, codeSubtitle, glyph, accent, modifier = m, trailing = "›", onClick = onCode) }
                val address: @Composable (Modifier) -> Unit = { m -> ChoiceCard("Server address & password", "Type the server's address, your username and password", "+", Indigo, modifier = m, trailing = "›", onClick = onAddress) }
                if (addressFirst) {
                    address(Modifier.focusRequester(first))
                    code(Modifier)
                } else {
                    code(Modifier.focusRequester(first))
                    address(Modifier)
                }
                PillButton("Back", primary = false, onClick = onCancel)
            }
        }
    }
}

/** A server by address, username and password (Emby, Jellyfin or Silo). */
@Composable
private fun AddressStep(
    title: String,
    example: String,
    defaultKind: ServerKind,
    onConnected: (ServerConnection) -> Unit,
    onCancel: () -> Unit,
) {
    val hook = LocalContext.current.welcomeHook()
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val addressField = remember { FocusRequester() }
    val userField = remember { FocusRequester() }
    val passField = remember { FocusRequester() }
    val softKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { addressField.requestFocus() }
        delay(120)
        softKeyboard?.hide()
    }
    fun add() {
        if (address.isBlank() || username.isBlank() || busy) return
        softKeyboard?.hide()
        busy = true
        error = null
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val url = com.wholphinplus.sources.core.normalizeServerUrl(address.trim())
                        val info = hook.client.fetchPublicInfo(url)
                        val kind = if (info.serverKind == ServerKind.UNKNOWN) defaultKind else info.serverKind
                        hook.client.signIn(url, info.copy(serverKind = kind), username.trim(), password, "")
                    }
                }
            busy = false
            result.onSuccess(onConnected).onFailure { error = it.message ?: "Couldn't sign in to that server" }
        }
    }
    Row(Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 48.dp), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
        StepHeader("ADD A SERVER", title, "Its address (for example $example), and the username and password you use on it.", Modifier.weight(0.42f))
        GlassPanel(Modifier.weight(0.58f)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WelcomeField(address, { address = it }, "Server address", keyboard = KeyboardType.Uri, focusRequester = addressField, onDone = { runCatching { userField.requestFocus() } })
                WelcomeField(username, { username = it }, "Username", focusRequester = userField, onDone = { runCatching { passField.requestFocus() } })
                WelcomeField(password, { password = it }, "Password", password = true, imeAction = ImeAction.Done, focusRequester = passField, onDone = { add() })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(if (busy) "Signing in…" else "Add server", enabled = address.isNotBlank() && username.isNotBlank() && !busy, onClick = { add() })
                    PillButton("Back", primary = false, onClick = onCancel)
                }
                error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun JellyfinStep(
    onConnected: (ServerConnection) -> Unit,
    onCancel: () -> Unit,
    silo: Boolean = false,
) {
    val hook = LocalContext.current.welcomeHook()
    var address by rememberSaveable { mutableStateOf("") }
    var go by remember { mutableStateOf<String?>(null) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    val target = go
    if (target != null) {
        CodeStep(
            title = "Approve Orca+ on that server",
            start = { hook.client.startQuickConnect(com.wholphinplus.sources.core.normalizeServerUrl(target)) },
            poll = { hook.client.pollQuickConnect(it, "") },
            where = "In Jellyfin on that server (phone or computer): your profile, then Quick Connect, and enter this code.",
            onConnected = onConnected,
            onCancel = { go = null },
        )
        return
    }
    Row(Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 56.dp), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
        StepHeader("ADD A SERVER", if (silo) "Another Silo server" else "Another Jellyfin server", "Type its address, then approve the code Orca+ shows you.", Modifier.weight(0.42f))
        GlassPanel(Modifier.weight(0.58f)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                WelcomeField(address, { address = it }, "Server address", keyboard = KeyboardType.Uri, imeAction = ImeAction.Go, focusRequester = field, onDone = { if (address.isNotBlank()) go = address.trim() })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Continue", enabled = address.isNotBlank(), onClick = { go = address.trim() })
                    PillButton("Back", primary = false, onClick = onCancel)
                }
            }
        }
    }
}

@Composable
private fun LookStep(onChoose: (Boolean) -> Unit) {
    val cinemaCard = remember { FocusRequester() }
    var cinema by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { runCatching { cinemaCard.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 32.dp), verticalArrangement = Arrangement.Center) {
        StepHeader(tourStep(TourStop.LOOK), "Pick your home screen", "Cinema is the big-screen look with a featured billboard. Classic is the familiar home with a side menu. Switch any time in Settings.")
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            LookCard("Cinema", "Billboard, tall rows, title art", selected = cinema, modifier = Modifier.width(360.dp).focusRequester(cinemaCard), onFocus = { cinema = true }, onClick = { onChoose(true) }) { CinemaPreview() }
            LookCard("Classic", "The familiar home with a side menu", selected = !cinema, modifier = Modifier.width(360.dp), onFocus = { cinema = false }, onClick = { onChoose(false) }) { ClassicPreview() }
        }
        Spacer(Modifier.height(12.dp))
        Text("Press OK to start watching", color = InkDim, fontSize = 13.sp)
    }
}

@Composable
private fun LookCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    preview: @Composable () -> Unit,
) {
    val f = com.wholphinplus.sources.cinema.rememberFocusFade(Glass, Color(0x33FFFFFF), Ink, Ink)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = f.fill, contentColor = f.content, focusedContainerColor = f.fill, focusedContentColor = f.content),
        border =
            ClickableSurfaceDefaults.border(
                border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, GlassLine), shape = RoundedCornerShape(20.dp)),
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        interactionSource = f.source,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() }.tapClick(onClick).then(com.wholphinplus.sources.cinema.glideEdge(1.03f, 20.dp)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)).background(Stage)) { preview() }
            Spacer(Modifier.height(2.dp))
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, fontSize = 13.sp, color = InkDim, maxLines = 1)
        }
    }
}

/** A miniature of Cinema mode: billboard, title, buttons, a row of wide cards. */
@Composable
private fun CinemaPreview() {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3A2D6B), Color(0xFF151320), Color(0xFF5B2238))))) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Stage, Color.Transparent))))
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("+ SERIES", color = InkDim, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Box(Modifier.width(110.dp).height(16.dp).clip(RoundedCornerShape(3.dp)).background(Ink))
            Box(Modifier.width(80.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(InkDim))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.width(30.dp).height(10.dp).clip(RoundedCornerShape(50)).background(Ink))
                Box(Modifier.width(36.dp).height(10.dp).clip(RoundedCornerShape(50)).background(Color(0x55FFFFFF)))
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(4) { i -> Box(Modifier.width(48.dp).height(27.dp).clip(RoundedCornerShape(3.dp)).background(listOf(Violet, Rose, Indigo, Label)[i].copy(alpha = 0.8f))) }
        }
    }
}

/** A miniature of the classic home: side menu and a grid of posters. */
@Composable
private fun ClassicPreview() {
    Row(Modifier.fillMaxSize().background(Color(0xFF1B1A24))) {
        Column(Modifier.width(26.dp).fillMaxHeight().background(Color(0xFF24222F)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(6) { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0x55FFFFFF))) }
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { repeat(6) { j -> Box(Modifier.width(22.dp).height(33.dp).clip(RoundedCornerShape(3.dp)).background(listOf(Violet, Indigo, Rose)[j % 3].copy(alpha = 0.7f))) } }
            }
        }
    }
}

/** The way in: the wordmark swells and the welcome dissolves into Home. */
@Composable
private fun Outro(onFinished: () -> Unit) {
    val grow = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        grow.animateTo(1f, tween(900, easing = CinemaEase))
        fade.animateTo(0f, tween(700, easing = CinemaEase))
        onFinished()
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(Stage), contentAlignment = Alignment.Center) {
        Row(
            Modifier.graphicsLayer {
                scaleX = 1f + grow.value * 0.5f
                scaleY = 1f + grow.value * 0.5f
                alpha = 1f - grow.value * 0.2f
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ORCA", color = Ink, fontSize = 72.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
            Text("+", color = Plus, fontSize = 72.sp, fontWeight = FontWeight.Black)
        }
        Text("Enjoy the show", color = InkDim, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center).padding(top = 150.dp).graphicsLayer { alpha = grow.value })
    }
}

/** Which servers can be the main one and which can be added, so nobody is surprised at sign-in. */
@Composable
private fun ServerRoles() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RoleRow("MAIN SERVER", listOf("Jellyfin" to Violet, "Silo" to Color(0xFF3D8BD8)))
        RoleRow("ADD AFTERWARDS", listOf("Emby" to Color(0xFF52B54B), "Plex" to Color(0xFFE5A00D), "Jellyfin" to Violet, "Silo" to Color(0xFF3D8BD8)))
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RoleRow(
    label: String,
    kinds: List<Pair<String, Color>>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = InkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            kinds.forEach { (name, colour) ->
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Glass).border(1.dp, GlassLine, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(colour))
                    Spacer(Modifier.width(5.dp))
                    Text(name, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}


/** A saved server on the "pick a server" screen. [reachable]: null while it's being checked. */
data class SavedServer(
    val key: String,
    val name: String,
    val address: String,
    val reachable: Boolean?,
)

/**
 * Signing in again (the sign-in ran out, or "Change server"): the saved servers in the welcome's
 * look, and a way to add another. [onPick] opens a server's sign-in; [onAdd] starts adding one.
 */
@Composable
fun WelcomeServerChoice(
    servers: List<SavedServer>,
    onPick: (SavedServer) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(servers.isNotEmpty()) { runCatching { first.requestFocus() } }
    Box(modifier.fillMaxSize()) {
        WelcomeBackdrop(emptyList())
        Row(Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 56.dp), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
            StepHeader(
                "SIGN IN",
                "Pick your server",
                "Pick the server to sign in to. Your Orca+ setup, rows and settings stay as they are.",
                Modifier.weight(0.42f),
            )
            GlassPanel(Modifier.weight(0.58f)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    servers.forEachIndexed { i, sv ->
                        ChoiceCard(
                            title = sv.name.ifBlank { "Jellyfin server" },
                            subtitle = sv.address,
                            subtitleOneLine = true,
                            glyph = sv.name.firstOrNull()?.uppercase() ?: "J",
                            accent = Violet,
                            trailing =
                                when (sv.reachable) {
                                    null -> "Checking…"
                                    true -> "›"
                                    false -> "Can't reach it · try again"
                                },
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            onClick = { onPick(sv) },
                        )
                    }
                    ChoiceCard(
                        title = "Add a server",
                        subtitle = "Another Jellyfin or Silo server, by address",
                        glyph = "+",
                        accent = Indigo,
                        modifier = if (servers.isEmpty()) Modifier.focusRequester(first) else Modifier,
                        onClick = onAdd,
                    )
                }
            }
        }
    }
}
