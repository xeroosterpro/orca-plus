package com.wholphinplus.sources.cinema

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.rotate
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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.wholphinplus.sources.ui.SourcesEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
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

/** The top menu's Search or Settings, left for that screen: Back from it lands on its icon again (it landed on Play). */
private object NavReturn {
    @Volatile var to: CinemaNav? = null
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
    val openTitle = guarded(onOpen)
    val context = LocalContext.current
    val entry = remember { EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java) }
    val hook = remember { entry.sourceHook() }
    val art = remember { entry.cinemaArt() }
    val repo = remember { CinemaRepository(hook, hook.collections, entry.searchService().tmdb) }
    // An episode's card can be pressed before its show's id came in (the first rows after a start
    // don't wait for it): it opened Wholphin's own episode page, a blank screen with a spinner.
    // Cinema shows episodes on their show's page, so look the show up first.
    // One lookup at a time, at most a few seconds (then the episode's own page), and dropped if
    // the remote moved on meanwhile: on a slow link a second OK used to open the page twice, or
    // a page popped up seconds later over whatever was being browsed
    val openScope = rememberCoroutineScope()
    val lookingUp = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    // D-pad presses so far (counted by the screen's key handler below)
    val moves = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    // An extra server's title (My Servers): the main server's page when it has it too, else
    // its copies on the extra servers (as search does for titles not in the library)
    val search = remember { entry.searchService() }
    var copiesOf by remember { mutableStateOf<com.wholphinplus.sources.core.TmdbItem?>(null) }
    val playTitle = guarded(onPlay)
    // [play]: from a Play button: a movie the main server has plays at once (a show opens its page)
    val openElsewhere: (ServerTitles.Title, Boolean) -> Unit =
        remember(openTitle, playTitle) {
            { elsewhere, play ->
                    if (lookingUp[0]?.isActive != true) {
                        val moved = moves.get()
                        lookingUp[0] =
                            openScope.launch {
                                val t = elsewhere.title
                                val tmdbId =
                                    t.tmdbId ?: kotlinx.coroutines.withTimeoutOrNull(EPISODE_LOOKUP_MS) {
                                        runCatching { search.search(t.name) }.getOrNull()?.items?.firstOrNull { r ->
                                            (r.type == com.wholphinplus.sources.core.TmdbType.TV) == t.series &&
                                                (t.year == null || r.year == null || kotlin.math.abs(r.year - t.year) <= 1) &&
                                                com.wholphinplus.sources.core.Matcher.normalizeTitle(r.title) == com.wholphinplus.sources.core.Matcher.normalizeTitle(t.name)
                                        }?.id
                                    }
                                if (moves.get() != moved) return@launch
                                if (tmdbId == null) {
                                    android.widget.Toast.makeText(context, "Orca+ couldn't tell which title this is", android.widget.Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val item = ServerTitles.tmdbItem(t, tmdbId)
                                when (val a = kotlinx.coroutines.withTimeoutOrNull(10_000L) { runCatching { search.availability(item) }.getOrNull() }) {
                                    is com.wholphinplus.sources.Availability.Library ->
                                        if (moves.get() == moved) {
                                            if (play && !a.series) playTitle(a.itemId, 0L) else openTitle(a.itemId, if (a.series) BaseItemKind.SERIES else BaseItemKind.MOVIE)
                                        }
                                    else -> if (moves.get() == moved) copiesOf = item
                                }
                            }
                    }
            }
        }
    @Suppress("NAME_SHADOWING") val onOpen: (UUID, BaseItemKind) -> Unit =
        remember(openTitle, repo, openElsewhere) {
            { id, kind ->
                val elsewhere = ServerTitles.of(id)
                if (elsewhere != null) {
                    openElsewhere(elsewhere, false)
                } else if (kind != BaseItemKind.EPISODE) {
                    openTitle(id, kind)
                } else if (lookingUp[0]?.isActive != true) {
                    val moved = moves.get()
                    lookingUp[0] =
                        openScope.launch {
                            val series = kotlinx.coroutines.withTimeoutOrNull(EPISODE_LOOKUP_MS) { repo.seriesOf(id) }
                            if (moves.get() != moved) return@launch
                            if (series != null) openTitle(series, BaseItemKind.SERIES) else openTitle(id, kind)
                        }
                }
            }
        }
    DisposableEffect(Unit) { onDispose { art.save() } }
    remember { StreamCache.attach(context) }
    remember { PageSnapshots.attach(context) }

    // Play on an extra server's title (a server page's billboard) opens it as OK does: the main
    // server's page, or its copies
    @Suppress("NAME_SHADOWING") val onPlay: (UUID, Long) -> Unit =
        remember(playTitle, openElsewhere) { { id, at -> ServerTitles.of(id)?.let { openElsewhere(it, true) } ?: playTitle(id, at) } }

    // The tab you were on survives a trip to a details page and back
    var tab by rememberSaveable { mutableStateOf(CinemaTab.HOME) }
    // Each tab shows its last load instantly and refreshes quietly when stale
    val pages = remember { mutableStateMapOf<CinemaTab, CinemaHomeData>().apply { putAll(TabCache.data) } }
    // Per tab: a failed Shows load must not put its error on Home. [attempt] reloads on Try again.
    val errors = remember { mutableStateMapOf<CinemaTab, String>() }
    var attempt by remember { mutableIntStateOf(0) }
    // The tab on screen takes focus when Home opens (or comes back from a title page, a search, the
    // player: then on the card or button that was used, see [ReturnCard]); [refocus] asks again
    // (Back to Home from another tab, a page closing with nothing to return focus to)
    // Back from Search or Settings: their icon takes focus instead
    val navBack = remember { NavReturn.to.also { NavReturn.to = null } }
    val searchFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    var grabFocus by remember { mutableStateOf(navBack == null) }
    var refocus by remember { mutableStateOf(false) }
    // Back from a tile's page or a See all: focus goes back where it was on the tab
    var returning by remember { mutableStateOf(false) }
    val rollUp by hook.store.cinemaRollUp.collectAsState()
    val shuffleLocks by hook.store.shuffleLocks.collectAsState()
    // A row opened full screen (See all); kept across a trip to a title page
    var grid by remember { mutableStateOf(OpenGrid.state) }
    val overlays by hook.store.overlays.collectAsState()
    val ratingPrefs by hook.store.ratingPrefs.collectAsState()
    val ratings = remember { entry.ratings() }
    // Per tab: the [HomeCollections.changed] its page was loaded under; a newer one may bring rows it lacks
    val loadedUnder = remember { mutableMapOf<CinemaTab, Int>() }
    // Tabs showing a page saved on the device: the fresh load replaces it when it lands
    val fromDevice = remember { mutableSetOf<CinemaTab>() }
    // Lists and charts that were just matched (first start, the 6-hourly refresh) show once the
    // load in progress is done, never by cancelling it: the refresh announces its groups seconds
    // apart, and each used to restart the tab's whole load from its first request (slow pages
    // after nearly every start). A busy collector gets only the latest change: several, one reload
    LaunchedEffect(tab, attempt) {
        // This load's tab, fixed: [tab] itself may already name the next one (the effect is only
        // cancelled at the next frame), and a late Home load was stored as the Shows page
        @Suppress("NAME_SHADOWING") val tab = tab
        var opened = true
        hook.collections.changed.collect { listsChanged ->
            val firstLook = opened
            opened = false
            // Something played since the page loaded: its Continue Watching is out of date (order, progress)
            val plays = repo.plays
            if (tab != CinemaTab.MY_LIST && pages[tab] != null && TabCache.fresh(tab, listsChanged, plays)) return@collect
            // Lists changed while the tab is open: the 6-hourly refresh announces each group of
            // lists, and every announcement reloaded the page. Only one that touches this page's
            // own lists does now (unknown: reload as before). Opening the tab still refreshes.
            val under = loadedUnder[tab]
            val rowsPage = tab.rowsPage
            if (!firstLook && under != null && rowsPage != null && pages[tab] != null) {
                val touched = hook.collections.changedSince(under)
                if (touched != null && touched.none { it in repo.pageListIds(rowsPage) }) {
                    loadedUnder[tab] = listsChanged
                    return@collect
                }
            }
            // Warm the title art and badge facts for what's on screen first (badges: a few requests
            // per row, batched by the server, not one per card)
            // The first two rows (on screen) at once; rows further down once the remote is still
            fun warm(d: CinemaHomeData) {
                d.rows.forEachIndexed { i, row ->
                    launch {
                        if (i >= ON_SCREEN_ROWS) Conductor.whenQuiet()
                        row.items.take(8).forEach { item -> item.tmdbId?.let { id -> launch { art.art(item.tmdbTv, id) } } }
                        if (overlays.needsStreams) {
                            val ids = row.items.filter { item -> item.kind != BaseItemKind.SERIES && row.server.let { it == null || it == SERVER_MAIN } }.map { item -> item.id }
                            if (i < ON_SCREEN_ROWS) StreamCache.prefetch(ids, repo::streamTagsBatch) else Conductor.later { StreamCache.prefetch(ids, repo::streamTagsBatch) }
                        }
                    }
                }
            }
            // A first visit shows the quick rows at once; the genre rows join when they arrive
            var partial = false
            val onFirst = { first: CinemaHomeData ->
                launch {
                    if (pages[tab] == null) StartWarmup.once(context, first, art, overlays)
                    if (pages[tab] == null) {
                        pages[tab] = first
                        partial = true
                    }
                    warm(first)
                }
                Unit
            }
            errors.remove(tab)
            if (pages[tab] == null && tab !in PageSnapshots.UNSAVED) {
                PageSnapshots.read(tab, repo::owner)?.let { saved ->
                    // The app's first screen: a moment for the first rows' pictures, so it opens whole
                    if (pages[tab] == null) StartWarmup.once(context, saved, art, overlays)
                    if (pages[tab] == null) {
                        pages[tab] = saved
                        fromDevice += tab
                        warm(saved)
                    }
                }
            }
            suspend fun loadTab(): CinemaHomeData {
                // A row whose request fails keeps its copy from what's on screen, else the last load
                val previous = listOfNotNull(pages[tab], TabCache.data[tab])
                return when (tab) {
                    CinemaTab.HOME -> repo.load(RowsPage.HOME, onFirst, previous)
                    CinemaTab.SHOWS -> repo.load(RowsPage.SHOWS, onFirst, previous)
                    CinemaTab.MOVIES -> repo.load(RowsPage.MOVIES, onFirst, previous)
                    CinemaTab.NEW_POPULAR -> repo.load(RowsPage.NEW_POPULAR, onFirst, previous)
                    CinemaTab.KIDS -> repo.load(RowsPage.KIDS, onFirst, previous)
                    // Shown as it fills (tiles and placeholders at once); a page already up stays until the load is done
                    CinemaTab.SERVERS ->
                        repo.serversPage { d ->
                            launch {
                                if (pages[tab] == null || partial) {
                                    pages[tab] = d
                                    partial = true
                                }
                            }
                        }
                    CinemaTab.MY_LIST -> CinemaHomeData(emptyList(), listOf(CinemaRow("My List", repo.myList())), null, null)
                }
            }
            // Nothing to show yet (the TV woke before its network): try again quietly a few times
            // before showing an error. Leaving the tab cancels the load; that's not a failure.
            var tries = 0
            var rowRetries = 0
            while (true) {
                var rowsFailed = false
                val failure =
                    try {
                        val page = loadTab()
                        rowsFailed = page.failed > 0
                        val shown = pages[tab]
                        // A page already showing keeps its rows (no shuffle under the remote) unless
                        // rows just matched in the background gave it more to show (a first start).
                        // Placeholders on screen (a first visit's first rows) always give way
                        val gained = loadedUnder[tab] != listsChanged && page.richerThan(shown)
                        val next =
                            when {
                                tab in fromDevice && shown != null -> page.over(shown)
                                shown == null || partial || shown.rows.any { it.loading } || tab == CinemaTab.MY_LIST || tab == CinemaTab.SERVERS -> page
                                // In their places, keeping the billboard and the random rows on screen
                                // (a reload for rows that failed brings them back the same way)
                                gained || rowRetries > 0 -> page.over(shown)
                                // Back from playing: the page on screen stays, with the fresh Continue
                                // Watching (what was just watched first, its progress)
                                else -> shown.withContinueWatching(page)
                            }
                        if (next != null) {
                            fromDevice -= tab
                            pages[tab] = next
                            loadedUnder[tab] = listsChanged
                            // The first rows have given way; a later reload mustn't swap the page whole
                            partial = false
                        }
                        // Back from a title page the tab is rebuilt from here: the fresh Continue
                        // Watching and lists, but the billboard and random rows that were on screen
                        TabCache.data[tab] = next ?: shown?.let(page::over) ?: page
                        // A page with rows that failed (shown as before, or left out) isn't saved
                        // or counted as fresh: the next visit asks again
                        if (page.failed == 0) {
                            TabCache.stamp(tab, listsChanged, plays)
                            launch { PageSnapshots.save(tab, repo::owner, page) }
                        } else {
                            TabCache.at.remove(tab)
                        }
                        warm(page)
                        null
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        timber.log.Timber.w(e, "Cinema %s load failed", tab.name)
                        e
                    }
                if (failure == null) {
                    // Rows failed (shown as before, or left out): loaded again quietly a little
                    // later while the tab is open, so a blip doesn't cost them until the next visit
                    if (!rowsFailed || ++rowRetries > ROW_RETRIES) break
                    delay(20_000L * rowRetries)
                    continue
                }
                val shown = pages[tab]
                if (shown != null && shown.rows.any { it.items.isNotEmpty() }) {
                    // Titles are on screen (a saved page, the last load, the first rows): they
                    // stay, and the load is tried again quietly while the tab is open, so the
                    // rows come back with the server instead of on the next visit
                    if (++tries > QUIET_TRIES) break
                    // Rows still spinning after a couple of tries won't come this time
                    if (tries == 2 && shown.rows.any { it.loading }) pages[tab] = shown.copy(rows = shown.rows.filterNot { it.loading })
                    delay((5_000L * tries).coerceAtMost(60_000L))
                    continue
                }
                if (++tries >= LOAD_TRIES) {
                    // Only placeholders were up: the error and its Try again take their place
                    // (dropped, they left a page saying it had no rows switched on)
                    pages.remove(tab)
                    errors[tab] = failure.message?.takeIf { it.isNotBlank() } ?: "Couldn't load your library"
                    break
                }
                delay(1_500L * tries)
            }
        }
    }
    // Once Home is up, quietly load Shows and Movies so switching tabs is instant
    LaunchedEffect(pages[CinemaTab.HOME] != null) {
        if (pages[CinemaTab.HOME] == null) return@LaunchedEffect
        delay(1_500)
        listOf(CinemaTab.SHOWS to RowsPage.SHOWS, CinemaTab.MOVIES to RowsPage.MOVIES).forEach { (t, rowsPage) ->
            if (pages[t] == null) {
                // Off the critical path: only while the remote is still, so browsing stays smooth
                try {
                    val gen = repo.listsGeneration
                    val plays = repo.plays
                    val page = Conductor.later { repo.load(rowsPage, previous = listOfNotNull(TabCache.data[t])) }
                    TabCache.data[t] = page
                    // Rows failed: opening the tab asks again (and nothing half-loaded is saved)
                    if (page.failed == 0) {
                        TabCache.stamp(t, gen, plays)
                        PageSnapshots.save(t, repo::owner, page)
                    }
                    if (pages[t] == null) pages[t] = page
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Loads normally when the tab is opened
                }
            }
        }
    }
    // Home's Play takes focus: it stayed on the old tab's content while that faded out (keys moved
    // in the invisible tab), then on nothing
    BackHandler(enabled = tab != CinemaTab.HOME) {
        tab = CinemaTab.HOME
        refocus = true
    }
    // The Kids tab switched off while it was open: back to Home
    val kidsTab by hook.store.kidsTab.collectAsState()
    LaunchedEffect(kidsTab) {
        if (!kidsTab && tab == CinemaTab.KIDS) {
            tab = CinemaTab.HOME
            refocus = true
        }
    }
    // A service's or genre's page, opened from its tile (kept across a trip to a title page)
    var openPage by rememberSaveable { mutableStateOf<String?>(null) }
    var pageSeries by rememberSaveable { mutableStateOf(false) }
    val onTile =
        remember {
            { t: PageTile ->
                if (NavGuard.allow()) {
                    // From Shows a page opens on its shows; elsewhere on its movies
                    pageSeries = tab == CinemaTab.SHOWS
                    openPage = t.id
                }
            }
        }

    val rowActions =
        remember(shuffleLocks) {
            RowActions(
                load = { source, at ->
                    repo.more(source, at).also { p ->
                        if (overlays.needsStreams && source.server == null) StreamCache.prefetch(p.items.filter { it.kind != BaseItemKind.SERIES }.map { it.id }, repo::streamTagsBatch)
                    }
                },
                locks = shuffleLocks,
                setLocked = { source, on -> hook.store.setShuffleLock(source.lockKey, on) },
                seeAll = { state ->
                    if (NavGuard.allow()) {
                        state.gridAt = 0
                        // Back from it lands on See all again, as a tile's page lands on its tile.
                        // Kept apart from the tile's own: on a tile's page it overwrote it, and Back
                        // from the page then landed on Continue Watching instead of the tile
                        ReturnFocus.grid = state.buttons[0]
                        OpenGrid.state = state
                        grid = state
                    }
                },
            )
        }

    // When the remote was last used; read only by the billboard's rotation (no recomposition)
    val lastInput = remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    Box(
        modifier.fillMaxSize().background(Stage).holdRepeat().onPreviewKeyEvent {
            if (HeldKeys.behind(it)) return@onPreviewKeyEvent true
            lastInput.longValue = android.os.SystemClock.uptimeMillis()
            Conductor.touch()
            if (it.type == KeyEventType.KeyDown && it.key in DPAD) moves.incrementAndGet()
            false
        },
    ) {
        // A page from a tile (or a row's See all) covers the tab. The tab stays built underneath,
        // hidden and closed to focus, so Back returns to the same scroll and the same tile
        // (it used to be torn down: focus came back on Play and the rows were at the top)
        val covered = openPage != null || grid != null
        // A page or grid open over the tab takes focus itself; closing it returns focus to the tile
        // or See all ([ReturnFocus]), so the tab mustn't grab Play then
        LaunchedEffect(covered) { if (covered) grabFocus = false }
        val tabAlpha = animateFloatAsState(if (covered) 0f else 1f, if (covered) tween(160) else tween(380, easing = CinemaEase), label = "tabAlpha")
        val tabFocus = remember { FocusRequester() }
        LaunchedEffect(covered) {
            if (!covered && returning) {
                returning = false
                // A frame for focus to be allowed back in, then the restorer puts it on the tile
                withFrameNanos {}
                val back = ReturnFocus.target
                ReturnFocus.target = null
                val ok = back?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true || runCatching { tabFocus.requestFocus() }.getOrDefault(false)
                if (!ok) refocus = true
            }
        }
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = tabAlpha.value }
                .focusRequester(tabFocus)
                .focusProperties { onEnter = { if (covered) cancelFocusChange() } }
                .focusRestorer()
                .focusGroup(),
        ) {
            CompositionLocalProvider(LocalArt provides art, LocalOverlays provides overlays, LocalStreamLookup provides StreamLookup(repo::streamTagsOf), LocalRatingPrefs provides ratingPrefs, LocalRatings provides ratings, LocalRowActions provides rowActions) {
                AnimatedContent(
                    targetState = tab,
                    // Overlapping: the old tab eases out as the new one comes in, no dip to black between
                    transitionSpec = { fadeIn(tween(420, delayMillis = 60, easing = CinemaEase)) togetherWith fadeOut(tween(260, easing = CinemaFade)) },
                    label = "tab",
                ) { t ->
                    val d = pages[t]
                    val error = errors[t]
                    Box(Modifier.fillMaxSize()) {
                        when {
                            d == null && error != null ->
                                LoadError(error, onRetry = { attempt++ }, onClassic = null, Modifier.align(Alignment.Center))
                            // The logo only shows while the app starts; other tabs just fade in
                            d == null -> if (t == CinemaTab.HOME) Wordmark(Modifier.align(Alignment.Center), size = 34)
                            t == CinemaTab.MY_LIST -> {
                                // Opened as the app's first screen, or back from a title: its first title
                                val take = t == tab && !covered && (grabFocus || refocus)
                                MyListScreen(d.rows.firstOrNull()?.items.orEmpty(), onOpen, focusAt = if (take) 0 else null)
                                if (take) {
                                    LaunchedEffect(Unit) {
                                        grabFocus = false
                                        refocus = false
                                    }
                                }
                            }
                            // A page with no rows switched on (or nothing in them yet) says how to fill it
                            d.rows.isEmpty() -> EmptyPage(t, Modifier.align(Alignment.Center))
                            else -> {
                                // Only the tab being shown (the old one is still fading out)
                                val focusPlay = t == tab && !covered && (grabFocus || refocus)
                                CinemaScreen(d, "tab:${t.name}", onOpen, onPlay, focusPlay, rollUp, lastInput, onTile, onGrabbed = {
                                    grabFocus = false
                                    refocus = false
                                })
                            }
                        }
                    }
                }
            }
            TopNav(tab, kidsTab, onTab = {
                // Picked from the menu: focus stays on the menu
                grabFocus = false
                refocus = false
                tab = it
            }, onNavigate = {
                if (it == CinemaNav.Search || it == CinemaNav.Settings) NavReturn.to = it
                onNavigate(it)
            }, searchFocus, settingsFocus)
            if (navBack != null) {
                LaunchedEffect(Unit) {
                    val icon = if (navBack == CinemaNav.Search) searchFocus else settingsFocus
                    for (attempt in 0 until 30) {
                        withFrameNanos {}
                        if (runCatching { icon.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                    }
                    refocus = true
                }
            }
        }
        // A service's, genre's or decade's page. A row of it opened full screen (See all) covers it,
        // and it stays built underneath, hidden and closed to focus, like the tab under a page:
        // it used to be torn down, so Back from See all rebuilt it and landed on its Play
        openPage?.let { id ->
            val underGrid = grid != null
            val pageAlpha = animateFloatAsState(if (underGrid) 0f else 1f, if (underGrid) tween(160) else tween(380, easing = CinemaEase), label = "pageAlpha")
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = pageAlpha.value }
                    .focusProperties { onEnter = { if (underGrid) cancelFocusChange() } }
                    .focusGroup(),
            ) {
                CompositionLocalProvider(LocalArt provides art, LocalOverlays provides overlays, LocalStreamLookup provides StreamLookup(repo::streamTagsOf), LocalRatingPrefs provides ratingPrefs, LocalRatings provides ratings, LocalRowActions provides rowActions) {
                    if (serverOfTile(id) != null) {
                        // A server's tile (My Servers): its libraries
                        ServerPageView(
                            id,
                            pages[CinemaTab.SERVERS],
                            onOpen,
                            onPlay,
                            onClose = {
                                openPage = null
                                returning = true
                            },
                            rollUp = rollUp,
                            lastInput = lastInput,
                            covered = underGrid,
                        )
                    } else {
                        CloudPageView(
                            id,
                            pageSeries,
                            { pageSeries = it },
                            repo,
                            onOpen,
                            onPlay,
                            onClose = {
                                openPage = null
                                returning = true
                            },
                            rollUp = rollUp,
                            lastInput = lastInput,
                            covered = underGrid,
                        )
                    }
                }
            }
        }
        // Back from a See all opened on a page: focus back on that row's See all
        var pageReturning by remember { mutableStateOf(false) }
        // The row the See all was of: its buttons are only built while the row has focus, so when
        // See all can't take focus back, the card that was focused in the grid does (focus was
        // left on nothing and the remote did nothing)
        var gridRow by remember { mutableStateOf<RowState?>(null) }
        // Keyed on the grid alone: clearing [pageReturning] below restarted an effect keyed on it
        // too, cancelling it before it moved focus (Back from See all on a page left focus on nothing)
        LaunchedEffect(grid == null) {
            if (grid != null || !pageReturning) return@LaunchedEffect
            pageReturning = false
            val back = ReturnFocus.grid
            ReturnFocus.grid = null
            val row = gridRow
            gridRow = null
            for (i in 0 until 10) {
                withFrameNanos {}
                if (back != null && runCatching { back.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
            if (row == null) return@LaunchedEffect
            val key = (row.items.getOrNull(row.gridAt) ?: row.items.firstOrNull())?.key ?: return@LaunchedEffect
            row.returnKey = key
            for (i in 0 until 30) {
                withFrameNanos {}
                if (runCatching { row.cardFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                if (i == 3) runCatching { row.reveal?.invoke(key) }
            }
        }
        // After the page, so its Back comes first
        grid?.let { state ->
            BackHandler {
                OpenGrid.state = null
                grid = null
                if (openPage != null) {
                    gridRow = state
                    pageReturning = true
                } else {
                    ReturnFocus.target = ReturnFocus.grid
                    ReturnFocus.grid = null
                    returning = true
                }
            }
            CompositionLocalProvider(LocalArt provides art, LocalOverlays provides overlays, LocalStreamLookup provides StreamLookup(repo::streamTagsOf), LocalRatingPrefs provides ratingPrefs, LocalRatings provides ratings) {
                RowGridScreen(state, rowActions, onOpen)
            }
        }
        // Over everything: the library index being read (rows fill in when it's done)
        // Top right, over the billboard's picture: it never covers a row
        Column(Modifier.align(Alignment.TopEnd).padding(top = 76.dp, end = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
            LibraryNotice(hook)
            PosterNotice()
        }
        // An extra server's title the main server doesn't have: its copies
        copiesOf?.let { com.wholphinplus.sources.ui.TitleSheet(it, search, onDismiss = { copiesOf = null }) }
    }
}

/** Auto poster size just went small: says why and where to change it, for a few seconds. */
@Composable
private fun PosterNotice() {
    val shown = PosterSize.justSwitched
    LaunchedEffect(shown) {
        if (shown) {
            delay(7_000)
            PosterSize.justSwitched = false
        }
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(300, easing = CinemaEase)),
        exit = fadeOut(tween(400, easing = CinemaFade)),
    ) {
        Column(
            Modifier.widthIn(min = 260.dp, max = 340.dp).background(Stage.copy(alpha = 0.9f), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Smaller posters for now", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Pictures were loading slowly, so these load faster. Settings → Home & Look → Poster size.", color = InkDim, fontSize = 12.sp)
        }
    }
}

/**
 * "Getting your library ready": shown while the library index is read in full (the first time,
 * and the weekly refresh), then "Your library is ready" for a moment after a first read. Not
 * focusable; it never takes a key press.
 */
@Composable
private fun LibraryNotice(
    hook: com.wholphinplus.sources.SourceHook,
    modifier: Modifier = Modifier,
) {
    val progress by hook.collections.indexProgress.collectAsState()
    var ready by remember { mutableStateOf(false) }
    var wasFirst by remember { mutableStateOf(false) }
    LaunchedEffect(progress == null) {
        if (progress != null) {
            wasFirst = progress?.first == true
            ready = false
        } else if (wasFirst) {
            wasFirst = false
            ready = true
            delay(4_000)
            ready = false
        }
    }
    val p = progress
    AnimatedVisibility(
        visible = p != null || ready,
        enter = fadeIn(tween(300, easing = CinemaEase)),
        exit = fadeOut(tween(400, easing = CinemaFade)),
        modifier = modifier,
    ) {
        Column(
            Modifier.widthIn(min = 260.dp, max = 340.dp).background(Stage.copy(alpha = 0.9f), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (p == null) {
                Text("Your library is ready", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Every row now has all of its titles.", color = InkDim, fontSize = 12.sp)
            } else {
                val share = if (p.total > 0) p.done.toFloat() / p.total else 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (p.first) "Getting your library ready" else "Updating your library", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${(share * 100).toInt()}%", color = InkDim, fontSize = 13.sp)
                }
                Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(4.dp).background(Plus, RoundedCornerShape(2.dp)))
                }
                Text(
                    if (p.first) "Matching ${"%,d".format(p.total)} titles so every row can show all it has. Browse away; it waits while you use the remote." else "A quick weekly check of ${"%,d".format(p.total)} titles.",
                    color = InkDim,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** A page with no rows yet: where to pick them. */
@Composable
private fun EmptyPage(
    tab: CinemaTab,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Nothing on ${tab.label} yet", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Pick its rows in Settings → Home & Look → Rows: trending charts, streaming Top 10s, curated lists and your own library.",
            color = InkDim,
            fontSize = 16.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** A page that couldn't load: why, Try again (focused), and a way back to Wholphin's home. */
@Composable
internal fun LoadError(
    message: String,
    onRetry: () -> Unit,
    onClassic: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val retry = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Couldn't load this page", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = InkDim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 560.dp))
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeroButton("Try again", Icons.Filled.Refresh, primary = true, modifier = Modifier.focusRequester(retry), onClick = onRetry)
            onClassic?.let { HeroButton("Use the classic home", Icons.Filled.Settings, primary = false, onClick = it) }
        }
    }
}

/** Quiet attempts before a page with nothing to show reports an error. */
internal const val LOAD_TRIES = 3

/** Quiet reloads of a tab whose load failed while a page shows (5 s, 10 s, … then every minute: ~15 min). */
private const val QUIET_TRIES = 20

/** Quiet reloads of a tab that loaded with failed rows (20 s, then 40 s later). */
private const val ROW_RETRIES = 2

/** Cinema mode's top-menu pages. They switch in place, like a streaming app's tabs. */
enum class CinemaTab(
    val label: String,
) {
    HOME("Home"),
    SHOWS("Shows"),
    MOVIES("Movies"),
    NEW_POPULAR("New & Popular"),
    KIDS("Kids"),
    SERVERS("My Servers"),
    MY_LIST("My List"),
}

/** The rows page a tab shows (My Servers and My List have none). */
private val CinemaTab.rowsPage: RowsPage?
    get() =
        when (this) {
            CinemaTab.HOME -> RowsPage.HOME
            CinemaTab.SHOWS -> RowsPage.SHOWS
            CinemaTab.MOVIES -> RowsPage.MOVIES
            CinemaTab.NEW_POPULAR -> RowsPage.NEW_POPULAR
            CinemaTab.KIDS -> RowsPage.KIDS
            CinemaTab.SERVERS, CinemaTab.MY_LIST -> null
        }

internal val TopNavHeight = 54.dp

// The billboard panel's heights (full, buttons hidden, rolled up) and the gap below it. The rows
// sit at RowsTopRolled and slide down by the panel's extra height (see CinemaScreen).
private val PanelTop = 16.dp
private val PanelFull = 262.dp
private val PanelNoButtons = 210.dp
private val PanelRolled = 52.dp
private val GapFull = 18.dp
private val GapRolled = 8.dp
private val RowsTopRolled = TopNavHeight + PanelTop + PanelRolled + GapRolled

private val RolledLogoHeight = 44.dp
private val RolledShadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.75f), blurRadius = 10f)

/**
 * The app's first screen waits up to [WAIT_MS] while the first rows' art and card pictures load
 * into memory (owner, 2026-10-09: the first load may take "a second longer… to avoid lag straight
 * out the gate"). The saved page used to show at once and its pictures arrived and decoded while
 * the remote was already moving. Once per start; tabs and later loads don't wait.
 */
internal object StartWarmup {
    private const val WAIT_MS = 1_200L
    private const val ROWS = 3
    private const val CARDS = 6
    private var done = false

    suspend fun once(
        context: android.content.Context,
        page: CinemaHomeData,
        art: CinemaArt?,
        overlays: PosterOverlays,
    ) {
        if (done) return
        done = true
        val started = android.os.SystemClock.uptimeMillis()
        // The first screen's cards build whole as it appears (nobody is moving yet)
        CardFill.openUntil = started + WAIT_MS + 1_500L
        val loader = coil3.SingletonImageLoader.get(context)
        val got =
            kotlinx.coroutines.withTimeoutOrNull(WAIT_MS) {
                kotlinx.coroutines.coroutineScope {
                    // The billboard's first picture, too (it fades in from black otherwise)
                    launch { preloadBackdrop(context, page.featured.firstOrNull()?.backdropUrl, WAIT_MS) }
                    page.rows.filterNot { it.loading }.take(ROWS).flatMap { row -> row.items.take(CARDS).map { row to it } }.map { (row, item) ->
                        async {
                            if (row.ranked) {
                                // Top 10 posters ([PosterFace])
                                (item.posterUrl ?: item.cardUrl)?.let { loader.execute(cardRequest(context, it, 224, 336)) }
                            } else {
                                val a = item.tmdbId?.let { id -> art?.art(item.tmdbTv, id) }
                                cardChoice(item, a, overlays).url?.let { loader.execute(cardRequest(context, it, 416, 234)) }
                            }
                        }
                    }.awaitAll()
                }
            }
        timber.log.Timber.i("Start warm-up: %s in %d ms", if (got != null) "first rows ready" else "gave up waiting", android.os.SystemClock.uptimeMillis() - started)
    }
}

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

    /** A Services or Genres tile: the billboard keeps the last title shown. */
    fun onTile(rowIndex: Int) {
        billboard = false
        row = rowIndex
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CinemaScreen(
    data: CinemaHomeData,
    /** Which page this is ("tab:HOME", "page:…"): its rows' state and the card to come back to. */
    screenKey: String,
    onOpen: (UUID, BaseItemKind) -> Unit,
    onPlay: (UUID, Long) -> Unit,
    grabFocus: Boolean,
    rollUp: Boolean,
    lastInput: androidx.compose.runtime.MutableLongState,
    onTile: (PageTile) -> Unit = {},
    onGrabbed: () -> Unit = {},
) {
    val context = LocalContext.current
    // Each row's titles as browsed (more loaded, shuffled), kept across refreshes and title pages
    val rowStates = remember(screenKey) { RowMemory.page(screenKey) }
    // Back from a title page (or the player) opened here: the card, or the billboard, it came from
    val back = remember(screenKey) { ReturnCard.take(screenKey) }
    val backRow = remember(back) { back?.row?.let { r -> data.rows.indexOfFirst { it.title == r && !it.loading } }?.takeIf { it >= 0 } }
    val backItem = remember(back) { backRow?.let { i -> (rowStates[data.rows[i].title]?.items ?: data.rows[i].items).firstOrNull { it.key == back?.key } } }
    // Coming back to a card, the billboard is as it was left: rolled up over that row, its title
    val focus =
        remember {
            HomeFocus().apply {
                if (backRow != null && backItem != null) {
                    billboard = false
                    row = backRow
                    focused = backItem
                }
            }
        }
    // 0 = full billboard, 1 = rolled up to a quarter while browsing rows (if the setting is on).
    // Read only in layout/draw, so the animation never recomposes the screen.
    // The first row (Continue Watching) still shows the full billboard for the focused card;
    // it rolls up from the second row down
    // Derived so moving between rows only recomposes when "rolled" actually flips, not on every row
    val rolled by remember(rollUp) { derivedStateOf { rollUp && !focus.billboard && focus.row >= 1 } }
    val roll = animateFloatAsState(if (rolled) 1f else 0f, CinemaGlide, label = "roll")
    // 1 while the buttons are hidden (browsing rows): the panel drops their space so the first
    // row moves up instead of leaving a gap
    val noButtons = animateFloatAsState(if (focus.billboard) 0f else 1f, CinemaGlide, label = "noButtons")
    var featuredIndex by remember { mutableIntStateOf(0) }
    val featured = data.featured.getOrNull(featuredIndex % data.featured.size.coerceAtLeast(1))
    val playFocus = remember { FocusRequester() }
    // Rows just off screen are kept built (in idle time, while the remote is still), so a move
    // down never builds a whole row of cards inside a scroll frame: that took 37-51 ms on the
    // Shield, a visible stop mid-glide
    val columnState = rememberLazyListState(cacheWindow = remember { androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow(
                    // Full: about three rows each way (held Up/Down reuses them instead of rebuilding); lighter boxes keep less
                    ahead = if (com.wholphinplus.sources.DeviceClass.tier == com.wholphinplus.sources.DeviceClass.Tier.FULL) 900.dp else 520.dp * com.wholphinplus.sources.DeviceClass.cacheScale,
                    // Behind: about three rows on Full (eight made every press heavier, measured 2026-10-09)
                    behind = if (com.wholphinplus.sources.DeviceClass.tier == com.wholphinplus.sources.DeviceClass.Tier.FULL) 900.dp else 260.dp * com.wholphinplus.sources.DeviceClass.cacheScale,
                ) })
    // The page counts as moving too (one counter for the billboard to wait on)
    ReportMotion(columnState, page = true)
    // Card pictures load around you while the remote rests
    PosterPrefetch.Follow(data, columnState)
    val scope = rememberCoroutineScope()

    // What the top shows. While you move it waits until the move has finished (every row and
    // the page have stopped scrolling), so fast scrolling stays fluid instead of reloading art on every
    // press. Changing it builds a new panel and starts the backdrop's crossfade, a 20-30 ms frame
    // on the Shield: landing mid-scroll (it used to come at 220 ms) was the stutter after each move.
    // Held as a State and read only inside the backdrop and the panel: read here, every change
    // recomposed the whole screen and re-measured every row on it (~10 ms on the Shield)
    val shownState = remember { mutableStateOf(focus.focused ?: featured) }
    var shown by shownState
    LaunchedEffect(featured) {
        snapshotFlow { if (focus.billboard) featured else focus.focused ?: featured }.collectLatest { target ->
            // The same title in a fresher copy (a refresh after watching): shown as it is, at once
            if (target != null && target.key == shown?.key && target != shown) shown = target
            if (target?.key != shown?.key) {
                if (!focus.billboard) {
                    // Start fetching the picture at once, wait out the move, then give it a little
                    // longer, so text and picture change together
                    // The picture is only asked for once focus rests a moment: holding the remote
                    // started a full backdrop download per press that was then thrown away (on
                    // HTTP/2 a cancelled one still arrives in full), and the posters being looked
                    // at queued behind them. It still gets the rest of the glide as a head start.
                    delay(SETTLE_MS)
                    coroutineScope {
                        val picture = async { preloadBackdrop(context, target?.backdropUrl, 520) }
                        snapshotFlow { CardFill.anyBusy }.first { !it }
                        picture.await()
                    }
                }
                // A marker in system traces (Perfetto), to check when it lands against the scrolling
                android.os.Trace.beginSection("Orca:billboard")
                shown = target
                android.os.Trace.endSection()
            }
        }
    }

    LaunchedEffect(focus.billboard, featuredIndex, data.featured.size) {
        if (focus.billboard && data.featured.size > 1) {
            delay(10_000)
            // Nobody has touched the remote for 2 minutes: hold this title, so the screen comes
            // to rest (nothing redrawn), until the next key press
            if (android.os.SystemClock.uptimeMillis() - lastInput.longValue > IDLE_MS) {
                val since = lastInput.longValue
                snapshotFlow { lastInput.longValue }.first { it != since }
            }
            // Next picture ready first, so the title and its picture swap together
            preloadBackdrop(context, data.featured[(featuredIndex + 1) % data.featured.size].backdropUrl, 3_000)
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
            // Glides back when the top is near (it nearly always is); from far down, the stock jump
            if (columnState.firstVisibleItemIndex == 0) {
                columnState.animateScrollBy(-columnState.firstVisibleItemScrollOffset.toFloat(), CinemaGlide)
            } else {
                columnState.animateScrollToItem(0)
            }
        }
        Unit
        }
    }
    BillboardBack(focus, toBillboard)
    DownTargets.Register(screenKey, playFocus)
    // Focus on arrival: back on the card that was opened, else Play (when this page is to take it).
    // Keyed on [grabFocus], so a later ask (refocus) is answered too; tried each frame until the
    // target is attached.
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(grabFocus) {
        val spot = back.takeUnless { arrived }
        arrived = true
        if (spot == null && !grabFocus) return@LaunchedEffect
        val state = backRow?.let { rowStates[data.rows[it].title] }
        if (state != null && backItem != null) {
            state.returnKey = backItem.key
            for (attempt in 0 until 30) {
                withFrameNanos {}
                if (runCatching { state.cardFocus.requestFocus() }.getOrDefault(false)) {
                    onGrabbed()
                    return@LaunchedEffect
                }
                // Not built (the page wasn't where it was left): bring the row into view
                if (attempt == 3) runCatching { columnState.scrollToItem(backRow) }
                // The card itself not built (far along its row): bring it in
                if (attempt == 6) runCatching { state.reveal?.invoke(backItem.key) }
            }
        }
        toBillboard()
        onGrabbed()
    }
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
    // Where this page's focus was when it opened something over itself (title page, player)
    val markCard = remember(screenKey) { { row: String, item: CinemaItem -> ReturnCard.mark(screenKey, row, item.key) } }
    val density = LocalDensity.current
    val rowSpec = remember(density) { pivot(with(density) { 40.dp.toPx() }) }
    val cardSpec = remember(density) { pivot(with(density) { 48.dp.toPx() }) }
    // Top 10 rows land the poster after its number, so the number stays on screen
    val topSpec = remember(density) { pivot(with(density) { (48.dp + NumeralWidth).toPx() }) }

    // A fresh copy of the page (a reload, the 6-hourly refresh): each row's browsed titles are
    // reconciled with it in place, so loaded titles, a shuffle and the focused card stay
    remember(data) { data.rows.forEach { r -> rowStates[r.title]?.refresh(r) } }
    // The focused title's fresh copy (the billboard shows it): matched by title, as the
    // episode to resume may have moved on
    LaunchedEffect(data) {
        focus.focused?.let { f ->
            data.rows.firstNotNullOfOrNull { r -> r.items.firstOrNull { it.detailsId == f.detailsId } }?.takeIf { it != f }?.let { focus.focused = it }
        }
    }
    // Up from the first real row brings the billboard back (a placeholder row can't take focus)
    val firstRow = data.rows.indexOfFirst { !it.loading }
    Box(Modifier.fillMaxSize()) {
        // Under everything else, so it's never seen
        NumeralWarmup()
        ShownBackdrop(shownState, focus)
        // Rolled up, the rows sit higher over the picture: darken behind them so titles stay legible
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = roll.value
                // Fades drawn with the alpha, not in a layer of their own (see RowControls)
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
            }.background(
                Brush.verticalGradient(0.12f to Color.Transparent, 0.4f to Stage.copy(alpha = 0.78f), 1f to Stage),
            ),
        )
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(TopNavHeight))
            InfoPanel(
                item = { shownState.value },
                billboard = { focus.billboard },
                roll = { roll.value },
                noButtons = { noButtons.value },
                playFocus = playFocus,
                onPlay = {
                    shown?.let {
                        StageArt.set(it)
                        ReturnCard.mark(screenKey, null, null)
                        onPlay(it.id, it.resumeMs)
                    }
                },
                onMoreInfo = {
                    shown?.let {
                        DetailsPreview.put(it)
                        ReturnCard.mark(screenKey, null, null)
                        onOpen(it.detailsId, it.detailsKind)
                    }
                },
                onButtonsFocused = { focus.billboard = true },
            )
        }
        // The rows keep one fixed layout (where they sit when rolled up) and slide down by the
        // panel's extra height. Animating the panel's height used to re-measure the whole list of
        // rows on every frame of a roll: 5-6 dropped frames per roll on the emulator.
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec.gliding(columnState)) {
            LazyColumn(
                state = columnState,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 220.dp),
                modifier =
                    Modifier.fillMaxSize().padding(top = RowsTopRolled).graphicsLayer {
                        val panel = lerp(lerp(PanelFull, PanelNoButtons, noButtons.value), PanelRolled, roll.value)
                        val gap = lerp(GapFull, GapRolled, roll.value)
                        translationY = (panel + gap - PanelRolled - GapRolled).toPx()
                    },
            ) {
                itemsIndexed(data.rows, key = { _, r -> r.title }, contentType = { _, r -> if (r.tiles.isNotEmpty()) "tiles" else "row" }) { i, row ->
                    if (row.loading) {
                        LoadingRow(row)
                    } else if (row.tiles.isNotEmpty()) {
                        val onTileFocused = remember(focus, i) { { focus.onTile(i) } }
                        TileRowView(row, cardSpec, onTileFocused, onTile, if (i == firstRow) upToBillboard else Modifier)
                    } else {
                        val onCard = remember(focus, i) { { item: CinemaItem -> focus.onCard(item, i) } }
                        val state = rowStates.getOrPut(row.title) { RowState(row) }
                        val onClick =
                            remember(onCardClick, markCard, row.title) {
                                { item: CinemaItem ->
                                    markCard(row.title, item)
                                    onCardClick(item)
                                }
                            }
                        CinemaRowView(row, state, if (row.ranked) topSpec else cardSpec, onCard, onClick, if (i == firstRow) upToBillboard else Modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopNav(
    tab: CinemaTab,
    kidsTab: Boolean,
    onTab: (CinemaTab) -> Unit,
    onNavigate: (CinemaNav) -> Unit,
    searchFocus: FocusRequester,
    settingsFocus: FocusRequester,
) {
    val selected = remember { FocusRequester() }
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 40.dp, top = 18.dp).height(TopNavHeight - 18.dp)
            // Up from Play lands on the tab you're on (it took the nearest pill: Home)
            .upToSelected(selected)
            // Down from the menu lands on Play (My List: its first title). The nearest thing below
            // the pill was taken: More Info, or My List's third card
            .onPreviewKeyEvent {
                it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown &&
                    DownTargets.of("tab:${tab.name}")?.let { f -> runCatching { f.requestFocus() }.getOrDefault(false) } == true
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Wordmark(Modifier.padding(end = 18.dp), shine = true)
        CinemaTab.entries.filter { it != CinemaTab.KIDS || kidsTab }.forEach { t ->
            NavPill(t.label, selected = t == tab, modifier = if (t == tab) Modifier.focusRequester(selected) else Modifier) { onTab(t) }
        }
        Spacer(Modifier.weight(1f))
        NavIcon(Icons.Filled.Search, "Search", Modifier.focusRequester(searchFocus)) { onNavigate(CinemaNav.Search) }
        NavIcon(Icons.Filled.Settings, "Settings", Modifier.focusRequester(settingsFocus)) { onNavigate(CinemaNav.Settings) }
    }
}

@Composable
internal fun NavPill(
    text: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val f = rememberFocusFade(if (selected) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0f), Ink, if (selected) Ink else InkDim, Stage)
    Surface(
        onClick = onClick,
        modifier = modifier.glideLift(scale = 1.04f, edge = false).tapToClick(onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(containerColor = f.fill, contentColor = f.content, focusedContainerColor = f.fill, focusedContentColor = f.content),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        interactionSource = f.source,
    ) {
        Text(text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

@Composable
private fun NavIcon(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val f = rememberFocusFade(Color.White.copy(alpha = 0f), Ink, Ink, Stage)
    Surface(
        onClick = onClick,
        modifier = modifier.glideLift(scale = 1.08f, edge = false).tapToClick(onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(containerColor = f.fill, contentColor = f.content, focusedContainerColor = f.fill, focusedContentColor = f.content),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        interactionSource = f.source,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.padding(7.dp).size(20.dp))
    }
}

/** A top bar entered with Up (from Play) lands on [selected], the pill of the page being shown. */
internal fun Modifier.upToSelected(selected: FocusRequester): Modifier =
    focusProperties {
        onEnter = {
            if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Up) runCatching { selected.requestFocus() }
        }
    }.focusGroup()

/** Height eased between [full] and [rolled] by [roll] (0..1), in the layout pass only. */
private fun Modifier.rollHeight(
    full: Dp,
    rolled: Dp,
    roll: () -> Float,
): Modifier = animatedHeight { lerp(full, rolled, roll()) }

/** Width eased between [full] and [rolled] by [roll] (0..1), in the layout pass only. */
private fun Modifier.rollWidth(
    full: Dp,
    rolled: Dp,
    roll: () -> Float,
): Modifier =
    layout { measurable, constraints ->
        val w = lerp(full, rolled, roll()).roundToPx().coerceAtMost(constraints.maxWidth)
        val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
        layout(w, placeable.height) { placeable.place(0, 0) }
    }

/**
 * A height read in the layout pass only, so animating it never recomposes. Clips its content,
 * allowing [slack] past the left, right and bottom edges (a focused button's zoom).
 */
private fun Modifier.animatedHeight(
    slack: Dp = 0.dp,
    height: () -> Dp,
): Modifier =
    (if (slack == 0.dp) clipToBounds() else drawWithContent {
        val s = slack.toPx()
        clipRect(-s, 0f, size.width + s, size.height + s) { this@drawWithContent.drawContent() }
    }).layout { measurable, constraints ->
        val h = height().roundToPx()
        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
        layout(placeable.width, h) { placeable.place(0, 0) }
    }

/**
 * Fixed-height panel: the focused title changing or the buttons fading never change its size,
 * so the rows below don't move. Only rolling up (the setting) shrinks it, to a quarter: a small
 * logo and the episode or Top 10 line stay, the rest fades out.
 */
/** Back while browsing rows brings the billboard back (its own scope: flips don't recompose the screen). */
@Composable
private fun BillboardBack(
    focus: HomeFocus,
    toBillboard: () -> Unit,
) {
    BackHandler(enabled = !focus.billboard) { toBillboard() }
}

/** The server's backdrop, else TMDB's clean picture (some servers have none for a title). */
@Composable
private fun ShownBackdrop(
    shown: androidx.compose.runtime.State<CinemaItem?>,
    focus: HomeFocus,
) {
    val item = shown.value
    val art = rememberArt(item)
    StableBackdrop(item?.backdropUrl ?: art?.cleanBackdrop?.let { "https://image.tmdb.org/t/p/w1280$it" }, drift = focus.billboard)
}

@Composable
private fun InfoPanel(
    item: () -> CinemaItem?,
    billboard: () -> Boolean,
    roll: () -> Float,
    noButtons: () -> Float,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    onMoreInfo: () -> Unit,
    onButtonsFocused: () -> Unit,
) {
    // Full: details + buttons (262). Buttons hidden: details only (210). Rolled up: a quarter (72).
    // Wide enough for the rolled-up details beside the logo; the text below keeps 480 dp
    Box(Modifier.padding(start = 48.dp, top = PanelTop).animatedHeight(slack = 12.dp) { lerp(lerp(PanelFull, PanelNoButtons, noButtons()), PanelRolled, roll()) }.widthIn(max = 1000.dp)) {
        AnimatedContent(
            targetState = item(),
            // The fade is drawn below with the alpha: AnimatedContent's own fade put each panel in
            // a layer of its own for every frame of it (the Shield's GPU draws that twice)
            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            contentKey = { it?.key },
            label = "info",
        ) { shownItem ->
            val shownAlpha by transition.animateFloat(
                // Overlapping, not out-then-in: the old text eases away as the new one comes up
                transitionSpec = { if (targetState == EnterExitState.Visible) tween(520, delayMillis = 60, easing = CinemaEase) else tween(260, easing = CinemaFade) },
                label = "infoAlpha",
            ) { if (it == EnterExitState.Visible) 1f else 0f }
            // The new title rises the last few dp into place as it fades in; the old one stays put
            val rising = transition.targetState == EnterExitState.Visible
            // The rolled-up details and the full ones are never seen together (each is invisible past
            // half the roll), so only one is built: a change measured both (~16 texts, 9 ms on the
            // Shield). They swap at the halfway point, where both are transparent.
            val rolledNow by remember { derivedStateOf { roll() > 0.5f } }
            Column(
                Modifier.graphicsLayer {
                    alpha = shownAlpha
                    if (rising) translationY = (1f - shownAlpha) * 14.dp.toPx()
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                },
            ) {
                if (shownItem == null) return@Column
                val logo = rememberArt(shownItem)?.logoUrl() ?: shownItem.logoUrl
                // The logo's shape once loaded: rolled up, its box hugs it, so the details sit
                // right beside it instead of after a fixed 220 dp slot (a narrow logo left a gap)
                var logoAspect by remember(logo) { mutableFloatStateOf(0f) }
                val rolledLogo = if (logo != null && logoAspect > 0f) (RolledLogoHeight * logoAspect).coerceIn(48.dp, 220.dp) else 220.dp
                Row(Modifier.rollHeight(70.dp, RolledLogoHeight, roll), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.fillMaxHeight().rollWidth(340.dp, rolledLogo, roll), contentAlignment = Alignment.BottomStart) {
                    // A logo that arrives a moment late crossfades over the title text
                    Crossfade(targetState = logo, animationSpec = tween(300, easing = CinemaEase), label = "logo") { l ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                            if (l != null) {
                                AsyncImage(
                                    model = rememberPlain(l),
                                    contentDescription = shownItem.title,
                                    contentScale = ContentScale.Fit,
                                    alignment = Alignment.BottomStart,
                                    onSuccess = { r -> r.painter.intrinsicSize.let { if (it.height > 0f) logoAspect = it.width / it.height } },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                // No logo: the name shrinks to fit two lines of the logo's box rather than
                                // being cut off ("Echoes of the Ice A…" at one line of 34 sp)
                                androidx.compose.foundation.text.BasicText(
                                    shownItem.title,
                                    style = TextStyle(color = Ink, fontWeight = FontWeight.ExtraBold, lineHeight = 1.05.em),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 34.sp, stepSize = 2.sp),
                                )
                            }
                        }
                    }
                }
                // Rolled up, the details sit beside the logo, after a hairline: still there while
                // browsing rows. A soft shadow keeps them legible over a bright picture.
                if (rolledNow) Row(
                    Modifier.graphicsLayer {
                        alpha = ((roll() - 0.5f) * 2f).coerceIn(0f, 1f)
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.padding(horizontal = 18.dp).width(1.dp).height(30.dp).background(Ink.copy(alpha = 0.3f)))
                    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(shadow = RolledShadow)) {
                        Column(Modifier.widthIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                // The Top 10 place joins the details (its own line folds away rolled up)
                                shownItem.rank?.takeIf { shownItem.subtitle == null }?.let { RankLine(it, null) }
                                MetaLine(Meta(shownItem.meta, shownItem.rating))
                            }
                            // An episode's name says more than the show's story here
                            Text(
                                shownItem.subtitle ?: shownItem.overview,
                                color = if (shownItem.subtitle != null) Ink else Ink.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                fontWeight = if (shownItem.subtitle != null) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                }
                Box(Modifier.rollHeight(8.dp, 0.dp, roll))
                // One fixed line: the episode, else the title's Top 10 place, else blank. Rolled up it
                // folds away (the details beside the logo carry it), so the rows sit 20 dp higher
                Box(Modifier.rollHeight(20.dp, 0.dp, roll), contentAlignment = Alignment.CenterStart) {
                    val rank = shownItem.rank
                    when {
                        shownItem.subtitle != null ->
                            Text(shownItem.subtitle, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        rank != null -> RankLine(rank, shownItem.rankLabel)
                    }
                }
                Box(Modifier.rollHeight(8.dp, 0.dp, roll))
                if (!rolledNow) Column(Modifier.widthIn(max = 480.dp).graphicsLayer {
                    alpha = (1f - roll() * 2f).coerceIn(0f, 1f)
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetaLine(Meta(shownItem.meta, shownItem.rating))
                    Text(shownItem.overview, color = Ink.copy(alpha = 0.86f), fontSize = 14.sp, lineHeight = 20.sp, minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        AnimatedVisibility(
            visible = billboard(),
            // Waits for the panel to mostly grow back (~0.4 s on CinemaGlide), so the buttons never fade in
            // over the description while there's no room for them yet
            enter = fadeIn(tween(320, delayMillis = 360, easing = CinemaEase)),
            exit = fadeOut(tween(200, easing = CinemaFade)),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroButton("Play", Icons.Filled.PlayArrow, primary = true, modifier = Modifier.focusRequester(playFocus), onFocused = onButtonsFocused, onClick = onPlay)
                HeroButton("More Info", Icons.Filled.Info, primary = false, onFocused = onButtonsFocused, onClick = onMoreInfo)
            }
        }
    }
}

/**
 * A row still on its way: its name with a small spinner, and cards of the real size gently
 * pulsing, so it fills in where it stands. Not focusable: the remote passes over it until it's in.
 * The pulse and the spinner are drawn only (no recomposition per frame).
 */
@Composable
private fun LoadingRow(row: CinemaRow) {
    val motion = androidx.compose.animation.core.rememberInfiniteTransition(label = "loading")
    val glow =
        motion.animateFloat(
            0.05f,
            0.11f,
            androidx.compose.animation.core.infiniteRepeatable(tween(900, easing = CinemaEase), androidx.compose.animation.core.RepeatMode.Reverse),
            label = "glow",
        )
    val turn = motion.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(tween(1000, easing = androidx.compose.animation.core.LinearEasing)), label = "turn")
    fun Modifier.pulse(corner: Dp) =
        drawBehind { drawRoundRect(Color.White.copy(alpha = glow.value), cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner.toPx())) }
    val captions = LocalOverlays.current.captions
    // Laid out like CinemaRowView: the same name line, gaps and card sizes
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(row.title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Box(
                Modifier.size(12.dp).drawBehind {
                    rotate(turn.value) {
                        drawArc(Plus, 0f, 270f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    }
                },
            )
        }
        Row(
            Modifier.fillMaxWidth().clipToBounds().wrapContentWidth(Alignment.Start, unbounded = true).padding(start = 48.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (row.ranked) 4.dp else 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            repeat(6) {
                if (row.ranked) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Spacer(Modifier.width(NumeralWidth).height(PosterHeight))
                        Box(Modifier.width(PosterWidth).height(PosterHeight).pulse(4.dp))
                    }
                } else {
                    Column(Modifier.width(208.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).pulse(6.dp))
                        // The caption's room, so the row is as tall as it will be
                        if (captions) {
                            Column(Modifier.fillMaxWidth().padding(top = 9.dp, start = 2.dp, end = 2.dp)) {
                                Text(" ", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Row(Modifier.padding(top = 2.dp)) { Text(" ", fontSize = 11.sp, maxLines = 1) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CinemaRowView(
    row: CinemaRow,
    state: RowState,
    cardSpec: BringIntoViewSpec,
    onItemFocused: (CinemaItem) -> Unit,
    onItemClick: (CinemaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(Modifier.onPreviewKeyEvent(state::stepButtons).then(modifier).onFocusChanged { state.focused = it.hasFocus }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The name and how many titles the whole row has
        Row(Modifier.padding(start = 48.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(row.title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            state.count?.takeIf { it > 0 }?.let { Text("$it titles", color = InkDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 1.dp)) }
        }
        // The controls sit in the left margin, so the first card stays where every row starts
        val actions = LocalRowActions.current
        val controls = !row.ranked && state.source != null && actions != null
        // The cards just off screen to the side are built only for the row you rest on (owner,
        // 2026-10-09: rows "don't have to load too many horizontal unless the user stops on the
        // row"): a row passed through builds its visible cards only, about 5 instead of 7
        val rested = remember { mutableStateOf(false) }
        LaunchedEffect(state) {
            snapshotFlow { state.focused }.collectLatest { here ->
                if (here) kotlinx.coroutines.delay(ROW_REST_MS)
                rested.value = here
            }
        }
        val rowState = androidx.compose.foundation.lazy.rememberLazyListState(cacheWindow = remember { RestedRowWindow(rested) })
        // The row you rest on looks up the art of the next cards ahead of you: the page's warm-up
        // covers each row's first 8, and past them every card waited for its art (up to 1.5 s)
        // before its picture even started (owner: "the posters can't keep up… even though I'm
        // moving slow")
        val artAhead = LocalArt.current
        LaunchedEffect(state, rowState) {
            snapshotFlow { if (rested.value) rowState.firstVisibleItemIndex else -1 }.collectLatest { first ->
                if (first < 0 || artAhead == null) return@collectLatest
                state.items.drop(first).take(com.wholphinplus.sources.DeviceClass.artAhead).forEach { item ->
                    item.tmdbId?.let { id -> launch { runCatching { artAhead.art(item.tmdbTv, id) } } }
                }
            }
        }
        ReportMotion(rowState)
        // Continue Watching after a play: the title just watched comes first, and the list kept its
        // place on the card it showed, leaving the new first card off screen. Back to its start
        // (not while the remote is in the row: focus keeps its card then).
        if (row.continueWatching) {
            val first = state.items.firstOrNull()?.key
            val seen = remember { mutableStateOf(first) }
            LaunchedEffect(first) {
                if (first == seen.value) return@LaunchedEffect
                seen.value = first
                // Glides back (the browsing glide); it used to jump there in one frame
                if (!state.focused) glideToStart(rowState)
            }
        }
        // Continue Watching's long-press menu, and the cards it took out this session
        var menuFor by remember { mutableStateOf<CinemaItem?>(null) }
        val openMenu = remember { { item: CinemaItem -> menuFor = item } }
        val menuScope = rememberCoroutineScope()
        val continueRepo = rememberContinueRepo()
        val shownItems = if (row.continueWatching) state.items.filterNot { ContinueEdits.hides(it, continueRepo.first.overlay) } else state.items
        // Left and Right move by position ([RowSteps])
        val steps = remember { RowSteps() }
        val stepKeys = (if (row.ranked) row.items else shownItems).map { it.key }
        val focusedHere: (CinemaItem) -> Unit = { steps.at = it.key; onItemFocused(it) }
        // Nearing the end asks for more: a row goes on as long as its list
        if (actions != null && !row.ranked) LoadNearEnd({ state.more(actions.load) }) { rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to rowState.layoutInfo.totalItemsCount }
        DisposableEffect(state, rowState, controls) {
            state.reveal = { key ->
                val at = state.items.indexOfFirst { it.key == key }
                if (at >= 0) rowState.scrollToItem(at + if (controls) 1 else 0)
            }
            onDispose { state.reveal = null }
        }
        // The row reaches one card past the screen's left edge ([LeftReach]): the card before the
        // focused one is placed there, out of sight, so Left finds it like Right finds the next
        val reach = with(LocalDensity.current) { LeftReach.roundToPx() }
        val reachSpec = remember(cardSpec, reach) { cardSpec.reaching(reach.toFloat()) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides reachSpec.gliding(rowState), LocalRowRested provides rested) {
            LazyRow(
                state = rowState,
                // Right after the last card (a Top 10's #10) stays there; it used to reach the top menu
                modifier =
                    Modifier
                        .layout { m, c ->
                            val p = m.measure(c.copy(minWidth = c.minWidth + reach, maxWidth = if (c.hasBoundedWidth) c.maxWidth + reach else c.maxWidth))
                            layout((p.width - reach).coerceAtLeast(0), p.height) { p.place(-reach, 0) }
                        }.staysInRow()
                        .onPreviewKeyEvent { steps.step(it, stepKeys, rowState) },
                horizontalArrangement = Arrangement.spacedBy(if (row.ranked) 4.dp else 10.dp),
                contentPadding = PaddingValues(start = (if (controls) 4.dp else 48.dp) + LeftReach, end = 400.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (row.ranked) {
                    items(row.items, key = { it.key }, contentType = { "top10" }) { item ->
                        Top10Card(item, item.rank ?: (row.items.indexOf(item) + 1), focusedHere, onItemClick, if (item.key == state.returnKey) Modifier.focusRequester(state.cardFocus) else Modifier)
                    }
                } else {
                    // Above the cards, so a button's label can show over the first one
                    if (controls) item(key = "controls", contentType = "controls") { Box(Modifier.zIndex(1f)) { RowControls(state, actions!!) } }
                    if (row.continueWatching) {
                        // Held OK opens its menu: Remove / Mark as watched; cards then glide closed
                        items(shownItems, key = { it.key }, contentType = { "card" }) { item ->
                            Box(Modifier.animateItem(fadeInSpec = tween(420, easing = CinemaFade), placementSpec = CardsClose, fadeOutSpec = tween(280, easing = CinemaFade))) {
                                CinemaCard(item, focusedHere, onItemClick, modifier = if (item.key == state.returnKey) Modifier.focusRequester(state.cardFocus) else Modifier, onLongClick = openMenu)
                                if (menuFor?.key == item.key) {
                                    ContinueMenu(item, onClose = { menuFor = null }) { action ->
                                        menuFor = null
                                        val shownNow = shownItems
                                        menuScope.launch { applyContinueAction(action, item, state, shownNow, continueRepo.first, continueRepo.second) }
                                    }
                                }
                            }
                        }
                    } else {
                        val items = state.items
                        items(items, key = { it.key }, contentType = { "card" }) { item ->
                            CinemaCard(item, focusedHere, onItemClick, modifier = if (item.key == state.returnKey) Modifier.focusRequester(state.cardFocus) else Modifier)
                        }
                    }
                }
            }
        }
    }
}

/** How far a card row reaches past the screen's left edge: two wide cards (a held Left outruns the glide by about that). */
private val LeftReach = 560.dp

/** [this] for a list whose viewport starts [reachPx] left of the screen: the pivot measured from the screen's edge. */
private fun BringIntoViewSpec.reaching(reachPx: Float): BringIntoViewSpec {
    val inner = this
    return object : BringIntoViewSpec {
        override fun calculateScrollDistance(
            offset: Float,
            size: Float,
            containerSize: Float,
        ): Float = inner.calculateScrollDistance(offset - reachPx, size, containerSize - reachPx)
    }
}

/**
 * Held Left along a row: a press whose card isn't on screen yet waits (owner, 2026-10-09: "going
 * reverse horizontal on a row it's not smooth and bugging out"). The glide trails the focus; going
 * right the next card is always on screen, going left it was still past the left edge, so
 * Compose's focus search laid the row out ~10 times, found nothing and lost the press: two of three
 * held Left presses, the costliest key work on the Shield. Asking that card for focus directly
 * moved it but made the glide jump back ~70 px; skipping the press is cheap, and the next repeat,
 * once the glide has brought the card in, moves as usual.
 */
private class RowSteps {
    var at: Any? = null

    fun step(
        e: androidx.compose.ui.input.key.KeyEvent,
        keys: List<Any>,
        list: androidx.compose.foundation.lazy.LazyListState,
    ): Boolean {
        if (e.type != KeyEventType.KeyDown || e.key != androidx.compose.ui.input.key.Key.DirectionLeft) return false
        // Only a held key's repeat waits: a single press always goes through
        if (e.nativeKeyEvent.repeatCount == 0) return false
        val i = keys.indexOf(at)
        if (i <= 0) return false
        val target = keys[i - 1]
        return list.layoutInfo.visibleItemsInfo.none { it.key == target }
    }
}

/** How long the remote rests on a row before the cards just off its side are built ([RestedRowWindow]). */
private const val ROW_REST_MS = 300L

/** A row's side cache: none until it's [rested] on, then about four cards each way. */
@OptIn(ExperimentalFoundationApi::class)
private class RestedRowWindow(
    private val rested: androidx.compose.runtime.State<Boolean>,
) : androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow {
    override fun androidx.compose.ui.unit.Density.calculateAheadWindow(viewport: Int): Int =
        if (rested.value) com.wholphinplus.sources.DeviceClass.rowAheadDp.dp.roundToPx() else 0

    override fun androidx.compose.ui.unit.Density.calculateBehindWindow(viewport: Int): Int =
        // As much as ahead: the focused card sits at the row's left edge, so going left the next
        // card is always off screen; with one card kept there, each Left press built one and
        // spamming Left felt slower than Right (owner, 2026-10-09)
        if (rested.value) com.wholphinplus.sources.DeviceClass.rowAheadDp.dp.roundToPx() else 0
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

/**
 * Scrolls a row back to its first card on [CinemaGlide]: the distance from the cards' own size
 * (a lazy row only knows the ones built), the last bit by the list itself. Far along (past what a
 * glide reads as one move), the stock jump, as the page does.
 */
private suspend fun glideToStart(state: androidx.compose.foundation.lazy.LazyListState) {
    val first = state.firstVisibleItemIndex
    if (first == 0 && state.firstVisibleItemScrollOffset == 0) return
    if (first > GLIDE_BACK_MAX) return state.scrollToItem(0)
    val info = state.layoutInfo
    val step = info.visibleItemsInfo.firstOrNull()?.let { it.size + info.mainAxisItemSpacing } ?: 0
    state.animateScrollBy(-(first * step + state.firstVisibleItemScrollOffset).toFloat(), CinemaGlide)
    if (state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0) state.scrollToItem(0)
}

/** Cards a row glides back over to its start; further, it jumps. */
private const val GLIDE_BACK_MAX = 12

/** Continue Watching's cards closing the gap a removed one leaves: the browsing glide, no snap. */
private val CardsClose = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 110f, visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold)

/** The Top 10 numbers' style: filled, and the outline drawn over it. */
@Composable
private fun rememberNumeral(): Pair<TextStyle, Stroke> {
    val density = LocalDensity.current
    return remember(density) {
        TextStyle(
            fontSize = 136.sp,
            lineHeight = 136.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-14).sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.Both),
        ) to Stroke(width = with(density) { 2.5.dp.toPx() })
    }
}

/** Set once the Top 10 numbers have been drawn in this process ([NumeralWarmup]). */
private var numeralsWarm = false

/**
 * Draws the Top 10 numbers once, out of sight, while the page is still. The outlined 136 sp
 * digits are built as paths the first time each is drawn: 40 ms on the Shield, which landed on
 * the move that first brought a Top 10 row into view. Drawn under an opaque box, then removed.
 */
@Composable
private fun NumeralWarmup() {
    var on by remember { mutableStateOf(!numeralsWarm) }
    if (!on) return
    val (numeral, stroke) = rememberNumeral()
    Box(Modifier.size(NumeralWidth * 6, PosterHeight)) {
        Text("1234567890", color = Stage, style = numeral, maxLines = 1, softWrap = false)
        Text("1234567890", color = Color(0xFF8A8A8A), style = numeral.copy(drawStyle = stroke), maxLines = 1, softWrap = false)
        Box(Modifier.matchParentSize().background(Stage))
    }
    LaunchedEffect(Unit) {
        // A few frames on screen, after Home's own first frames
        delay(1_200)
        numeralsWarm = true
        on = false
    }
}
internal val PosterWidth = 112.dp
internal val PosterHeight = 168.dp

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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(4.dp)
    val (numeral, stroke) = rememberNumeral()
    Row(verticalAlignment = Alignment.Bottom) {
        Box(Modifier.width(if (rank >= 10) NumeralWidth + 56.dp else NumeralWidth).height(PosterHeight), contentAlignment = Alignment.BottomEnd) {
            // Filled in stage black, outlined in grey, nudged under the poster
            Text("$rank", color = Stage, style = numeral, maxLines = 1, softWrap = false, modifier = Modifier.offset(x = 16.dp, y = 6.dp))
            Text("$rank", color = Color(0xFF8A8A8A), style = numeral.copy(drawStyle = stroke), maxLines = 1, softWrap = false, modifier = Modifier.offset(x = 16.dp, y = 6.dp))
        }
        Card(
            onClick = { onClick(item) },
            shape = CardDefaults.shape(shape),
            border = CardDefaults.border(focusedBorder = Border.None),
            scale = CardDefaults.scale(focusedScale = 1f),
            glow = CardDefaults.glow(),
            colors = CardDefaults.colors(containerColor = Color(0xFF1F1F1F)),
            modifier = modifier.width(PosterWidth).height(PosterHeight).glideLift(corner = 4.dp) { onFocused(item) }.tapToClick { onClick(item) },
        ) {
            if (rememberCardFilled(item.key)) PosterFace(item)
        }
    }
}

/** A Top 10 poster's picture and tags; shared with the settings preview. */
@Composable
internal fun PosterFace(item: CinemaItem) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        val url = item.posterUrl ?: item.cardUrl
        if (url != null) {
            CardPicture(url, 224, 336, contentDescription = item.title, modifier = Modifier.fillMaxSize())
        }
        if (item.posterUrl == null) {
            Text(item.title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        if (LocalOverlays.current.progress) item.progress?.let { p -> ProgressBar(p, Modifier.align(Alignment.BottomStart)) }
        PosterBadges(item, tall = true)
    }
}

@Composable
internal fun CinemaCard(
    item: CinemaItem,
    onFocused: (CinemaItem) -> Unit,
    onClick: (CinemaItem) -> Unit,
    width: Dp = 208.dp,
    /** On the card itself (the focusable part): a focus requester to come back to it. */
    modifier: Modifier = Modifier,
    /** OK held on the card (Continue Watching's menu); null: a long press is just a press. */
    onLongClick: ((CinemaItem) -> Unit)? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.width(width)) {
        // Built while the remote moves: the shell now, the face once still ([CardFill])
        val filled = rememberCardFilled(item.key)
        // One art lookup for the picture, logo, scores and caption, with the face (Home's warm-up
        // looks up the art of each row's first cards ahead; a lookup per shell made every key
        // press that brought in a row twice as heavy)
        val art = if (filled) rememberArtState(item) else null
        Card(
            onClick = { onClick(item) },
            onLongClick = onLongClick?.let { l -> { l(item) } },
            shape = CardDefaults.shape(shape),
            border = CardDefaults.border(focusedBorder = Border.None),
            scale = CardDefaults.scale(focusedScale = 1f),
            glow = CardDefaults.glow(),
            colors = CardDefaults.colors(containerColor = Color(0xFF1F1F1F)),
            modifier = modifier.width(width).aspectRatio(16f / 9f).glideLift { onFocused(item) }.tapToClick { onClick(item) },
        ) {
            if (art != null) CinemaCardFace(item, width, art)
        }
        if (LocalOverlays.current.captions) CardCaption(item, art?.art)
    }
}

/** "Apr 24, 2019", in the device's language. */
private val CAPTION_DATE = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")

/** Under a card (Poster tags → Title and date): the name, then its date and length. */
@Composable
internal fun CardCaption(
    item: CinemaItem,
    art: com.wholphinplus.sources.core.TitleArt? = rememberArt(item),
) {
    Column(Modifier.fillMaxWidth().padding(top = 9.dp, start = 2.dp, end = 2.dp)) {
        Text(item.title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            // The full release date from TMDB when the server only knows the year
            val released = art?.released?.takeIf { item.kind != BaseItemKind.EPISODE }
            val date = released?.let { runCatching { CAPTION_DATE.format(java.time.LocalDate.parse(it)) }.getOrNull() } ?: item.captionDate.orEmpty()
            Text(date, color = InkDim, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
            item.captionLength?.let { Text(it, color = InkDim, fontSize = 11.sp, maxLines = 1) }
        }
    }
}

/**
 * A wide card's picture and every tag on it (Settings → Orca+ → Poster tags). Shared by the
 * rows and the settings preview, so the preview is exactly what the rows draw.
 */
@Composable
internal fun CinemaCardFace(
    item: CinemaItem,
    width: Dp,
    artState: ArtState = rememberArtState(item),
) {
    val context = LocalContext.current
    val o = LocalOverlays.current
    val art = artState.art
    // TMDB's art is on its way (for a moment at most): the picture and the logo or name wait for
    // it, rather than the server's picture loading and then being swapped
    val waiting = artState.waiting
    val choice = cardChoice(item, art, o)
    val logo = choice.logo
    val clean = choice.clean
    val titled = choice.titled
    val url = choice.url
    // No logo: the name is written on the picture only when it isn't already under the card
    val drawName = clean != null || (titled == null && !item.cardHasTitleArt && !o.captions)
    Box(Modifier.fillMaxSize()) {
        if (waiting) {
            Unit
        } else if (url != null) {
            // Decoded at card size: small, fast, and cached for the next visit
            CardPicture(url, 416, 234, contentDescription = item.title, modifier = Modifier.fillMaxSize())
        } else {
            // No wide art on the server or TMDB: the poster filling the card, or a plain title
            // card, instead of an empty grey box
            NoWideArt(item.posterUrl, context)
        }
        if (drawName && !waiting) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
            val bottom = if (item.badge != null && o.newLabels) 22.dp else 10.dp
            if (logo != null && clean != null) {
                FittedLogo(
                    logo,
                    height = width * 0.23f,
                    // Up to 60% of the card: the small score in the opposite corner still clears it
                    maxWidth = width * 0.6f,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = bottom),
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
        item.badge?.takeIf { o.newLabels }?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).background(Label, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        if (o.progress) item.progress?.let { p -> ProgressBar(p, Modifier.align(Alignment.BottomStart)) }
        // A second pass: the streaming logos, quality tags and scores are ~35% of a card's cost,
        // built a frame after the picture and logo so no frame builds a whole card
        if (rememberCardFilled(CardExtras(item.key))) {
            PosterBadges(item)
            ServiceLogos(item, art?.serviceUrls().orEmpty().take(o.maxServices))
            CardRatings(item, art)
        }
    }
}

/** [CinemaCardFace]'s second pass, by title ([rememberCardFilled]). */
private data class CardExtras(
    val key: Any,
)

/** Which pictures a wide card draws ([CinemaCardFace]); the start-up warm-up asks for the same. */
internal class CardChoice(
    val logo: String?,
    val clean: String?,
    val titled: String?,
    val url: String?,
)

internal fun cardChoice(
    item: CinemaItem,
    art: com.wholphinplus.sources.core.TitleArt?,
    o: PosterOverlays,
): CardChoice {
    // Card-size logos: drawn at most ~250 px wide ([PosterSize.cardLogo])
    val logo = (art?.logoUrl() ?: item.logoUrl)?.let(PosterSize::cardLogo)
    // A title with a logo: its clean picture with the logo laid over the bottom corner, the same
    // on every card (art with the name baked in puts it anywhere, at any size). Without one, or
    // with title logos off: art with the name baked in, else the picture with the name on it
    // TMDB's picture first: its CDN answers in ~0.1 s on a connection of its own, where the
    // server's (often the same TMDB picture) took 0.4-0.7 s and queued behind the rows' requests
    val clean = if (logo != null && o.titleLogos) art?.cleanCardUrl() ?: item.cleanCardUrl else null
    val titled = if (clean == null) art?.cardUrl() else null
    return CardChoice(logo, clean, titled, clean ?: titled ?: item.cardUrl)
}

/**
 * A wide card's picture for a title that only has a poster, or nothing at all. The poster fills
 * the card, cropped to its upper middle: the key art sits there on most posters, and the title
 * printed lower down would only repeat the name the card shows anyway.
 */
@Composable
private fun NoWideArt(
    poster: String?,
    context: android.content.Context,
) {
    if (poster != null) {
        CardPicture(
            poster,
            416,
            624,
            contentDescription = null,
            alignment = androidx.compose.ui.BiasAlignment(0f, -0.35f),
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2B2734), Color(0xFF141318))))) {
            Text("+", color = Plus.copy(alpha = 0.35f), fontSize = 44.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp))
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

/**
 * A real title from your home for the Poster tags preview: one with a logo and streaming
 * services if there is one (so those tags show), else any title with art; null before Home
 * has loaded.
 */
internal fun previewTitle(art: CinemaArt?): CinemaItem? {
    val all = TabCache.data[CinemaTab.HOME]?.rows?.flatMap { it.items }.orEmpty().filter { it.kind != BaseItemKind.EPISODE && it.tmdbId != null }
    fun known(i: CinemaItem) = art?.cached(i.tmdbTv, i.tmdbId!!)
    return all.firstOrNull { known(it)?.let { a -> a.logo != null && a.services.isNotEmpty() } == true }
        ?: all.firstOrNull { known(it)?.logo != null }
        ?: all.firstOrNull { it.cardUrl != null }
}

/**
 * Loads Home ahead of time (the welcome tour does, so Home is there the moment it ends) and the
 * title art of its first rows, so previews can show a real title.
 */
internal suspend fun preloadHome(
    repo: CinemaRepository,
    art: CinemaArt,
) {
    val gen = repo.listsGeneration
    val plays = repo.plays
    val d = repo.load(RowsPage.HOME)
    TabCache.data[CinemaTab.HOME] = d
    if (d.failed == 0) TabCache.stamp(CinemaTab.HOME, gen, plays)
    kotlinx.coroutines.coroutineScope {
        d.rows.take(3).flatMap { it.items.take(6) }.filter { it.tmdbId != null && it.kind != BaseItemKind.EPISODE }.forEach { i ->
            launch { runCatching { art.art(i.tmdbTv, i.tmdbId!!) } }
        }
    }
}

/**
 * The card (or the billboard: [row] null) a page opened a title page or the player from, so Back
 * lands on it instead of on Play (Home) or on nothing (the other tabs). Taken once, by that page.
 */
internal object ReturnCard {
    class Spot(
        val screen: String,
        val row: String?,
        val key: String?,
    )

    @Volatile private var spot: Spot? = null

    fun mark(
        screen: String,
        row: String?,
        key: String?,
    ) {
        spot = Spot(screen, row, key)
    }

    fun take(screen: String): Spot? = spot?.takeIf { it.screen == screen }?.also { spot = null }
}

/** What Down from a page's top menu lands on (its Play, My List's first title), by page. */
internal object DownTargets {
    private val map = java.util.concurrent.ConcurrentHashMap<String, FocusRequester>()

    fun of(screen: String): FocusRequester? = map[screen]

    @Composable
    fun Register(
        screen: String,
        target: FocusRequester,
    ) {
        DisposableEffect(screen, target) {
            map[screen] = target
            onDispose { map.remove(screen, target) }
        }
    }
}

/** Each tab's last page, kept for the life of the app so returning to it is instant. */
private object TabCache {
    val data = java.util.concurrent.ConcurrentHashMap<CinemaTab, CinemaHomeData>()
    val at = java.util.concurrent.ConcurrentHashMap<CinemaTab, Long>()

    /** The [HomeCollections.changed] each tab's page was loaded under (when it started). */
    private val under = java.util.concurrent.ConcurrentHashMap<CinemaTab, Int>()

    /** The progress overlay's [ProgressOverlay.changes] each tab's page was loaded under. */
    private val watched = java.util.concurrent.ConcurrentHashMap<CinemaTab, Long>()

    /** [tab]'s page was loaded just now, from the lists as they were at [changed] and the plays as at [plays]. */
    fun stamp(
        tab: CinemaTab,
        changed: Int,
        plays: Long,
    ) {
        at[tab] = System.currentTimeMillis()
        under[tab] = changed
        watched[tab] = plays
    }

    /**
     * [tab]'s page is recent and from the current lists. A load that began before a refresh
     * announced new rows (and so finished after the caches were cleared) isn't fresh: the
     * announcement's reload would otherwise skip it and the new rows wait for the next visit.
     */
    fun fresh(
        tab: CinemaTab,
        changed: Int,
        plays: Long,
    ) = under[tab] == changed && watched[tab] == plays && System.currentTimeMillis() - (at[tab] ?: 0L) < 2 * 60_000
}

/**
 * Each tab's last full page on the device, so a cold start (Android often closes Orca+ while
 * other apps run) shows the page at once instead of after its 2-6 s of requests; the fresh load
 * replaces it when it lands. Kept per server and profile, for a week.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal object PageSnapshots {
    /** Tabs never saved: My List is quick, My Servers' extra-server titles mean nothing after a restart ([ServerTitles]). */
    val UNSAVED = setOf(CinemaTab.MY_LIST, CinemaTab.SERVERS)

    @kotlinx.serialization.Serializable
    private class Saved(
        val owner: String,
        val at: Long,
        val page: CinemaHomeData,
    )

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private var dir: java.io.File? = null

    fun attach(context: android.content.Context) {
        if (dir == null) dir = java.io.File(context.applicationContext.noBackupFilesDir, "cinema_pages")
    }

    private fun file(tab: CinemaTab) = dir?.let { java.io.File(it, "${tab.name.lowercase()}.json") }

    /** [whose]: [CinemaRepository.owner], asked off the main thread (it may ask the server once). */
    suspend fun read(
        tab: CinemaTab,
        whose: () -> String?,
    ): CinemaHomeData? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val owner = whose()
            val f = file(tab)?.takeIf { owner != null && it.exists() }
            if (f == null) {
                timber.log.Timber.i("Cinema snapshot %s: none (signed in: %b)", tab.name, owner != null)
                return@withContext null
            }
            val saved = runCatching { f.inputStream().buffered().use { json.decodeFromStream(Saved.serializer(), it) } }.getOrNull()
            if (saved == null) f.delete()
            val page = saved?.takeIf { it.owner == owner && System.currentTimeMillis() - it.at < MAX_AGE_MS }?.page
            timber.log.Timber.i("Cinema snapshot %s: %s in %d ms", tab.name, if (page != null) "shown" else "not used", System.currentTimeMillis() - started)
            page
        }

    /** What each tab's file holds (its rows and titles) and when it was written, to skip rewriting the same page. */
    private val written = java.util.concurrent.ConcurrentHashMap<CinemaTab, Pair<Int, Long>>()

    /**
     * Written once the remote rests (encoding ~1.4 MB on the Shield's cores while a row glides
     * cost frames), streamed to the file, and not at all when the same rows and titles were saved
     * within the day (most reloads change nothing but progress).
     */
    suspend fun save(
        tab: CinemaTab,
        whose: () -> String?,
        page: CinemaHomeData,
    ) {
        if (tab in UNSAVED) return
        Conductor.later {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val owner = whose() ?: return@withContext
                val f = file(tab) ?: return@withContext
                val shape = snapshotShape(owner, page)
                val now = System.currentTimeMillis()
                written[tab]?.let { (was, at) -> if (was == shape && now - at < RESAVE_MS && f.exists()) return@withContext }
                runCatching {
                    f.parentFile?.mkdirs()
                    val tmp = java.io.File(f.path + ".tmp")
                    tmp.outputStream().buffered().use { json.encodeToStream(Saved.serializer(), Saved(owner, now, page), it) }
                    tmp.renameTo(f)
                    written[tab] = shape to now
                }.onFailure { timber.log.Timber.w(it, "Cinema snapshot %s not saved", tab.name) }
            }
        }
    }

    /** The rows were rearranged: a saved page no longer matches its layout. */
    fun clear() {
        written.clear()
        dir?.listFiles()?.forEach { it.delete() }
    }

    private const val MAX_AGE_MS = 7 * 24 * 60 * 60_000L

    /** A page with the same rows and titles is written again after this long (its age counts). */
    private const val RESAVE_MS = 24 * 60 * 60_000L
}

/** A page's rows and titles in order (and whose they are), as a number: equal when a save would write the same page. */
internal fun snapshotShape(
    owner: String,
    page: CinemaHomeData,
): Int {
    var h = owner.hashCode()
    page.featured.forEach { h = h * 31 + it.key.hashCode() }
    page.rows.forEach { row ->
        h = h * 31 + row.title.hashCode()
        row.items.forEach { h = h * 31 + it.key.hashCode() }
        row.tiles.forEach { h = h * 31 + it.id.hashCode() }
    }
    return h
}

/** How long the billboard waits after a press before it checks the rows have stopped ([RowMotion]). */
private const val SETTLE_MS = 160L

/** How long OK on an episode card waits for its show's id before opening the episode itself. */
private const val EPISODE_LOOKUP_MS = 4_000L

private val DPAD = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

/** Rows that are on screen when a page opens; their art and badges load first. */
private const val ON_SCREEN_ROWS = 2

/** What MemoryTrim may drop: every tab's page but Home (they reload in the background). */
internal object CinemaCaches {
    fun trim() {
        TabCache.data.keys.filter { it != CinemaTab.HOME }.forEach {
            TabCache.data.remove(it)
            TabCache.at.remove(it)
        }
        RowMemory.trim { it == "tab:${CinemaTab.HOME.name}" }
        trimDetails()
    }

    /** My Servers' rows changed (its extra servers switched on or off): it loads afresh next time it shows. */
    fun serversChanged() {
        TabCache.data.remove(CinemaTab.SERVERS)
        TabCache.at.remove(CinemaTab.SERVERS)
        RowMemory.trim { it != "tab:${CinemaTab.SERVERS.name}" && !it.startsWith("server:") }
    }

    /** The rows were rearranged: every tab loads afresh next time it shows. */
    fun homeChanged() {
        TabCache.data.clear()
        TabCache.at.clear()
        RowMemory.clear()
        PageSnapshots.clear()
    }

    /**
     * Rows kept their places but gained or lost titles (the 6-hourly match, a new day's charts):
     * every tab loads afresh, but the pages saved on the device stay. They show at once and the
     * fresh load fills in over them. Deleting them (as [homeChanged] did for this too) meant the
     * first open after nearly every refresh built the page row by row, slow rows pushing in
     * between the ones already shown.
     */
    fun rowsRefreshed() {
        TabCache.data.clear()
        TabCache.at.clear()
    }
}

/** My List: every saved title in a calm grid, the focused one's art behind it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MyListScreen(
    items: List<CinemaItem>,
    onOpen: (UUID, BaseItemKind) -> Unit,
    focusAt: Int?,
) {
    val first = remember { FocusRequester() }
    DownTargets.Register("tab:${CinemaTab.MY_LIST.name}", first)
    TitleGrid("My List", items, "Titles you add with My List on a title's page show up here.", onOpen, focusAt = focusAt, firstFocus = first)
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
            Text("Your home, playback, subtitles and servers", color = InkDim, fontSize = 15.sp)
        }
    }
}
