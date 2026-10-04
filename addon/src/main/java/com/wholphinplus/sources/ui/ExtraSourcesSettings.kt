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
}

private fun Context.sourceHook(): SourceHook = EntryPointAccessors.fromApplication(applicationContext, SourcesEntryPoint::class.java).sourceHook()

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
                    if (tmdb.isBlank()) "search off" else "smart search",
                    "${lists.count { it.showOnHome }} home collections",
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
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                    .padding(horizontal = 48.dp, vertical = 24.dp),
            ) {
                ExtraSourcesScreen(hook, onClose = { open = false })
            }
        }
    }
}

private sealed interface Screen {
    /** Orca+ start page: Extra sources, Search, Home collections. */
    data object Menu : Screen

    /** Extra sources: the server list. */
    data object List : Screen

    data object Collections : Screen

    data object AddCollection : Screen

    data class Collection(
        val id: String,
    ) : Screen

    data object TraktKey : Screen

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
}

@Composable
private fun ExtraSourcesScreen(
    hook: SourceHook,
    onClose: () -> Unit,
) {
    val connections by hook.store.connections.collectAsState()
    var screen by remember { mutableStateOf<Screen>(Screen.Menu) }
    // Back walks up: server pages → Extra sources → menu → close
    androidx.activity.compose.BackHandler {
        screen =
            when (screen) {
                Screen.Menu -> return@BackHandler onClose()
                Screen.List, Screen.TmdbKey, Screen.Collections -> Screen.Menu
                Screen.AddCollection, is Screen.Collection, Screen.TraktKey -> Screen.Collections
                else -> Screen.List
            }
    }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun run(
        label: String,
        block: suspend () -> Unit,
    ) {
        scope.launch {
            busy = label
            message = null
            try {
                block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                message = e.message ?: e.javaClass.simpleName
            } finally {
                busy = null
            }
        }
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
                Screen.Menu -> "Orca+" to "Everything Orca+ adds to Wholphin. Press Back to leave."
                Screen.TmdbKey -> "Search" to "Smart search."
                Screen.Collections, Screen.AddCollection, is Screen.Collection, Screen.TraktKey ->
                    "Home collections" to "Trakt and MDBList lists as rows on your home screen. They follow the list as it changes " +
                        "(checked every 6 hours) and show the titles you have on your Jellyfin server."
                else ->
                    "Extra sources" to "When you press Play, these servers are searched for the same title and you pick where to " +
                        "stream from. Watched status and resume stay on your Jellyfin."
            }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        busy?.let { Text("$it…", color = MaterialTheme.colorScheme.primary) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        when (val s = screen) {
            Screen.Menu -> MenuScreen(hook) { screen = it }

            Screen.Collections -> CollectionsScreen(hook) { screen = it }

            Screen.AddCollection -> AddCollectionScreen(hook, onDone = { screen = Screen.Collections })

            is Screen.Collection -> CollectionScreen(hook, s.id, onDone = { screen = Screen.Collections })

            Screen.TraktKey -> TraktKeyScreen(hook, onDone = { screen = Screen.Collections })

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
                    "Smart search turns on when you add a TMDB API key: typos, actors, \"best horror movies\", " +
                        "\"movies like Alien\", and titles from all your servers. Get a free key at themoviedb.org → " +
                        "Settings → API. Leave empty to use Wholphin's normal search.",
                )
                Field("TMDB API key (v3)", key, Modifier.focusRequester(focus))
                Text(TMDB_NOTICE, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionRow {
                    Button(onClick = {
                        hook.store.setTmdbKey(key.text.toString())
                        message = if (key.text.isBlank()) "Smart search off" else "TMDB key saved"
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
            MenuItem("Search", if (tmdb.isBlank()) "Wholphin's search  ·  add a TMDB key for smart search" else "Smart search is on") {
                go(Screen.TmdbKey)
            }
        }
        item {
            val cinema by hook.store.cinemaMode.collectAsState()
            PlusListItem(
                onClick = { hook.store.setCinemaMode(!cinema) },
                headlineContent = { Text("Cinema mode", style = MaterialTheme.typography.titleMedium) },
                supportingContent = {
                    Text(if (cinema) "On: big-screen streaming home with a featured billboard and wide rows" else "Off: Wholphin's classic home")
                },
                trailingContent = { androidx.tv.material3.Switch(checked = cinema, onCheckedChange = null) },
                modifier = Modifier.width(720.dp),
            )
        }
        item {
            MenuItem(
                "Home collections",
                if (lists.isEmpty()) "Add Trakt or MDBList lists as home rows" else "${lists.count { it.showOnHome }} of ${lists.size} on the home screen",
            ) { go(Screen.Collections) }
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
        trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
        modifier = modifier.width(720.dp),
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
                onClick = { go(Screen.AddCollection) },
                headlineContent = { Text("+ Add a list link") },
                supportingContent = { Text("mdblist.com/lists/…  or  trakt.tv/users/…/lists/…") },
                modifier = Modifier.focusRequester(first),
            )
        }
        items(lists, key = { it.id }) { c ->
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

/** Required by TMDB's API terms wherever TMDB data is used. */
internal const val TMDB_NOTICE = "Search data and images from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB."
