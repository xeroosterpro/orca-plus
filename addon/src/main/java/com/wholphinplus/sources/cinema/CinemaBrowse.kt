package com.wholphinplus.sources.cinema

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

/** Which top-nav tab is active on a Cinema browse screen. */
enum class CinemaTopTab {
    Home,
    Shows,
    Movies,
}

/** Movies / Shows libraries use the same Cinema shell instead of Wholphin's grid. */
fun isCinemaLibraryRoute(
    type: BaseItemKind,
    collectionType: CollectionType?,
): Boolean =
    (type == BaseItemKind.COLLECTION_FOLDER || type == BaseItemKind.FOLDER) &&
        (collectionType == CollectionType.TVSHOWS || collectionType == CollectionType.MOVIES)

@Stable
private class BrowseFocus {
    var billboard by mutableStateOf(true)
    var focused by mutableStateOf<CinemaItem?>(null)

    fun onCard(item: CinemaItem) {
        billboard = false
        focused = item
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CinemaBrowseScreen(
    data: CinemaHomeData,
    topTab: CinemaTopTab,
    onOpen: (UUID, BaseItemKind) -> Unit,
    onPlay: (UUID, Long) -> Unit,
    onNavigate: (CinemaNav) -> Unit,
) {
    val focus = remember { BrowseFocus() }
    var featuredIndex by remember { mutableIntStateOf(0) }
    val featured = data.featured.getOrNull(featuredIndex % data.featured.size.coerceAtLeast(1))
    val playFocus = remember { FocusRequester() }
    val columnState = rememberLazyListState()
    val scope = rememberCoroutineScope()

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
            CinemaTopNav(data, topTab, onNavigate, scrim = !focus.billboard)
            BrowseInfoPanel(
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
internal fun CinemaTopNav(
    data: CinemaHomeData,
    topTab: CinemaTopTab,
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
            NavPill("Home", selected = topTab == CinemaTopTab.Home) {
                if (topTab != CinemaTopTab.Home) onNavigate(CinemaNav.Home)
            }
            data.shows?.let { lib ->
                NavPill("Shows", selected = topTab == CinemaTopTab.Shows) {
                    if (topTab != CinemaTopTab.Shows) {
                        onNavigate(CinemaNav.Library(lib.id, lib.kind, lib.collectionType))
                    }
                }
            }
            data.movies?.let { lib ->
                NavPill("Movies", selected = topTab == CinemaTopTab.Movies) {
                    if (topTab != CinemaTopTab.Movies) {
                        onNavigate(CinemaNav.Library(lib.id, lib.kind, lib.collectionType))
                    }
                }
            }
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

@Composable
private fun BrowseInfoPanel(
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
internal fun CinemaRowView(
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
