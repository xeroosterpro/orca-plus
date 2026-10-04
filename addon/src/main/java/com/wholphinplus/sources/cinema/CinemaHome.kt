package com.wholphinplus.sources.cinema

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.wholphinplus.sources.ui.SourcesEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
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

// Palette: true black stage, warm off-white type, a red only for labels and progress
private val Stage = Color(0xFF0B0B0B)
private val Ink = Color(0xFFF2F2F2)
private val InkDim = Color(0xFFB9B9B9)
private val Label = Color(0xFFD62839)
private val Plus = Color(0xFFA78BFA)

private val LocalArt = androidx.compose.runtime.staticCompositionLocalOf<CinemaArt?> { null }

/** TMDB title art for [item], loaded lazily; null until known or without a TMDB key. */
@Composable
private fun rememberArt(item: CinemaItem?): com.wholphinplus.sources.core.TitleArt? {
    val art = LocalArt.current
    return androidx.compose.runtime.produceState<com.wholphinplus.sources.core.TitleArt?>(null, item?.key) {
        value = item?.tmdbId?.let { id -> art?.art(item.tmdbTv, id) }
    }.value
}

/** Is Cinema mode on? Read by Wholphin's navigation to show this home full screen. */
@Composable
fun cinemaModeOn(): Boolean {
    val context = LocalContext.current
    val hook = remember { EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java).sourceHook() }
    return hook.store.cinemaMode.collectAsState().value
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CinemaHome(
    onOpen: (UUID, BaseItemKind) -> Unit,
    onPlay: (UUID, Long) -> Unit,
    onNavigate: (CinemaNav) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hook = remember { EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java).sourceHook() }
    val repo = remember { CinemaRepository(hook, hook.collections) }
    val art = remember { EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java).cinemaArt() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { art.save() } }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CinemaScreen(
    data: CinemaHomeData,
    onOpen: (UUID, BaseItemKind) -> Unit,
    onPlay: (UUID, Long) -> Unit,
    onNavigate: (CinemaNav) -> Unit,
) {
    var billboard by remember { mutableStateOf(true) }
    var featuredIndex by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf<CinemaItem?>(null) }
    val featured = data.featured.getOrNull(featuredIndex % data.featured.size.coerceAtLeast(1))
    val shown = if (billboard) featured else focused ?: featured
    val playFocus = remember { FocusRequester() }
    val columnState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    // The billboard rotates through featured titles while you're on it
    LaunchedEffect(billboard, featuredIndex, data.featured.size) {
        if (billboard && data.featured.size > 1) {
            delay(10_000)
            featuredIndex = (featuredIndex + 1) % data.featured.size
        }
    }
    // Back from the rows returns to the billboard, as on the big streaming apps
    BackHandler(enabled = !billboard) {
        scope.launch { columnState.animateScrollToItem(0) }
        runCatching { playFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(shown?.backdropUrl, kenBurns = billboard)
        Column(Modifier.fillMaxSize()) {
            TopNav(data, onNavigate)
            InfoPanel(
                item = shown,
                billboard = billboard,
                playFocus = playFocus,
                onPlay = { shown?.let { onPlay(it.id, it.resumeMs) } },
                onMoreInfo = { shown?.let { onOpen(it.detailsId, it.detailsKind) } },
                onButtonsFocused = { billboard = true },
            )
            // Rows pivot: the focused row slides up to the top of the row area, title included
            val density = androidx.compose.ui.platform.LocalDensity.current
            val rowPivot =
                remember(density) {
                    val keep = with(density) { 40.dp.toPx() }
                    object : BringIntoViewSpec {
                        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - keep
                    }
                }
            CompositionLocalProvider(LocalBringIntoViewSpec provides rowPivot) {
                LazyColumn(
                    state = columnState,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 200.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    itemsIndexed(data.rows, key = { _, r -> r.title }) { _, row ->
                        CinemaRowView(
                            row,
                            onItemFocused = { item ->
                                billboard = false
                                focused = item
                            },
                            onItemClick = { item -> onOpen(item.detailsId, item.detailsKind) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Backdrop(
    url: String?,
    kenBurns: Boolean,
) {
    val t = rememberInfiniteTransition(label = "kenburns")
    val drift by t.animateFloat(1f, 1.07f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse), label = "scale")
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = url, animationSpec = tween(700), label = "backdrop", modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.80f).fillMaxHeight(0.82f)) { u ->
            if (u != null) {
                AsyncImage(
                    model = u,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val s = if (kenBurns) drift else 1f
                        scaleX = s
                        scaleY = s
                    },
                )
            }
        }
        // Fade the art into the stage: from the left behind the text, and from the bottom behind the rows
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Stage, 0.22f to Stage.copy(alpha = 0.92f), 0.5f to Stage.copy(alpha = 0.35f), 0.75f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Stage.copy(alpha = 0.55f), 0.12f to Color.Transparent, 0.5f to Color.Transparent, 0.78f to Stage)))
    }
}

@Composable
private fun Wordmark(
    modifier: Modifier = Modifier,
    size: Int = 22,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("ORCA", color = Ink, fontSize = size.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (size * 0.06).sp)
        Text("+", color = Plus, fontSize = size.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun TopNav(
    data: CinemaHomeData,
    onNavigate: (CinemaNav) -> Unit,
) {
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
private fun InfoPanel(
    item: CinemaItem?,
    billboard: Boolean,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    onMoreInfo: () -> Unit,
    onButtonsFocused: () -> Unit,
) {
    Column(Modifier.padding(start = 48.dp, top = 20.dp).height(if (billboard) 268.dp else 214.dp).widthIn(max = 470.dp)) {
        AnimatedContent(
            targetState = item,
            transitionSpec = { (fadeIn(tween(450, delayMillis = 120)) + slideInVertically(tween(450, easing = FastOutSlowInEasing)) { it / 12 }) togetherWith fadeOut(tween(200)) },
            contentKey = { it?.key },
            label = "info",
        ) { it ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (it == null) return@Column
                val logo = rememberArt(it)?.logoUrl() ?: it.logoUrl
                if (logo != null) {
                    AsyncImage(
                        model = logo,
                        contentDescription = it.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        modifier = Modifier.heightIn(max = 96.dp).widthIn(max = 340.dp),
                    )
                } else {
                    Text(it.title, color = Ink, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 40.sp)
                }
                it.subtitle?.let { s -> Text(s, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                MetaLine(it)
                Text(it.overview, color = Ink.copy(alpha = 0.86f), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (billboard) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroButton("Play", Icons.Filled.PlayArrow, primary = true, modifier = Modifier.focusRequester(playFocus), onFocused = onButtonsFocused, onClick = onPlay)
                HeroButton("More Info", Icons.Filled.Info, primary = false, onFocused = onButtonsFocused, onClick = onMoreInfo)
            }
        }
    }
}

@Composable
private fun MetaLine(item: CinemaItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        item.meta.forEachIndexed { i, m ->
            if (i > 0) Text("•", color = InkDim, fontSize = 13.sp)
            Text(m, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        item.rating?.let {
            Text(
                it,
                color = Ink,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.border(1.dp, InkDim, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun HeroButton(
    text: String,
    icon: ImageVector,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        colors =
            ButtonDefaults.colors(
                containerColor = if (primary) Ink else Color.White.copy(alpha = 0.22f),
                contentColor = if (primary) Stage else Ink,
                focusedContainerColor = Ink,
                focusedContentColor = Stage,
            ),
        border =
            ButtonDefaults.border(
                focusedBorder = Border(BorderStroke(2.dp, Ink), inset = (-4).dp, shape = RoundedCornerShape(50)),
            ),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CinemaRowView(
    row: CinemaRow,
    onItemFocused: (CinemaItem) -> Unit,
    onItemClick: (CinemaItem) -> Unit,
) {
    // Cards pivot: the focused card glides to the start of the row, at the page margin
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cardPivot =
        remember(density) {
            val margin = with(density) { 48.dp.toPx() }
            object : BringIntoViewSpec {
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - margin
            }
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(row.title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 48.dp))
        CompositionLocalProvider(LocalBringIntoViewSpec provides cardPivot) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(start = 48.dp, end = 400.dp, top = 6.dp, bottom = 6.dp),
            ) {
                itemsIndexed(row.items, key = { _, it -> it.key }) { _, item ->
                    CinemaCard(item, onFocused = { onItemFocused(item) }, onClick = { onItemClick(item) })
                }
            }
        }
    }
}

@Composable
private fun CinemaCard(
    item: CinemaItem,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Ink), shape = shape)),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        glow = CardDefaults.glow(),
        colors = CardDefaults.colors(containerColor = Color(0xFF1F1F1F)),
        modifier =
            Modifier
                .width(208.dp)
                .aspectRatio(16f / 9f)
                .zIndex(if (isFocused) 1f else 0f)
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) onFocused()
                },
    ) {
        val art = rememberArt(item)
        val titled = art?.cardUrl()
        Box(Modifier.fillMaxSize()) {
            // A backdrop with the title baked in (TMDB) beats the server's plain backdrop
            Crossfade(targetState = titled ?: item.cardUrl, animationSpec = tween(350), label = "card") { url ->
                AsyncImage(model = url, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (titled == null && !item.cardHasTitleArt) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
                val cardLogo = art?.logoUrl() ?: item.logoUrl
                if (cardLogo != null) {
                    AsyncImage(
                        model = cardLogo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = if (item.badge != null) 22.dp else 10.dp).height(34.dp).widthIn(max = 150.dp),
                    )
                } else {
                    Text(
                        item.title,
                        color = Ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = if (item.badge != null) 22.dp else 10.dp),
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
            item.progress?.let { p ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().background(Label))
                }
            }
        }
    }
}

/** The last Cinema home, kept for the life of the app so returning to it is instant. */
private object HomeCache {
    @Volatile var data: CinemaHomeData? = null

    @Volatile var at: Long = 0L
}
