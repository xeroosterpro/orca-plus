package com.wholphinplus.sources.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Border
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.surfaceColorAtElevation
import coil3.compose.AsyncImage
import com.wholphinplus.sources.Availability
import com.wholphinplus.sources.PickerUi
import com.wholphinplus.sources.SearchService
import com.wholphinplus.sources.core.ExternalSource
import com.wholphinplus.sources.core.TmdbEpisode
import com.wholphinplus.sources.core.TmdbItem
import com.wholphinplus.sources.core.TmdbSearch
import com.wholphinplus.sources.core.TmdbSeason
import com.wholphinplus.sources.core.TmdbType
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Smart search, replacing Wholphin's search page when a TMDB key is set (otherwise
 * [fallback], Wholphin's own page, is shown). Titles on the main server open Wholphin's details
 * page via [onOpenLibraryItem]; others open a sheet that finds them on the extra servers.
 */
@OptIn(FlowPreview::class)
@Composable
fun SmartSearchPage(
    initialQuery: String,
    onOpenLibraryItem: (itemId: UUID, series: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val service = remember { EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java).searchService() }
    if (!service.enabled) return fallback()

    val query = rememberTextFieldState(initialQuery)
    var results by remember { mutableStateOf<TmdbSearch?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<TmdbItem?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(Unit) {
        snapshotFlow { query.text.toString().trim() }
            .debounce(350)
            .distinctUntilChanged()
            .collect { q ->
                if (q.length < 2) {
                    results = null
                    return@collect
                }
                loading = true
                error = null
                results =
                    try {
                        service.search(q)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        error = e.message
                        null
                    }
                loading = false
            }
    }

    Column(modifier = modifier.fillMaxSize().padding(start = 24.dp, top = 16.dp, end = 16.dp)) {
        BasicTextField(
            state = query,
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorator = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth(0.6f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    if (query.text.isEmpty()) {
                        Text(
                            "Search movies, shows, people — or \"best horror movies\", \"movies like Alien\"",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.focusRequester(focus),
        )
        when {
            loading && results == null -> Text("Searching…", modifier = Modifier.padding(top = 16.dp))
            error != null -> Text("Search failed: $error", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
        }
        val r = results
        if (r != null) {
            val rows =
                buildList {
                    if (r.interpretation != null) {
                        add(r.interpretation to r.items)
                    } else {
                        add("Movies" to r.items.filter { it.type == TmdbType.MOVIE })
                        add("TV Shows" to r.items.filter { it.type == TmdbType.TV })
                        r.people.forEach { add(it.name to it.knownFor) }
                    }
                }.filter { it.second.isNotEmpty() }
            if (rows.isEmpty()) Text("No results", modifier = Modifier.padding(top = 16.dp))
            Text(
                TMDB_NOTICE,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 10.dp),
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp),
            ) {
                items(rows, key = { it.first }) { (title, items) ->
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 10.dp, bottom = 2.dp))
                        // Padding on all sides so a focused (scaled, outlined) card is never clipped
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(start = 10.dp, end = 32.dp, top = 8.dp, bottom = 8.dp),
                        ) {
                            items(items, key = { it.key }) { item ->
                                ResultCard(item, service) { availability ->
                                    when (availability) {
                                        is Availability.Library -> onOpenLibraryItem(availability.itemId, availability.series)
                                        else -> opened = item
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    opened?.let { TitleSheet(it, service, onDismiss = { opened = null }) }
}

@Composable
private fun ResultCard(
    item: TmdbItem,
    service: SearchService,
    onOpen: (Availability?) -> Unit,
) {
    val availability by produceState<Availability?>(null, item.key) { value = service.availability(item) }
    val inLibrary = availability is Availability.Library
    // Same focus look as the source list: accent outline and a slight lift, no white glow
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = { onOpen(availability) },
        shape = CardDefaults.shape(shape),
        border =
            CardDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), shape = shape),
            ),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        glow = CardDefaults.glow(),
        colors = CardDefaults.colors(containerColor = Color(0xFF1E1E2A), focusedContainerColor = Color(0xFF1E1E2A)),
        modifier = Modifier.width(140.dp),
    ) {
        Box {
            AsyncImage(
                model = item.posterUrl(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .alpha(if (availability == null || inLibrary) 1f else 0.6f),
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .padding(8.dp),
            ) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White, style = MaterialTheme.typography.labelLarge)
                Text(
                    listOfNotNull(item.year?.toString(), if (item.type == TmdbType.TV) "Series" else null).joinToString(" · "),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    when (availability) {
                        null -> "…"
                        is Availability.Library -> "▶ In your library"
                        else -> "Search other servers"
                    },
                    color = if (inLibrary) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * A title that isn't on the main server: find it on the extra servers and play it there. The
 * copies show in the same "Choose a copy" screen as everywhere else (this was an older list of
 * its own); a show picks its episode first.
 */
@Composable
internal fun TitleSheet(
    item: TmdbItem,
    service: SearchService,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var seasons by remember { mutableStateOf<List<TmdbSeason>?>(null) }
    var season by remember { mutableStateOf<TmdbSeason?>(null) }
    var episodes by remember { mutableStateOf<List<TmdbEpisode>?>(null) }
    var episode by remember { mutableStateOf<TmdbEpisode?>(null) }
    var picker by remember { mutableStateOf<PickerUi?>(null) }
    val firstFocus = remember { FocusRequester() }

    // One lookup at a time: a slow one for an earlier episode used to land under a later pick's
    // header, and OK then played the earlier episode
    var lookup by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun play(
        src: ExternalSource,
        ep: TmdbEpisode?,
    ) {
        val hook = EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java).sourceHook()
        val connection = hook.store.connections.value.firstOrNull { it.connectionId == src.connectionId }
        if (connection == null) {
            Toast.makeText(context, "That server was removed", Toast.LENGTH_SHORT).show()
        } else {
            val title = ep?.let { "${item.title} S%02dE%02d".format(it.season, it.number) } ?: item.title
            ExternalPlayerActivity.start(context, ExternalPlayerActivity.Request(src, connection, hook.client, title))
        }
    }

    fun closePicker() {
        lookup?.cancel()
        picker = null
        if (item.type == TmdbType.MOVIE) onDismiss() else episode = null
    }

    fun findSources(ep: TmdbEpisode?) {
        lookup?.cancel()
        val title = ep?.let { "${item.title} · S%02dE%02d %s".format(it.season, it.number, it.name) } ?: (item.title + (item.year?.let { " ($it)" } ?: ""))
        val base =
            PickerUi(
                title = title,
                rows = emptyList(),
                searching = true,
                serversTotal = 0,
                serversDone = 0,
                onSelect = { play(it, ep) },
                onCancel = ::closePicker,
                backdrop = item.backdropUrl(),
            )
        picker = base
        lookup =
            scope.launch {
                val found =
                    runCatching {
                        service.sourcesFor(item, ep?.season, ep?.number) { rows, done, total, misses ->
                            scope.launch { if (episode == ep && picker != null) picker = picker?.copy(rows = rows, serversDone = done, serversTotal = total, misses = misses) }
                        }
                    }.getOrElse {
                        if (it is CancellationException) throw it
                        emptyList()
                    }
                if (episode != ep) return@launch
                picker = picker?.copy(rows = found, searching = false)
            }
    }

    LaunchedEffect(item.key) {
        if (item.type == TmdbType.MOVIE) {
            findSources(null)
        } else {
            seasons = withContext(Dispatchers.IO) { runCatching { service.tmdb.seasons(item.id) }.getOrDefault(emptyList()) }
            season = seasons?.firstOrNull { it.number > 0 } ?: seasons?.firstOrNull()
        }
    }
    LaunchedEffect(season) {
        val s = season ?: return@LaunchedEffect
        episodes = null
        episode = null
        episodes = withContext(Dispatchers.IO) { runCatching { service.tmdb.episodes(item.id, s.number) }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(episodes, episode) { if (episode == null) runCatching { firstFocus.requestFocus() } }

    picker?.let {
        com.wholphinplus.sources.cinema.CinemaSourcePicker(it)
        return
    }
    if (item.type != TmdbType.TV) return

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))) {
            AsyncImage(
                model = item.backdropUrl(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.25f),
            )
            Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    item.title + (item.year?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(item.overview, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.7f))
                Text("Not on your main server", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)

                val seasonList = seasons
                if (seasonList != null) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasonList, key = { it.number }) { s ->
                            Button(onClick = { season = s }) { Text((if (s == season) "● " else "") + s.name) }
                        }
                    }
                    val list = episodes
                    when {
                        list == null -> Text("Loading episodes…")
                        list.isEmpty() -> Text("No episodes listed")
                        else ->
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(420.dp)) {
                                items(list, key = { it.number }) { ep ->
                                    PlusListItem(
                                        onClick = {
                                            episode = ep
                                            findSources(ep)
                                        },
                                        headlineContent = { Text("${ep.number}. ${ep.name}") },
                                        supportingContent = { Text(ep.overview, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        modifier = if (ep == list.first()) Modifier.focusRequester(firstFocus) else Modifier,
                                    )
                                }
                            }
                    }
                }
            }
        }
    }
}
