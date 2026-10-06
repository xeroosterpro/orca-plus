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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRestorer
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
    @Suppress("NAME_SHADOWING") val onPlay = guarded(onPlay)
    @Suppress("NAME_SHADOWING") val onOpen = guarded(onOpen)
    val context = LocalContext.current
    val entry = remember { EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java) }
    val hook = remember { entry.sourceHook() }
    val art = remember { entry.cinemaArt() }
    val search = remember { entry.searchService() }
    val repo = remember { CinemaRepository(hook, hook.collections) }
    val overlays by hook.store.overlays.collectAsState()
    val ratingPrefs by hook.store.ratingPrefs.collectAsState()
    val ratings = remember { entry.ratings() }
    remember { StreamCache.attach(context) }
    DisposableEffect(Unit) { onDispose { art.save() } }

    // Back from playback shows the page at once; the Play button's position refreshes quietly
    // ...or, opened from a card, draws from what the card knew while the rest loads
    var data by remember(itemId) { mutableStateOf(DetailsCache[itemId] ?: DetailsPreview[itemId]?.let { preview(it) }) }
    var error by remember(itemId) { mutableStateOf<String?>(null) }
    var attempt by remember(itemId) { mutableIntStateOf(0) }
    LaunchedEffect(itemId, attempt) {
        error = null
        // A failed load retries quietly before giving up; leaving the page isn't a failure
        var tries = 0
        while (true) {
            try {
                val loaded = repo.details(itemId, kind)
                // More Like This is loaded on its own (below); a refresh keeps the one already found
                val known = (DetailsCache[itemId] ?: data)?.similar.orEmpty()
                val page = loaded.copy(similar = known)
                DetailsCache[itemId] = page
                data = page
                if (known.isEmpty()) {
                    launch {
                        val similar =
                            try {
                                repo.moreLikeThis(page.item, search.tmdb)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                timber.log.Timber.w(e, "More Like This failed for %s", itemId)
                                return@launch
                            }
                        data = data?.copy(similar = similar)?.also { DetailsCache[itemId] = it }
                        // Their badges in one request
                        if (overlays.needsStreams) StreamCache.prefetch(similar.filter { s -> s.kind != BaseItemKind.SERIES }.map { s -> s.id }, repo::streamTagsBatch)
                    }
                }
                break
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                timber.log.Timber.w(e, "Cinema title page %s failed to load", itemId)
                if (++tries >= LOAD_TRIES) {
                    error = e.message?.takeIf { m -> m.isNotBlank() } ?: "Couldn't load this title"
                    break
                }
                delay(1_500L * tries)
            }
        }
    }

    Box(
        modifier.fillMaxSize().background(Stage).onPreviewKeyEvent {
            Conductor.touch()
            false
        },
    ) {
        val d = data
        val failed = error
        when {
            // A card's preview of a show can't play (its episode never came): show the error
            failed != null && (d == null || d.play?.pending == true) ->
                LoadError(failed, onRetry = { attempt++ }, onClassic = null, modifier = Modifier.align(Alignment.Center))
            d != null -> CompositionLocalProvider(LocalArt provides art, LocalOverlays provides overlays, LocalStreamLookup provides StreamLookup(repo::streamTagsOf), LocalRatingPrefs provides ratingPrefs, LocalRatings provides ratings) { DetailsScreen(d, repo, onPlay, onOpen) }
        }
    }
}

@Stable
private class DetailsFocus {
    /** True while focus is in the top section, which pins the list to the top. */
    var hero by mutableStateOf(true)

    /**
     * True while focus is in the episodes: the page rests with their heading at the top (it
     * moved to each card before, scrolling the heading away) and More Like This dims below.
     */
    var episodes by mutableStateOf(false)
}

/** Where the Episodes heading rests while you browse episodes. */
private val EpisodesTop = 36.dp

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
    val currentEp = remember { FocusRequester() }
    val art = rememberArt(item)

    // Rows settle 64dp below the top so their heading stays in view; the hero never moves.
    // The page can't scroll that far for its last row (More Like This): aimed at the
    // unreachable spot, the quick-start ease used up the whole ~40dp that's left in the first
    // frame, a snap. So the page aims only as far as it can go, and that glides.
    val rowSpec = remember(density) { pivot(with(density) { 64.dp.toPx() }) }
    val pageSpec =
        remember(rowSpec, list) {
            object : BringIntoViewSpec {
                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override val scrollAnimationSpec = rowSpec.scrollAnimationSpec

                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float,
                ): Float {
                    // The top section and the episodes each have one resting place (set when focus
                    // enters them), so moving inside them never scrolls the page
                    if (focus.hero || focus.episodes) return 0f
                    val wanted = rowSpec.calculateScrollDistance(offset, size, containerSize)
                    return if (wanted > 0f) wanted.coerceAtMost(scrollLeft(list)) else wanted
                }
            }
        }

    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    // Entrance: the whole page rises a touch and fades in, once
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(480, easing = CinemaEase)) }

    // Keyed on the facts, not the whole page: More Like This arriving must not undo a tick
    var favorite by remember(d.item.id, d.favorite) { mutableStateOf(d.favorite) }

    Box(Modifier.fillMaxSize()) {
        StableBackdrop(item.backdropUrl ?: art?.cleanBackdrop?.let { "https://image.tmdb.org/t/p/w1280$it" }, drift = true, widthFraction = 0.78f, heightFraction = 0.9f)
        // Below the hero the art sinks back so episode text stays readable
        val dim by animateFloatAsState(if (focus.hero) 0f else 0.78f, tween(420, easing = CinemaEase), label = "dim")
        // Scrolled below the top, the title block fades away instead of being sliced by the
        // screen edge
        val heroAlpha = animateFloatAsState(if (focus.hero) 1f else 0f, tween(320, easing = CinemaEase), label = "heroAlpha")
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim }.background(Stage))
        CompositionLocalProvider(LocalBringIntoViewSpec provides pageSpec) {
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
                    // At least 90% of the screen (the episodes peek in below), taller when it has
                    // to be: a show with a progress line and a long overview got its "13m left"
                    // cut in half by the Episodes heading at a fixed 90%
                    val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
                    Column(
                        Modifier
                            .heightIn(min = screen * 0.9f)
                            .graphicsLayer { alpha = heroAlpha.value }
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
                                DetailsCache.remove(item.id)
                                scope.launch {
                                    // Didn't save: put the tick back the way the server has it
                                    runCatching { repo.setFavorite(item.id, now) }.onFailure {
                                        if (it is kotlinx.coroutines.CancellationException) throw it
                                        timber.log.Timber.w(it, "My List change failed for %s", item.id)
                                        if (favorite == now) favorite = !now
                                    }
                                }
                            },
                            // Straight to the episode you're up to; else the season tabs
                            onEpisodes = {
                                if (!runCatching { currentEp.requestFocus() }.getOrDefault(false)) runCatching { episodesFocus.requestFocus() }
                            },
                        )
                    }
                }
                if (d.series && d.seasons.isNotEmpty()) {
                    item(key = "episodes") {
                        Box(
                            Modifier.onFocusChanged {
                                if (it.hasFocus && !focus.episodes) {
                                    focus.hero = false
                                    focus.episodes = true
                                    // Settle once: the Episodes heading at the top, whatever got focus
                                    scope.launch {
                                        val section = list.layoutInfo.visibleItemsInfo.firstOrNull { i -> i.key == "episodes" }
                                        if (section == null) {
                                            list.scrollToItem(1)
                                        } else {
                                            val by = (section.offset - with(density) { EpisodesTop.toPx() }).coerceAtMost(scrollLeft(list))
                                            list.animateScrollBy(by, tween(360, easing = CinemaEase))
                                        }
                                    }
                                } else if (!it.hasFocus) {
                                    focus.episodes = false
                                }
                            },
                        ) {
                            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) { Episodes(item.id, d, repo, episodesFocus, currentEp, onPlay) }
                        }
                    }
                }
                if (d.similar.isNotEmpty()) {
                    item(key = "similar") {
                        // Below the episodes it waits lower and dimmed (just its top showing), and
                        // lights up when you come down to it
                        val dimmed by animateFloatAsState(if (focus.episodes) 0.35f else 1f, tween(320, easing = CinemaEase), label = "similarDim")
                        Column(
                            Modifier
                                .padding(top = if (d.series && d.seasons.isNotEmpty()) 44.dp else 18.dp)
                                .graphicsLayer { alpha = dimmed }
                                .onFocusChanged { if (it.hasFocus) focus.hero = false },
                        ) {
                            SectionTitle("More Like This")
                            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
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
    // The tag sits right on top of the logo, whatever its shape: in a fixed box a wide, short
    // logo (WALL·E) sank to the bottom and left a big gap under the tag. The block keeps one
    // height, so the text below never moves; the tag shows with the logo, so it doesn't jump.
    var logoShown by remember(logo) { mutableStateOf(logo == null) }
    // The logo's shape once it's loaded: it then fills 460 x 120 dp as far as its shape allows
    // (before, the full box, so it loads at that size)
    var logoRatio by remember(logo) { mutableStateOf<Float?>(null) }
    Box(Modifier.height(146.dp).widthIn(max = 460.dp), contentAlignment = Alignment.BottomStart) {
        Column {
            KindTag(item.kind, Modifier.graphicsLayer { alpha = if (logoShown) 1f else 0f })
            Spacer(Modifier.height(8.dp))
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    onState = { state ->
                        logoShown = state !is coil3.compose.AsyncImagePainter.State.Loading && state !is coil3.compose.AsyncImagePainter.State.Empty
                        if (state is coil3.compose.AsyncImagePainter.State.Success) {
                            val image = state.result.image
                            if (image.width > 0 && image.height > 0) logoRatio = image.width.toFloat() / image.height
                        }
                    },
                    modifier =
                        logoRatio?.let { Modifier.widthIn(max = 460.dp).heightIn(max = 120.dp).aspectRatio(it) }
                            ?: Modifier.width(460.dp).height(120.dp),
                )
            } else {
                Text(item.title, color = Ink, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, lineHeight = 46.sp, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    MetaLine(Meta(item.meta, item.rating, d.quality))
    // Always takes its line, so the overview doesn't jump when the genres arrive
    Spacer(Modifier.height(6.dp))
    Text(d.genres.joinToString("  •  ").ifEmpty { " " }, color = InkDim, fontSize = 13.sp, maxLines = 1)
    // Review scores (Settings → Orca+ → Ratings); takes no room when there are none
    Box(Modifier.padding(top = 10.dp)) { TitleRatings(item) }
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

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun Episodes(
    seriesId: UUID,
    d: CinemaDetailsData,
    repo: CinemaRepository,
    episodesFocus: FocusRequester,
    currentEp: FocusRequester,
    onPlay: (UUID, Long) -> Unit,
) {
    // The episode you're up to (what Play resumes or starts): its season opens scrolled to it
    val upTo = d.play?.id
    val start = d.seasons.indexOfFirst { it.number == d.startSeason }.coerceAtLeast(0)
    // Keyed on the seasons, not the whole page: More Like This arriving later must not reset
    // the season you picked
    var selected by remember(d.seasons, start) { mutableStateOf(start) }
    var focusedTab by remember(d.seasons, start) { mutableStateOf(start) }
    val loaded = remember(seriesId) { mutableStateMapOf<UUID, List<CinemaEpisode>>() }

    // A season loads once you rest on its tab, so sweeping across tabs doesn't fire requests
    LaunchedEffect(d.seasons, start) {
        snapshotFlow { focusedTab }.collectLatest { i ->
            if (i != selected) delay(260)
            selected = i
        }
    }
    LaunchedEffect(selected) {
        // An empty answer is usually a failed request: not kept, so the season asks again next time
        suspend fun load(id: UUID) {
            if (id !in loaded) repo.episodes(seriesId, id).takeIf { it.isNotEmpty() }?.let { loaded[id] = it }
        }
        load(d.seasons[selected].id)
        // Warm the neighbour so the next tab is instant
        d.seasons.getOrNull(selected + 1)?.let { load(it.id) }
    }

    Column(Modifier.padding(top = 8.dp)) {
        SectionTitle("Episodes")
        if (d.seasons.size > 1) {
            LazyRow(
                // Entering the tabs (Down from Resume) lands on the season you're on, not on
                // whichever tab happens to sit under the button
                modifier = Modifier.focusRestorer(episodesFocus),
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
            val current = eps?.indexOfFirst { it.id == upTo }?.takeIf { it >= 0 }
            val row = rememberLazyListState()
            // Opens at your episode instead of episode 1
            LaunchedEffect(current) { if (current != null) row.scrollToItem(current) }
            LazyRow(
                state = row,
                contentPadding = PaddingValues(horizontal = 58.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                // Down from the season tabs lands on your episode
                modifier = Modifier.height(300.dp).focusRestorer(if (current != null) currentEp else FocusRequester.Default),
            ) {
                if (eps != null) {
                    itemsIndexed(eps, key = { _, e -> e.id }) { i, e ->
                        EpisodeCard(
                            e,
                            modifier =
                                Modifier
                                    .then(if (i == current) Modifier.focusRequester(currentEp) else Modifier)
                                    .then(if (d.seasons.size == 1 && i == (current ?: 0)) Modifier.focusRequester(episodesFocus) else Modifier),
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
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() }.tapToClick(onFocused),
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
    Column(Modifier.width(340.dp)) {
        Card(
            onClick = onClick,
            shape = CardDefaults.shape(shape),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Ink), shape = shape)),
            scale = CardDefaults.scale(focusedScale = 1.05f),
            colors = CardDefaults.colors(containerColor = Color(0xFF1F1F1F)),
            modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).tapToClick(onClick),
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

/**
 * How much further down [list] can scroll, in px (its last item is in view, so the end is
 * known); unlimited while the end is still off screen.
 */
private fun scrollLeft(list: androidx.compose.foundation.lazy.LazyListState): Float {
    val info = list.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return Float.MAX_VALUE
    if (last.index != info.totalItemsCount - 1) return Float.MAX_VALUE
    return (last.offset + last.size + info.afterContentPadding - info.viewportEndOffset).coerceAtLeast(0).toFloat()
}

/** Recently opened titles, so Back from the player (or a re-open) draws instantly. */
/** Drops cached title pages (MemoryTrim); they reload when opened. */
internal fun trimDetails() = DetailsCache.clear()

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

    @Synchronized fun clear() = map.clear()
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
