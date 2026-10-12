package com.wholphinplus.sources.ui

import com.wholphinplus.sources.core.cancellable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.wholphinplus.sources.SourceHook
import com.wholphinplus.sources.core.label
import kotlinx.coroutines.launch

/** The now-playing card (owner, 2026-10-08): on or off, and each of its parts. */
@Composable
private fun NowPlayingSettings(
    hook: SourceHook,
    firstModifier: Modifier,
) {
    val p by hook.store.playInfoPrefs.collectAsState()
    val confirmLeave by hook.store.confirmLeave.collectAsState()
    SettingsHeader("Leaving the player")
    PlusListItem(
        onClick = { hook.store.setConfirmLeave(!confirmLeave) },
        headlineContent = { Text("Ask before closing", style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(if (confirmLeave) "On: Back asks \"Stop watching?\" first, so a stray press never ends the movie" else "Off: Back closes the player at once") },
        trailingContent = { androidx.tv.material3.Switch(checked = confirmLeave, onCheckedChange = null, colors = plusSwitchColors()) },
        modifier = firstModifier,
    )
    SettingsHeader("Now playing card")
    PlusListItem(
        onClick = { hook.store.setPlayInfoPrefs(p.copy(on = !p.on)) },
        headlineContent = { Text("Show the card", style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                if (p.on) {
                    "On: when a title starts, a card shows how it's playing"
                } else {
                    "Off: turn on to see direct play or transcode, quality, sound and server as a title starts"
                },
            )
        },
        trailingContent = { androidx.tv.material3.Switch(checked = p.on, onCheckedChange = null, colors = plusSwitchColors()) },
    )
    if (!p.on) return
    listOf(
        Triple("Direct play or transcode", p.method) { hook.store.setPlayInfoPrefs(p.copy(method = !p.method)) },
        Triple("Picture: resolution, HDR and codec", p.quality) { hook.store.setPlayInfoPrefs(p.copy(quality = !p.quality)) },
        Triple("Sound: format and channels", p.audio) { hook.store.setPlayInfoPrefs(p.copy(audio = !p.audio)) },
        Triple("Which server it plays from", p.server) { hook.store.setPlayInfoPrefs(p.copy(server = !p.server)) },
    ).forEach { (label, on, toggle) ->
        PlusListItem(
            onClick = toggle,
            headlineContent = { Text(label, style = MaterialTheme.typography.titleMedium) },
            trailingContent = { androidx.tv.material3.Switch(checked = on, onCheckedChange = null, colors = plusSwitchColors()) },
            modifier = Modifier.padding(start = 24.dp),
        )
    }
}

/**
 * How a copy is chosen (Settings → Servers & Copies), as two plain questions (owner, 2026-10-09:
 * "the settings for source picker is so confusing": three switches that worked together).
 */
@Composable
private fun PickerTuning(hook: SourceHook) {
    val prefs by hook.store.pickerPrefs.collectAsState()
    val connections by hook.store.connections.collectAsState()
    val servers = connections.filter { it.enabled }
    val main = com.wholphinplus.sources.ServerBrands.mainName()
    val MAIN = com.wholphinplus.sources.core.PickerPrefs.MAIN
    SettingsHeader("Choosing a copy")
    Text(
        "Copies this TV plays well come first, then 4K before 1080p and HDR before SDR, then the bigger file.",
        color = com.wholphinplus.sources.cinema.InkDim,
        fontSize = 14.sp,
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 820.dp),
    )
    OptionRow(
        "When you press Play",
        listOf(
            Option("list", "Show me the copies", "Pick from the list every time"),
            Option("auto", "Play the best copy", "No list; if it won't play, you're offered the next one"),
        ),
        selected = if (prefs.autoPlay) "auto" else "list",
    ) { hook.store.setPickerPrefs(prefs.copy(autoPlay = it == "auto")) }
    val preferOptions =
        listOf(
            // Your server on a tie is always the rule now (owner, 2026-10-09)
            Option("tie", "Best quality", "The best copy wins; when copies are just as good, $main's comes first"),
            Option(MAIN, "$main first", "$main's best copy is on top whenever it has one"),
        ) + servers.map { Option(it.connectionId, "${it.label} first", "${it.label}'s best copy is on top whenever it has one") }
    val preferNow =
        when {
            prefs.first.isNotBlank() && preferOptions.any { it.key == prefs.first } -> prefs.first
            else -> "tie"
        }
    OptionRow("Prefer", preferOptions, selected = preferNow) { key ->
        hook.store.setPickerPrefs(
            when (key) {
                "tie" -> prefs.copy(first = "", preferMain = true)
                else -> prefs.copy(first = key, preferMain = false)
            },
        )
    }
}

private data class Option(
    val key: String,
    val label: String,
    val detail: String,
)

/**
 * A setting with a few named answers: the question and its answer; OK opens the answers under it,
 * each with what it means, the current one ticked (cycling through them on OK hid what was coming).
 */
@Composable
private fun OptionRow(
    title: String,
    options: List<Option>,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.key == selected } ?: options.first()
    val firstOption = remember { FocusRequester() }
    PlusListItem(
        onClick = { open = !open },
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(if (open) "Choose one" else current.label) },
        trailingContent = { Text(if (open) "⌃" else "›", style = MaterialTheme.typography.titleLarge, color = com.wholphinplus.sources.cinema.InkDim) },
        modifier = modifier,
    )
    if (open) {
        LaunchedEffect(Unit) { runCatching { firstOption.requestFocus() } }
        Column(Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEachIndexed { i, o ->
                val on = o.key == current.key
                PlusListItem(
                    onClick = {
                        onSelect(o.key)
                        open = false
                    },
                    headlineContent = { Text(o.label, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(o.detail) },
                    trailingContent = { if (on) Text("✓", style = MaterialTheme.typography.titleLarge, color = SettingsAccent) },
                    modifier = if (on || (i == 0 && options.none { it.key == current.key })) Modifier.focusRequester(firstOption) else Modifier,
                )
            }
        }
    }
}

/**
 * The Orca+ items at the top of a Settings topic: Home & Look (home style, rows, poster tags,
 * lists), Servers & Search (extra sources, search), Account & Cloud (cloud sync), About
 * (credits). Each opens its Orca+ page full screen; Back comes back here.
 */
@Composable
fun OrcaTopicItems(
    topic: String,
    firstModifier: Modifier = Modifier,
) {
    val hook = LocalContext.current.sourceHook()
    var open by remember { mutableStateOf<Screen?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (topic) {
            "APPEARANCE" -> {
                SettingsHeader("Home")
                MenuItem("Rows", "Pick, order and rename the rows on Home, Shows, Movies and New & Popular", firstModifier) { open = Screen.HomeRows() }
                val rollUp by hook.store.cinemaRollUp.collectAsState()
                PlusListItem(
                    onClick = { hook.store.setCinemaRollUp(!rollUp) },
                    headlineContent = { Text("Roll up the billboard while browsing", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(if (rollUp) "On: the title details shrink when you scroll into the rows" else "Off: the title details stay full size") },
                    trailingContent = { androidx.tv.material3.Switch(checked = rollUp, onCheckedChange = null, colors = com.wholphinplus.sources.ui.plusSwitchColors()) },
                    
                )
                val kidsTab by hook.store.kidsTab.collectAsState()
                PlusListItem(
                    onClick = {
                        hook.store.setKidsTab(!kidsTab)
                        // Its rows are matched to the library only once it's wanted
                        if (!kidsTab) hook.collections.refreshStale(hook)
                    },
                    headlineContent = { Text("Kids tab", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(if (kidsTab) "On: a Kids tab at the top, with kids' movies and shows only" else "Off: turn on for a tab of kids' movies, shows and little ones' favourites") },
                    trailingContent = { androidx.tv.material3.Switch(checked = kidsTab, onCheckedChange = null, colors = com.wholphinplus.sources.ui.plusSwitchColors()) },
                    
                )
                SettingsHeader("Posters")
                val overlays by hook.store.overlays.collectAsState()
                val ratings by hook.store.ratingPrefs.collectAsState()
                val on =
                    listOf(
                        overlays.titleLogos to "Title logos",
                        overlays.services to "Streaming",
                        ratings.onCards to "Scores",
                        (overlays.resolution || overlays.hdr || overlays.audio) to "Quality",
                        overlays.top10 to "Top 10",
                        overlays.watched to "Watched",
                        overlays.newLabels to "New",
                        overlays.captions to "Titles",
                    ).filter { it.first }.map { it.second }
                MenuItem("Poster tags", if (on.isEmpty()) "Pictures only" else on.joinToString(" · ")) { open = Screen.Overlays }
                val dt by hook.store.descriptionTags.collectAsState()
                val dtOn =
                    listOf(
                        (dt.resolution || dt.hdr || dt.audio) to "Quality",
                        dt.genres to "Genres",
                        dt.tagline to "Tagline",
                        (dt.makers || dt.studio) to "Who made it",
                        (dt.budget || dt.boxOffice) to "Money",
                    ).filter { it.first }.map { it.second }
                MenuItem("Description tags", "What a title page shows by its story: " + (if (dtOn.isEmpty()) "the basics" else dtOn.joinToString(" · "))) { open = Screen.DescriptionTags }
                // OK steps Auto → Sharp → Fast
                val posterSize by hook.store.posterSize.collectAsState()
                val sizes = com.wholphinplus.sources.cinema.PosterSize
                PlusListItem(
                    onClick = {
                        hook.store.setPosterSize(
                            when (posterSize) {
                                sizes.AUTO -> sizes.SHARP
                                sizes.SHARP -> sizes.FAST
                                else -> sizes.AUTO
                            },
                        )
                    },
                    headlineContent = { Text("Poster size", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(
                            when (posterSize) {
                                sizes.SHARP -> "Sharp: full-size pictures, best on a fast connection"
                                sizes.FAST -> "Fast: smaller pictures that load quicker, a little softer"
                                else -> if (com.wholphinplus.sources.DeviceClass.light) "Auto: smaller pictures, they're quicker on this TV" else if (sizes.slow) "Auto: smaller pictures for now, they were loading slowly" else "Auto: sharp, and smaller if pictures load slowly"
                            },
                        )
                    },
                    trailingContent = {
                        Text(
                            when (posterSize) {
                                sizes.SHARP -> "Sharp"
                                sizes.FAST -> "Fast"
                                else -> "Auto"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    
                )
            
                // Lists you added (the Orca+ cloud's charts are arranged in Rows, not here)
                val all by hook.collections.lists.collectAsState()
                val lists = all.filterNot { hook.collections.isChart(it) || hook.collections.isTopStreaming(it) }
                SettingsHeader("Lists")
                MenuItem(
                    "Your lists",
                    if (lists.isEmpty()) "Add MDBList, Trakt or Top Streaming lists as rows" else "${lists.size} added  ·  MDBList, Trakt, Top Streaming",
                ) { open = Screen.Collections }
            }
            "SOURCES" -> {
                val connections by hook.store.connections.collectAsState()
                SettingsHeader("Your servers")
                MenuItem(
                    "Extra servers",
                    if (connections.isEmpty()) "Play from your other Plex, Emby, Jellyfin and Silo servers" else "${connections.count { it.enabled }} of ${connections.size} servers on",
                    firstModifier,
                ) { open = Screen.List }
                if (connections.any { it.enabled }) {
                    val browse by hook.store.browseExtras.collectAsState()
                    PlusListItem(
                        onClick = {
                            hook.store.setBrowseExtras(!browse)
                            com.wholphinplus.sources.cinema.CinemaCaches.serversChanged()
                        },
                        headlineContent = { Text("Browse them in My Servers", style = MaterialTheme.typography.titleMedium) },
                        supportingContent = {
                            Text(
                                if (browse) {
                                    "On: My Servers shows each extra server's libraries too (they're asked while the tab is open)"
                                } else {
                                    "Off: My Servers shows your main server only; extra servers are asked only when you press Play"
                                },
                            )
                        },
                        trailingContent = { androidx.tv.material3.Switch(checked = browse, onCheckedChange = null, colors = com.wholphinplus.sources.ui.plusSwitchColors()) },
                    )
                }
                // The TMDB key (search) lives in Keys & Services with every other key
                if (connections.any { it.enabled }) PickerTuning(hook)
            }
            "PLAYBACK" -> NowPlayingSettings(hook, firstModifier)
            "ACCOUNT" -> AccountCard(hook, firstModifier, openCloud = { open = Screen.Cloud })
            "KEYS" -> KeysAndServices(hook, firstModifier, open = { open = it })
            "DEVICE" -> ThisTvPage(hook, firstModifier)
            "ABOUT" -> {
                var credits by remember { mutableStateOf(false) }
                SettingsHeader("Orca+")
                MenuItem("Credits", "Orca+ is built on Wholphin by damontecres (GPL)", firstModifier) { credits = !credits }
                if (credits) {
                    Text(
                        "Orca+ is built on Wholphin by damontecres and contributors, under the GNU GPL. " +
                            "Not affiliated with or endorsed by the Wholphin project.\n" + TMDB_NOTICE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(720.dp).padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
    open?.let { start ->
        Dialog(
            onDismissRequest = { open = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(SettingsPageColor)
                    .background(SettingsBackdrop)
                    .padding(horizontal = 64.dp, vertical = 28.dp),
            ) {
                ExtraSourcesScreen(hook, onClose = { open = null }, start = start)
            }
        }
    }
}

/**
 * Settings → Account (owner, 2026-10-09: "a proper Account tab with all the info they need"): who
 * is signed in and where, the Orca+ name and cloud sync at a glance, and the actions as buttons
 * under it. The PIN is never kept on the TV, so it says "PIN set" and offers to change it.
 */
@Composable
private fun AccountCard(
    hook: SourceHook,
    firstModifier: Modifier,
    openCloud: () -> Unit,
) {
    val sync by hook.profileSync.status.collectAsState()
    val who = remember { com.wholphinplus.sources.AccountActions.who() }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmOut by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    val cloud = com.wholphinplus.sources.sync.ProfileSync.AVAILABLE
    val name = sync.name
    SettingsHeader("Your account")
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .widthIn(max = 820.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(16.dp))
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF8A6CF0), Color(0xFF5D3FD3)))),
            contentAlignment = Alignment.Center,
        ) {
            Text((name ?: who?.user ?: "?").take(1).uppercase(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                name ?: if (sync.on) "No Orca+ name yet" else "No Orca+ account on this TV",
                color = SettingsInk,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            who?.let { w ->
                val server = w.server.ifBlank { w.url.substringAfter("://").substringBefore("/") }
                Text("Signed in to $server as ${w.user}", color = com.wholphinplus.sources.cinema.InkDim, fontSize = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    when {
                        !cloud -> "Cloud sync isn't in this build"
                        !sync.on -> "Cloud sync off"
                        sync.busy -> "Syncing…"
                        sync.problem != null -> "Sync needs attention"
                        else -> "Cloud sync on  ·  synced ${ago(sync.lastSync)}"
                    },
                    good = sync.on && sync.problem == null,
                )
                if (sync.on) StatusChip("PIN set", good = false)
            }
            sync.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        }
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        var first = firstModifier
        fun take(): Modifier = first.also { first = Modifier }
        if (cloud && sync.on) {
            Button(
                onClick = {
                    message = "Syncing…"
                    scope.launch {
                        message = runCatching { hook.profileSync.syncNow(hook) }.cancellable().fold({ "Synced just now." }, { com.wholphinplus.sources.sync.ProfileSync.describe(it) })
                    }
                },
                modifier = take(),
            ) { Text("Sync now") }
            Button(onClick = openCloud) { Text(if (name == null) "Pick a name" else "Name & PIN") }
        } else if (cloud) {
            Button(onClick = openCloud, modifier = take()) { Text("Create an account or sign in") }
        }
        if (com.wholphinplus.sources.AccountActions.accounts != null) {
            Button(onClick = { switching = !switching }, modifier = take()) { Text(if (switching) "Close accounts" else "Switch account") }
        } else {
            com.wholphinplus.sources.AccountActions.switchUser?.let { sw -> Button(onClick = sw, modifier = take()) { Text("Switch user") } }
        }
        com.wholphinplus.sources.AccountActions.signOut?.let { out ->
            Button(
                onClick = {
                    if (!confirmOut) {
                        confirmOut = true
                        message = "Press again to sign this TV out. Your server, your Orca+ account and everything saved in the cloud stay as they are."
                    } else {
                        hook.profileSync.turnOff()
                        // Signed out: the welcome comes back (Orca+ name and PIN, the phone's QR)
                        hook.store.setOnboarding(com.wholphinplus.sources.welcome.Onboarding.STARTED)
                        out()
                    }
                },
                modifier = take(),
            ) { Text(if (confirmOut) "Yes, sign out" else "Sign out of this TV") }
        }
    }
    message?.let { Text(it, color = com.wholphinplus.sources.cinema.InkDim, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
    if (switching) AccountSwitcher(onMessage = { message = it })
}

/**
 * Every account this TV has signed in to, each a server and a user with its own main server, Orca+
 * setup and cloud profile. OK switches at once, without signing out (a user with a Wholphin PIN is
 * asked it on Wholphin's own screen); the setup on this TV goes with the account it belongs to
 * ([com.wholphinplus.sources.sync.ProfileSync.accountChanged]).
 */
@Composable
private fun AccountSwitcher(onMessage: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<com.wholphinplus.sources.AccountActions.Account>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { list = runCatching { com.wholphinplus.sources.AccountActions.accounts?.invoke() }.cancellable().getOrNull().orEmpty() }
    SettingsHeader("Accounts on this TV")
    val all = list ?: return
    all.sortedByDescending { it.current }.forEach { a ->
        val server = a.server.ifBlank { a.url.substringAfter("://").substringBefore("/") }
        PlusListItem(
            onClick = {
                if (a.current || busy != null) return@PlusListItem
                busy = a.key
                onMessage("Switching to ${a.user} on $server…")
                scope.launch {
                    runCatching { com.wholphinplus.sources.AccountActions.switchTo?.invoke(a.key, null) }
                        .onFailure {
                            busy = null
                            onMessage(it.message ?: "Couldn't switch to that account")
                        }
                }
            },
            headlineContent = { Text("${a.user} on $server", style = MaterialTheme.typography.titleMedium) },
            supportingContent = {
                Text(
                    when {
                        a.current -> "Signed in now"
                        busy == a.key -> "Switching…"
                        a.pin -> "Asks for its PIN"
                        else -> a.url
                    },
                )
            },
            trailingContent = { if (a.current) Text("✓") else Text("›") },
        )
    }
    com.wholphinplus.sources.AccountActions.addAccount?.let { add ->
        PlusListItem(
            onClick = add,
            headlineContent = { Text("+ Add an account", style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text("Another Jellyfin, Emby or Silo server or user. This one stays here to switch back to.") },
        )
    }
}

/** A small pill with a state in it: green when all is well. */
@Composable
private fun StatusChip(
    text: String,
    good: Boolean,
) {
    Text(
        text,
        color = if (good) Color(0xFF7BD88F) else com.wholphinplus.sources.cinema.InkDim,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (good) Color(0xFF4CC38A).copy(alpha = 0.15f) else Color.White.copy(alpha = 0.07f))
                .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/**
 * Settings → Keys & Services: every outside account in one list, with what it does for you and
 * whether it's set (they were spread over Search, Poster tags → Score sources and Your lists).
 * Keys can come from a phone, as in the first run.
 */
@Composable
private fun KeysAndServices(
    hook: SourceHook,
    firstModifier: Modifier,
    open: (Screen) -> Unit,
) {
    val tmdb by hook.store.tmdbKey.collectAsState()
    val mdb by hook.store.mdblistKey.collectAsState()
    val trakt by hook.collections.traktClientId.collectAsState()
    val topStreaming by hook.collections.topStreamingAccount.collectAsState()
    val cloud = com.wholphinplus.sources.sync.ProfileSync.AVAILABLE
    var phone by remember { mutableStateOf(false) }
    if (cloud) {
        SettingsHeader("Add keys")
        MenuItem("Add keys from your phone", "Scan a code, paste your keys on your phone: no typing with the remote", firstModifier) { phone = true }
    }
    SettingsHeader("Services")
    KeyRow(
        "TMDB",
        "Title art, smart search and More Like This",
        when {
            tmdb.isNotBlank() -> "Your key" to true
            cloud -> "Built in" to true
            else -> "Not set" to false
        },
        if (cloud) Modifier else firstModifier,
    ) { open(Screen.TmdbKey) }
    KeyRow("MDBList", "IMDb, Rotten Tomatoes, Metacritic and Letterboxd scores", if (mdb.isNotBlank()) "Added" to true else "Not set" to false) { open(Screen.MdblistKey) }
    KeyRow("Trakt", "Your Trakt lists as rows on Home", if (trakt.isNotBlank()) "Added" to true else "Not set" to false) { open(Screen.TraktKey) }
    KeyRow("Top Streaming", "Your countries' streaming Top 10s", if (topStreaming.isNotBlank()) "Added" to true else "Not set" to false) { open(Screen.TopStreaming) }
    if (phone) {
        PadDialog(onClose = { phone = false }) {
            Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 64.dp), verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
                Text("Add keys from your phone", color = SettingsInk, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Text("Scan the code, or open the address and type the code. Keys you send appear here as they arrive. Back when you're done.", color = com.wholphinplus.sources.cinema.InkDim, fontSize = 16.sp)
                com.wholphinplus.sources.welcome.KeysFromPhone(hook, Modifier.widthIn(max = 980.dp))
            }
        }
    }
}

/** One service: what it does, and whether it's set. */
@Composable
private fun KeyRow(
    name: String,
    does: String,
    status: Pair<String, Boolean>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    PlusListItem(
        onClick = onClick,
        headlineContent = { Text(name, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(does) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (status.second) Color(0xFF4CC38A) else Color(0xFF6F6A7E)))
                Text(status.first, style = MaterialTheme.typography.titleSmall)
            }
        },
        modifier = modifier,
    )
}

/**
 * Settings → This TV: what [com.wholphinplus.sources.DeviceClass] found on this box, in plain
 * words, and the performance class as four buttons (it cycled through one row). Language, the
 * image cache and the screensaver follow (Wholphin's settings, below these).
 */
@Composable
private fun ThisTvPage(
    hook: SourceHook,
    firstModifier: Modifier,
) {
    val device = com.wholphinplus.sources.DeviceClass
    val f = device.facts
    val mode by hook.store.deviceMode.collectAsState()
    SettingsHeader("This box")
    val screen =
        listOfNotNull(
            when {
                f.screenHeight >= 2160 -> "4K"
                f.screenHeight > 0 -> "${f.screenHeight}p"
                else -> null
            },
        ) + listOf("Dolby Vision", "HDR10+", "HDR10", "HLG").filter { it in f.screenHdr }
    val specs =
        listOf(
            "Device" to device.nameOf(f),
            "Chip  ·  memory" to listOfNotNull(f.soc.takeIf { it.isNotBlank() }, f.ramMb.takeIf { it > 0 }?.let { "%.1f GB".format(it / 1024f) }).joinToString("  ·  ").ifBlank { "Unknown" },
            "Screen" to screen.joinToString("  ·  ").ifBlank { "Reading…" },
            "Plays in hardware" to
                when {
                    f.decoders.isNotEmpty() -> f.decoders.keys.sorted().joinToString("  ·  ")
                    // The scan reads the screen too: done and nothing found
                    f.screenHeight > 0 -> "Software only"
                    else -> "Reading…"
                },
            "Card pictures" to if (com.wholphinplus.sources.cinema.PosterSize.small) "Smaller, they load faster here" else "Sharp",
            "Runs as" to device.tier.label + if (mode != device.AUTO) "  ·  your pick" else if (device.auto != device.detected) "  ·  measured" else "  ·  auto",
        )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        specs.chunked(3).forEach { line ->
            // Equal heights: a value that wraps grows its whole line, not one box
            Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                line.forEach { (label, value) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(label.uppercase(), color = com.wholphinplus.sources.cinema.InkDim, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                        Text(value, color = SettingsInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    }
                }
            }
        }
    }
    SettingsHeader("Performance")
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val choices = listOf(device.AUTO to "Auto (${device.auto.label})") + com.wholphinplus.sources.DeviceClass.Tier.entries.map { it.name to it.label }
        choices.forEachIndexed { i, (key, label) ->
            val on = mode == key
            Button(
                onClick = {
                    // Auto picked again: measure this TV afresh (a step down it found is forgotten)
                    if (key == device.AUTO && mode == device.AUTO) device.forgetMeasured(context)
                    hook.store.setDeviceMode(key)
                },
                colors =
                    androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = if (on) SettingsAccent else Color.White.copy(alpha = 0.08f),
                        contentColor = Color.White,
                    ),
                modifier = if (i == 0) firstModifier else Modifier,
            ) { Text(label) }
        }
    }
    Text(
        when (device.tier) {
            com.wholphinplus.sources.DeviceClass.Tier.FULL -> "Full: every effect, and the most loaded ahead for smooth browsing."
            com.wholphinplus.sources.DeviceClass.Tier.BALANCED -> "Balanced: a little less loaded ahead, for mid-range boxes."
            com.wholphinplus.sources.DeviceClass.Tier.LIGHT -> "Light: simpler, quicker motion, cards fill in when you stop, smaller pictures, less held in memory."
        } + (if (mode == device.AUTO && device.auto == device.detected) " Auto picked it: ${device.reasonOf(f).replaceFirstChar { it.lowercase() }}." else "") +
            (if (mode == device.AUTO && device.auto != device.detected) " Chosen after timing this TV (${device.measuredWhy ?: "frames ran late"}); press Auto again to measure afresh." else "") +
            (if (mode == device.AUTO) "" else " Fully applies after a restart.") +
            " Copies this TV can't decode are marked when you choose a copy.",
        color = com.wholphinplus.sources.cinema.InkDim,
        fontSize = 14.sp,
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 820.dp),
    )
}
