package com.wholphinplus.sources.cinema

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.wholphinplus.sources.Availability
import com.wholphinplus.sources.core.TmdbItem
import com.wholphinplus.sources.core.TmdbSearch
import com.wholphinplus.sources.core.TmdbType
import com.wholphinplus.sources.ui.SourcesEntryPoint
import com.wholphinplus.sources.ui.TMDB_NOTICE
import com.wholphinplus.sources.ui.TitleSheet
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/**
 * Cinema mode's search: an on-screen keyboard on the left, title cards on the right. Before you
 * type it suggests titles from your home; results come from smart search (TMDB), and a title
 * that's on your server opens its Cinema page, otherwise the other-servers sheet.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun CinemaSearch(
    initialQuery: String,
    onOpen: (UUID, BaseItemKind) -> Unit,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    @Suppress("NAME_SHADOWING") val onOpen = guarded(onOpen)
    val context = LocalContext.current
    val entry = remember { EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java) }
    val service = remember { entry.searchService() }
    val art = remember { entry.cinemaArt() }
    val overlays by entry.sourceHook().store.overlays.collectAsState()
    val ratingPrefs by entry.sourceHook().store.ratingPrefs.collectAsState()
    val ratings = remember { entry.ratings() }
    if (!service.enabled) return fallback()
    DisposableEffect(Unit) { onDispose { art.save() } }

    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<TmdbSearch?>(null) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<TmdbItem?>(null) }
    val scope = rememberCoroutineScope()
    val firstKey = remember { FocusRequester() }
    val micFocus = remember { FocusRequester() }
    val (voice, toggleVoice) = rememberCinemaVoice { heard -> query = heard }
    // The mic takes focus when search opens (the keyboard's first key if voice isn't available)
    LaunchedEffect(Unit) { runCatching { if (voice.available) micFocus.requestFocus() else firstKey.requestFocus() } }
    // The device's own keyboard (Gboard etc.), on request: a hidden text field bound to the query
    val imeField = remember { FocusRequester() }
    val imeKey = remember { FocusRequester() }
    var imeFocused by remember { mutableStateOf(false) }
    val softKeyboard = LocalSoftwareKeyboardController.current
    val openSystemKeyboard = {
        runCatching { imeField.requestFocus() }
        softKeyboard?.show()
        Unit
    }
    val closeSystemKeyboard = {
        softKeyboard?.hide()
        runCatching { imeKey.requestFocus() }
        Unit
    }
    // After voice search, focus goes back to the mic
    LaunchedEffect(voice.active) { if (!voice.active && voice.available) runCatching { micFocus.requestFocus() } }

    // Search as you type, once typing pauses
    LaunchedEffect(Unit) {
        snapshotFlow { query.trim() }.distinctUntilChanged().collectLatest { q ->
            if (q.length < 2) {
                results = null
                searching = false
                return@collectLatest
            }
            delay(300)
            searching = true
            failed = false
            results =
                try {
                    service.search(q)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failed = true
                    null
                }
            searching = false
            // Look the first results up on the server now, so opening one is instant
            results?.items?.take(12)?.forEach { launch { runCatching { service.availability(it) } } }
        }
    }

    // A second OK while the first is still looking the title up opens nothing more
    var opening by remember { mutableStateOf(false) }

    fun open(item: TmdbItem) {
        if (opening) return
        opening = true
        scope.launch {
            try {
                when (val a = runCatching { service.availability(item) }.getOrNull()) {
                    is Availability.Library -> {
                        val kind = if (a.series) BaseItemKind.SERIES else BaseItemKind.MOVIE
                        DetailsPreview.put(item.toCinemaItem().copy(id = a.itemId, detailsId = a.itemId, kind = kind, detailsKind = kind))
                        onOpen(a.itemId, kind)
                    }
                    else -> sheet = item
                }
            } finally {
                opening = false
            }
        }
    }

    Box(
        modifier.fillMaxSize().background(Stage).onPreviewKeyEvent { e ->
            Conductor.touch()
            when {
                // Backstop: while the mic is open, Back only closes it, wherever focus is
                voice.active && (e.key == Key.Back || e.key == Key.Escape) -> {
                    if (e.type == KeyEventType.KeyUp) voice.cancel()
                    true
                }
                // A real keyboard (PC, Bluetooth) types straight into the search; the device
                // keyboard's own field handles its keys itself
                !imeFocused && !voice.active && e.type == KeyEventType.KeyDown && e.key == Key.Backspace -> {
                    query = query.dropLast(1)
                    true
                }
                !imeFocused && !voice.active && e.type == KeyEventType.KeyDown && typedChar(e) != null -> {
                    val c = typedChar(e)!!
                    if (c != ' ' || (query.isNotEmpty() && !query.endsWith(" "))) query += c
                    true
                }
                else -> false
            }
        },
    ) {
        Row(Modifier.fillMaxSize().padding(start = 48.dp, top = 36.dp)) {
            // ---- keyboard column
            Column(Modifier.width(300.dp).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (voice.available) {
                        MicButton(onClick = toggleVoice, modifier = Modifier.focusRequester(micFocus))
                        Spacer(Modifier.width(12.dp))
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = InkDim, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        query.ifEmpty { if (imeFocused) "" else "Search" },
                        color = if (query.isEmpty()) InkDim else Ink,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Typing on the device keyboard: a caret shows where the text goes
                    if (imeFocused) Box(Modifier.padding(start = 2.dp).width(2.dp).height(26.dp).background(Ink))
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.18f)))
                Spacer(Modifier.height(16.dp))
                Keyboard(
                    firstKey = firstKey,
                    onKey = { query += it },
                    onSpace = { if (query.isNotEmpty() && !query.endsWith(" ")) query += " " },
                    onDelete = { query = query.dropLast(1) },
                    onClear = { query = "" },
                    imeKey = imeKey,
                    onSystemKeyboard = openSystemKeyboard,
                )
                Spacer(Modifier.height(18.dp))
                // Streaming-style "explore" list: people the search matched
                results?.people?.filter { p -> p.knownFor.count { it.backdropPath != null } >= 2 }?.take(4)?.takeIf { it.isNotEmpty() }?.let { people ->
                    Text("Explore titles related to:", color = InkDim, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    people.forEach { p -> Suggestion(p.name) { query = p.name } }
                }
                Spacer(Modifier.weight(1f))
                Text(TMDB_NOTICE, color = InkDim.copy(alpha = 0.6f), fontSize = 9.sp, lineHeight = 12.sp, modifier = Modifier.padding(bottom = 18.dp, end = 12.dp))
            }
            Spacer(Modifier.width(36.dp))
            // ---- results
            val r = results
            val heading: String?
            // Labelled groups of cards; a null label is an unlabelled group
            val sections: List<Pair<String?, List<CinemaItem>>>
            val tmdbByKey: Map<String, TmdbItem>
            when {
                r != null -> {
                    // Titles without any art are obscure noise; a streaming app wouldn't show them
                    val all = (r.items + r.people.flatMap { it.knownFor }).distinctBy { it.key }.filter { it.backdropPath != null }
                    heading = r.interpretation ?: if (all.isEmpty()) "No results for \"${query.trim()}\"" else null
                    val cards = all.map { it.toCinemaItem() }
                    // The best few matches first, then everything else split into movies and shows
                    val top = cards.take(TOP_RESULTS)
                    val rest = cards.drop(TOP_RESULTS)
                    sections =
                        listOf(
                            "Top Results" to top,
                            "Movies" to rest.filter { it.kind == BaseItemKind.MOVIE },
                            "TV Shows" to rest.filter { it.kind != BaseItemKind.MOVIE },
                        ).filter { it.second.isNotEmpty() }
                    tmdbByKey = all.associateBy { "tmdb:" + it.key }
                }
                query.trim().length >= 2 -> {
                    heading = if (failed) "Search isn't working right now" else ""
                    sections = emptyList()
                    tmdbByKey = emptyMap()
                }
                else -> {
                    heading = "Recommended for You"
                    sections = listOf(null to homeSuggestions())
                    tmdbByKey = emptyMap()
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (heading != null) {
                    Text(heading, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.height(28.dp))
                }
                AnimatedContent(
                    targetState = sections,
                    transitionSpec = { fadeIn(tween(280, delayMillis = 60, easing = CinemaEase)) togetherWith fadeOut(tween(140)) },
                    contentKey = { list -> list.map { (label, items) -> label to items.map { it.key } } },
                    label = "results",
                ) { groups ->
                    val density = LocalDensity.current
                    // Room above the focused row for its group's label (Movies, TV Shows)
                    val spec = remember(density) { pivot(with(density) { 52.dp.toPx() }) }
                    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, LocalArt provides art, LocalOverlays provides overlays, LocalRatingPrefs provides ratingPrefs, LocalRatings provides ratings) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(top = 10.dp, end = 48.dp, bottom = 80.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            groups.forEachIndexed { g, (label, groupItems) ->
                                if (label != null) {
                                    item(key = "label:$label", span = { GridItemSpan(maxLineSpan) }, contentType = "label") {
                                        Text(
                                            label,
                                            color = Ink,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(top = if (g == 0) 0.dp else 12.dp),
                                        )
                                    }
                                }
                                items(groupItems, key = { it.key }, contentType = { "card" }) { item ->
                                CinemaCard(
                                    item,
                                    onFocused = {},
                                    onClick = { c ->
                                        val t = tmdbByKey["tmdb:" + c.tmdbKey()]
                                        if (t != null) {
                                            open(t)
                                        } else {
                                            DetailsPreview.put(c)
                                            onOpen(c.detailsId, c.detailsKind)
                                        }
                                    },
                                    width = 166.dp,
                                )
                                }
                            }
                        }
                    }
                }
            }
        }
        // Bound to the query; only the device keyboard ever focuses it
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { closeSystemKeyboard() }, onDone = { closeSystemKeyboard() }),
            modifier =
                Modifier
                    .size(1.dp)
                    .alpha(0f)
                    // Back is caught before the keyboard and the text field see it (the field would
                    // drop focus and let Back leave search): one press closes the keyboard and
                    // returns to the on-screen one
                    .onPreInterceptKeyBeforeSoftKeyboard { e ->
                        if (e.key == Key.Back) {
                            if (e.type == KeyEventType.KeyUp) closeSystemKeyboard()
                            true
                        } else {
                            false
                        }
                    }.focusRequester(imeField)
                    .onFocusChanged { imeFocused = it.isFocused }
                    // Up/Down only arrive here once the keyboard is closed (it takes them while open)
                    .onPreviewKeyEvent { e ->
                        if (e.key == Key.DirectionDown || e.key == Key.DirectionUp) {
                            if (e.type == KeyEventType.KeyUp) closeSystemKeyboard()
                            true
                        } else {
                            false
                        }
                    },
        )
        if (searching && results == null) {
            Text("Searching…", color = InkDim, fontSize = 14.sp, modifier = Modifier.align(Alignment.TopStart).padding(start = 384.dp, top = 80.dp))
        }
    }
    // Android 13+ with predictive back delivers Back as a callback, not a key press, so the key
    // handlers above never see it there (the Shield's Android 11 still sends the key)
    androidx.activity.compose.BackHandler(enabled = voice.active) { voice.cancel() }
    VoiceOverlay(voice, onRetry = toggleVoice)
    sheet?.let { TitleSheet(it, service, onDismiss = { sheet = null }) }
}

/** The dedicated mic: a round button in front of the search field. */
@Composable
private fun MicButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.12f),
                contentColor = Ink,
                focusedContainerColor = Ink,
                focusedContentColor = Stage,
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        modifier = modifier.size(44.dp).tapToClick(onClick),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(MicIcon, contentDescription = "Search by voice", modifier = Modifier.size(24.dp))
        }
    }
}

/** Material's keyboard icon (not in the core icon set). */
private val KeyboardIcon: ImageVector by lazy {
    ImageVector
        .Builder("Keyboard", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            addPathNodes(
                "M20,5H4c-1.1,0 -1.99,0.9 -1.99,2L2,17c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2z" +
                    "M11,8h2v2h-2V8zM11,11h2v2h-2v-2zM8,8h2v2H8V8zM8,11h2v2H8v-2zM7,13H5v-2h2v2zM7,10H5V8h2v2z" +
                    "M16,17H8v-2h8v2zM16,13h-2v-2h2v2zM16,10h-2V8h2v2zM19,13h-2v-2h2v2zM19,10h-2V8h2v2z",
            ),
            fill = SolidColor(Color.Black),
        ).build()
}

/**
 * The letter, digit or space a keyboard key types, or null for remote and control keys (the
 * D-pad, OK, Back and media keys type nothing).
 */
private fun typedChar(e: androidx.compose.ui.input.key.KeyEvent): Char? {
    val n = e.nativeKeyEvent
    if (n.isCtrlPressed || n.isAltPressed || n.isMetaPressed) return null
    val c = n.unicodeChar.takeIf { it != 0 }?.toChar() ?: return null
    return if (c.isLetterOrDigit() || c == ' ' || c in "'&:-.!") c.lowercaseChar() else null
}

/** How many best matches lead the results, one grid row. */
private const val TOP_RESULTS = 3

private const val KEYS = "abcdefghijklmnopqrstuvwxyz1234567890"

@Composable
private fun Keyboard(
    firstKey: FocusRequester,
    onKey: (String) -> Unit,
    onSpace: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    imeKey: FocusRequester,
    onSystemKeyboard: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Key("SPACE", width = 110.dp, onClick = onSpace)
            Key("", icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft, iconLabel = "Delete", width = 54.dp, onClick = onDelete, onLongClick = onClear)
            Key("CLEAR", width = 64.dp, small = true, onClick = onClear)
            // Opens the device's own keyboard, for anyone who prefers it
            Key("", icon = KeyboardIcon, iconLabel = "Use the device keyboard", width = 56.dp, modifier = Modifier.focusRequester(imeKey), onClick = onSystemKeyboard)
        }
        KEYS.chunked(6).forEachIndexed { row, chars ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                chars.forEachIndexed { col, c ->
                    Key(
                        c.toString(),
                        modifier = if (row == 0 && col == 0) Modifier.focusRequester(firstKey) else Modifier,
                        onClick = { onKey(c.toString()) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Key(
    text: String,
    modifier: Modifier = Modifier,
    width: Dp = 46.dp,
    small: Boolean = false,
    icon: ImageVector? = null,
    iconLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.width(width).height(40.dp).tapToClick(onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Ink,
                focusedContainerColor = Ink,
                focusedContentColor = Stage,
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = iconLabel, modifier = Modifier.size(24.dp))
            } else {
                Text(text, fontSize = if (small || text.length > 1) 12.sp else 18.sp, fontWeight = FontWeight.Bold, letterSpacing = if (text.length > 1) 1.sp else 0.sp)
            }
        }
    }
}

@Composable
private fun Suggestion(
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = InkDim, focusedContainerColor = Ink, focusedContentColor = Stage),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.fillMaxWidth().tapToClick(onClick),
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
    }
}

/** A TMDB result as a card. Its id is a placeholder; clicks map back to the TMDB item. */
private fun TmdbItem.toCinemaItem(): CinemaItem {
    val tv = type == TmdbType.TV
    val placeholder = UUID(0L, (if (tv) 1L shl 40 else 0L) + id)
    val kind = if (tv) BaseItemKind.SERIES else BaseItemKind.MOVIE
    return CinemaItem(
        id = placeholder,
        kind = kind,
        detailsId = placeholder,
        detailsKind = kind,
        title = title,
        subtitle = null,
        meta = listOfNotNull(year?.toString(), if (tv) "Series" else null),
        rating = null,
        overview = overview,
        backdropUrl = backdropUrl(),
        cardUrl = backdropUrl(780),
        cardHasTitleArt = false,
        logoUrl = null,
        badge = null,
        resumeMs = 0L,
        progress = null,
        tmdbId = id,
        tmdbTv = tv,
    )
}

private fun CinemaItem.tmdbKey(): String = (if (tmdbTv) TmdbType.TV else TmdbType.MOVIE).name + ":" + tmdbId
