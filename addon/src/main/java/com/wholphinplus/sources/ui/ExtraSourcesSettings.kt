package com.wholphinplus.sources.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.surfaceColorAtElevation
import com.wholphinplus.sources.SourceHook
import com.wholphinplus.sources.core.CodeLogin
import com.wholphinplus.sources.core.ServerConnection
import com.wholphinplus.sources.core.ServerInfo
import com.wholphinplus.sources.core.ServerKind
import com.wholphinplus.sources.core.label
import com.wholphinplus.sources.core.normalizeServerUrl
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SourcesEntryPoint {
    fun sourceHook(): SourceHook

    fun searchService(): com.wholphinplus.sources.SearchService

    fun cinemaArt(): com.wholphinplus.sources.cinema.CinemaArt

    fun ratings(): com.wholphinplus.sources.cinema.RatingsRepository
}

internal fun Context.sourceHook(): SourceHook = EntryPointAccessors.fromApplication(applicationContext, SourcesEntryPoint::class.java).sourceHook()

/** The one row Wholphin's settings screen shows for the addon. */
@Composable
fun ExtraSourcesEntry(modifier: Modifier = Modifier) {
    val hook = LocalContext.current.sourceHook()
    val connections by hook.store.connections.collectAsState()
    val lists by hook.collections.lists.collectAsState()
    val tmdb by hook.store.tmdbKey.collectAsState()
    var open by remember { mutableStateOf(false) }
    ListItem(
        selected = false,
        onClick = { open = true },
        headlineContent = { Text("Orca+") },
        supportingContent = {
            Text(
                listOf(
                    "${connections.count { it.enabled }} extra servers",
                    "smart search",
                    "${lists.count { it.showOnHome }} lists on home",
                ).joinToString("  ·  "),
            )
        },
        modifier = modifier,
    )
    if (open) {
        Dialog(
            onDismissRequest = { open = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(SettingsPageColor)
                    .background(SettingsBackdrop)
                    .padding(horizontal = 64.dp, vertical = 28.dp),
            ) {
                ExtraSourcesScreen(hook, onClose = { open = false })
            }
        }
    }
}

/** Settings topics that start with Orca+ items (names of Wholphin's OrcaSection). */
object OrcaTopics {
    fun has(topic: String): Boolean = topic in setOf("APPEARANCE", "SOURCES", "PLAYBACK", "PROFILE", "ABOUT")
}

/** The now-playing card (owner, 2026-10-08): on or off, and each of its parts. */
@Composable
private fun NowPlayingSettings(
    hook: SourceHook,
    firstModifier: Modifier,
) {
    val p by hook.store.playInfoPrefs.collectAsState()
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
        modifier = firstModifier,
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
 * How the copy picker orders and chooses (owner, 2026-10-08): your server first on a tie, one
 * server put first, and the top copy playing without the list.
 */
@Composable
private fun PickerTuning(hook: SourceHook) {
    val prefs by hook.store.pickerPrefs.collectAsState()
    val connections by hook.store.connections.collectAsState()
    val servers = connections.filter { it.enabled }
    SettingsHeader("Choosing a copy")
    PlusListItem(
        onClick = { hook.store.setPickerPrefs(prefs.copy(preferMain = !prefs.preferMain)) },
        headlineContent = { Text("Your server first on a tie", style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                if (prefs.preferMain) {
                    "On: when copies are the same quality and size, your server's comes first"
                } else {
                    "Off: copies of the same quality and size follow the order of your servers"
                },
            )
        },
        trailingContent = { androidx.tv.material3.Switch(checked = prefs.preferMain, onCheckedChange = null, colors = plusSwitchColors()) },
        
    )
    // OK steps Best match → Your server → each extra server
    val choices = listOf("" to "Best match", com.wholphinplus.sources.core.PickerPrefs.MAIN to "Your server") + servers.map { it.connectionId to it.label }
    val current = choices.indexOfFirst { it.first == prefs.first }.coerceAtLeast(0)
    PlusListItem(
        onClick = { hook.store.setPickerPrefs(prefs.copy(first = choices[(current + 1) % choices.size].first)) },
        headlineContent = { Text("Put first", style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                if (current == 0) {
                    "The best copy is at the top, from any server"
                } else {
                    "${choices[current].second}'s best copy is at the top when it has one"
                },
            )
        },
        trailingContent = { Text(choices[current].second, style = MaterialTheme.typography.titleMedium) },
        
    )
    PlusListItem(
        onClick = { hook.store.setPickerPrefs(prefs.copy(autoPlay = !prefs.autoPlay)) },
        headlineContent = { Text("Play the top copy without asking", style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                if (prefs.autoPlay) {
                    "On: the list is skipped; if that copy won't play, you're offered another"
                } else {
                    "Off: the list of copies shows each time you press Play"
                },
            )
        },
        trailingContent = { androidx.tv.material3.Switch(checked = prefs.autoPlay, onCheckedChange = null, colors = plusSwitchColors()) },
        
    )
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
    val cinema by hook.store.cinemaMode.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (topic) {
            "APPEARANCE" -> {
                if (cinema) {
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
                }
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
                val tmdb by hook.store.tmdbKey.collectAsState()
                SettingsHeader("Your servers")
                MenuItem(
                    "Extra servers",
                    if (connections.isEmpty()) "Play from your Plex, Emby and other Jellyfin servers" else "${connections.count { it.enabled }} of ${connections.size} servers on",
                    firstModifier,
                ) { open = Screen.List }
                MenuItem("Search", if (tmdb.isBlank()) "Smart search is on  ·  your own TMDB key is optional" else "Smart search is on, with your TMDB key") { open = Screen.TmdbKey }
                if (connections.any { it.enabled }) PickerTuning(hook)
            }
            "PLAYBACK" -> NowPlayingSettings(hook, firstModifier)
            "PROFILE" -> {
                val cloud by hook.profileSync.status.collectAsState()
                SettingsHeader("Cloud")
                MenuItem(
                    "Cloud sync",
                    when {
                        !com.wholphinplus.sources.sync.ProfileSync.AVAILABLE -> "Not available in this build"
                        !cloud.on -> "Off: keep your whole setup in the cloud and bring it to any TV"
                        cloud.problem != null -> "On  ·  ${cloud.problem}"
                        else -> "On  ·  synced ${ago(cloud.lastSync)}"
                    },
                    firstModifier,
                ) { if (com.wholphinplus.sources.sync.ProfileSync.AVAILABLE) open = Screen.Cloud }
            }
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
                ThisTv(hook)
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
 * What [com.wholphinplus.sources.DeviceClass] found on this box, and the class it runs as. OK steps
 * Auto → Full → Balanced → Light; the change applies from the next start (lists keep their
 * cache windows while they're on screen).
 */
@Composable
private fun ThisTv(hook: SourceHook) {
    val device = com.wholphinplus.sources.DeviceClass
    val mode by hook.store.deviceMode.collectAsState()
    val steps = listOf(device.AUTO) + com.wholphinplus.sources.DeviceClass.Tier.entries.map { it.name }
    val shown = if (mode == device.AUTO) "Auto (${device.detected.label})" else device.tier.label
    SettingsHeader("This TV")
    PlusListItem(
        onClick = { hook.store.setDeviceMode(steps[(steps.indexOf(mode).coerceAtLeast(0) + 1) % steps.size]) },
        headlineContent = { Text("Performance", style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                when (device.tier) {
                    com.wholphinplus.sources.DeviceClass.Tier.FULL -> "Full: every effect, the most built ahead for smooth browsing"
                    com.wholphinplus.sources.DeviceClass.Tier.BALANCED -> "Balanced: a little less built ahead, for mid-range boxes"
                    com.wholphinplus.sources.DeviceClass.Tier.LIGHT -> "Light: smaller pictures, no backdrop zoom, less held in memory"
                } + if (mode == device.AUTO) "" else "  ·  fully applies after a restart",
            )
        },
        trailingContent = { Text(shown, style = MaterialTheme.typography.titleMedium) },
    )
    Text(
        device.summary().ifBlank { "Still reading this TV's hardware" } +
            "\nCopies this TV can't decode are marked and listed after the ones it plays.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(720.dp).padding(horizontal = 16.dp),
    )
}

/** Cinema mode's "Customize home": the home rows editor as a page of its own. */
@Composable
fun CinemaHomeRowsPage(modifier: Modifier = Modifier) {
    val hook = LocalContext.current.sourceHook()
    Box(
        modifier
            .fillMaxSize()
            .background(SettingsPageColor)
            .background(SettingsBackdrop)
            .padding(horizontal = 64.dp, vertical = 28.dp),
    ) {
        ExtraSourcesScreen(hook, onClose = null, start = Screen.HomeRows())
    }
}

private sealed interface Screen {
    /** Orca+ start page: Extra sources, Search, Home collections. */
    data object Menu : Screen

    /** Extra sources: the server list. */
    data object List : Screen

    data object Collections : Screen

    /** Add a list link; [back] is the page it was opened from. */
    data class AddCollection(
        val back: Screen = Collections,
    ) : Screen

    /** A Cinema page's rows: show, hide, move, rename. */
    data class HomeRows(
        val page: com.wholphinplus.sources.cinema.RowsPage = com.wholphinplus.sources.cinema.RowsPage.HOME,
    ) : Screen

    data class RenameRow(
        val page: com.wholphinplus.sources.cinema.RowsPage,
        val key: String,
        val title: String,
        val defaultName: String,
    ) : Screen

    data class Collection(
        val id: String,
    ) : Screen

    data object TraktKey : Screen

    /** Top Streaming: the account whose catalogs become rows. */
    data object TopStreaming : Screen

    /** Cloud sync: this TV's whole setup in an encrypted profile. */
    data object Cloud : Screen

    data class Server(
        val connection: ServerConnection,
    ) : Screen

    data object AddUrl : Screen

    data class Password(
        val url: String,
        val name: String,
        val info: ServerInfo,
    ) : Screen

    data class Code(
        val login: CodeLogin,
        val name: String,
    ) : Screen

    data class PlexToken(
        val url: String,
        val name: String,
    ) : Screen

    data object TmdbKey : Screen

    /** Cinema mode's poster overlays. */
    data object Overlays : Screen

    /** Review scores: which, where, and the MDBList key. */
    data object Ratings : Screen

    data object MdblistKey : Screen
}

@Composable
private fun ExtraSourcesScreen(
    hook: SourceHook,
    onClose: (() -> Unit)?,
    start: Screen = Screen.Menu,
) {
    val connections by hook.store.connections.collectAsState()
    var screen by remember { mutableStateOf(start) }
    // Back walks up: server pages → Extra sources → menu → close. As a page of its own (no
    // [onClose]), Back on the first screen is the app's own: it leaves the page
    // (The rows editor counts as the first screen on any page it shows)
    fun atStart(s: Screen) = s == start || (start is Screen.HomeRows && s is Screen.HomeRows)
    androidx.activity.compose.BackHandler(enabled = onClose != null || !atStart(screen)) {
        screen =
            when (val s = screen) {
                start -> return@BackHandler onClose?.invoke() ?: Unit
                is Screen.HomeRows -> if (atStart(s)) return@BackHandler onClose?.invoke() ?: Unit else Screen.Menu
                Screen.List, Screen.TmdbKey, Screen.Collections, Screen.Overlays, Screen.Cloud -> Screen.Menu
                Screen.Ratings -> Screen.Overlays
                Screen.MdblistKey -> Screen.Ratings
                is Screen.RenameRow -> Screen.HomeRows(s.page)
                is Screen.AddCollection -> s.back
                is Screen.Collection, Screen.TraktKey, Screen.TopStreaming -> Screen.Collections
                else -> Screen.List
            }
    }
    // Opened at one page from a Settings topic: anything that would go back to the menu closes
    LaunchedEffect(screen) { if (screen == Screen.Menu && start != Screen.Menu) onClose?.invoke() }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // The request a screen started, and that screen: Cancel or Back leaves it, and its answer
    // must not pull the screen back (a sign-in form after Cancel, a server added after Cancel)
    val running = remember { arrayOfNulls<Pair<Screen, kotlinx.coroutines.Job>>(1) }
    LaunchedEffect(screen) {
        running[0]?.let { (from, job) -> if (from != screen) job.cancel() }
    }

    fun run(
        label: String,
        block: suspend () -> Unit,
    ) {
        val from = screen
        running[0]?.second?.cancel()
        val job =
            scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                busy = label
                message = null
                try {
                    block()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    message = e.message ?: e.javaClass.simpleName
                } finally {
                    // Not when a newer request already took over the busy line
                    if (running[0]?.second === kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]) busy = null
                }
            }
        running[0] = from to job
        job.start()
    }

    fun saved(connection: ServerConnection) {
        hook.store.save(connection)
        hook.clearCache()
        message = "Added ${connection.label}"
        screen = Screen.List
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val (title, about) =
            when (screen) {
                Screen.Menu -> "Orca+" to "Everything Orca+ can do. Press Back to leave."
                Screen.TmdbKey -> "Search" to "Finds a title on every server you have."
                Screen.Ratings, Screen.MdblistKey -> "Score sources" to "Review scores on cards and title pages, in the order you turn them on."
                Screen.Overlays -> "Poster tags" to "Everything a card on Home can show. The preview changes as you go."
                is Screen.HomeRows, is Screen.RenameRow -> "Rows" to "The rows on each page, in the order they show. Changes show the next time you open the page."
                Screen.Cloud ->
                    "Cloud sync" to "Your whole setup on every TV: settings, rows, lists, keys, extra servers and where you left off. " +
                        "It's encrypted on this TV with your sync PIN before it's sent, so nobody else can read it."
                Screen.TopStreaming ->
                    "Top Streaming" to "Today's Top 10 from each streaming service, as rows on your home. Pick countries and services at top-streaming.stream; Orca+ follows your picks."
                Screen.Collections, is Screen.AddCollection, is Screen.Collection, Screen.TraktKey ->
                    "Your lists" to "MDBList, Trakt and Top Streaming lists as rows on your home screen. They follow the list as it changes " +
                        "(checked every 6 hours) and show the titles you have on your Jellyfin server."
                else ->
                    "Extra servers" to "When you press Play, these servers are searched for the same title and you pick where to " +
                        "stream from. Watched status and resume stay on your Jellyfin."
            }
        SettingsPageHeader(title, about)
        busy?.let { Text("$it…", color = MaterialTheme.colorScheme.primary) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        when (val s = screen) {
            Screen.Menu -> if (start == Screen.Menu) MenuScreen(hook) { screen = it }

            Screen.Collections -> CollectionsScreen(hook) { screen = it }

            Screen.Overlays -> OverlaysScreen(hook) { screen = it }

            Screen.Ratings -> RatingsScreen(hook) { screen = it }

            Screen.MdblistKey -> MdblistKeyScreen(hook, onDone = { screen = Screen.Ratings })

            is Screen.AddCollection -> AddCollectionScreen(hook, onDone = { screen = s.back })

            is Screen.HomeRows ->
                HomeRowsScreen(
                    hook,
                    page = s.page,
                    onPage = { screen = Screen.HomeRows(it) },
                    onRename = { spec, name -> screen = Screen.RenameRow(s.page, spec.key, spec.title, name) },
                    onAddList = { screen = Screen.AddCollection(back = s) },
                )

            is Screen.RenameRow -> RenameRowScreen(hook, s, onDone = { screen = Screen.HomeRows(s.page) })

            is Screen.Collection -> CollectionScreen(hook, s.id, onDone = { screen = Screen.Collections })

            Screen.TraktKey -> TraktKeyScreen(hook, onDone = { screen = Screen.Collections })

            Screen.TopStreaming -> TopStreamingScreen(hook, onDone = { screen = Screen.Collections })

            Screen.Cloud -> CloudScreen(hook)

            Screen.List -> {
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    item {
                        PlusListItem(
                            onClick = { screen = Screen.AddUrl },
                            headlineContent = { Text("+ Add server") },
                            supportingContent = { Text("Plex, Emby or Jellyfin") },
                            modifier = Modifier.focusRequester(first),
                        )
                    }
                    items(connections, key = { it.connectionId }) { c ->
                        PlusListItem(
                            onClick = { screen = Screen.Server(c) },
                            headlineContent = { Text((if (c.enabled) "● " else "○ ") + c.label) },
                            supportingContent = {
                                Text(
                                    listOf(c.serverKind.label, c.userName, "${c.collections.count { it.enabled }} libraries", c.serverUrl)
                                        .filter { it.isNotBlank() }
                                        .joinToString("  ·  "),
                                )
                            },
                        )
                    }
                }
            }

            is Screen.Server -> {
                val c = connections.firstOrNull { it.connectionId == s.connection.connectionId } ?: s.connection
                Text(c.label, style = MaterialTheme.typography.titleLarge)
                Text("${c.serverKind.label} · ${c.userName} · ${c.serverUrl}")
                ActionRow {
                    Button(onClick = { hook.store.replace(c.copy(enabled = !c.enabled)).also { hook.clearCache() } }) {
                        Text(if (c.enabled) "Turn off" else "Turn on")
                    }
                    Button(onClick = {
                        run("Testing ${c.label}") {
                            val fresh = withContext(Dispatchers.IO) { hook.client.refresh(c) }
                            hook.store.replace(fresh.copy(connectionId = c.connectionId))
                            hook.clearCache()
                            message = "OK: ${fresh.serverName}, ${fresh.collections.size} libraries"
                        }
                    }) { Text("Test") }
                    Button(onClick = {
                        hook.store.remove(c.connectionId)
                        hook.clearCache()
                        screen = Screen.List
                    }) { Text("Remove") }
                    Button(onClick = { screen = Screen.List }) { Text("Back") }
                }
                if (c.collections.isNotEmpty()) {
                    Text("Libraries searched (select to toggle)", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(c.collections, key = { it.id }) { col ->
                            PlusListItem(
                                onClick = {
                                    hook.store.replace(
                                        c.copy(collections = c.collections.map { if (it.id == col.id) it.copy(enabled = !it.enabled) else it }),
                                    )
                                    hook.clearCache()
                                },
                                headlineContent = { Text((if (col.enabled) "☑ " else "☐ ") + col.name) },
                                supportingContent = { Text(col.type.ifBlank { "mixed" }) },
                            )
                        }
                    }
                }
            }

            Screen.AddUrl -> {
                val url = rememberTextFieldState()
                val name = rememberTextFieldState()
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Field("Server address (e.g. http://my-server.local:8096). Leave empty for your own Plex account.", url, Modifier.focusRequester(focus), KeyboardType.Uri)
                Field("Display name (optional)", name)
                ActionRow {
                    Button(onClick = {
                        val raw = url.text.toString()
                        val label = name.text.toString()
                        if (raw.isBlank()) {
                            run("Asking Plex for a code") {
                                val login = withContext(Dispatchers.IO) { hook.client.startPlexPin("") }
                                screen = Screen.Code(login, label)
                            }
                            return@Button
                        }
                        val normalized = normalizeServerUrl(raw)
                        run("Contacting $normalized") {
                            val info = withContext(Dispatchers.IO) { hook.client.fetchPublicInfo(normalized) }
                            screen =
                                when (info.serverKind) {
                                    ServerKind.PLEX -> Screen.PlexToken(normalized, label)
                                    ServerKind.EMBY, ServerKind.JELLYFIN -> Screen.Password(normalized, label, info)
                                    ServerKind.UNKNOWN -> error("No Plex, Emby or Jellyfin server answered at $normalized")
                                }
                        }
                    }) { Text("Next") }
                    Button(onClick = { screen = Screen.List }) { Text("Cancel") }
                }
            }

            is Screen.Password -> {
                val user = rememberTextFieldState()
                val pass = rememberTextFieldState()
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Text("${s.info.serverKind.label} server “${s.info.serverName}” at ${s.url}")
                Field("Username", user, Modifier.focusRequester(focus))
                Field("Password", pass, isPassword = true)
                ActionRow {
                    Button(onClick = {
                        run("Signing in") {
                            val c =
                                withContext(Dispatchers.IO) {
                                    hook.client.signIn(s.url, s.info, user.text.toString().trim(), pass.text.toString(), s.name)
                                }
                            saved(c)
                        }
                    }) { Text("Sign in") }
                    if (s.info.serverKind == ServerKind.JELLYFIN) {
                        Button(onClick = {
                            run("Starting Quick Connect") {
                                val login = withContext(Dispatchers.IO) { hook.client.startQuickConnect(s.url) }
                                screen = Screen.Code(login, s.name)
                            }
                        }) { Text("Use Quick Connect") }
                    }
                    Button(onClick = { screen = Screen.List }) { Text("Cancel") }
                }
            }

            is Screen.PlexToken -> {
                val token = rememberTextFieldState()
                Text("Plex server at ${s.url}")
                ActionRow {
                    Button(onClick = {
                        run("Asking Plex for a code") {
                            val login = withContext(Dispatchers.IO) { hook.client.startPlexPin(s.url) }
                            screen = Screen.Code(login, s.name)
                        }
                    }) { Text("Sign in with a code") }
                    Button(onClick = { screen = Screen.List }) { Text("Cancel") }
                }
                Field("…or paste a Plex token", token, isPassword = true)
                Button(onClick = {
                    run("Checking token") {
                        val c = withContext(Dispatchers.IO) { hook.client.buildPlexConnection(token.text.toString(), s.url, s.name) }
                        saved(c)
                    }
                }) { Text("Use token") }
            }

            Screen.TmdbKey -> {
                val key = rememberTextFieldState(hook.store.tmdbKey.value)
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Text(
                    "Smart search understands typos, actors, \"best horror movies\" and \"movies like Alien\" across all " +
                        "your servers, through Orca+. Optional: add your own free TMDB key (themoviedb.org → Settings → API) " +
                        "to ask TMDB directly.",
                )
                Field("TMDB API key (v3)", key, Modifier.focusRequester(focus))
                Text(TMDB_NOTICE, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionRow {
                    Button(onClick = {
                        hook.store.setTmdbKey(key.text.toString())
                        message = if (key.text.isBlank()) "Using Orca+ smart search" else "TMDB key saved"
                        screen = Screen.Menu
                    }) { Text("Save") }
                    Button(onClick = { screen = Screen.Menu }) { Text("Cancel") }
                }
            }

            is Screen.Code -> {
                val login = s.login
                val where =
                    if (login.kind == ServerKind.PLEX) {
                        "On your phone or computer go to plex.tv/link and enter"
                    } else {
                        "In Jellyfin on another device: Settings → Quick Connect, enter"
                    }
                Text(where)
                Text(login.code, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                Text("Waiting for approval…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val cancel = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
                Button(onClick = { screen = Screen.List }, modifier = Modifier.focusRequester(cancel)) { Text("Cancel") }
                LaunchedEffect(login) {
                    val deadline = System.currentTimeMillis() + 10 * 60 * 1000L
                    while (System.currentTimeMillis() < deadline) {
                        delay(login.intervalSeconds * 1000L)
                        val result =
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    if (login.kind == ServerKind.PLEX) {
                                        hook.client.pollPlexPin(login, s.name)
                                    } else {
                                        hook.client.pollQuickConnect(login, s.name)
                                    }
                                }
                            }
                        result.onFailure { if (it is CancellationException) throw it }
                        result.exceptionOrNull()?.let {
                            message = it.message
                            return@LaunchedEffect
                        }
                        result.getOrNull()?.let {
                            saved(it)
                            return@LaunchedEffect
                        }
                    }
                    message = "The code expired"
                    screen = Screen.List
                }
            }
        }
    }
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 4.dp)) { content() }
}

@Composable
private fun Field(
    label: String,
    state: TextFieldState,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        val boxModifier =
            modifier
                .width(640.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(12.dp)
        val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        val cursor = SolidColor(MaterialTheme.colorScheme.primary)
        if (isPassword) {
            BasicSecureTextField(state = state, modifier = boxModifier, textStyle = style, cursorBrush = cursor)
        } else {
            BasicTextField(
                state = state,
                modifier = boxModifier.fillMaxWidth(),
                textStyle = style,
                cursorBrush = cursor,
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            )
        }
    }
}

// ---------------------------------------------------------------- Orca+ menu

@Composable
private fun MenuScreen(
    hook: SourceHook,
    go: (Screen) -> Unit,
) {
    val connections by hook.store.connections.collectAsState()
    val tmdb by hook.store.tmdbKey.collectAsState()
    val lists by hook.collections.lists.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            MenuItem(
                "Extra sources",
                if (connections.isEmpty()) {
                    "Play from your Plex, Emby and other Jellyfin servers"
                } else {
                    "${connections.count { it.enabled }} of ${connections.size} servers on"
                },
                Modifier.focusRequester(first),
            ) { go(Screen.List) }
        }
        item {
            MenuItem("Search", if (tmdb.isBlank()) "Smart search is on  ·  your own TMDB key is optional" else "Smart search is on, with your TMDB key") {
                go(Screen.TmdbKey)
            }
        }
        item {
            val cinema by hook.store.cinemaMode.collectAsState()
            val rollUp by hook.store.cinemaRollUp.collectAsState()
            if (cinema) {
                PlusListItem(
                    onClick = { hook.store.setCinemaRollUp(!rollUp) },
                    headlineContent = { Text("Roll up the billboard while browsing", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(if (rollUp) "On: the title details shrink to a quarter when you scroll into the rows" else "Off: the title details stay full size")
                    },
                    trailingContent = { androidx.tv.material3.Switch(checked = rollUp, onCheckedChange = null, colors = com.wholphinplus.sources.ui.plusSwitchColors()) },
                    
                )
            }
        }
        item {
            val cinema by hook.store.cinemaMode.collectAsState()
            if (cinema) MenuItem("Rows", "Pick, order and rename the rows on Home, Shows, Movies and New & Popular") { go(Screen.HomeRows()) }
        }
        item {
            val cinema by hook.store.cinemaMode.collectAsState()
            val overlays by hook.store.overlays.collectAsState()
            if (cinema) {
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
                    ).filter { it.first }.map { it.second }
                MenuItem("Poster tags", if (on.isEmpty()) "Pictures only. Add logos, streaming services, scores, quality and more" else on.joinToString(" · ")) {
                    go(Screen.Overlays)
                }
            }
        }
        item {
            MenuItem(
                "Your lists",
                if (lists.isEmpty()) "Add Trakt or MDBList lists as home rows" else "${lists.count { it.showOnHome }} of ${lists.size} on the home screen",
            ) { go(Screen.Collections) }
        }
        item {
            val cloud by hook.profileSync.status.collectAsState()
            MenuItem(
                "Cloud sync",
                when {
                    !com.wholphinplus.sources.sync.ProfileSync.AVAILABLE -> "Not available in this build"
                    !cloud.on -> "Off: keep your whole setup in the cloud and bring it to any TV"
                    cloud.problem != null -> "On  ·  ${cloud.problem}"
                    else -> "On  ·  synced ${ago(cloud.lastSync)}"
                },
            ) { if (com.wholphinplus.sources.sync.ProfileSync.AVAILABLE) go(Screen.Cloud) }
        }
        item {
            Text(
                "Orca+ is built on Wholphin by damontecres (GPL). Not affiliated with Wholphin.\n" + TMDB_NOTICE,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun MenuItem(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    PlusListItem(
        onClick = onClick,
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(summary) },
        trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge, color = com.wholphinplus.sources.cinema.InkDim) },
        modifier = modifier,
    )
}

// ---------------------------------------------------------------- home collections

private fun ago(ms: Long): String {
    if (ms <= 0) return "never"
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        min < 48 * 60 -> "${min / 60}h ago"
        else -> "${min / (24 * 60)}d ago"
    }
}

@Composable
private fun CollectionsScreen(
    hook: SourceHook,
    go: (Screen) -> Unit,
) {
    val lists by hook.collections.lists.collectAsState()
    val refreshing by hook.collections.refreshing.collectAsState()
    val trakt by hook.collections.traktClientId.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    refreshing?.let { Text("Updating “$it”…", color = MaterialTheme.colorScheme.primary) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            PlusListItem(
                onClick = { go(Screen.AddCollection()) },
                headlineContent = { Text("+ Add a list link") },
                supportingContent = { Text("mdblist.com/lists/…  or  trakt.tv/users/…/lists/…") },
                modifier = Modifier.focusRequester(first),
            )
        }
        items(lists.filterNot { hook.collections.isTopStreaming(it) || hook.collections.isChart(it) }, key = { it.id }) { c ->
            PlusListItem(
                onClick = { go(Screen.Collection(c.id)) },
                headlineContent = { Text((if (c.showOnHome) "● " else "○ ") + c.name) },
                supportingContent = {
                    Text(
                        c.error?.let { "Problem: $it" }
                            ?: "${c.itemIds.size} of ${c.listSize} titles in your library  ·  updated ${ago(c.refreshedAt)}",
                        color = if (c.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
        item {
            val account by hook.collections.topStreamingAccount.collectAsState()
            val charts = lists.filter { hook.collections.isTopStreaming(it) }
            PlusListItem(
                onClick = { go(Screen.TopStreaming) },
                headlineContent = { Text("Top Streaming") },
                supportingContent = {
                    Text(
                        if (account.isBlank()) {
                            "Not set: today's Top 10 per streaming service, as rows"
                        } else {
                            "${charts.size} charts  ·  ${charts.count { it.showOnHome }} on the home  ·  arrange them in Rows"
                        },
                    )
                },
            )
        }
        item {
            PlusListItem(
                onClick = { go(Screen.TraktKey) },
                headlineContent = { Text("Trakt client ID") },
                supportingContent = { Text(if (trakt.isBlank()) "Not set: needed for Trakt links (MDBList works without it)" else "Set") },
            )
        }
    }
}

@Composable
private fun AddCollectionScreen(
    hook: SourceHook,
    onDone: () -> Unit,
) {
    val url = rememberTextFieldState()
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Text("Paste or type a public list link, for example mdblist.com/lists/garycrawford/latest-tv-shows")
    Field("List link", url, Modifier.focusRequester(focus), KeyboardType.Uri)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    ActionRow {
        Button(onClick = {
            val source = com.wholphinplus.sources.core.ListSource.parse(url.text.toString())
            when {
                source == null -> error = "That isn't an mdblist.com or trakt.tv list link"
                source is com.wholphinplus.sources.core.ListSource.Trakt && hook.collections.traktClientId.value.isBlank() ->
                    error = "Trakt links need a Trakt client ID first (see “Trakt client ID” on the previous page)"
                else -> {
                    val c = hook.collections.newCollection(source.url)
                    hook.collections.add(c)
                    hook.collections.refreshStale(hook, maxAgeMs = -1, onlyId = c.id)
                    onDone()
                }
            }
        }) { Text("Add to home") }
        Button(onClick = onDone) { Text("Cancel") }
    }
}

@Composable
private fun CollectionScreen(
    hook: SourceHook,
    id: String,
    onDone: () -> Unit,
) {
    val lists by hook.collections.lists.collectAsState()
    val c = lists.firstOrNull { it.id == id } ?: return onDone()
    val name = rememberTextFieldState(c.name)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Text(c.url, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("${c.itemIds.size} of ${c.listSize} titles in your library  ·  updated ${ago(c.refreshedAt)}")
    ActionRow {
        Button(onClick = { hook.collections.update(c.copy(showOnHome = !c.showOnHome)) }, modifier = Modifier.focusRequester(first)) {
            Text(if (c.showOnHome) "Hide from home" else "Show on home")
        }
        Button(onClick = { hook.collections.move(c.id, -1) }) { Text("Move up") }
        Button(onClick = { hook.collections.move(c.id, +1) }) { Text("Move down") }
        Button(onClick = {
            hook.collections.forgetMisses()
            hook.collections.refreshStale(hook, maxAgeMs = -1, onlyId = c.id)
        }) { Text("Refresh now") }
        Button(onClick = {
            hook.collections.remove(c.id)
            onDone()
        }) { Text("Remove") }
    }
    Field("Row name", name)
    Button(onClick = {
        hook.collections.update(c.copy(name = name.text.toString().trim().ifBlank { c.name }))
        onDone()
    }) { Text("Save name") }
}

@Composable
private fun TraktKeyScreen(
    hook: SourceHook,
    onDone: () -> Unit,
) {
    val key = rememberTextFieldState(hook.collections.traktClientId.value)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Text(
        "Trakt only shares lists with apps that have their own client ID. Make one free at trakt.tv → Settings → " +
            "Your API Apps → New Application (any name; redirect URI urn:ietf:wg:oauth:2.0:oob), then copy its Client ID here.",
    )
    Field("Trakt client ID", key, Modifier.focusRequester(focus))
    ActionRow {
        Button(onClick = {
            hook.collections.setTraktClientId(key.text.toString())
            onDone()
        }) { Text("Save") }
        Button(onClick = onDone) { Text("Cancel") }
    }
}

@Composable
private fun CloudScreen(hook: SourceHook) {
    val sync = hook.profileSync
    val status by sync.status.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    // Off: whether the cloud already has a profile for this account (null: it couldn't be asked)
    var exists by remember { mutableStateOf<Boolean?>(null) }
    var checked by remember { mutableStateOf(false) }
    var asked by remember { mutableIntStateOf(0) }
    // The full-screen PIN pad: "on" (set up or bring the setup here) or "pin" (a new PIN)
    var pad by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(status.on, asked) {
        if (!status.on) {
            checked = false
            exists = sync.cloudHasProfile(hook)
            checked = true
        }
    }
    LaunchedEffect(status.on, checked, pad) { if (pad == null) runCatching { focus.requestFocus() } }

    fun act(
        label: String,
        block: suspend () -> Unit,
    ) {
        if (working) return
        working = true
        message = label
        scope.launch {
            message =
                try {
                    block()
                    null
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    com.wholphinplus.sources.sync.ProfileSync.describe(e)
                }
            working = false
        }
    }
    // Why sync went off by itself (the profile was deleted on another TV)
    if (!status.on) status.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    when {
        !status.on && !checked -> Text("Checking the cloud for your profile…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        !status.on && exists == null -> {
            Text("Can't reach the Orca+ cloud right now.")
            ActionRow { Button(onClick = { asked++ }, modifier = Modifier.focusRequester(focus)) { Text("Try again") } }
        }
        !status.on -> {
            Text(
                if (exists == true) {
                    "Your setup is saved in the cloud for this account. Bring it to this TV with your 6-digit sync PIN; it replaces this TV's settings."
                } else {
                    "Keep this TV's whole setup in the cloud with a 6-digit sync PIN, then bring it to any other TV by signing in and entering the PIN."
                },
            )
            ActionRow {
                Button(onClick = { pad = "on" }, modifier = Modifier.focusRequester(focus)) { Text(if (exists == true) "Bring my setup here" else "Set up cloud sync") }
            }
        }
        else -> {
            Text(
                if (status.busy) "Syncing…" else "On. Last synced ${ago(status.lastSync)}. This TV syncs when Home opens and when you leave the app.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            // "Forgot PIN" was used elsewhere: say when the setup goes, and offer to keep it
            val context = androidx.compose.ui.platform.LocalContext.current
            if (status.resetAt > 0) {
                Text(
                    "Someone used \"Forgot PIN\" on another TV: the setup saved for this account will be deleted ${resetTime(context, status.resetAt)} unless you keep it.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ActionRow {
                if (status.resetAt > 0) Button(onClick = { act("Keeping your setup…") { sync.keepSetup(hook) } }) { Text("Keep my setup") }
                Button(onClick = { act("Syncing…") { sync.syncNow(hook) } }, modifier = Modifier.focusRequester(focus)) { Text("Sync now") }
                Button(onClick = { pad = "pin" }) { Text("Change PIN") }
                Button(onClick = { sync.turnOff() }) { Text("Turn off on this TV") }
                Button(onClick = {
                    if (!confirmDelete) {
                        confirmDelete = true
                        message = "Press again to delete the cloud profile for every TV."
                    } else {
                        act("Deleting…") { sync.deleteCloud(hook) }
                    }
                }) { Text(if (confirmDelete) "Yes, delete it" else "Delete cloud profile") }
            }
            // An Orca+ name: a new TV then needs only the name and the PIN, no server sign-in
            Text(
                status.name?.let { "Orca+ name: $it. On a new TV, choose \"I have an Orca+ account\" and type $it and your PIN: the TV signs in to your server by itself." }
                    ?: "Pick an Orca+ name, and a new TV needs only the name and your PIN: it signs in to your server by itself.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Refilled when the name changes (set on the PIN screen, or by another TV)
            val name = remember(status.name) { androidx.compose.foundation.text.input.TextFieldState(status.name.orEmpty()) }
            Field("Orca+ name", name)
            ActionRow {
                Button(onClick = {
                    act("Saving your name…") {
                        sync.setName(hook, name.text.toString().trim())
                        // The sign-in a new TV uses goes up with the next sync
                        sync.syncNow(hook)
                    }
                }) { Text(if (status.name == null) "Save name" else "Change name") }
            }
        }
    }
    message?.let { Text(it, color = if (working) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }

    when (pad) {
        "on" -> PadDialog(onClose = { pad = null }) {
            CloudPinFlow(hook, exists == true, onDone = { pad = null }, onSkip = { pad = null }, skipLabel = "Cancel", modifier = Modifier.fillMaxSize())
        }
        "pin" -> PadDialog(onClose = { pad = null }) {
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                NewPinPad(
                    title = "Pick a new sync PIN",
                    subtitle = "Six digits. Your other TVs will ask for it once.",
                    error = error,
                    busy = busy,
                    busyText = "Changing the PIN…",
                    onPin = { p ->
                        busy = true
                        scope.launch {
                            try {
                                sync.changePin(hook, p)
                                message = "PIN changed. Other TVs will ask for the new one."
                                pad = null
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                error = com.wholphinplus.sources.sync.ProfileSync.describe(e)
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
                Spacer(Modifier.height(18.dp))
                SkipButton("Cancel", busy) { pad = null }
            }
        }
    }
}

@Composable
private fun TopStreamingScreen(
    hook: SourceHook,
    onDone: () -> Unit,
) {
    val account = rememberTextFieldState(hook.collections.topStreamingAccount.value)
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val saveButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Text(
        "Sign in at top-streaming.stream, pick your countries and services under Content and Catalogs, then copy " +
            "Your Account UUID from the Account tab here (the Copy URL link works too). New charts start switched off.",
    )
    Field("Account UUID", account, Modifier.focusRequester(focus).focusProperties { down = saveButton })
    status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    ActionRow {
        Button(
            onClick = {
                if (busy) return@Button
                busy = true
                status = "Loading your charts…"
                scope.launch {
                    status =
                        try {
                            val id = hook.collections.setTopStreamingAccount(account.text.toString())
                            if (id.isEmpty()) {
                                "That doesn't contain an account UUID (like 1a2b3c4d-…)."
                            } else {
                                val n = withContext(Dispatchers.IO) { hook.collections.syncTopStreaming() }
                                hook.collections.refreshStale(hook)
                                "$n charts found. They start on New & Popular; add them to Home, Shows or Movies in Rows. They update every few hours."
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            "Top Streaming didn't answer: ${e.message ?: e.javaClass.simpleName}"
                        }
                    busy = false
                }
            },
            modifier = Modifier.focusRequester(saveButton),
        ) { Text("Save and load charts") }
        Button(onClick = {
            hook.collections.setTopStreamingAccount("")
            onDone()
        }) { Text("Remove") }
        Button(onClick = onDone) { Text("Back") }
    }
}

@Composable
private fun RenameRowScreen(
    hook: SourceHook,
    row: Screen.RenameRow,
    onDone: () -> Unit,
) {
    val name = rememberTextFieldState(row.title.ifBlank { row.defaultName })
    val focus = remember { FocusRequester() }
    val saveButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Renaming reads the saved layout fresh, so a change made meanwhile isn't lost
    fun save(title: String) {
        val base = hook.store.pageLayouts.value[row.page] ?: return onDone()
        hook.store.setPageLayout(row.page, base.renamed(row.key, if (title == row.defaultName) "" else title))
        com.wholphinplus.sources.cinema.CinemaCaches.homeChanged()
        onDone()
    }
    Text("Its own name is “${row.defaultName}”.")
    // Down from the name lands on Save, not on the button below the middle of the field
    Field("Row name", name, Modifier.focusRequester(focus).focusProperties { down = saveButton })
    ActionRow {
        Button(onClick = { save(name.text.toString().trim()) }, modifier = Modifier.focusRequester(saveButton)) { Text("Save") }
        Button(onClick = { save("") }) { Text("Use its own name") }
        Button(onClick = onDone) { Text("Cancel") }
    }
}

// ---------------------------------------------------------------- ratings

@Composable
private fun RatingsScreen(
    hook: SourceHook,
    go: (Screen) -> Unit,
) {
    val r by hook.store.ratingPrefs.collectAsState()
    val key by hook.store.mdblistKey.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val set = { n: com.wholphinplus.sources.cinema.RatingPrefs -> hook.store.setRatingPrefs(n) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.width(720.dp)) {
        item {
            MenuItem(
                "MDBList API key",
                if (key.isBlank()) "Needed for every score but TMDB's. Free at mdblist.com → Preferences → API" else "Saved  ·  IMDb, Rotten Tomatoes, Metacritic, Letterboxd and Trakt scores on",
                Modifier.focusRequester(first),
            ) { go(Screen.MdblistKey) }
        }
        com.wholphinplus.sources.cinema.RatingSource.entries.forEach { source ->
            item {
                val on = source in r.sources
                // Its place among the scores that can actually show (the others wait for the key)
                val usable = r.sources.filter { !it.needsKey || key.isNotBlank() }
                val place = usable.indexOf(source)
                val note =
                    when {
                        !on -> "Off"
                        source.needsKey && key.isBlank() -> "On, needs the MDBList key"
                        place >= 2 -> "On · #${place + 1}: title pages (cards show the first two)"
                        else -> "On · #${place + 1}"
                    }
                Toggle(source.label, note, on) { set(r.with(source, it)) }
            }
        }
        item { Toggle("On cards", "The first two scores in the corner of each card", r.onCards) { set(r.copy(onCards = it)) } }
        item { Toggle("On title pages", "Every picked score under the genres", r.onTitlePage) { set(r.copy(onTitlePage = it)) } }
        item {
            Text(
                "Scores are kept on the device for a week, so each title is asked about rarely (a free key allows about 1,000 a day).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun MdblistKeyScreen(
    hook: SourceHook,
    onDone: () -> Unit,
) {
    val key = rememberTextFieldState(hook.store.mdblistKey.value)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Text(
        "IMDb, Rotten Tomatoes, Metacritic, Letterboxd and Trakt scores come from MDBList. Sign in at mdblist.com, " +
            "open Preferences → API, and copy your API key here. Leave empty to show only TMDB's score.",
    )
    Field("MDBList API key", key, Modifier.focusRequester(focus))
    ActionRow {
        Button(onClick = {
            hook.store.setMdblistKey(key.text.toString())
            onDone()
        }) { Text("Save") }
        Button(onClick = onDone) { Text("Cancel") }
    }
}

/** Required by TMDB's API terms wherever TMDB data is used. */
internal const val TMDB_NOTICE = "Search data and images from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB."

// ---------------------------------------------------------------- poster overlays

@Composable
private fun OverlaysScreen(
    hook: SourceHook,
    go: (Screen) -> Unit,
) {
    val o by hook.store.overlays.collectAsState()
    val r by hook.store.ratingPrefs.collectAsState()
    val mdb by hook.store.mdblistKey.collectAsState()
    val context = LocalContext.current
    val entry = remember { EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java) }
    val art = remember { entry.cinemaArt() }
    val ratings = remember { entry.ratings() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val set = { n: com.wholphinplus.sources.cinema.PosterOverlays -> hook.store.setOverlays(n) }
    // A preset keeps your layout (corner, style) and sets every tag, scores on cards included
    val preset = { p: com.wholphinplus.sources.cinema.PosterOverlays, scores: Boolean ->
        set(p.copy(corner = o.corner, style = o.style))
        hook.store.setRatingPrefs(r.copy(onCards = scores))
    }
    val quality = if (o.corner == com.wholphinplus.sources.cinema.OverlayCorner.TOP_RIGHT) "Top right" else "Top left"
    val services = if (o.corner == com.wholphinplus.sources.cinema.OverlayCorner.TOP_RIGHT) "Top left" else "Top right"
    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp), modifier = Modifier.width(470.dp)) {
            item { Section("Quick start", "Set every tag at once, then fine-tune below") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    PresetButton("Clean", Modifier.focusRequester(first)) { preset(com.wholphinplus.sources.cinema.PosterOverlays.CLEAN, false) }
                    PresetButton("Standard") { preset(com.wholphinplus.sources.cinema.PosterOverlays.STANDARD, false) }
                    PresetButton("Everything") { preset(com.wholphinplus.sources.cinema.PosterOverlays.EVERYTHING, true) }
                }
            }
            item { Section("Title", "Bottom left") }
            item { Toggle("Title logos", "The title's logo over a clean picture; off: art with the name in it", o.titleLogos) { set(o.copy(titleLogos = it)) } }
            item { Toggle("New labels", "Recently Added and New Episodes along the bottom edge", o.newLabels) { set(o.copy(newLabels = it)) } }
            item { Toggle("Title and date under cards", "The name, release date and length below each card", o.captions) { set(o.copy(captions = it)) } }
            item { Section("Quality", "$quality, for movies once the server knows the file") }
            item { Toggle("Resolution", "4K, HD or SD", o.resolution) { set(o.copy(resolution = it)) } }
            item { Toggle("HDR", "Dolby Vision, HDR10+ or HDR", o.hdr) { set(o.copy(hdr = it)) } }
            item { Toggle("Audio", "Atmos, 7.1 or 5.1", o.audio) { set(o.copy(audio = it)) } }
            item { Toggle("Maturity rating", "PG-13, TV-MA…", o.rating) { set(o.copy(rating = it)) } }
            item { Section("Where it streams", services) }
            item { Toggle("Streaming services", "A show's own network first, then where it streams", o.services) { set(o.copy(services = it)) } }
            if (o.services) {
                item { Choice("Logos per card", if (o.maxServices == 1) "One" else "Up to two") { set(o.copy(maxServices = if (o.maxServices == 1) 2 else 1)) } }
            }
            item { Section("Scores", "Bottom right") }
            item {
                Toggle(
                    "Scores on cards",
                    when {
                        r.sources.isEmpty() -> "No score sources picked"
                        r.sources.all { it.needsKey } && mdb.isBlank() -> "Waiting for an MDBList key (see Score sources)"
                        else -> "The first two of " + r.sources.filter { !it.needsKey || mdb.isNotBlank() }.joinToString(", ") { it.label }
                    },
                    r.onCards,
                ) { hook.store.setRatingPrefs(r.copy(onCards = it)) }
            }
            item { MenuItem("Score sources", "IMDb, Rotten Tomatoes, Metacritic and more; the MDBList key; title pages") { go(Screen.Ratings) } }
            item { Section("Status", null) }
            item { Toggle("Top 10", "A red TOP 10 corner on titles in your Top lists", o.top10) { set(o.copy(top10 = it)) } }
            item { Toggle("Watched", "A check on titles you've finished", o.watched) { set(o.copy(watched = it)) } }
            item { Toggle("Progress bar", "How far you got, along the bottom edge", o.progress) { set(o.copy(progress = it)) } }
            item { Section("Layout and style", null) }
            item {
                Choice("Quality badges", "$quality (streaming logos take the other corner)") {
                    set(o.copy(corner = com.wholphinplus.sources.cinema.OverlayCorner.entries.let { it[(o.corner.ordinal + 1) % it.size] }))
                }
            }
            item {
                val next = com.wholphinplus.sources.cinema.OverlayStyle.entries.let { it[(o.style.ordinal + 1) % it.size] }
                Choice("Badge style", if (o.style == com.wholphinplus.sources.cinema.OverlayStyle.MINIMAL) "Minimal: dark glass chips" else "Colour: each badge in its own colour") { set(o.copy(style = next)) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            Text("Preview", style = MaterialTheme.typography.titleMedium)
            com.wholphinplus.sources.cinema.PosterTagsPreview(o, r, art, ratings)
        }
    }
}

/** A group heading in Poster tags: its name, and where on the card its tags sit. */
@Composable
private fun Section(
    title: String,
    where: String?,
) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp, start = 4.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
        where?.let {
            Text("  ·  $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PresetButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, modifier = modifier) { Text(label) }
}

@Composable
private fun Toggle(
    title: String,
    summary: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    PlusListItem(
        onClick = { onChange(!checked) },
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(summary) },
        trailingContent = { androidx.tv.material3.Switch(checked = checked, onCheckedChange = null, colors = com.wholphinplus.sources.ui.plusSwitchColors()) },
        modifier = modifier,
    )
}

@Composable
private fun Choice(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    PlusListItem(
        onClick = onClick,
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(value) },
        trailingContent = { Text("⇄", style = MaterialTheme.typography.titleLarge) },
    )
}
