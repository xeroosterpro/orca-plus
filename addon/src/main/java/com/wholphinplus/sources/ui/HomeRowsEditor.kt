package com.wholphinplus.sources.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.wholphinplus.sources.SourceHook
import com.wholphinplus.sources.cinema.CinemaCaches
import com.wholphinplus.sources.cinema.CinemaLibrary
import com.wholphinplus.sources.cinema.CinemaRepository
import com.wholphinplus.sources.cinema.HomeLayout
import com.wholphinplus.sources.cinema.HomeRowSpec
import com.wholphinplus.sources.cinema.HomeRowType
import com.wholphinplus.sources.cinema.RowsPage

/**
 * Cinema mode's home rows: the ones that show, in order, then the ones you can add. OK shows or
 * hides a row; Right reaches Move (Up and Down carry the row, OK drops it) and Rename.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
internal fun HomeRowsScreen(
    hook: SourceHook,
    page: RowsPage,
    onPage: (RowsPage) -> Unit,
    onRename: (HomeRowSpec, String) -> Unit,
    onAddList: () -> Unit,
) {
    val layouts by hook.store.pageLayouts.collectAsState()
    val saved = layouts[page]
    val lists by hook.collections.lists.collectAsState()
    val repo = remember { CinemaRepository(hook, hook.collections) }
    // null while loading; the rows that depend on libraries are named from them
    val libs by produceState<List<CinemaLibrary>?>(null) { value = repo.libraries() }
    val listNames = lists.associate { it.id to it.name }
    val shown = lists.filter { it.showOnHome }.map { it.id }.toSet()
    val charts = lists.filter { hook.collections.isTopStreaming(it) }.map { it.id }.toSet()
    val orcaCharts = lists.filter { hook.collections.isChart(it) }.map { it.id }.toSet()
    var moving by remember { mutableStateOf<String?>(null) }
    // After a row is shown or hidden it leaves for the other group; focus stays put instead
    var refocus by remember { mutableStateOf<String?>(null) }
    val focus = remember { mutableMapOf<String, FocusRequester>() }
    val reveal = remember { mutableMapOf<String, BringIntoViewRequester>() }
    val listState = rememberLazyListState()

    // The page picker: each page keeps its own rows
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
        val kidsTab by hook.store.kidsTab.collectAsState()
        RowsPage.entries.filter { it != RowsPage.KIDS || kidsTab }.forEach { p ->
            FilterChip(
                selected = p == page,
                onClick = { if (p != page) onPage(p) },
                leadingIcon = if (p == page) ({ Text("✓", style = MaterialTheme.typography.labelLarge) }) else null,
            ) { Text(p.label, style = MaterialTheme.typography.labelLarge) }
        }
    }
    val l = libs
    if (l == null) {
        Text("Loading your libraries…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (l.isEmpty()) {
        // Saving now would drop every library row; wait until the server answers
        Text("Your server didn't answer, so the rows can't be changed right now. Try again in a moment.", color = MaterialTheme.colorScheme.error)
        return
    }
    // On Home, a list hidden from the home (Home collections) counts as off here too
    val layout =
        repo.layoutFor(page, l, saved).let { base ->
            if (page != RowsPage.HOME) base else HomeLayout(base.rows.map { if (it.type == HomeRowType.COLLECTION && it.ref !in shown && it.ref !in orcaCharts) it.copy(on = false) else it })
        }.tidy()

    fun save(next: HomeLayout) {
        hook.store.setPageLayout(page, next)
        CinemaCaches.homeChanged()
    }

    fun switch(spec: HomeRowSpec) {
        val on = !spec.on
        val group = layout.rows.filter { it.on == spec.on }
        val at = group.indexOfFirst { it.key == spec.key }
        refocus = (group.getOrNull(at + 1) ?: group.getOrNull(at - 1)?.takeIf { it.type != HomeRowType.CONTINUE_WATCHING } ?: spec).key
        // On Home, a list's switch is the same one as in Home collections
        if (page == RowsPage.HOME && spec.type == HomeRowType.COLLECTION && spec.ref !in orcaCharts) lists.firstOrNull { it.id == spec.ref }?.let { if (it.showOnHome != on) hook.collections.update(it.copy(showOnHome = on)) }
        save(layout.switched(spec.key, on))
    }

    fun rename(spec: HomeRowSpec) {
        // The rename page edits the saved home; a home still on its defaults is saved first
        if (saved == null) hook.store.setPageLayout(page, layout)
        onRename(spec, spec.defaultName(l, listNames))
    }

    val on = layout.rows.filter { it.on }
    val off = layout.rows.filterNot { it.on }

    LaunchedEffect(Unit) { runCatching { focus[(on.firstOrNull() ?: off.first()).key]?.requestFocus() } }
    LaunchedEffect(refocus) {
        val key = refocus ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { focus[key]?.requestFocus() }
        refocus = null
    }
    // A moved row stays in view as it travels
    LaunchedEffect(moving, layout) { moving?.let { reveal[it]?.bringIntoView() } }

    androidx.activity.compose.BackHandler(enabled = moving != null) { moving = null }
    Text(
        "OK shows or hides a row.  Right: Move (Up and Down carry it, OK puts it down) and Rename.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
    ) {
        item { Heading("On ${page.label}", "${on.size} rows, top to bottom") }
        items(on, key = { it.key }) { spec ->
            RowLine(
                spec = spec,
                name = spec.title.ifBlank { spec.defaultName(l, listNames) },
                note = note(spec, l, listNames, charts, orcaCharts),
                showing = true,
                moving = moving == spec.key,
                canMove = spec.type != HomeRowType.CONTINUE_WATCHING && on.count { it.type != HomeRowType.CONTINUE_WATCHING } > 1,
                focus = focus.getOrPut(spec.key) { FocusRequester() },
                reveal = reveal.getOrPut(spec.key) { BringIntoViewRequester() },
                onClick = {
                    when {
                        moving == spec.key -> moving = null
                        spec.type != HomeRowType.CONTINUE_WATCHING -> switch(spec)
                    }
                },
                onMove = { by -> save(layout.moved(spec.key, by)) },
                onStartMove = {
                    moving = spec.key
                    runCatching { focus[spec.key]?.requestFocus() }
                },
                onEndMove = { moving = null },
                onRename = { rename(spec) },
            )
        }
        item { Heading("More rows", "OK adds one to the bottom of ${page.label}") }
        items(off, key = { it.key }) { spec ->
            RowLine(
                spec = spec,
                name = spec.title.ifBlank { spec.defaultName(l, listNames) },
                note = note(spec, l, listNames, charts, orcaCharts),
                showing = false,
                moving = false,
                canMove = false,
                focus = focus.getOrPut(spec.key) { FocusRequester() },
                reveal = reveal.getOrPut(spec.key) { BringIntoViewRequester() },
                onClick = { switch(spec) },
                onMove = {},
                onStartMove = {},
                onEndMove = {},
                onRename = { rename(spec) },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
                Button(onClick = onAddList) { Text("+ Add a Trakt or MDBList list") }
                Button(onClick = {
                    save(HomeLayout.defaults(page, l, repo.pageLists(), repo.tiles(page)))
                    moving = null
                }) { Text("Reset ${page.label} to the standard rows") }
            }
        }
    }
}

/** What a row is, under its name. */
private fun note(
    spec: HomeRowSpec,
    libs: List<CinemaLibrary>,
    lists: Map<String, String>,
    charts: Set<String>,
    orcaCharts: Set<String>,
): String {
    val kind =
        when (spec.type) {
            HomeRowType.CONTINUE_WATCHING -> "Always first"
            HomeRowType.COLLECTION -> if (spec.ref in orcaCharts) "Orca+ chart, updated daily" else if (spec.ref in charts) "Top Streaming chart" else "Your list"
            HomeRowType.GENRE -> "Genre"
            HomeRowType.LIBRARY -> "One library"
            HomeRowType.MY_LIST -> "Titles you saved"
            HomeRowType.RECENT_MOVIES, HomeRowType.NEW_EPISODES -> "Every library, each title once"
            HomeRowType.NEW_RELEASE_MOVIES, HomeRowType.NEW_RELEASE_SHOWS -> "By release date"
            HomeRowType.NEW_ARRIVALS -> "Newest on the server"
            HomeRowType.JUST_AIRED -> "Episodes by air date, one per show"
            HomeRowType.SERVICES -> "Streaming services, each opens its page"
            HomeRowType.GENRES -> "Genres, each opens its page"
            HomeRowType.DECADES -> "Decades, each opens its page"
            HomeRowType.BECAUSE_YOU_WATCHED -> "More like what you're watching"
        }
    val renamed = spec.title.isNotBlank() && spec.title != spec.defaultName(libs, lists)
    return listOfNotNull(kind, if (renamed) "was “${spec.defaultName(libs, lists)}”" else null).joinToString("  ·  ")
}

@Composable
private fun Heading(
    title: String,
    about: String,
) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp, start = 4.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
        Text("  ·  $about", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One row: its name (OK shows or hides it), then Move and Rename. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowLine(
    spec: HomeRowSpec,
    name: String,
    note: String,
    showing: Boolean,
    moving: Boolean,
    canMove: Boolean,
    focus: FocusRequester,
    reveal: BringIntoViewRequester,
    onClick: () -> Unit,
    onMove: (Int) -> Unit,
    onStartMove: () -> Unit,
    onEndMove: () -> Unit,
    onRename: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val accent = com.wholphinplus.sources.cinema.CinemaColors.toggle
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.bringIntoViewRequester(reveal),
    ) {
        ListItem(
            selected = moving,
            onClick = onClick,
            shape = ListItemDefaults.shape(shape),
            colors =
                ListItemDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.04f),
                    contentColor = if (showing) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedContainerColor = Color(0xFF2A2A2A),
                    focusedContentColor = Color.White,
                    selectedContainerColor = accent.copy(alpha = 0.25f),
                    selectedContentColor = Color.White,
                ),
            border =
                ListItemDefaults.border(
                    focusedBorder = Border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape = shape),
                    focusedSelectedBorder = Border(BorderStroke(3.dp, accent), shape = shape),
                ),
            scale = ListItemDefaults.scale(focusedScale = 1.02f),
            headlineContent = { Text(if (moving) "⇅  $name" else name, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(if (moving) "Up and Down to move it  ·  OK to put it down" else note) },
            trailingContent = {
                if (spec.type != HomeRowType.CONTINUE_WATCHING) androidx.tv.material3.Switch(checked = showing, onCheckedChange = null, colors = com.wholphinplus.sources.ui.plusSwitchColors())
            },
            modifier =
                Modifier
                    .width(560.dp)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        if (!moving) return@onPreviewKeyEvent false
                        // While moving, the arrows carry the row; Back (a key on Android 11) puts it down
                        when (e.key) {
                            Key.DirectionUp, Key.DirectionDown -> {
                                if (e.type == KeyEventType.KeyDown) onMove(if (e.key == Key.DirectionUp) -1 else 1)
                                true
                            }
                            Key.DirectionLeft, Key.DirectionRight -> true
                            Key.Back -> {
                                if (e.type == KeyEventType.KeyUp) onEndMove()
                                true
                            }
                            else -> false
                        }
                    },
        )
        if (canMove) SmallButton("Move", onStartMove)
        SmallButton("Rename", onRename)
    }
}

@Composable
private fun SmallButton(
    label: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        scale = ButtonDefaults.scale(focusedScale = 1.05f),
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}
