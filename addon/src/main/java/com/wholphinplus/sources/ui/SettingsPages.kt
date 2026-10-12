package com.wholphinplus.sources.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.wholphinplus.sources.SourceHook
import com.wholphinplus.sources.core.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CollectionsScreen(
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
internal fun AddCollectionScreen(
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
internal fun CollectionScreen(
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
internal fun TraktKeyScreen(
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
internal fun CloudScreen(hook: SourceHook) {
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
            CloudPinFlow(hook, exists == true, onDone = { pad = null }, onSkip = { pad = null }, skipLabel = "Cancel", modifier = Modifier.fillMaxSize(), nameFirst = exists != true)
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
internal fun TopStreamingScreen(
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
internal fun RenameRowScreen(
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
internal fun RatingsScreen(
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
internal fun MdblistKeyScreen(
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
