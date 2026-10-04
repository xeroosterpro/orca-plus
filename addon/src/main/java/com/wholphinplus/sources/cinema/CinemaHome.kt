package com.wholphinplus.sources.cinema

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.wholphinplus.sources.ui.SourcesEntryPoint
import dagger.hilt.android.EntryPointAccessors
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

    data object Home : CinemaNav

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

    var data by remember { mutableStateOf(HomeCache.data) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (data != null && System.currentTimeMillis() - HomeCache.at < 2 * 60_000) return@LaunchedEffect
        runCatching { repo.load() }
            .onSuccess {
                HomeCache.data = it
                HomeCache.at = System.currentTimeMillis()
                if (data == null) data = it
                it.rows.forEach { row -> row.items.take(8).forEach { item -> item.tmdbId?.let { id -> launch { art.art(item.tmdbTv, id) } } } }
            }.onFailure { if (data == null) error = it.message ?: "Couldn't load your library" }
    }

    Box(modifier.fillMaxSize().background(Stage)) {
        val d = data
        when {
            d != null -> CompositionLocalProvider(LocalArt provides art) { CinemaBrowseScreen(d, CinemaTopTab.Home, onOpen, onPlay, onNavigate) }
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
