@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.wholphinplus.sources.cinema

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.wholphinplus.sources.ui.SourcesEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/** The actor a title page opened, per title: Back from their page lands on them again. */
internal object CastReturn {
    private val map = java.util.concurrent.ConcurrentHashMap<UUID, String>()

    fun mark(
        title: UUID,
        key: String,
    ) {
        map[title] = key
    }

    fun peek(title: UUID): String? = map[title]

    fun take(title: UUID): String? = map.remove(title)
}

/** Each title's full cast for the session (a few dozen titles). */
private object CastCache {
    private val map =
        object : LinkedHashMap<UUID, List<CastMember>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, List<CastMember>>?) = size > 40
        }

    @Synchronized operator fun get(id: UUID): List<CastMember>? = map[id]

    @Synchronized operator fun set(
        id: UUID,
        cast: List<CastMember>,
    ) {
        map[id] = cast
    }
}

/**
 * A title page's Cast row: round photos, name and part. The server's people first; photos it
 * lacks (and the whole cast, when it lists nobody) come from TMDB, asked once the row is built
 * (it's built only as the page nears it). A circle with initials when nobody has a photo.
 */
@Composable
internal fun CastRow(
    item: CinemaItem,
    people: List<CastMember>,
    repo: CinemaRepository,
    rowSpec: androidx.compose.foundation.gestures.BringIntoViewSpec,
    returnTo: String?,
    returnFocus: FocusRequester,
    onOpen: (CastMember) -> Unit,
) {
    // Back from an actor the cast found last time is there at once, so focus can land on them
    var cast by remember(item.detailsId) { mutableStateOf(CastCache[item.detailsId] ?: people) }
    LaunchedEffect(item.detailsId) {
        if (CastCache[item.detailsId] != null) return@LaunchedEffect
        // The page's own requests first
        delay(CAST_DELAY_MS)
        cast = runCatching { repo.fullCast(item, people).also { CastCache[item.detailsId] = it } }.getOrDefault(people)
    }
    if (cast.isEmpty()) return
    Column {
        Text("Cast", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 58.dp))
        val state = rememberLazyListState(initialFirstVisibleItemIndex = returnTo?.let { k -> cast.indexOfFirst { it.key == k } }?.takeIf { it > 0 }?.minus(1) ?: 0)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec.gliding(state)) {
            LazyRow(
                state = state,
                modifier = Modifier.staysInRow(),
                contentPadding = PaddingValues(start = 58.dp, end = 58.dp, top = 14.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                items(cast, key = { it.key }) { m ->
                    CastCard(m, if (m.key == returnTo) Modifier.focusRequester(returnFocus) else Modifier) { onOpen(m) }
                }
            }
        }
    }
}

@Composable
private fun CastCard(
    m: CastMember,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(Modifier.width(CastPhoto + 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF242424), focusedContainerColor = Color(0xFF242424)),
            modifier = modifier.size(CastPhoto).glideLift(corner = CastPhoto / 2).tapToClick(onClick),
        ) {
            PersonPhoto(listOfNotNull(m.photo, m.tmdbPhoto), m.name, CastPhoto, 22.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(m.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 16.sp)
        m.role?.let { Text(it, color = InkDim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 14.sp, modifier = Modifier.padding(top = 2.dp)) }
    }
}

/** A round photo from the first of [urls] that loads, else the initials. */
@Composable
private fun PersonPhoto(
    urls: List<String>,
    name: String,
    size: Dp,
    initialsSize: androidx.compose.ui.unit.TextUnit,
) {
    var at by remember(urls) { mutableIntStateOf(0) }
    Box(Modifier.size(size).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFF2E2E34), Color(0xFF1B1B1F)))), contentAlignment = Alignment.Center) {
        Text(initials(name), color = InkDim, fontSize = initialsSize, fontWeight = FontWeight.Bold)
        urls.getOrNull(at)?.let { url ->
            AsyncImage(
                model = rememberPlain(url, CARD_FADE_MS),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                onError = { at++ },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

internal fun initials(name: String): String =
    name.split(' ', '-').filter { it.isNotBlank() }.let { parts ->
        listOfNotNull(parts.firstOrNull(), parts.drop(1).lastOrNull()).joinToString("") { it.take(1).uppercase() }
    }

// ---------------------------------------------------------------- the actor's page

/** Actors' pages for the session: Back from a title shows one at once, as it was left. */
private object PersonCache {
    private val map =
        object : LinkedHashMap<UUID, PersonPageData>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, PersonPageData>?) = size > 8
        }

    @Synchronized operator fun get(id: UUID): PersonPageData? = map[id]

    @Synchronized operator fun set(
        id: UUID,
        d: PersonPageData,
    ) {
        map[id] = d
    }
}

/** Where an actor's page was left when a title opened from it: See all open, which title. */
private object PersonReturn {
    class Spot(
        val grid: Boolean,
        val gridAt: Int,
        val key: String?,
    )

    val spots = java.util.concurrent.ConcurrentHashMap<UUID, Spot>()
}

/**
 * An actor's page in the Cinema look: their portrait, what they're known for, born / died, the
 * biography (More opens it whole), then "In your library": the titles of theirs on the server,
 * newest first, with See all for every one.
 */
@Composable
fun CinemaPerson(
    personId: UUID,
    onOpen: (UUID, BaseItemKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("NAME_SHADOWING") val onOpen = guarded(onOpen)
    val context = LocalContext.current
    val entry = remember { EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java) }
    val hook = remember { entry.sourceHook() }
    val art = remember { entry.cinemaArt() }
    val repo = remember { CinemaRepository(hook, hook.collections, entry.searchService().tmdb) }
    val overlays by hook.store.overlays.collectAsState()
    var data by remember(personId) { mutableStateOf(PersonCache[personId]) }
    var error by remember(personId) { mutableStateOf<String?>(null) }
    var attempt by remember(personId) { mutableIntStateOf(0) }
    LaunchedEffect(personId, attempt) {
        if (data != null) return@LaunchedEffect
        error = null
        var tries = 0
        while (true) {
            try {
                val d = repo.person(personId)
                PersonCache[personId] = d
                data = d
                break
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                timber.log.Timber.w(e, "Actor page %s failed to load", personId)
                if (++tries >= LOAD_TRIES) {
                    error = e.message?.takeIf { it.isNotBlank() } ?: "Couldn't load this page"
                    break
                }
                delay(1_500L * tries)
            }
        }
    }
    val spot = remember(personId) { PersonReturn.spots.remove(personId) }
    var grid by remember(personId) { mutableStateOf(spot?.grid == true) }
    var gridAt by remember(personId) { mutableIntStateOf(spot?.gridAt ?: 0) }
    Box(
        modifier.fillMaxSize().background(Stage).holdRepeat().onPreviewKeyEvent {
            if (HeldKeys.behind(it)) return@onPreviewKeyEvent true
            Conductor.touch()
            false
        },
    ) {
        val d = data
        when {
            d == null && error != null -> LoadError(error!!, onRetry = { attempt++ }, onClassic = null, modifier = Modifier.align(Alignment.Center))
            d == null -> Unit
            grid -> {
                // Back closes the grid (a key on the Shield, a callback with predictive back)
                BackHandler { grid = false }
                Box(
                    Modifier.onPreviewKeyEvent {
                        if (it.key == Key.Back && it.type == KeyEventType.KeyUp) {
                            grid = false
                            true
                        } else {
                            it.key == Key.Back
                        }
                    },
                ) {
                    CompositionLocalProvider(LocalArt provides art, LocalOverlays provides overlays) {
                        TitleGrid(
                            d.name,
                            d.titles,
                            "None of their titles are in your library.",
                            { id, kind ->
                                PersonReturn.spots[personId] = PersonReturn.Spot(true, gridAt, null)
                                onOpen(id, kind)
                            },
                            count = d.titles.size,
                            focusAt = gridAt,
                            onFocusedAt = { gridAt = it },
                        )
                    }
                }
            }
            else ->
                CompositionLocalProvider(LocalArt provides art, LocalOverlays provides overlays) {
                    PersonScreen(
                        d,
                        returnKey = spot?.key.takeIf { spot?.grid == false },
                        onSeeAll = {
                            gridAt = 0
                            grid = true
                        },
                        onOpenTitle = { item ->
                            PersonReturn.spots[personId] = PersonReturn.Spot(false, 0, item.key)
                            DetailsPreview.put(item)
                            onOpen(item.detailsId, item.detailsKind)
                        },
                    )
                }
        }
    }
}

@Composable
private fun PersonScreen(
    d: PersonPageData,
    returnKey: String?,
    onSeeAll: () -> Unit,
    onOpenTitle: (CinemaItem) -> Unit,
) {
    val density = LocalDensity.current
    val list = rememberLazyListState()
    val rowSpec = remember(density) { pivot(with(density) { 64.dp.toPx() }) }
    val cardSpec = remember(density) { pivot(with(density) { 58.dp.toPx() }) }
    val firstCard = remember { FocusRequester() }
    val backCard = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }
    val seeAllFocus = remember { FocusRequester() }
    var expanded by remember { mutableStateOf(false) }
    val bioFocus = remember { FocusRequester() }
    val paragraphs = remember(d.biography) { bioParagraphs(d) }
    val scope = rememberCoroutineScope()
    // Focus on the actor (More, the biography's start): the whole page glides back to its top, so
    // the photo and name are in view again. The usual pivot put More 64 dp from the top with the
    // photo scrolled off above it (owner, 2026-10-09: "it won't let me go back up").
    val atActor = remember { mutableStateOf(false) }
    // See all already on screen: the page stays put (pinning it near the top hid the actor on the way up)
    val atSeeAll = remember { mutableStateOf(false) }
    val pageSpec =
        remember(rowSpec, list) {
            object : androidx.compose.foundation.gestures.BringIntoViewSpec {
                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float,
                ): Float =
                    if (atActor.value && list.firstVisibleItemIndex == 0) {
                        -list.firstVisibleItemScrollOffset.toFloat()
                    } else if (atSeeAll.value && offset >= 0f && offset + size <= containerSize) {
                        0f
                    } else {
                        rowSpec.calculateScrollDistance(offset, size, containerSize)
                    }
            }
        }
    val hasMore = !expanded && (paragraphs.size > 1 || paragraphs.firstOrNull().orEmpty().length > BIO_SHORT)

    /** Up from their titles: See all, then More; with neither, the page glides back to the actor. */
    fun up(fromCards: Boolean): Boolean {
        if (expanded) return false
        if (fromCards && d.titles.size > 1 && runCatching { seeAllFocus.requestFocus() }.getOrDefault(false)) return true
        if (hasMore && runCatching { moreFocus.requestFocus() }.getOrDefault(false)) return true
        scope.launch {
            if (list.firstVisibleItemIndex == 0) list.animateScrollBy(-list.firstVisibleItemScrollOffset.toFloat(), CinemaGlide) else list.animateScrollToItem(0)
        }
        return true
    }
    val upKey: (androidx.compose.ui.input.key.KeyEvent, Boolean) -> Boolean = { e, cards ->
        e.key == androidx.compose.ui.input.key.Key.DirectionUp && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && up(cards)
    }
    // Opened: the More button is gone, so focus goes on to the biography's first paragraph
    LaunchedEffect(expanded) {
        if (!expanded) return@LaunchedEffect
        for (attempt in 0 until 20) {
            withFrameNanos {}
            if (runCatching { bioFocus.requestFocus() }.getOrDefault(false)) break
        }
    }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(480, easing = CinemaEase)) }
    // Focus on arrival: back on the title opened from here; else the actor first (owner,
    // 2026-10-08: it jumped to their titles, scrolling the actor away): More, or with no More the
    // first title with the page kept at the top
    LaunchedEffect(Unit) {
        val back = returnKey != null && d.titles.any { it.key == returnKey }
        for (attempt in 0 until 30) {
            withFrameNanos {}
            if (back) {
                if (runCatching { backCard.requestFocus() }.getOrDefault(false)) break
                continue
            }
            val placed = runCatching { moreFocus.requestFocus() }.getOrDefault(false) || (d.titles.isNotEmpty() && runCatching { firstCard.requestFocus() }.getOrDefault(false))
            if (placed) {
                // Focusing scrolls the page to the focused thing; the name and photo stay in view
                withFrameNanos {}
                list.scrollToItem(0)
                break
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        // A title of theirs behind, dimmed: the page isn't a black sheet
        StableBackdrop(d.titles.firstNotNullOfOrNull { it.backdropUrl }, drift = true, widthFraction = 0.7f, heightFraction = 0.8f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Stage, 0.45f to Stage.copy(alpha = 0.86f), 1f to Stage.copy(alpha = 0.55f))))
        CompositionLocalProvider(LocalBringIntoViewSpec provides pageSpec.gliding(list)) {
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(top = 64.dp, bottom = 120.dp),
                modifier =
                    Modifier.fillMaxSize().graphicsLayer {
                        alpha = enter.value
                        translationY = (1f - enter.value) * 28.dp.toPx()
                    },
            ) {
                item(key = "who") {
                    Row(Modifier.padding(horizontal = 58.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(Portrait)) { PersonPhoto(listOfNotNull(d.photo, d.tmdbPhoto), d.name, Portrait, 56.sp) }
                        Spacer(Modifier.width(40.dp))
                        Column(Modifier.widthIn(max = 760.dp)) {
                            d.department?.let { Text(departmentLabel(it).uppercase(), color = InkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp) }
                            Spacer(Modifier.height(6.dp))
                            Text(d.name, color = Ink, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, lineHeight = 44.sp, overflow = TextOverflow.Ellipsis)
                            lifeLines(d).forEach { line ->
                                Text(line, color = InkDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            }
                            Spacer(Modifier.height(16.dp))
                            // The start of the biography; More opens it whole below, a paragraph at a time
                            if (!expanded && paragraphs.isNotEmpty()) {
                                Text(paragraphs.first(), color = Ink, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(16.dp))
                                if (paragraphs.size > 1 || paragraphs.first().length > BIO_SHORT) {
                                    // Down goes to the first title, not whichever card is under the button
                                    val toFirst = d.titles.isNotEmpty() && d.titles.first().key != returnKey
                                    HeroButton(
                                        "More",
                                        Icons.Filled.KeyboardArrowDown,
                                        primary = false,
                                        modifier =
                                            Modifier
                                                .focusRequester(moreFocus)
                                                .focusProperties { if (toFirst) down = firstCard }
                                                .onFocusChanged { atActor.value = it.hasFocus },
                                    ) { expanded = true }
                                }
                            }
                        }
                    }
                }
                if (expanded) {
                    items(paragraphs.withIndex().toList(), key = { "bio:" + it.index }) { (i, p) ->
                        // Each paragraph takes focus in turn, so Down reads on and the page glides
                        var on by remember { mutableStateOf(false) }
                        Text(
                            p,
                            color = if (on) Ink else Ink.copy(alpha = 0.82f),
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            modifier =
                                Modifier
                                    .padding(start = 58.dp + Portrait + 40.dp, end = 58.dp, top = if (i == 0) 0.dp else 12.dp)
                                    .widthIn(max = 760.dp)
                                    .then(if (i == 0) Modifier.focusRequester(bioFocus) else Modifier)
                                    .onFocusChanged {
                                        on = it.isFocused
                                        if (i == 0) atActor.value = it.isFocused
                                    }
                                    .focusable(),
                        )
                    }
                }
                item(key = "titles") {
                    Column(Modifier.padding(top = 36.dp)) {
                        Row(Modifier.padding(start = 58.dp, end = 58.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("In your library", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            if (d.titles.isNotEmpty()) Text(if (d.titles.size == 1) "1 title" else "${d.titles.size} titles", color = InkDim, fontSize = 13.sp)
                            Spacer(Modifier.weight(1f))
                            if (d.titles.size > 1) HeroButton("See all", GridIcon, primary = false, modifier = Modifier.focusRequester(seeAllFocus).onFocusChanged { atSeeAll.value = it.hasFocus }.onPreviewKeyEvent { upKey(it, false) }, onClick = onSeeAll)
                        }
                        if (d.titles.isEmpty()) {
                            Text("None of their titles are in your library.", color = InkDim, fontSize = 14.sp, modifier = Modifier.padding(start = 58.dp, top = 14.dp))
                        } else {
                            val row = rememberLazyListState(initialFirstVisibleItemIndex = returnKey?.let { k -> d.titles.indexOfFirst { it.key == k } }?.takeIf { it > 0 }?.minus(1) ?: 0)
                            CompositionLocalProvider(LocalBringIntoViewSpec provides cardSpec.gliding(row)) {
                                LazyRow(
                                    state = row,
                                    modifier = Modifier.staysInRow().onPreviewKeyEvent { upKey(it, true) },
                                    contentPadding = PaddingValues(start = 58.dp, end = 400.dp, top = 12.dp, bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    items(d.titles.take(ROW_TITLES), key = { it.key }) { t ->
                                        val m =
                                            when {
                                                t.key == returnKey -> Modifier.focusRequester(backCard)
                                                t === d.titles.first() -> Modifier.focusRequester(firstCard)
                                                else -> Modifier
                                            }
                                        CinemaCard(t, onFocused = {}, onClick = onOpenTitle, modifier = m)
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

/** The biography's paragraphs (blank lines or single line breaks between them). */
private fun bioParagraphs(d: PersonPageData): List<String> = d.biography.split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() }

/** "Acting" → "Actor"; TMDB's other departments as they are. */
private fun departmentLabel(d: String): String =
    when (d) {
        "Acting" -> "Actor"
        "Directing" -> "Director"
        "Writing" -> "Writer"
        "Production" -> "Producer"
        else -> d
    }

private val LONG_DATE = java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy")

/** "Born March 4, 1974 in Haverfordwest, Wales" and "Died …, aged 58". */
internal fun lifeLines(d: PersonPageData): List<String> {
    fun date(s: String?) = s?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    val born = date(d.born)
    val died = date(d.died)
    val out = ArrayList<String>(2)
    if (born != null || d.birthPlace != null) {
        out += listOfNotNull("Born", born?.let { LONG_DATE.format(it) }, d.birthPlace?.let { "in $it" }).joinToString(" ")
    }
    if (died != null) {
        val age = born?.let { java.time.Period.between(it, died).years }
        out += listOfNotNull("Died ${LONG_DATE.format(died)}", age?.let { "aged $it" }).joinToString(", ")
    }
    return out
}

private val CastPhoto = 112.dp
private val Portrait = 220.dp

/** Titles in the row; See all has the rest. */
private const val ROW_TITLES = 20

/** A biography this short shows whole: no More. */
private const val BIO_SHORT = 420

/** The cast row waits this long for TMDB, so the page's own requests go first on a slow link. */
private const val CAST_DELAY_MS = 600L
