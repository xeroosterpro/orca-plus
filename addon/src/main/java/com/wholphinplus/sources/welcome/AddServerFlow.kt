package com.wholphinplus.sources.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.wholphinplus.sources.cinema.Ink
import com.wholphinplus.sources.cinema.InkDim
import com.wholphinplus.sources.cinema.Stage
import com.wholphinplus.sources.core.ServerConnection
import com.wholphinplus.sources.core.ServerKind
import com.wholphinplus.sources.sync.SetupCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import kotlin.io.encoding.Base64

private enum class AddStep { CHOOSE, PHONE, EMBY_CHOOSE, EMBY, EMBY_PICK, EMBY_ADDRESS, PLEX, JELLYFIN_CHOOSE, SILO_CHOOSE, JELLYFIN, OTHER_ADDRESS }

/**
 * Settings → Servers & Copies → Extra servers → Add a server, full screen (owner, 2026-10-09:
 * "where is phone QR code… or Emby Connect or Jellyfin connect?"; it was one address box). The
 * welcome tour's own sign-ins (Emby Connect, Plex code, Quick Connect, address) plus the phone:
 * a QR code whose page asks the address, username and password, encrypted for this TV.
 * [save] keeps a signed-in server; Back walks up a step, and out from the first.
 */
@Composable
fun AddServerDialog(
    save: (ServerConnection) -> Unit,
    onClose: () -> Unit,
) {
    val hook = LocalContext.current.welcomeHook()
    val connections by hook.store.connections.collectAsState()
    var step by remember { mutableStateOf(AddStep.CHOOSE) }
    var silo by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun added(c: ServerConnection) {
        save(c)
        message = "Added ${c.label} ✓"
        step = AddStep.CHOOSE
    }

    fun back() {
        step =
            when (step) {
                AddStep.CHOOSE -> return onClose()
                AddStep.EMBY, AddStep.EMBY_ADDRESS -> AddStep.EMBY_CHOOSE
                AddStep.JELLYFIN, AddStep.OTHER_ADDRESS -> if (silo) AddStep.SILO_CHOOSE else AddStep.JELLYFIN_CHOOSE
                else -> AddStep.CHOOSE
            }
    }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
        androidx.activity.compose.BackHandler { back() }
        Box(Modifier.fillMaxSize().background(Stage)) {
            when (step) {
                AddStep.CHOOSE -> AddChoices(connections, message, onPick = { message = null; step = it }, onDone = onClose)
                AddStep.PHONE -> PhoneServerStep(onConnected = ::added, onCancel = { step = AddStep.CHOOSE })
                AddStep.EMBY_CHOOSE ->
                    ChooseStep(
                        "Add your Emby server",
                        "Use your Emby Connect account to pick from your servers, or enter one server's details yourself.",
                        codeTitle = "Emby Connect",
                        codeSubtitle = "Enter a code at emby.media/pin on your phone, then pick your servers",
                        glyph = "E",
                        accent = EmbyGreen,
                        onCode = { step = AddStep.EMBY },
                        onAddress = { step = AddStep.EMBY_ADDRESS },
                        onCancel = { step = AddStep.CHOOSE },
                    )
                AddStep.JELLYFIN_CHOOSE, AddStep.SILO_CHOOSE -> {
                    silo = step == AddStep.SILO_CHOOSE
                    ChooseStep(
                        if (silo) "Add a Silo server" else "Add a Jellyfin server",
                        if (silo) "Silo works like Jellyfin. Sign in with its address and your account, or approve a Quick Connect code if the server has it on." else "Approve a Quick Connect code from your phone, or sign in with the server's address and your account.",
                        codeTitle = "Quick Connect",
                        codeSubtitle = "Type the address, then approve a code on your phone",
                        glyph = if (silo) "S" else "J",
                        accent = if (silo) SiloBlue else Violet,
                        onCode = { step = AddStep.JELLYFIN },
                        onAddress = { step = AddStep.OTHER_ADDRESS },
                        onCancel = { step = AddStep.CHOOSE },
                        addressFirst = silo,
                    )
                }
                AddStep.EMBY -> EmbyStep(onAccount = { step = AddStep.EMBY_PICK }, onCancel = { step = AddStep.EMBY_CHOOSE }, onError = { message = it })
                AddStep.EMBY_PICK ->
                    EmbyPickStep(onAdded = { list ->
                        list.forEach(save)
                        message = "Added ${list.joinToString { it.label }} ✓"
                        step = AddStep.CHOOSE
                    }, onCancel = { step = AddStep.CHOOSE })
                AddStep.EMBY_ADDRESS -> AddressStep(title = "Your Emby server", example = "10.0.0.30:8096", defaultKind = ServerKind.EMBY, onConnected = ::added, onCancel = { step = AddStep.EMBY_CHOOSE })
                AddStep.PLEX ->
                    CodeStep(
                        title = "Sign in to Plex",
                        start = { hook.client.startPlexPin("") },
                        poll = { hook.client.pollPlexPin(it, "") },
                        where = "On your phone or computer go to plex.tv/link and enter this code.",
                        onConnected = ::added,
                        onCancel = { step = AddStep.CHOOSE },
                    )
                AddStep.JELLYFIN -> JellyfinStep(silo = silo, onConnected = ::added, onCancel = { step = if (silo) AddStep.SILO_CHOOSE else AddStep.JELLYFIN_CHOOSE })
                AddStep.OTHER_ADDRESS ->
                    AddressStep(
                        title = if (silo) "Your Silo server" else "Your Jellyfin server",
                        example = if (silo) "silo.example.com" else "10.0.0.20:8096",
                        defaultKind = ServerKind.JELLYFIN,
                        onConnected = ::added,
                        onCancel = { step = if (silo) AddStep.SILO_CHOOSE else AddStep.JELLYFIN_CHOOSE },
                    )
            }
        }
    }
}

private val EmbyGreen = Color(0xFF52B54B)
private val SiloBlue = Color(0xFF3D8BD8)
private val PlexGold = Color(0xFFE5A00D)

@Composable
private fun AddChoices(
    connections: List<ServerConnection>,
    message: String?,
    onPick: (AddStep) -> Unit,
    onDone: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    fun added(kind: ServerKind) = connections.count { it.serverKind == kind }.takeIf { it > 0 }?.let { "$it added ✓" } ?: "›"
    val phone = com.wholphinplus.sources.sync.ProfileSync.AVAILABLE
    Row(Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 48.dp), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
        StepHeader(
            "EXTRA SERVERS",
            "Add a server",
            "Emby, Plex, or another Jellyfin or Silo server. Orca+ then finds every title on it: press Play and pick the best copy." +
                if (phone) " Easiest: fill it in on your phone." else "",
            Modifier.weight(0.42f),
        )
        GlassPanel(Modifier.weight(0.58f)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (phone) {
                    ChoiceCard("From your phone", "Scan a code and type the details there", "⌁", Violet, compact = true, trailing = "›", modifier = Modifier.focusRequester(first)) { onPick(AddStep.PHONE) }
                }
                ChoiceCard("Emby", "Emby Connect, or address and sign-in", "E", EmbyGreen, compact = true, trailing = added(ServerKind.EMBY), modifier = if (phone) Modifier else Modifier.focusRequester(first)) { onPick(AddStep.EMBY_CHOOSE) }
                ChoiceCard("Plex", "A code at plex.tv/link, nothing to type", "P", PlexGold, compact = true, trailing = added(ServerKind.PLEX)) { onPick(AddStep.PLEX) }
                ChoiceCard("Jellyfin", "Quick Connect, or address and sign-in", "J", Violet, compact = true, trailing = added(ServerKind.JELLYFIN)) { onPick(AddStep.JELLYFIN_CHOOSE) }
                ChoiceCard("Silo", "Address and sign-in, or a Quick Connect code", "S", SiloBlue, compact = true, trailing = "›") { onPick(AddStep.SILO_CHOOSE) }
                message?.let { Text(it, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                PillButton("Done", primary = false, onClick = onDone)
            }
        }
    }
}

/**
 * A QR code for the phone; its page (cloud setup.js, mode "server") encrypts the address, username
 * and password for this TV. A failed sign-in goes back to the phone, which can send it again
 * without scanning anew.
 */
@Composable
internal fun PhoneServerStep(
    onConnected: (ServerConnection) -> Unit,
    onCancel: () -> Unit,
) {
    val hook = LocalContext.current.welcomeHook()
    var qr by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Getting a code…") }
    var problem by remember { mutableStateOf<String?>(null) }
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { cancel.requestFocus() }
        while (true) {
            val keys = SetupCrypto.newKeys()
            val s =
                try {
                    hook.profileSync.setupStart(Base64.Default.encode(SetupCrypto.rawPublic(keys.public as ECPublicKey)), mode = "server")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    status = "Can't reach the Orca+ cloud right now. Trying again…"
                    delay(10_000)
                    continue
                }
            qr = s.qr
            code = s.code
            status = "Scan with your phone's camera"
            val until = System.currentTimeMillis() + 19 * 60_000L
            while (System.currentTimeMillis() < until) {
                delay(2_000)
                val box =
                    try {
                        hook.profileSync.setupCollect(s)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Ran out or refused: a fresh code
                        if (e is com.wholphinplus.sources.sync.CloudException && e.code in 400..499) break
                        null
                    } ?: continue
                val details =
                    runCatching {
                        val bytes = SetupCrypto.open(keys.private as ECPrivateKey, s.code, Base64.Default.decode(box.epk), Base64.Default.decode(box.iv), Base64.Default.decode(box.ct))
                        val o = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
                        fun f(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
                        Triple(f("server").trim(), f("username").trim(), f("password"))
                    }.getOrNull()
                if (details == null || details.first.isBlank() || details.second.isBlank()) {
                    runCatching { hook.profileSync.setupResult(s, false, "Your TV couldn't read that. Send it again.", false) }
                    continue
                }
                val (server, username, password) = details
                status = "Signing in to $server…"
                problem = null
                runCatching { hook.profileSync.setupResult(s, true, "Signing in to $server…", false) }
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val url = com.wholphinplus.sources.core.normalizeServerUrl(server)
                            val info = hook.client.fetchPublicInfo(url)
                            when (info.serverKind) {
                                ServerKind.PLEX -> error("That's a Plex server: choose Plex on the TV instead")
                                ServerKind.UNKNOWN -> error("No Emby, Jellyfin or Silo server answered at $server")
                                else -> hook.client.signIn(url, info, username, password, "")
                            }
                        }
                    }
                val connection = result.getOrNull()
                if (connection != null) {
                    runCatching { hook.profileSync.setupResult(s, true, "Added ${connection.label} to your TV ✓", true) }
                    onConnected(connection)
                    return@LaunchedEffect
                }
                val why = result.exceptionOrNull()?.message?.let { if ("401" in it) "Wrong username or password" else it } ?: "Couldn't sign in"
                problem = why
                status = "Fix it on your phone and send it again"
                runCatching { hook.profileSync.setupResult(s, false, why, false) }
            }
        }
    }
    Row(Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 56.dp), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
        StepHeader("ADD A SERVER", "From your phone", "Scan the code, then type the server's address, your username and password on your phone. It's encrypted for this TV only. Emby, Jellyfin and Silo; for Plex, use its code.", Modifier.weight(0.42f))
        GlassPanel(Modifier.weight(0.58f)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(196.dp).clip(RoundedCornerShape(14.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                        val q = qr
                        if (q != null) AsyncImage(model = q, contentDescription = "QR code for your phone", modifier = Modifier.size(180.dp)) else Text("…", color = Color.Black, fontSize = 22.sp)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(status, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        code?.let { Text("Code $it", color = InkDim, fontSize = 14.sp) }
                        problem?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 15.sp) }
                    }
                }
                Box(Modifier.height(4.dp))
                PillButton("Back", primary = false, modifier = Modifier.focusRequester(cancel), onClick = onCancel)
            }
        }
    }
}
