package com.wholphinplus.sources.cinema

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.wholphinplus.sources.ui.SourcesEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/**
 * Cinema mode's title page: full-bleed art, logo, one big Play that knows where you are, and
 * below it the seasons and episodes, then More Like This.
 */
@Composable
fun CinemaDetails(
    itemId: UUID,
    kind: BaseItemKind,
    onPlay: (UUID, Long) -> Unit,
    onOpen: (UUID, BaseItemKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val entry = remember { EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java) }
    val hook = remember { entry.sourceHook() }
    val art = remember { entry.cinemaArt() }
    val repo = remember { CinemaRepository(hook, hook.collections) }
    val overlays by hook.store.overlays.collectAsState()
    remember { StreamCache.attach(context) }
    DisposableEffect(Unit) { onDispose { art.save() } }

    // Back from playback shows the page at once; the Play button's position refreshes quietly
    // ...or, opened from a card, draws from what the card knew while the rest loads
    var data by remember(itemId) { mutableStateOf(DetailsCache[itemId] ?: DetailsPreview[itemId]?.let { preview(it) }) }
    var error by remember(itemId) { mutableStateOf<String?>(null) }
    LaunchedEffect(itemId) {
        runCatching { repo.details(itemId, kind) }
            .onSuccess {
                DetailsCache[itemId] = it
                data = it
                // More Like This badges in one request
                if (overlays.needsStreams) launch { StreamCache.prefetch(it.similar.filter { s -> s.kind != BaseItemKind.SERIES }.map { s -> s.id }, repo::streamTagsBatch) }
            }.onFailure { if (data == null) error = it.message ?: "Couldn't load this title" }
    }

    Box(modifier.fillMaxSize().background(Stage)) {
        val d = data
        when {
            d != null -> CompositionLocalProvider(LocalArt provides art, LocalOverlays provides overlays, LocalStreamLookup provides StreamLookup(repo::streamTagsOf)) { DetailsScreen(d, repo, onPlay, onOpen) }
            error != null -> Text(error!!, color = Ink, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Stable
private class DetailsFocus {
    /** True while focus is in the top section, which pins the list to the top. */
    var hero by mutableStateOf(true)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailsScreen(
    d: CinemaDetailsData,
    repo: CinemaRepository,
    onPlay: (UUID, Long) -> Unit,
    onOpen: (UUID, BaseItemKind) -> Unit,
) {
    val item = d.item
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focus = remember { DetailsFocus() }
    val list = rememberLazyListState()
    val playFocus = remember { FocusRequester() }
    val episodesFocus = remember { FocusRequester() }
    val art = rememberArt(item)

    // Rows settle 64dp below the top so their heading stays in view; the hero never moves
    val rowsSpec =
        remember(density) {
            val base = pivot(with(density) { 64.dp.toPx() })
            object : BringIntoViewSpec {
                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override val scrollAnimationSpec = base.scrollAnimationSpec

                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float,
                ): Float = if (focus.hero) 0f else base.calculateScrollDistance(offset, size, containerSize)
            }
        }

    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    // Entrance: the whole page rises a touch and fades in, once
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(480, easing = CinemaEase)) }

    var favorite by remember(d) { mutableStateOf(d.favorite) }

    Box(Modifier.fillMaxSize()) {
        StableBackdrop(item.backdropUrl ?: art?.cleanBackdrop?.let { "https://image.tmdb.org/t/p/w1280$it" }, drift = true, widthFraction = 0.78f, heightFraction = 0.9f)
        // Below the hero the art sinks back so episode text stays readable
        val dim by animateFloatAsState(if (focus.hero) 0f else 0.78f, tween(420, easing = CinemaEase), label = "dim")
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim }.background(Stage))
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowsSpec) {
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(bottom = 80.dp),
                modifier =
                    Modifier.fillMaxSize().graphicsLayer {
                        alpha = enter.value
                        translationY = (1f - enter.value) * 28.dp.toPx()
                    },
            ) {
                item(key = "hero") {
                    Column(
                        Modifier
                            .fillParentMaxHeight(0.9f)
                            .padding(start = 58.dp, top = 56.dp, end = 58.dp)
                            .onFocusChanged {
                                if (it.hasFocus && !focus.hero) {
                                    focus.hero = true
                                    scope.launch {
                                        if (list.firstVisibleItemIndex == 0) {
                                            list.animateScrollBy(-list.firstVisibleItemScrollOffset.toFloat(), tween(320, easing = CinemaEase))
                                        } else {
                                            list.scrollToItem(0)
                                        }
                                    }
                                }
                            },
                    ) {
                        Hero(d, art?.logoUrl() ?: item.logoUrl, favorite, playFocus,
                            onPlay = onPlay,
                            onFavorite = {
                                favorite = !favorite
                                val now = favorite
                                scope.launch { runCatching { repo.setFavorite(item.id, now) } }
                                DetailsCache.remove(item.id)
                            },
                            onEpisodes = { runCatching { episodesFocus.requestFocus() } },
                        )
                    }
                }
                if (d.series && d.seasons.isNotEmpty()) {
                    item(key = "episodes") {
                        Box(Modifier.onFocusChanged { if (it.hasFocus) focus.hero = false }) {
                            Episodes(item.id, d, repo, episodesFocus, onPlay)
                        }
                    }
                }
                if (d.similar.isNotEmpty()) {
                    item(key = "similar") {
                        Column(Modifier.padding(top = 18.dp).onFocusChanged { if (it.hasFocus) focus.hero = false }) {
                            SectionTitle("More Like This")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 58.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                items(d.similar, key = { it.key }) { s ->
                                    CinemaCard(
                                        s,
                                        onFocused = {},
                                        onClick = {
                                            DetailsPreview.put(it)
                                            onOpen(it.detailsId, it.detailsKind)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(
    d: CinemaDetailsData,
    logo: String?,
    favorite: Boolean,
    playFocus: FocusRequester,
    onPlay: (UUID, Long) -> Unit,
    onFavorite: () -> Unit,
    onEpisodes: () -> Unit,
) {
    val item = d.item
    KindTag(item.kind)
    Spacer(Modifier.height(6.dp))
    Box(Modifier.height(120.dp).widthIn(max = 460.dp), contentAlignment = Alignment.BottomStart) {
        if (logo != null) {
            AsyncImage(model = logo, contentDescription = item.title, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.fillMaxSize())
        } else {
            Text(item.title, color = Ink, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, lineHeight = 46.sp, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(18.dp))
    MetaLine(Meta(item.meta, item.rating, d.quality))
    // Always takes its line, so the overview doesn't jump when the genres arrive
    Spacer(Modifier.height(6.dp))
    Text(d.genres.joinToString("  •  ").ifEmpty { " " }, color = InkDim, fontSize = 13.sp, maxLines = 1)
    Spacer(Modifier.height(14.dp))
    Text(
        item.overview.trim().replace(Regex("\\s+"), " "),
        color = Ink,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 580.dp),
    )
    Spacer(Modifier.height(22.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        val play = d.play
        if (play != null) {
            HeroButton(play.label, Icons.Filled.PlayArrow, primary = true, modifier = Modifier.focusRequester(playFocus)) { if (!play.pending) onPlay(play.id, play.positionMs) }
            if (play.positionMs > 0) HeroButton("Restart", Icons.Filled.Refresh, primary = false) { onPlay(play.id, 0L) }
        }
        HeroButton(
            "My List",
            if (favorite) Icons.Filled.Check else Icons.Filled.Add,
            primary = false,
            modifier = if (play == null) Modifier.focusRequester(playFocus) else Modifier,
            onClick = onFavorite,
        )
        if (d.series && d.seasons.isNotEmpty()) HeroButton("Episodes", Icons.Filled.KeyboardArrowDown, primary = false, onClick = onEpisodes)
    }
    d.play?.let { p ->
        if (p.progress != null && p.progress > 0f) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressBar(p.progress, Modifier.width(220.dp), height = 4.dp)
                p.remaining?.let {
                    Spacer(Modifier.width(12.dp))
                    Text(it, color = InkDim, fontSize = 13.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    Credit("Starring", d.cast)
    Credit(d.makersLabel, d.makers)
}

@Composable
private fun Credit(
    label: String,
    names: List<String>,
) {
    if (names.isEmpty()) return
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = InkDim)) { append("$label: ") }
            append(names.joinToString(", "))
        },
        color = Ink,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 620.dp).padding(bottom = 4.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 58.dp))
}

@Composable
private fun Episodes(
    seriesId: UUID,
    d: CinemaDetailsData,
    repo: CinemaRepository,
    episodesFocus: FocusRequester,
    onPlay: (UUID, Long) -> Unit,
) {
    val start = d.seasons.indexOfFirst { it.number == d.startSeason }.coerceAtLeast(0)
    var selected by remember(d) { mutableStateOf(start) }
    var focusedTab by remember(d) { mutableStateOf(start) }
    val loaded = remember(seriesId) { mutableStateMapOf<UUID, List<CinemaEpisode>>() }

    // A season loads once you rest on its tab, so sweeping across tabs doesn't fire requests
    LaunchedEffect(d) {
        snapshotFlow { focusedTab }.collectLatest { i ->
            if (i != selected) delay(260)
            selected = i
        }
    }
    LaunchedEffect(selected) {
        val s = d.seasons[selected]
        if (s.id !in loaded) runCatching { repo.episodes(seriesId, s.id) }.onSuccess { loaded[s.id] = it }
        // Warm the neighbour so the next tab is instant
        d.seasons.getOrNull(selected + 1)?.let { n -> if (n.id !in loaded) runCatching { repo.episodes(seriesId, n.id) }.onSuccess { loaded[n.id] = it } }
    }

    Column(Modifier.padding(top = 8.dp)) {
        SectionTitle("Episodes")
        if (d.seasons.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 58.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(d.seasons.size) { i ->
                    SeasonTab(
                        d.seasons[i].name,
                        selected = i == selected,
                        modifier = if (i == start) Modifier.focusRequester(episodesFocus) else Modifier,
                        onFocused = { focusedTab = i },
                    )
                }
            }
        }
        val season = d.seasons[selected]
        AnimatedContent(
            targetState = season.id,
            transitionSpec = { fadeIn(tween(260, easing = CinemaEase)) togetherWith fadeOut(tween(160)) },
            label = "season",
        ) { id ->
            val eps = loaded[id]
            LazyRow(
                contentPadding = PaddingValues(horizontal = 58.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.height(268.dp),
            ) {
                if (eps != null) {
                    items(eps, key = { it.id }) { e ->
                        EpisodeCard(
                            e,
                            modifier = if (d.seasons.size == 1 && e === eps.first()) Modifier.focusRequester(episodesFocus) else Modifier,
                            onClick = { onPlay(e.id, e.resumeMs) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonTab(
    name: String,
    selected: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onFocused,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent,
                contentColor = if (selected) Ink else InkDim,
                focusedContainerColor = Ink,
                focusedContentColor = Stage,
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
    ) {
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp))
    }
}

@Composable
private fun EpisodeCard(
    e: CinemaEpisode,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.width(300.dp)) {
        Card(
            onClick = onClick,
            shape = CardDefaults.shape(shape),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Ink), shape = shape)),
            scale = CardDefaults.scale(focusedScale = 1.05f),
            colors = CardDefaults.colors(containerColor = Color(0xFF1F1F1F)),
            modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f),
        ) {
            Box(Modifier.fillMaxSize()) {
                e.stillUrl?.let { AsyncImage(model = request(context, it, 600, 338), contentDescription = e.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
                if (e.played) {
                    Text("Watched", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                e.progress?.let { if (!e.played) ProgressBar(it, Modifier.align(Alignment.BottomStart)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${e.number}. ${e.title}", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            e.runtime?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, color = InkDim, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(e.overview, color = InkDim, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Recently opened titles, so Back from the player (or a re-open) draws instantly. */
private object DetailsCache {
    private val map = object : LinkedHashMap<UUID, CinemaDetailsData>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, CinemaDetailsData>?) = size > 12
    }

    @Synchronized operator fun get(id: UUID): CinemaDetailsData? = map[id]

    @Synchronized operator fun set(
        id: UUID,
        d: CinemaDetailsData,
    ) {
        map[id] = d
    }

    @Synchronized fun remove(id: UUID) {
        map.remove(id)
    }
}

/** The page drawn from a card's item before the full details arrive. */
private fun preview(item: CinemaItem): CinemaDetailsData {
    val series = item.kind == BaseItemKind.SERIES
    return CinemaDetailsData(
        item = item,
        genres = emptyList(),
        cast = emptyList(),
        makers = emptyList(),
        makersLabel = "",
        quality = null,
        favorite = false,
        series = series,
        seasons = emptyList(),
        // A show's Play needs the episode you're up to, so it waits for the load
        play = PlayTarget(item.id, item.resumeMs, if (item.resumeMs > 0 && !series) "Resume" else "Play", if (series) null else item.progress, null, pending = series),
        similar = emptyList(),
        startSeason = null,
    )
}
