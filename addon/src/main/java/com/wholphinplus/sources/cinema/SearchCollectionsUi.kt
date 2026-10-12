package com.wholphinplus.sources.cinema

import kotlinx.coroutines.sync.withLock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import com.wholphinplus.sources.SourceHook
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/**
 * One collection in search results: its picture as a little stack of cards (a shelf, not one
 * title), the name under it like a title card's, then what it is and how many titles are here.
 */
@Composable
internal fun CollectionTile(
    hit: CollectionSearch.Hit,
    width: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    // Room above for the stack (the row clips what's outside its items)
    Column(Modifier.width(width).padding(top = 8.dp)) {
        Box {
            // Two cards peeking out above: the stack
            Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().aspectRatio(16f / 9f).offset(y = (-8).dp).clip(shape).background(Color.White.copy(alpha = 0.10f)))
            Box(Modifier.padding(horizontal = 7.dp).fillMaxWidth().aspectRatio(16f / 9f).offset(y = (-4).dp).clip(shape).background(Color.White.copy(alpha = 0.18f)))
            Card(
                onClick = onClick,
                shape = CardDefaults.shape(shape),
                border = CardDefaults.border(focusedBorder = Border.None),
                scale = CardDefaults.scale(focusedScale = 1f),
                glow = CardDefaults.glow(),
                colors = CardDefaults.colors(containerColor = Color(0xFF1F1F1F)),
                modifier = modifier.width(width).aspectRatio(16f / 9f).glideLift().tapToClick(onClick),
            ) {
                Box(Modifier.fillMaxSize()) {
                    hit.picture?.let { CardPicture(it, 416, 234, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                        ?: Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2B2734), Color(0xFF141318)))))
                    // A soft shade at the foot so the count reads on any picture
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
                    hit.have?.let { n ->
                        Text(
                            "$n",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 9.dp, start = 2.dp, end = 2.dp)) {
            Text(hit.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(kindLine(hit), color = InkDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** "List · 682 titles", "Film series · 4 films", "On your server". */
private fun kindLine(hit: CollectionSearch.Hit): String =
    when {
        hit.source == CollectionSearch.Source.SERIES && hit.have != null -> "Film series  ·  ${hit.have} ${if (hit.have == 1) "film" else "films"}"
        hit.have != null -> "${hit.source.label}  ·  ${hit.have} ${if (hit.have == 1) "title" else "titles"}"
        else -> hit.source.label
    }

/**
 * A collection opened from search: its titles that are in the library, as cards; an MDBList list
 * can be added as a Home row from here. Back closes it.
 */
@Composable
internal fun CollectionPage(
    hit: CollectionSearch.Hit,
    finder: CollectionSearch,
    hook: SourceHook,
    onOpen: (UUID, BaseItemKind) -> Unit,
    onClose: () -> Unit,
) {
    // A page at a time as you scroll: a big list (900 titles) built at once kept the server busy for seconds
    var titles by remember(hit.key) { mutableStateOf<List<CinemaItem>?>(null) }
    var asked by remember(hit.key) { mutableStateOf(0) }
    var more by remember(hit.key) { mutableStateOf(true) }
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // One page request at a time: the scroll's and the one that loads everything for a sort/filter
    val paging = remember(hit.key) { kotlinx.coroutines.sync.Mutex() }
    val nextPage: suspend () -> Unit = {
        paging.withLock {
            if (more) {
                val from = asked
                val page = finder.titles(hit, from, PAGE)
                asked = from + PAGE
                if (page.size < PAGE) more = false
                titles = (titles.orEmpty() + page).distinctBy { it.key }
            }
        }
    }
    val g =
        rememberGridFiltering(
            titles.orEmpty(),
            "collection:" + hit.key,
            loadAll = if (more) ({ while (more && titles.orEmpty().size < GRID_ALL_CAP) nextPage() }) else null,
        )
    LaunchedEffect(hit.key) {
        androidx.compose.runtime.snapshotFlow {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            // Wants more: the first page, or within two rows of the end. Keyed on the size too, so
            // a page that lands with the end still in view asks for the next one
            val size = titles?.size ?: -1
            if (titles == null || last >= size - 8) size else Int.MIN_VALUE
        }.collect { wants ->
            if (wants == Int.MIN_VALUE || !more) return@collect
            nextPage()
        }
    }
    val lists by hook.collections.lists.collectAsState()
    // Saved (a Home row, on or off) under its link: a list, a film series or the server's collection
    val saveUrl = hit.saveUrl
    val savedAs = saveUrl?.let { u -> lists.firstOrNull { it.url.trimEnd('/') == u.trimEnd('/') } }
    val save = { onHome: Boolean ->
        com.wholphinplus.sources.core.ListSource.parse(saveUrl.orEmpty())?.let { source ->
            val c = hook.collections.newCollection(source.url).copy(name = hit.name, showOnHome = onHome, picture = hit.picture)
            hook.collections.add(c)
            hook.collections.refreshStale(hook, maxAgeMs = -1, onlyId = c.id)
            CinemaCaches.homeChanged()
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(titles != null) { runCatching { first.requestFocus() } }
    androidx.activity.compose.BackHandler { onClose() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Stage)
            .onPreviewKeyEvent { e ->
                // Android 11 sends Back as a key (the BackHandler covers 13+)
                if (e.key == Key.Back || e.key == Key.Escape) {
                    if (e.type == KeyEventType.KeyUp) onClose()
                    true
                } else {
                    false
                }
            },
    ) {
        hit.picture?.let { StableBackdrop(it.replace("/w500/", "/w1280/"), drift = false, widthFraction = 0.78f, heightFraction = 0.62f) }
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(start = 58.dp, end = 58.dp, top = 56.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        (if (hit.source == CollectionSearch.Source.LIST) "Collection" else hit.source.label).uppercase(),
                        color = Plus,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    )
                    Text(hit.name, color = Ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(720.dp))
                    val shown = titles
                    // The count the tile showed (the index's), not how many pages have loaded
                    val have = hit.have ?: shown?.size
                    Text(
                        when {
                            shown == null -> "Finding its titles in your library…"
                            shown.isEmpty() -> "None of its titles are in your library yet."
                            g.filter.active -> g.countLabel(have ?: shown.size)
                            hit.total != null && have != null -> "$have of ${hit.total} titles in your library"
                            else -> "${have ?: shown.size} titles"
                        },
                        color = InkDim,
                        fontSize = 15.sp,
                    )
                    if (saveUrl != null) {
                        // Save keeps it in My List → Saved collections (and the Rows editor); Add to
                        // Home also puts it on Home as a row. Both again: undone
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HeroButton(
                                if (savedAs != null) "Saved" else "Save collection",
                                if (savedAs != null) Icons.Filled.Check else Icons.Filled.Add,
                                primary = savedAs == null,
                                modifier = Modifier.focusRequester(first),
                            ) {
                                if (savedAs != null) {
                                    hook.collections.remove(savedAs.id)
                                    CinemaCaches.homeChanged()
                                } else {
                                    save(false)
                                }
                            }
                            val onHome = savedAs?.showOnHome == true
                            HeroButton(
                                if (onHome) "On your Home" else "Add to Home",
                                if (onHome) Icons.Filled.Check else Icons.Filled.Add,
                                primary = false,
                            ) {
                                when {
                                    savedAs == null -> save(true)
                                    else -> {
                                        hook.collections.update(savedAs.copy(showOnHome = !onHome))
                                        CinemaCaches.homeChanged()
                                    }
                                }
                            }
                        }
                    }
                    if (!shown.isNullOrEmpty()) GridFilterBar(g, shown, Modifier.padding(top = 4.dp))
                    if (!shown.isNullOrEmpty() && g.shown.isEmpty()) Text("No titles match these filters.", color = InkDim, fontSize = 15.sp)
                }
            }
            val shown = g.shown
            itemsIndexed(shown, key = { _, it -> it.key }) { i, item ->
                CinemaCard(
                    item,
                    onFocused = {},
                    onClick = {
                        DetailsPreview.put(it)
                        onOpen(it.detailsId, it.detailsKind)
                    },
                    width = 200.dp,
                    modifier = if (i == 0 && saveUrl == null) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
        BottomFade()
    }
}

/** Titles loaded per page on a collection's page. */
private const val PAGE = 40
