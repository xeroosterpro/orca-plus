package com.wholphinplus.sources.ui

import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
    fun has(topic: String): Boolean = topic in setOf("ACCOUNT", "APPEARANCE", "SOURCES", "PLAYBACK", "PLAYBACK_CARD", "KEYS", "DEVICE", "ABOUT")
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

internal sealed interface Screen {
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

    /** Title pages: what shows around the description. */
    data object DescriptionTags : Screen

    /** Review scores: which, where, and the MDBList key. */
    data object Ratings : Screen

    data object MdblistKey : Screen
}

@Composable
internal fun ExtraSourcesScreen(
    hook: SourceHook,
    onClose: (() -> Unit)?,
    start: Screen = Screen.Menu,
) {
    val connections by hook.store.connections.collectAsState()
    val health by hook.health.checks.collectAsState()
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
                Screen.List, Screen.TmdbKey, Screen.Collections, Screen.Overlays, Screen.DescriptionTags, Screen.Cloud -> Screen.Menu
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
    // The server being signed in to again (Sign in again on its page), until the new sign-in saves
    val signingInAgain = remember { arrayOfNulls<ServerConnection>(1) }
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
        // Signed in again ([signingInAgain]): the same entry, its on/off and library switches kept
        val again = signingInAgain[0]?.takeIf { it.serverUrl == connection.serverUrl || (it.serverId.isNotBlank() && it.serverId == connection.serverId) }
        signingInAgain[0] = null
        if (again != null) {
            val switches = again.collections.associate { it.id to it.enabled }
            hook.store.replace(
                connection.copy(
                    connectionId = again.connectionId,
                    enabled = again.enabled,
                    collections = connection.collections.map { c -> switches[c.id]?.let { c.copy(enabled = it) } ?: c },
                ),
            )
        } else {
            hook.store.save(connection)
        }
        hook.clearCache()
        message = if (again != null) "Signed in to ${connection.label} again" else "Added ${connection.label}"
        screen = Screen.List
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val (title, about) =
            when (screen) {
                Screen.Menu -> "Orca+" to "Everything Orca+ can do. Press Back to leave."
                Screen.TmdbKey -> "TMDB" to "Title art, smart search and More Like This."
                Screen.MdblistKey -> "MDBList" to "IMDb, Rotten Tomatoes, Metacritic and Letterboxd scores."
                Screen.Ratings -> "Score sources" to "Review scores on cards and title pages, in the order you turn them on."
                Screen.Overlays -> "Poster tags" to "Everything a card on Home can show. The preview changes as you go."
                Screen.DescriptionTags -> "Description tags" to "What a title page shows around its story. The preview changes as you go."
                is Screen.HomeRows, is Screen.RenameRow -> "Rows" to "The rows on each page, in the order they show. Changes show the next time you open the page."
                Screen.Cloud ->
                    "Cloud sync" to "Your whole setup on every TV: settings, rows, lists, keys, extra servers and where you left off. " +
                        "It's encrypted on this TV with your sync PIN before it's sent, so nobody else can read it."
                Screen.TopStreaming ->
                    "Top Streaming" to "Today's Top 10 from each streaming service, as rows on your home. Pick countries and services at top-streaming.stream; Orca+ follows your picks."
                Screen.TraktKey -> "Trakt" to "Your Trakt lists as rows on Home. Trakt needs a free Client ID of your own."
                Screen.Collections, is Screen.AddCollection, is Screen.Collection ->
                    "Your lists" to "MDBList, Trakt and Top Streaming lists as rows on your home screen. They follow the list as it changes " +
                        "(checked every 6 hours) and show the titles you have on your main server."
                else ->
                    "Extra servers" to "When you press Play, these servers are searched for the same title and you pick where to " +
                        "stream from. Watched status and resume stay on your main server."
            }
        SettingsPageHeader(title, about)
        busy?.let { Text("$it…", color = MaterialTheme.colorScheme.primary) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        when (val s = screen) {
            Screen.Menu -> if (start == Screen.Menu) MenuScreen(hook) { screen = it }

            Screen.Collections -> CollectionsScreen(hook) { screen = it }

            Screen.Overlays -> OverlaysScreen(hook) { screen = it }
            Screen.DescriptionTags -> DescriptionTagsScreen(hook) { screen = it }

            Screen.Ratings -> RatingsScreen(hook) { screen = it }

            Screen.MdblistKey -> MdblistKeyScreen(hook, onDone = { if (start == Screen.MdblistKey) onClose?.invoke() else screen = Screen.Ratings })

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

            Screen.TraktKey -> TraktKeyScreen(hook, onDone = { if (start == Screen.TraktKey) onClose?.invoke() else screen = Screen.Collections })

            Screen.TopStreaming -> TopStreamingScreen(hook, onDone = { if (start == Screen.TopStreaming) onClose?.invoke() else screen = Screen.Collections })

            Screen.Cloud -> CloudScreen(hook)

            Screen.List -> {
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                // The tour's sign-ins plus the phone, full screen (it was one address box)
                var adding by remember { mutableStateOf(false) }
                if (adding) {
                    com.wholphinplus.sources.welcome.AddServerDialog(
                        save = { c ->
                            hook.store.save(c)
                            hook.clearCache()
                            message = "Added ${c.label}"
                        },
                        onClose = {
                            adding = false
                            runCatching { first.requestFocus() }
                        },
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    item {
                        PlusListItem(
                            onClick = { adding = true },
                            headlineContent = { Text("+ Add a server") },
                            supportingContent = { Text("From your phone, Emby Connect, a Plex code, Quick Connect, or its address") },
                            modifier = Modifier.focusRequester(first),
                        )
                    }
                    items(connections, key = { it.connectionId }) { c ->
                        // How it answered its last lookup (nothing asked from here)
                        val trouble = health[c.connectionId]?.takeIf { it.signIn == c.lastConnectedAt.toString() }?.trouble
                        PlusListItem(
                            onClick = { screen = Screen.Server(c) },
                            headlineContent = { Text((if (c.enabled) "● " else "○ ") + c.label) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(trouble?.let { "⚠ " + it.reason }, c.serverKind.label, c.userName, "${c.collections.count { it.enabled }} libraries", c.serverUrl)
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
                            // It answers again: lookups ask it at once instead of waiting out its rest
                            hook.health.ok(fresh.copy(connectionId = c.connectionId))
                            message = "OK: ${fresh.serverName}, ${fresh.collections.size} libraries"
                        }
                    }) { Text("Test") }
                    // A new token when the old one stopped working (password changed, signed out on the server)
                    Button(onClick = {
                        run("Contacting ${c.label}") {
                            signingInAgain[0] = c
                            screen =
                                if (c.serverKind == ServerKind.PLEX) {
                                    Screen.PlexToken(c.serverUrl, c.displayName)
                                } else {
                                    val info = withContext(Dispatchers.IO) { hook.client.fetchPublicInfo(c.serverUrl) }
                                    Screen.Password(c.serverUrl, c.displayName, info)
                                }
                        }
                    }) { Text("Sign in again") }
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
                // "Use my phone": the address, username and password typed there (owner, 2026-10-09)
                var phone by remember { mutableStateOf(false) }
                if (phone) {
                    PadDialog(onClose = { phone = false }) {
                        com.wholphinplus.sources.welcome.PhoneServerStep(onConnected = { c ->
                            phone = false
                            saved(c)
                        }, onCancel = { phone = false })
                    }
                }
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
                    if (com.wholphinplus.sources.sync.ProfileSync.AVAILABLE) Button(onClick = { phone = true }) { Text("Use my phone") }
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
internal fun ActionRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 4.dp)) { content() }
}

@Composable
internal fun Field(
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
                    "Play from your other Plex, Emby, Jellyfin and Silo servers"
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
                    val rollUp by hook.store.cinemaRollUp.collectAsState()
            PlusListItem(
                onClick = { hook.store.setCinemaRollUp(!rollUp) },
                headlineContent = { Text("Roll up the billboard while browsing", style = MaterialTheme.typography.titleMedium) },
                supportingContent = {
                    Text(if (rollUp) "On: the title details shrink to a quarter when you scroll into the rows" else "Off: the title details stay full size")
                },
                trailingContent = { androidx.tv.material3.Switch(checked = rollUp, onCheckedChange = null, colors = com.wholphinplus.sources.ui.plusSwitchColors()) },
                
            )
        
        }
        item {
                    MenuItem("Rows", "Pick, order and rename the rows on Home, Shows, Movies and New & Popular") { go(Screen.HomeRows()) }
        }
        item {
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
                ).filter { it.first }.map { it.second }
            MenuItem("Poster tags", if (on.isEmpty()) "Pictures only. Add logos, streaming services, scores, quality and more" else on.joinToString(" · ")) {
                go(Screen.Overlays)
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
internal fun MenuItem(
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

internal fun ago(ms: Long): String {
    if (ms <= 0) return "never"
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        min < 48 * 60 -> "${min / 60}h ago"
        else -> "${min / (24 * 60)}d ago"
    }
}

/** Required by TMDB's API terms wherever TMDB data is used. */
internal const val TMDB_NOTICE = "Search data and images from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB."

// ---------------------------------------------------------------- poster overlays
