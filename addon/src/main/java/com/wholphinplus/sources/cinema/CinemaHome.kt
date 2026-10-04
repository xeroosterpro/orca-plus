package com.wholphinplus.sources.cinema

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.lerp
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.foundation.layout.offset
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
import kotlinx.coroutines.flow.first
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
    remember { StreamCache.attach(context) }

    // The tab you were on survives a trip to a details page and back
    var tab by rememberSaveable { mutableStateOf(CinemaTab.HOME) }
    // Each tab shows its last load instantly and refreshes quietly when stale
    val pages = remember { mutableStateMapOf<CinemaTab, CinemaHomeData>().apply { putAll(TabCache.data) } }
    var error by remember { mutableStateOf<String?>(null) }
    var grabFocus by remember { mutableStateOf(true) }
    val rollUp by hook.store.cinemaRollUp.collectAsState()
    val overlays by hook.store.overlays.collectAsState()
    LaunchedEffect(tab) {
        if (tab != CinemaTab.MY_LIST && pages[tab] != null && System.currentTimeMillis() - (TabCache.at[tab] ?: 0L) < 2 * 60_000) return@LaunchedEffect
        // Warm the title art and badge facts for what's on screen first (badges: a few requests
        // per row, batched by the server, not one per card)
        fun warm(d: CinemaHomeData) {
            d.rows.forEach { row -> row.items.take(8).forEach { item -> item.tmdbId?.let { id -> launch { art.art(item.tmdbTv, id) } } } }
            if (overlays.needsStreams) {
                d.rows.forEach { row ->
                    launch { StreamCache.prefetch(row.items.filter { item -> item.kind != BaseItemKind.SERIES }.map { item -> item.id }, repo::streamTagsBatch) }
                }
            }
        }
        // A first visit shows the quick rows at once; the genre rows join when they arrive
        var partial = false
        val onFirst = { first: CinemaHomeData ->
            launch {
                if (pages[tab] == null) {
                    pages[tab] = first
                    partial = true
                }
                warm(first)
            }
            Unit
        }
        runCatching {
            when (tab) {
                CinemaTab.HOME -> repo.load(onFirst)
                CinemaTab.SHOWS -> repo.loadKind(series = true, onFirst = onFirst)
                CinemaTab.MOVIES -> repo.loadKind(series = false, onFirst = onFirst)
                CinemaTab.NEW_POPULAR -> repo.loadNewPopular()
                CinemaTab.MY_LIST -> CinemaHomeData(emptyList(), listOf(CinemaRow("My List", repo.myList())), null, null)
            }
        }.onSuccess {
            TabCache.data[tab] = it
            TabCache.at[tab] = System.currentTimeMillis()
            if (pages[tab] == null || partial || tab == CinemaTab.MY_LIST) pages[tab] = it
            warm(it)
        }.onFailure { if (pages[tab] == null) error = it.message ?: "Couldn't load your library" }
    }
    // Once Home is up, quietly load Shows and Movies so switching tabs is instant
    LaunchedEffect(pages[CinemaTab.HOME] != null) {
        if (pages[CinemaTab.HOME] == null) return@LaunchedEffect
        delay(1_500)
        listOf(CinemaTab.SHOWS to true, CinemaTab.MOVIES to false).forEach { (t, series) ->
            if (pages[t] == null) {
                runCatching { repo.loadKind(series) }.onSuccess {
                    TabCache.data[t] = it
                    TabCache.at[t] = System.currentTimeMillis()
                    if (pages[t] == null) pages[t] = it
                }
            }
        }
    }
    BackHandler(enabled = tab != CinemaTab.HOME) { tab = CinemaTab.HOME }

    // When the remote was last used; read only by the billboard's rotation (no recomposition)
    val lastInput = remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    Box(
        modifier.fillMaxSize().background(Stage).onPreviewKeyEvent {
            lastInput.longValue = android.os.SystemClock.uptimeMillis()
            false
        },
    ) {
        CompositionLocalProvider(LocalArt provides art, LocalOverlays provides overlays, LocalStreamLookup provides StreamLookup(repo::streamTagsOf)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(360, delayMillis = 80, easing = CinemaEase)) togetherWith fadeOut(tween(180)) },
                label = "tab",
            ) { t ->
                val d = pages[t]
                Box(Modifier.fillMaxSize()) {
                    when {
                        d == null && error != null ->
                            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(error!!, color = Ink)
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { hook.store.setCinemaMode(false) }) { Text("Use the classic home") }
                            }
                        // The logo only shows while the app starts; other tabs just fade in
                        d == null -> if (t == CinemaTab.HOME) Wordmark(Modifier.align(Alignment.Center), size = 34)
                        t == CinemaTab.MY_LIST -> MyListScreen(d.rows.firstOrNull()?.items.orEmpty(), onOpen)
                        else -> {
                            val focusPlay = grabFocus && t == CinemaTab.HOME
                            CinemaScreen(d, onOpen, onPlay, focusPlay, rollUp, lastInput)
                            LaunchedEffect(Unit) { grabFocus = false }
                        }
                    }
                }
            }
        }
        TopNav(tab, onTab = { tab = it }, onNavigate)
    }
}

/** Cinema mode's top-menu pages. They switch in place, like a streaming app's tabs. */
enum class CinemaTab(
    val label: String,
) {
    HOME("Home"),
    SHOWS("Shows"),
    MOVIES("Movies"),
    NEW_POPULAR("New & Popular"),
    MY_LIST("My List"),
}

private val TopNavHeight = 54.dp

/** After this long without a key press the billboard stops rotating. */
private const val IDLE_MS = 120_000L

/**
 * Focus state, kept out of the rows: cards only write it, so moving the remote recomposes the
 * top panel and backdrop but never the rows themselves.
 */
@Stable
private class HomeFocus {
    var billboard by mutableStateOf(true)
    var focused by mutableStateOf<CinemaItem?>(null)

    /** The row the focused card is in; the first row keeps the billboard full size. */
    var row by mutableIntStateOf(0)

    fun onCard(
        item: CinemaItem,
        rowIndex: Int,
    ) {
        billboard = false
        focused = item
        row = rowIndex
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CinemaScreen(
    data: CinemaHomeData,
    onOpen: (UUID, BaseItemKind) -> Unit,
    onPlay: (UUID, Long) -> Unit,
    grabFocus: Boolean,
    rollUp: Boolean,
    lastInput: androidx.compose.runtime.MutableLongState,
) {
    val focus = remember { HomeFocus() }
    // 0 = full billboard, 1 = rolled up to a quarter while browsing rows (if the setting is on).
    // Read only in layout/draw, so the animation never recomposes the screen.
    // The first row (Continue Watching) still shows the full billboard for the focused card;
    // it rolls up from the second row down
    // Derived so moving between rows only recomposes when "rolled" actually flips, not on every row
    val rolled by remember(rollUp) { derivedStateOf { rollUp && !focus.billboard && focus.row >= 1 } }
    val roll = animateFloatAsState(if (rolled) 1f else 0f, tween(320, easing = CinemaEase), label = "roll")
    // 1 while the buttons are hidden (browsing rows): the panel drops their space so the first
    // row moves up instead of leaving a gap
    val noButtons = animateFloatAsState(if (focus.billboard) 0f else 1f, tween(320, easing = CinemaEase), label = "noButtons")
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

    LaunchedEffect(Unit) { if (grabFocus) runCatching { playFocus.requestFocus() } }
    LaunchedEffect(focus.billboard, featuredIndex, data.featured.size) {
        if (focus.billboard && data.featured.size > 1) {
            delay(10_000)
            // Nobody has touched the remote for 2 minutes: hold this title, so the screen comes
            // to rest (nothing redrawn), until the next key press
            if (android.os.SystemClock.uptimeMillis() - lastInput.longValue > IDLE_MS) {
                val since = lastInput.longValue
                snapshotFlow { lastInput.longValue }.first { it != since }
            }
            featuredIndex = (featuredIndex + 1) % data.featured.size
        }
    }
    // Back, or Up from the first row: bring the buttons back first. They aren't on screen while
    // browsing, so they can only take focus a frame after the billboard returns (otherwise Up
    // skips past them to the top menu and the billboard stays rolled up)
    val toBillboard = remember(focus, scope, playFocus, columnState) {
        {
        focus.billboard = true
        scope.launch {
            // Retry each frame until Play is attached and actually takes focus (up to ~0.5 s)
            for (attempt in 0 until 30) {
                withFrameNanos {}
                if (runCatching { playFocus.requestFocus() }.getOrDefault(false)) break
            }
            columnState.animateScrollToItem(0)
        }
        Unit
        }
    }
    BackHandler(enabled = !focus.billboard) { toBillboard() }
    // Remembered: a new modifier each pass would recompose the first row on every key press
    val upToBillboard =
        remember(focus, toBillboard) {
            Modifier.onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp && !focus.billboard) {
                    toBillboard()
                    true
                } else {
                    false
                }
            }
        }

    val onCardClick =
        remember(onOpen) {
            { item: CinemaItem ->
                DetailsPreview.put(item)
                onOpen(item.detailsId, item.detailsKind)
            }
        }
    val density = LocalDensity.current
    val rowSpec = remember(density) { pivot(with(density) { 40.dp.toPx() }) }
    val cardSpec = remember(density) { pivot(with(density) { 48.dp.toPx() }) }
    // Top 10 rows land the poster after its number, so the number stays on screen
    val topSpec = remember(density) { pivot(with(density) { (48.dp + NumeralWidth).toPx() }) }

    Box(Modifier.fillMaxSize()) {
        StableBackdrop(shown?.backdropUrl, drift = focus.billboard)
        // Rolled up, the rows sit higher over the picture: darken behind them so titles stay legible
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = roll.value }.background(
                Brush.verticalGradient(0.12f to Color.Transparent, 0.4f to Stage.copy(alpha = 0.78f), 1f to Stage),
            ),
        )
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(TopNavHeight))
            InfoPanel(
                item = shown,
                billboard = focus.billboard,
                roll = { roll.value },
                noButtons = { noButtons.value },
                playFocus = playFocus,
                onPlay = { shown?.let { onPlay(it.id, it.resumeMs) } },
                onMoreInfo = {
                    shown?.let {
                        DetailsPreview.put(it)
                        onOpen(it.detailsId, it.detailsKind)
                    }
                },
                onButtonsFocused = { focus.billboard = true },
            )
            // Breathing room between the buttons and the first row's title
            Spacer(Modifier.rollHeight(18.dp, 8.dp) { roll.value })
            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                LazyColumn(
                    state = columnState,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 220.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    itemsIndexed(data.rows, key = { _, r -> r.title }, contentType = { _, _ -> "row" }) { i, row ->
                        val onCard = remember(focus, i) { { item: CinemaItem -> focus.onCard(item, i) } }
                        CinemaRowView(row, if (row.ranked) topSpec else cardSpec, onCard, onCardClick, if (i == 0) upToBillboard else Modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopNav(
    tab: CinemaTab,
    onTab: (CinemaTab) -> Unit,
    onNavigate: (CinemaNav) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 40.dp, top = 18.dp).height(TopNavHeight - 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Wordmark(Modifier.padding(end = 18.dp))
        CinemaTab.entries.forEach { t -> NavPill(t.label, selected = t == tab) { onTab(t) } }
        Spacer(Modifier.weight(1f))
        NavIcon(Icons.Filled.Search, "Search") { onNavigate(CinemaNav.Search) }
        NavIcon(Icons.Filled.Settings, "Settings") { onNavigate(CinemaNav.Settings) }
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

/** Height eased between [full] and [rolled] by [roll] (0..1), in the layout pass only. */
private fun Modifier.rollHeight(
    full: Dp,
    rolled: Dp,
    roll: () -> Float,
): Modifier = animatedHeight { lerp(full, rolled, roll()) }

/** A height read in the layout pass only, so animating it never recomposes. Clips its content. */
private fun Modifier.animatedHeight(height: () -> Dp): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val h = height().roundToPx()
        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
        layout(placeable.width, h) { placeable.place(0, 0) }
    }

/**
 * Fixed-height panel: the focused title changing or the buttons fading never change its size,
 * so the rows below don't move. Only rolling up (the setting) shrinks it, to a quarter: a small
 * logo and the episode or Top 10 line stay, the rest fades out.
 */
@Composable
private fun InfoPanel(
    item: CinemaItem?,
    billboard: Boolean,
    roll: () -> Float,
    noButtons: () -> Float,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    onMoreInfo: () -> Unit,
    onButtonsFocused: () -> Unit,
) {
    // Full: details + buttons (262). Buttons hidden: details only (210). Rolled up: a quarter (72).
    Box(Modifier.padding(start = 48.dp, top = 16.dp).animatedHeight { lerp(lerp(262.dp, 210.dp, noButtons()), 72.dp, roll()) }.widthIn(max = 480.dp)) {
        AnimatedContent(
            targetState = item,
            transitionSpec = { fadeIn(tween(380, delayMillis = 90, easing = CinemaEase)) togetherWith fadeOut(tween(160)) },
            contentKey = { it?.key },
            label = "info",
        ) { shownItem ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shownItem == null) return@Column
                val logo = rememberArt(shownItem)?.logoUrl() ?: shownItem.logoUrl
                // Kind tag above the logo; it folds away when the billboard rolls up
                Box(Modifier.rollHeight(18.dp, 0.dp, roll), contentAlignment = Alignment.BottomStart) { KindTag(shownItem.kind) }
                Box(Modifier.rollHeight(70.dp, 44.dp, roll).widthIn(max = 340.dp), contentAlignment = Alignment.BottomStart) {
                    if (logo != null) {
                        AsyncImage(model = logo, contentDescription = shownItem.title, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.fillMaxSize())
                    } else {
                        Text(shownItem.title, color = Ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 36.sp)
                    }
                }
                // One fixed line: the episode, else the title's Top 10 place, else blank
                Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
                    val rank = shownItem.rank
                    when {
                        shownItem.subtitle != null ->
                            Text(shownItem.subtitle, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        rank != null -> RankLine(rank, shownItem.rankLabel)
                    }
                }
                Column(Modifier.graphicsLayer { alpha = (1f - roll() * 2f).coerceIn(0f, 1f) }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetaLine(Meta(shownItem.meta, shownItem.rating))
                    Text(shownItem.overview, color = Ink.copy(alpha = 0.86f), fontSize = 14.sp, lineHeight = 20.sp, minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
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
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(row.title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 48.dp))
        CompositionLocalProvider(LocalBringIntoViewSpec provides cardSpec) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(if (row.ranked) 4.dp else 10.dp),
                contentPadding = PaddingValues(start = 48.dp, end = 400.dp, top = 8.dp, bottom = 8.dp),
            ) {
                if (row.ranked) {
                    items(row.items, key = { it.key }, contentType = { "top10" }) { item ->
                        Top10Card(item, item.rank ?: (row.items.indexOf(item) + 1), onItemFocused, onItemClick)
                    }
                } else {
                    items(row.items, key = { it.key }, contentType = { "card" }) { item ->
                        CinemaCard(item, onItemFocused, onItemClick)
                    }
                }
            }
        }
    }
}

/** The billboard's chart line: a red TOP 10 badge and "#2 in Movies This Week". */
@Composable
private fun RankLine(
    rank: Int,
    label: String?,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.size(20.dp).background(Label, RoundedCornerShape(2.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("TOP", color = Color.White, style = BadgeText.copy(fontSize = 5.5.sp, lineHeight = 6.sp))
            Text("10", color = Color.White, style = BadgeText.copy(fontSize = 9.sp, lineHeight = 9.sp))
        }
        Text(listOfNotNull("#$rank", label?.let { "in $it" }).joinToString(" "), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private val BadgeText =
    TextStyle(
        fontWeight = FontWeight.Black,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )

private val NumeralWidth = 84.dp
private val PosterWidth = 112.dp
private val PosterHeight = 168.dp

/**
 * A Top 10 tile: a big outlined number with the poster tucked over its right edge, the way the
 * streaming charts row looks.
 */
@Composable
private fun Top10Card(
    item: CinemaItem,
    rank: Int,
    onFocused: (CinemaItem) -> Unit,
    onClick: (CinemaItem) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val shape = RoundedCornerShape(4.dp)
    val numeral =
        remember(density) {
            TextStyle(
                fontSize = 136.sp,
                lineHeight = 136.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-14).sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.Both),
            )
        }
    val stroke = remember(density) { Stroke(width = with(density) { 2.5.dp.toPx() }) }
    Row(verticalAlignment = Alignment.Bottom) {
        Box(Modifier.width(if (rank >= 10) NumeralWidth + 56.dp else NumeralWidth).height(PosterHeight), contentAlignment = Alignment.BottomEnd) {
            // Filled in stage black, outlined in grey, nudged under the poster
            Text("$rank", color = Stage, style = numeral, maxLines = 1, softWrap = false, modifier = Modifier.offset(x = 16.dp, y = 6.dp))
            Text("$rank", color = Color(0xFF8A8A8A), style = numeral.copy(drawStyle = stroke), maxLines = 1, softWrap = false, modifier = Modifier.offset(x = 16.dp, y = 6.dp))
        }
        Card(
            onClick = { onClick(item) },
            shape = CardDefaults.shape(shape),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Ink), shape = shape)),
            scale = CardDefaults.scale(focusedScale = 1.08f),
            glow = CardDefaults.glow(),
            colors = CardDefaults.colors(containerColor = Color(0xFF1F1F1F)),
            modifier = Modifier.width(PosterWidth).height(PosterHeight).onFocusChanged { if (it.isFocused) onFocused(item) },
        ) {
            Box(Modifier.fillMaxSize()) {
                val url = item.posterUrl ?: item.cardUrl
                if (url != null) {
                    AsyncImage(model = request(context, url, 224, 336), contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (item.posterUrl == null) {
                    Text(item.title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
                }
                item.progress?.let { p -> ProgressBar(p, Modifier.align(Alignment.BottomStart)) }
                PosterBadges(item, tall = true)
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
                // Decoded at card size: small, fast, and cached for the next visit
                AsyncImage(model = request(context, url, 416, 234), contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
            item.badge?.takeIf { LocalOverlays.current.newLabels }?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomCenter).background(Label, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            item.progress?.let { p -> ProgressBar(p, Modifier.align(Alignment.BottomStart)) }
            PosterBadges(item)
        }
    }
}

/**
 * What a card already knows about a title, handed to its Cinema page so the page draws at once
 * (art, logo, overview, Play) while the rest loads. Episodes aren't handed over: their card
 * opens the series.
 */
internal object DetailsPreview {
    private val map = java.util.concurrent.ConcurrentHashMap<UUID, CinemaItem>()

    fun put(item: CinemaItem) {
        if (item.id == item.detailsId) map[item.id] = item
    }

    operator fun get(id: UUID): CinemaItem? = map[id]
}

/** Titles to suggest before you search: what's on your home, billboard first. */
internal fun homeSuggestions(): List<CinemaItem> {
    val home = TabCache.data[CinemaTab.HOME] ?: return emptyList()
    return (home.featured + home.rows.flatMap { it.items })
        .filter { it.kind != BaseItemKind.EPISODE && (it.backdropUrl != null || it.cardUrl != null) }
        .distinctBy { it.detailsId }
        .take(18)
}

/** Each tab's last page, kept for the life of the app so returning to it is instant. */
private object TabCache {
    val data = java.util.concurrent.ConcurrentHashMap<CinemaTab, CinemaHomeData>()
    val at = java.util.concurrent.ConcurrentHashMap<CinemaTab, Long>()
}

/** My List: every saved title in a calm grid, the focused one's art behind it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MyListScreen(
    items: List<CinemaItem>,
    onOpen: (UUID, BaseItemKind) -> Unit,
) {
    var focused by remember { mutableStateOf<CinemaItem?>(null) }
    var shown by remember { mutableStateOf<CinemaItem?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { focused }.collectLatest {
            delay(220)
            shown = it
        }
    }
    val density = LocalDensity.current
    val spec = remember(density) { pivot(with(density) { 24.dp.toPx() }) }
    Box(Modifier.fillMaxSize()) {
        StableBackdrop(shown?.backdropUrl, drift = false)
        Box(Modifier.fillMaxSize().background(Stage.copy(alpha = 0.55f)))
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(TopNavHeight))
            Text("My List", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 48.dp, top = 22.dp, bottom = 6.dp))
            if (items.isEmpty()) {
                Text(
                    "Titles you add with My List on a title's page show up here.",
                    color = InkDim,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(start = 48.dp, top = 8.dp),
                )
            } else {
                CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 14.dp, bottom = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        gridItems(items, key = { it.key }, contentType = { "card" }) { item ->
                            CinemaCard(
                                item,
                                onFocused = { focused = it },
                                onClick = {
                                    DetailsPreview.put(it)
                                    onOpen(it.detailsId, it.detailsKind)
                                },
                                width = 204.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Settings' left side in Cinema mode: a faded billboard image, the wordmark and the heading. */
@Composable
fun CinemaSettingsSide(modifier: Modifier = Modifier) {
    val backdrop = remember { TabCache.data[CinemaTab.HOME]?.featured?.randomOrNull()?.backdropUrl }
    Box(modifier) {
        StableBackdrop(backdrop, drift = true, widthFraction = 1f, heightFraction = 1f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.55f to Color.Transparent, 1f to Stage)))
        Box(Modifier.fillMaxSize().background(Stage.copy(alpha = 0.35f)))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 64.dp)) {
            Wordmark(size = 26)
            Spacer(Modifier.height(14.dp))
            Text("Settings", color = Ink, fontSize = 46.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            Text("Playback, subtitles, servers and Orca+ extras", color = InkDim, fontSize = 15.sp)
        }
    }
}
