package com.wholphinplus.sources.cinema

import com.wholphinplus.sources.core.cancellable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.wholphinplus.sources.ui.SourcesEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
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

/** The top menu's Search or Settings, left for that screen: Back from it lands on its icon again (it landed on Play). */
private object NavReturn {
    @Volatile var to: CinemaNav? = null
}

/**
 * Is Cinema mode on? Always: there's one look (2026-10-07). Kept for Wholphin's hooks, which ask
 * it to pick Cinema's screens; a constant, so no screen watches a setting for it any more.
 */
fun cinemaModeOn(): Boolean = true

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
                                        runCatching { search.search(t.name) }.cancellable().getOrNull()?.items?.firstOrNull { r ->
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
                                when (val a = kotlinx.coroutines.withTimeoutOrNull(10_000L) { runCatching { search.availability(item) }.cancellable().getOrNull() }) {
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
    val messagesFocus = remember { FocusRequester() }
    // The Message Center (the bell): over the tab like a page, kept across a title page and back
    var messagesOpen by rememberSaveable { mutableStateOf(false) }
    var messagesClosed by remember { mutableStateOf(false) }
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
    // An Emby main server's Continue Watching is shown from the device at once and asked again in
    // the background (EmbyBridge); when the server's turns out different, the tab on screen reloads
    // and swaps it in, as after a play. Jellyfin and Silo never send this.
    LaunchedEffect(Unit) {
        com.wholphinplus.sources.EmbyBridge.resumeChanged.drop(1).collect {
            if (pages[tab] != null) {
                TabCache.at.remove(tab)
                attempt++
            }
        }
    }
    // Once Home is up, quietly load Shows and Movies so switching tabs is instant
    LaunchedEffect(pages[CinemaTab.HOME] != null) {
        if (pages[CinemaTab.HOME] == null) return@LaunchedEffect
        // Not on Light boxes: two more tabs (~2,000 titles) right after start was most of the
        // start's garbage collection on the 1 GB bench, and each tab opens from its saved copy anyway
        if (com.wholphinplus.sources.DeviceClass.light) return@LaunchedEffect
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
        val covered = openPage != null || grid != null || messagesOpen
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
                    // Overlapping: the old tab eases out as the new one comes in, no dip to black between.
                    // Light boxes switch at once: the overlap drew two whole tabs for ~half a second
                    transitionSpec = {
                        if (com.wholphinplus.sources.DeviceClass.simpleMotion) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            fadeIn(tween(420, delayMillis = 60, easing = CinemaEase)) togetherWith fadeOut(tween(260, easing = CinemaFade))
                        }
                    },
                    label = "tab",
                ) { t ->
                    val d = pages[t]
                    val error = errors[t]
                    Box(Modifier.fillMaxSize()) {
                        when {
                            d == null && error != null ->
                                LoadError(error, onRetry = { attempt++ }, modifier = Modifier.align(Alignment.Center))
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
            }, searchFocus, settingsFocus, onMessages = { messagesOpen = true }, messagesFocus = messagesFocus)
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
            // The welcome, once per start of the app, as Home first shows; pop-ups stack under it
            WelcomeGreeting(ready = pages[CinemaTab.HOME] != null && !covered)
            InboxBanner()
            LibraryNotice(hook)
            PosterNotice()
        }
        // The Message Center over everything; Back returns to the bell
        if (messagesOpen) {
            MessageCenter(
                hook,
                onAction = { a ->
                    when (a) {
                        is com.wholphinplus.sources.Inbox.Action.Update -> com.wholphinplus.sources.Inbox.openUpdate?.invoke()
                        is com.wholphinplus.sources.Inbox.Action.OpenTitle ->
                            runCatching { UUID.fromString(a.id) }.getOrNull()?.let { openTitle(it, if (a.series) BaseItemKind.SERIES else BaseItemKind.MOVIE) }
                        is com.wholphinplus.sources.Inbox.Action.Settings -> {
                            com.wholphinplus.sources.Inbox.settingsSection = a.section
                            onNavigate(CinemaNav.Settings)
                        }
                        is com.wholphinplus.sources.Inbox.Action.SyncNow -> openScope.launch { hook.profileSync.syncNow(hook) }
                    }
                },
                onClose = {
                    messagesOpen = false
                    messagesClosed = true
                },
            )
        }
        if (messagesClosed) {
            LaunchedEffect(Unit) {
                messagesClosed = false
                for (i in 0 until 30) {
                    withFrameNanos {}
                    if (runCatching { messagesFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                }
            }
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

    /** Completes once the first screen is up (its pictures in, or the wait given up). */
    private val firstScreen = kotlinx.coroutines.CompletableDeferred<Unit>()

    /**
     * Returns once the first screen is up, or after [maxMs] (Cinema mode off, or no page yet): for
     * background work that shouldn't compete with the app's first seconds ([com.wholphinplus.sources.sync.ProfileSync]).
     */
    suspend fun settled(maxMs: Long) {
        kotlinx.coroutines.withTimeoutOrNull(maxMs) { firstScreen.await() }
    }

    suspend fun once(
        context: android.content.Context,
        page: CinemaHomeData,
        art: CinemaArt?,
        overlays: PosterOverlays,
    ) {
        if (done) return
        done = true
        try {
            warm(context, page, art, overlays)
        } finally {
            firstScreen.complete(Unit)
        }
    }

    private suspend fun warm(
        context: android.content.Context,
        page: CinemaHomeData,
        art: CinemaArt?,
        overlays: PosterOverlays,
    ) {
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
internal class HomeFocus {
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
                    // Behind: about three rows on Full (eight made every press heavier, measured 2026-10-09).
                    // (Light tried 640/420 dp on the Fire Stick bench, 2026-10-11: more big frames, not fewer)
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
            // Light boxes change it half as often (each change redraws the whole screen)
            delay(if (com.wholphinplus.sources.DeviceClass.simpleMotion) 20_000 else 10_000)
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
    onMessages: () -> Unit,
    messagesFocus: FocusRequester,
) {
    val selected = remember { FocusRequester() }
    val unread = com.wholphinplus.sources.Inbox.messages.collectAsState().value.count { !it.read }
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
        NavIcon(Icons.Filled.Notifications, "Messages", Modifier.focusRequester(messagesFocus), badge = unread, onClick = onMessages)
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
    /** Unread messages: a dot with the count on the icon's corner. */
    badge: Int = 0,
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
        Box {
            Icon(icon, contentDescription = label, modifier = Modifier.padding(7.dp).size(20.dp))
            if (badge > 0) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 1.dp).size(15.dp).background(Plus, CircleShape), contentAlignment = Alignment.Center) {
                    Text(if (badge > 9) "9+" else "$badge", color = Stage, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** How long the billboard waits after a press before it checks the rows have stopped ([RowMotion]). */
private const val SETTLE_MS = 160L

/** How long OK on an episode card waits for its show's id before opening the episode itself. */
private const val EPISODE_LOOKUP_MS = 4_000L

private val DPAD = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

/** Rows that are on screen when a page opens; their art and badges load first. */
private const val ON_SCREEN_ROWS = 2

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
    // Collections saved from search (and your own lists), above your titles; OK opens one's page
    val (hook, repo) = rememberContinueRepo()
    val context = androidx.compose.ui.platform.LocalContext.current
    val finder =
        remember {
            val tmdb = dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, com.wholphinplus.sources.ui.SourcesEntryPoint::class.java).searchService().tmdb
            CollectionSearch(hook, repo, tmdb)
        }
    val lists by hook.collections.lists.collectAsState()
    val saved = remember(lists) { finder.saved(lists) }
    var open by remember { mutableStateOf<CollectionSearch.Hit?>(null) }
    val tileFocus = remember { HashMap<String, FocusRequester>() }
    val scope = rememberCoroutineScope()
    TitleGrid(
        "My List",
        items,
        "Titles you add with My List on a title's page show up here.",
        onOpen,
        focusAt = focusAt,
        firstFocus = first,
        header =
            if (saved.isEmpty()) {
                null
            } else {
                { SavedCollections(saved, tileFocus) { open = it } }
            },
    )
    open?.let { hit ->
        CollectionPage(hit, finder, hook, onOpen = onOpen, onClose = {
            open = null
            // Back on the tile it was opened from
            scope.launch {
                androidx.compose.runtime.withFrameNanos {}
                runCatching { tileFocus[hit.key]?.requestFocus() }
            }
        })
    }
}

/** My List's "Saved collections": one tile each, newest saved last (the order you saved them in). */
@Composable
private fun SavedCollections(
    saved: List<CollectionSearch.Hit>,
    focus: HashMap<String, FocusRequester>,
    onOpen: (CollectionSearch.Hit) -> Unit,
) {
    Column(Modifier.padding(top = 6.dp)) {
        Text("Saved collections", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 48.dp, bottom = 2.dp))
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(saved, key = { it.key }) { hit ->
                CollectionTile(hit, 240.dp, Modifier.focusRequester(focus.getOrPut(hit.key) { FocusRequester() })) { onOpen(hit) }
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
            Text("Your home, playback, subtitles and servers", color = InkDim, fontSize = 15.sp)
        }
    }
}
