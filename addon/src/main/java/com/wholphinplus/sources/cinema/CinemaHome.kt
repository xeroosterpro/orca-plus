package com.wholphinplus.sources.cinema

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.wholphinplus.sources.ui.SourcesEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

/** Where Cinema mode's top menu can go; Wholphin maps these to its own screens. */
sealed interface CinemaNav {
    data class Library(
        val id: UUID,
        val kind: BaseItemKind,
        val collectionType: CollectionType?,
    ) : CinemaNav

    data object MyList : CinemaNav

    data object Search : CinemaNav

    data object Settings : CinemaNav
}

/** Is Cinema mode on? Read by Wholphin's navigation to show Cinema screens. */
@Composable
fun cinemaModeOn(): Boolean {
    val context = LocalContext.current
    val hook = remember { EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java).sourceHook() }
    return hook.store.cinemaMode.collectAsState().value
}

@Composable
fun CinemaHome(
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

    // Coming back from a details page shows the last home instantly; it refreshes quietly
    var data by remember { mutableStateOf(HomeCache.data) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (data != null && System.currentTimeMillis() - HomeCache.at < 2 * 60_000) return@LaunchedEffect
        runCatching { repo.load() }
            .onSuccess {
                HomeCache.data = it
                HomeCache.at = System.currentTimeMillis()
                if (data == null) data = it
                // Warm the title-art cache for what's on screen first
                it.rows.forEach { row -> row.items.take(8).forEach { item -> item.tmdbId?.let { id -> launch { art.art(item.tmdbTv, id) } } } }
            }.onFailure { if (data == null) error = it.message ?: "Couldn't load your library" }
    }

    Box(modifier.fillMaxSize().background(Stage)) {
        val d = data
        when {
            d != null -> CompositionLocalProvider(LocalArt provides art) { CinemaScreen(d, onOpen, onPlay, onNavigate) }
            error != null ->
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = Ink)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { hook.store.setCinemaMode(false) }) { Text("Use the classic home") }
                }
            else -> Wordmark(Modifier.align(Alignment.Center), size = 34)
        }
    }
}

/**
 * Focus state, kept out of the rows: cards only write it, so moving the remote recomposes the
 * top panel and backdrop but never the rows themselves.
 */
@Stable
private class HomeFocus {
    var billboard by mutableStateOf(true)
    var focused by mutableStateOf<CinemaItem?>(null)

    fun onCard(item: CinemaItem) {
        billboard = false
        focused = item
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CinemaScreen(
    data: CinemaHomeData,
    onOpen: (UUID, BaseItemKind) -> Unit,
    onPlay: (UUID, Long) -> Unit,
    onNavigate: (CinemaNav) -> Unit,
) {
    val focus = remember { HomeFocus() }
    var featuredIndex by remember { mutableIntStateOf(0) }
    val featured = data.featured.getOrNull(featuredIndex % data.featured.size.coerceAtLeast(1))
    val playFocus = remember { FocusRequester() }
    val columnState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // What the top shows. While you move along a row it waits for you to settle (220 ms), so
    // fast scrolling stays fluid instead of reloading art on every press.
    var shown by remember { mutableStateOf(featured) }
    LaunchedEffect(featured) {
        snapshotFlow { if (focus.billboard) featured else focus.focused ?: featured }.collectLatest { target ->
            if (target?.key != shown?.key) {
                if (!focus.billboard) delay(220)
                shown = target
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    LaunchedEffect(focus.billboard, featuredIndex, data.featured.size) {
        if (focus.billboard && data.featured.size > 1) {
            delay(10_000)
            featuredIndex = (featuredIndex + 1) % data.featured.size
        }
    }
    BackHandler(enabled = !focus.billboard) {
        focus.billboard = true
        focus.focused = null
        scope.launch { columnState.animateScrollToItem(0) }
        runCatching { playFocus.requestFocus() }
    }

    val onCard = remember(focus) { { item: CinemaItem -> focus.onCard(item) } }
    val onCardClick = remember(onOpen) { { item: CinemaItem -> onOpen(item.detailsId, item.detailsKind) } }
    val density = LocalDensity.current
    val rowSpec = remember(density) { pivot(with(density) { 40.dp.toPx() }) }
    val cardSpec = remember(density) { pivot(with(density) { 48.dp.toPx() }) }

    val rowDim by animateFloatAsState(if (focus.billboard) 0f else 0.42f, tween(420, easing = CinemaEase), label = "rowDim")

    Box(Modifier.fillMaxSize()) {
        StableBackdrop(shown?.backdropUrl, drift = focus.billboard)
        Box(Modifier.fillMaxSize().background(Stage.copy(alpha = rowDim)))
        Column(Modifier.fillMaxSize()) {
            TopNav(data, onNavigate, scrim = !focus.billboard)
            InfoPanel(
                item = shown,
                billboard = focus.billboard,
                playFocus = playFocus,
                onPlay = { shown?.let { onPlay(it.id, it.resumeMs) } },
                onMoreInfo = { shown?.let { onOpen(it.detailsId, it.detailsKind) } },
                onButtonsFocused = { focus.billboard = true },
            )
            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                LazyColumn(
                    state = columnState,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 220.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(data.rows, key = { it.title }, contentType = { "row" }) { row ->
                        CinemaRowView(row, cardSpec, onCard, onCardClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopNav(
    data: CinemaHomeData,
    onNavigate: (CinemaNav) -> Unit,
    scrim: Boolean = false,
) {
    Box(Modifier.fillMaxWidth()) {
        if (scrim) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Brush.verticalGradient(0f to Stage.copy(alpha = 0.92f), 0.65f to Stage.copy(alpha = 0.55f), 1f to Color.Transparent)),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 40.dp, top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Wordmark(Modifier.padding(end = 18.dp))
        NavPill("Home", selected = true) {}
        data.shows?.let { lib -> NavPill("Shows") { onNavigate(CinemaNav.Library(lib.id, lib.kind, lib.collectionType)) } }
        data.movies?.let { lib -> NavPill("Movies") { onNavigate(CinemaNav.Library(lib.id, lib.kind, lib.collectionType)) } }
        NavPill("My List") { onNavigate(CinemaNav.MyList) }
        Spacer(Modifier.weight(1f))
        NavIcon(Icons.Filled.Search, "Search") { onNavigate(CinemaNav.Search) }
        NavIcon(Icons.Filled.Settings, "Settings") { onNavigate(CinemaNav.Settings) }
        }
    }
}

@Composable
private fun NavPill(
    text: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent,
                contentColor = if (selected) Ink else InkDim,
                focusedContainerColor = Ink,
                focusedContentColor = Stage,
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Text(text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

@Composable
private fun NavIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = Ink, focusedContainerColor = Ink, focusedContentColor = Stage),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.padding(7.dp).size(20.dp))
    }
}

/**
 * Fixed-height panel: nothing inside it can change its size, so the rows below never move when
 * the focused title changes or the buttons fade out.
 */
@Composable
private fun InfoPanel(
    item: CinemaItem?,
    billboard: Boolean,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    onMoreInfo: () -> Unit,
    onButtonsFocused: () -> Unit,
) {
    Box(Modifier.padding(start = 48.dp, top = 16.dp).height(262.dp).widthIn(max = 480.dp)) {
        AnimatedContent(
            targetState = item,
            transitionSpec = { fadeIn(tween(380, delayMillis = 90, easing = CinemaEase)) togetherWith fadeOut(tween(160)) },
            contentKey = { it?.key },
            label = "info",
        ) { shownItem ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shownItem == null) return@Column
                val logo = rememberArt(shownItem)?.logoUrl() ?: shownItem.logoUrl
                Box(Modifier.height(88.dp).widthIn(max = 340.dp), contentAlignment = Alignment.BottomStart) {
                    if (logo != null) {
                        AsyncImage(model = logo, contentDescription = shownItem.title, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.fillMaxSize())
                    } else {
                        Text(shownItem.title, color = Ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 36.sp)
                    }
                }
                Text(shownItem.subtitle.orEmpty(), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MetaLine(Meta(shownItem.meta, shownItem.rating))
                Text(shownItem.overview, color = Ink.copy(alpha = 0.86f), fontSize = 14.sp, lineHeight = 20.sp, minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        AnimatedVisibility(
            visible = billboard,
            enter = fadeIn(tween(260, easing = CinemaEase)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroButton("Play", Icons.Filled.PlayArrow, primary = true, modifier = Modifier.focusRequester(playFocus), onFocused = onButtonsFocused, onClick = onPlay)
                HeroButton("More Info", Icons.Filled.Info, primary = false, onFocused = onButtonsFocused, onClick = onMoreInfo)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CinemaRowView(
    row: CinemaRow,
    cardSpec: BringIntoViewSpec,
    onItemFocused: (CinemaItem) -> Unit,
    onItemClick: (CinemaItem) -> Unit,
) {
    val wideRow = row.title == "Continue Watching"
    val cardWidth = if (wideRow) 292.dp else 208.dp
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(row.title, color = Ink, fontSize = if (wideRow) 20.sp else 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 48.dp))
        CompositionLocalProvider(LocalBringIntoViewSpec provides cardSpec) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(if (wideRow) 12.dp else 10.dp),
                contentPadding = PaddingValues(start = 48.dp, end = 400.dp, top = 8.dp, bottom = 8.dp),
            ) {
                items(row.items, key = { it.key }, contentType = { "card" }) { item ->
                    CinemaCard(item, onItemFocused, onItemClick, width = cardWidth)
                }
            }
        }
    }
}

@Composable
internal fun CinemaCard(
    item: CinemaItem,
    onFocused: (CinemaItem) -> Unit,
    onClick: (CinemaItem) -> Unit,
    width: Dp = 208.dp,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(6.dp)
    val art = rememberArt(item)
    val titled = art?.cardUrl()
    val url = titled ?: item.cardUrl
    Card(
        onClick = { onClick(item) },
        shape = CardDefaults.shape(shape),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Ink), shape = shape)),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        glow = CardDefaults.glow(),
        colors = CardDefaults.colors(containerColor = Color(0xFF1F1F1F)),
        modifier = Modifier.width(width).aspectRatio(16f / 9f).onFocusChanged { if (it.isFocused) onFocused(item) },
    ) {
        Box(Modifier.fillMaxSize()) {
            if (url != null) {
                val w = width.value.toInt().coerceAtLeast(208)
                val h = (w * 9 / 16).coerceAtLeast(117)
                AsyncImage(model = request(context, url, w, h), contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (titled == null && !item.cardHasTitleArt) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
                val logo = art?.logoUrl() ?: item.logoUrl
                val bottom = if (item.badge != null) 22.dp else 10.dp
                if (logo != null) {
                    AsyncImage(
                        model = logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = bottom).height(34.dp).widthIn(max = 150.dp),
                    )
                } else {
                    Text(
                        item.title,
                        color = Ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = bottom),
                    )
                }
            }
            item.badge?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomCenter).background(Label, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            item.progress?.let { p -> ProgressBar(p, Modifier.align(Alignment.BottomStart)) }
        }
    }
}

/** The last Cinema home, kept for the life of the app so returning to it is instant. */
private object HomeCache {
    @Volatile var data: CinemaHomeData? = null

    @Volatile var at: Long = 0L
}
