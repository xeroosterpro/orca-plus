package com.wholphinplus.sources.cinema

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.wholphinplus.sources.ui.SourcesEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

/** Cinema-themed Movies or Shows library (genre rows + billboard), not Wholphin's grid. */
@Composable
fun CinemaLibrary(
    libraryId: UUID,
    collectionType: CollectionType,
    onOpen: (UUID, BaseItemKind) -> Unit,
    onPlay: (UUID, Long) -> Unit,
    onNavigate: (CinemaNav) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val entry = remember { EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java) }
    val hook = remember { entry.sourceHook() }
    val art = remember { entry.cinemaArt() }
    val repo = remember { CinemaRepository(hook, hook.collections) }
    DisposableEffect(Unit) { onDispose { art.save() } }

    val topTab =
        when (collectionType) {
            CollectionType.TVSHOWS -> CinemaTopTab.Shows
            CollectionType.MOVIES -> CinemaTopTab.Movies
            else -> CinemaTopTab.Home
        }

    var data by remember(libraryId) { mutableStateOf(LibraryCache[libraryId]) }
    var error by remember(libraryId) { mutableStateOf<String?>(null) }
    LaunchedEffect(libraryId, collectionType) {
        runCatching { repo.loadLibrary(libraryId, collectionType) }
            .onSuccess {
                LibraryCache[libraryId] = it
                data = it
                it.rows.forEach { row -> row.items.take(8).forEach { item -> item.tmdbId?.let { id -> launch { art.art(item.tmdbTv, id) } } } }
            }.onFailure { if (data == null) error = it.message ?: "Couldn't load this library" }
    }

    Box(modifier.fillMaxSize().background(Stage)) {
        val d = data
        when {
            d != null ->
                CompositionLocalProvider(LocalArt provides art) {
                    CinemaBrowseScreen(d, topTab, onOpen, onPlay, onNavigate)
                }
            error != null ->
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = Ink)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { onNavigate(CinemaNav.Home) }) { Text("Back to Home") }
                }
            else -> Wordmark(Modifier.align(Alignment.Center), size = 34)
        }
    }
}

private object LibraryCache {
    private val map = object : LinkedHashMap<UUID, CinemaHomeData>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, CinemaHomeData>?) = size > 3
    }

    @Synchronized operator fun get(id: UUID): CinemaHomeData? = map[id]

    @Synchronized operator fun set(
        id: UUID,
        data: CinemaHomeData,
    ) {
        map[id] = data
    }
}
