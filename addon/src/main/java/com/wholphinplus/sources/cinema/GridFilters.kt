package com.wholphinplus.sources.cinema

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Text
import com.wholphinplus.sources.core.TitleArt
import java.util.Locale
import kotlinx.coroutines.launch

/** Most titles a grid loads to sort or filter a row (Action has ~700 in a big library). */
internal const val GRID_ALL_CAP = 2_000

internal enum class GridSort(
    val label: String,
) {
    LISTED("As listed"),
    ADDED("Recently added"),
    NEWEST("Newest releases"),
    OLDEST("Oldest releases"),
    RATED("Top rated"),
    TITLE("A to Z"),
}

/**
 * How a full-screen grid (See all, My List, a collection, an actor's titles) is sorted and narrowed
 * down. [decade] and [year] are one choice ("Year": a decade or a single year). [language] is an
 * ISO 639-1 code from TMDB.
 */
@Immutable
internal data class GridFilter(
    val sort: GridSort = GridSort.LISTED,
    val decade: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val language: String? = null,
    val unwatched: Boolean = false,
) {
    val narrowed: Boolean get() = decade != null || year != null || genre != null || language != null || unwatched

    /** Anything other than the row as it comes: the whole row is loaded first. */
    val active: Boolean get() = sort != GridSort.LISTED || narrowed

    val yearLabel: String get() = year?.toString() ?: decade?.let { "${it}s" } ?: "Any"
}

/** The year a title counts under: the server's, else its release day's. */
internal fun CinemaItem.yearOf(): Int? = year ?: releasedDay?.let { java.time.LocalDate.ofEpochDay(it).year }

/** [items] as [this] says. [art] is the title's TMDB art (score, release date, language) when known. */
internal fun GridFilter.apply(
    items: List<CinemaItem>,
    art: (CinemaItem) -> TitleArt?,
): List<CinemaItem> {
    val kept =
        items.filter { item ->
            val y = item.yearOf()
            (!unwatched || !item.played) &&
                (year == null || y == year) &&
                (decade == null || (y != null && y / 10 * 10 == decade)) &&
                (genre == null || item.genres.any { it.equals(genre, ignoreCase = true) }) &&
                (language == null || art(item)?.lang == language)
        }
    val released = { item: CinemaItem -> item.releasedDay ?: art(item)?.released?.let { runCatching { java.time.LocalDate.parse(it).toEpochDay() }.getOrNull() } ?: item.yearOf()?.let { java.time.LocalDate.of(it, 1, 1).toEpochDay() } }
    // Titles missing the fact sort last, in their listed order (sortedWith is stable)
    return when (sort) {
        GridSort.LISTED -> kept
        GridSort.ADDED -> kept.sortedWith(compareBy(nullsLast(reverseOrder())) { it.added })
        GridSort.NEWEST -> kept.sortedWith(compareBy(nullsLast(reverseOrder())) { released(it) })
        GridSort.OLDEST -> kept.sortedWith(compareBy(nullsLast()) { released(it) })
        GridSort.RATED -> kept.sortedWith(compareBy(nullsLast(reverseOrder())) { it.score?.toDouble() ?: art(it)?.tmdbScore })
        GridSort.TITLE -> kept.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { sortTitle(it.title) })
    }
}

/** "The Matrix" files under M. */
internal fun sortTitle(title: String): String = title.trim().removePrefix("The ").removePrefix("A ").removePrefix("An ").trimStart()

/** A language code as a word: "ko" → "Korean". */
internal fun languageName(code: String): String = Locale(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

/**
 * The choices each menu offers for [items], with how many titles each keeps (most first for genres
 * and languages; years newest first, each decade before its years).
 */
internal fun yearChoices(items: List<CinemaItem>): List<Pair<Int?, Int>> {
    val years = items.mapNotNull { it.yearOf() }.groupingBy { it }.eachCount()
    return years.keys.map { it / 10 * 10 }.distinct().sortedDescending().flatMap { decade ->
        // A decade as its own choice (encoded negative), then its years
        listOf(-decade to years.filterKeys { it / 10 * 10 == decade }.values.sum()) +
            years.filterKeys { it / 10 * 10 == decade }.toSortedMap(reverseOrder()).map { (y, n) -> y to n }
    }
}

internal fun genreChoices(items: List<CinemaItem>): List<Pair<String, Int>> =
    items.flatMap { it.genres.distinct() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }

internal fun languageChoices(
    items: List<CinemaItem>,
    art: (CinemaItem) -> TitleArt?,
): List<Pair<String, Int>> =
    items.mapNotNull { art(it)?.lang }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }

/** Each grid's filter for this session, by the grid's name, so Back from a title finds it as left. */
internal object GridFilterMemory {
    private val kept = HashMap<String, MutableState<GridFilter>>()

    fun of(key: String): MutableState<GridFilter> = kept.getOrPut(key) { mutableStateOf(GridFilter()) }
}

/** A grid's sort and filters in use: what to show, and what the header says while it works. */
internal class GridFiltering(
    private val state: MutableState<GridFilter>,
    val shown: List<CinemaItem>,
    val artOf: (CinemaItem) -> TitleArt?,
    val loadingAll: Boolean,
    val filling: Boolean,
    val wantLanguages: () -> Unit,
) {
    val filter: GridFilter get() = state.value

    fun set(f: GridFilter) {
        state.value = f
    }

    /** "733 titles", "41 of 733 titles · loading all of them…". */
    fun countLabel(all: Int): String {
        val label = if (filter.narrowed) "${shown.size} of $all titles" else if (all == 1) "1 title" else "$all titles"
        val note =
            when {
                loadingAll -> " · loading all of them…"
                filling -> " · checking TMDB…"
                else -> ""
            }
        return label + note
    }
}

/**
 * [items] sorted and filtered as the grid [key] was left. With a sort or filter in use, [loadAll]
 * brings the rest of the titles first; Top rated and Language ask TMDB for the titles it hasn't
 * answered for yet (titles never shown as a card), 12 at a time, re-sorting as answers land.
 */
@Composable
internal fun rememberGridFiltering(
    items: List<CinemaItem>,
    key: String,
    loadAll: (suspend () -> Unit)?,
): GridFiltering {
    val state = remember(key) { GridFilterMemory.of(key) }
    val filter = state.value
    val art = LocalArt.current
    var artTick by remember { mutableStateOf(0) }
    val artOf: (CinemaItem) -> TitleArt? = { item -> item.tmdbId?.let { art?.cached(item.tmdbTv, it) } }
    var languagesWanted by remember(key) { mutableStateOf(false) }
    var filling by remember { mutableStateOf(false) }
    var loadingAll by remember { mutableStateOf(false) }
    LaunchedEffect(filter.active, loadAll) {
        if (filter.active && loadAll != null) {
            loadingAll = true
            try {
                loadAll()
            } finally {
                loadingAll = false
            }
        }
    }
    val needArt = filter.sort == GridSort.RATED || filter.language != null || languagesWanted
    LaunchedEffect(needArt, items.size, art) {
        if (!needArt || art == null) return@LaunchedEffect
        val missing = items.filter { it.tmdbId != null && artOf(it)?.lang == null }
        if (missing.isEmpty()) return@LaunchedEffect
        filling = true
        try {
            missing.chunked(12).forEach { batch ->
                kotlinx.coroutines.coroutineScope { batch.forEach { item -> launch { art.withLanguage(item.tmdbTv, item.tmdbId!!) } } }
                artTick++
            }
        } finally {
            filling = false
        }
    }
    val shown = remember(items, items.size, filter, artTick) { filter.apply(items.toList(), artOf) }
    return GridFiltering(state, shown, artOf, loadingAll, filling) { languagesWanted = true }
}

@Composable
internal fun GridFilterBar(
    g: GridFiltering,
    items: List<CinemaItem>,
    modifier: Modifier = Modifier,
) = GridFilterBar(g.filter, items, g.artOf, g.filling, g.wantLanguages, g::set, modifier)

private enum class GridMenu { SORT, YEAR, GENRE, LANGUAGE }

/**
 * The pills above a grid: Sort, Year, Genre, Language and Unwatched only. OK on one opens its
 * choices; [languagesPending] while TMDB is still being asked for the titles' languages.
 */
@Composable
internal fun GridFilterBar(
    filter: GridFilter,
    items: List<CinemaItem>,
    art: (CinemaItem) -> TitleArt?,
    languagesPending: Boolean,
    onLanguagesWanted: () -> Unit,
    onChange: (GridFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf<GridMenu?>(null) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        NavPill("Sort: ${filter.sort.label}", selected = filter.sort != GridSort.LISTED) { open = GridMenu.SORT }
        NavPill("Year: ${filter.yearLabel}", selected = filter.decade != null || filter.year != null) { open = GridMenu.YEAR }
        NavPill("Genre: ${filter.genre ?: "Any"}", selected = filter.genre != null) { open = GridMenu.GENRE }
        NavPill("Language: ${filter.language?.let(::languageName) ?: "Any"}", selected = filter.language != null) {
            onLanguagesWanted()
            open = GridMenu.LANGUAGE
        }
        NavPill("Unwatched only", selected = filter.unwatched) { onChange(filter.copy(unwatched = !filter.unwatched)) }
        if (filter.active) NavPill("Clear") { onChange(GridFilter()) }
    }
    val close = { open = null }
    when (open) {
        GridMenu.SORT ->
            ChoiceMenu("Sort by", GridSort.entries.map { Choice(it.label, null, it == filter.sort) { onChange(filter.copy(sort = it)) } }, close)
        GridMenu.YEAR -> {
            val choices =
                listOf(Choice("Any year", null, filter.decade == null && filter.year == null) { onChange(filter.copy(decade = null, year = null)) }) +
                    yearChoices(items).map { (v, n) ->
                        if (v != null && v < 0) {
                            Choice("${-v}s", n, filter.decade == -v && filter.year == null) { onChange(filter.copy(decade = -v, year = null)) }
                        } else {
                            Choice("    $v", n, filter.year == v) { onChange(filter.copy(year = v, decade = null)) }
                        }
                    }
            ChoiceMenu("Year", choices, close)
        }
        GridMenu.GENRE ->
            ChoiceMenu(
                "Genre",
                listOf(Choice("Any genre", null, filter.genre == null) { onChange(filter.copy(genre = null)) }) +
                    genreChoices(items).map { (g, n) -> Choice(g, n, filter.genre == g) { onChange(filter.copy(genre = g)) } },
                close,
            )
        GridMenu.LANGUAGE ->
            ChoiceMenu(
                if (languagesPending) "Language (still checking some titles…)" else "Language",
                listOf(Choice("Any language", null, filter.language == null) { onChange(filter.copy(language = null)) }) +
                    languageChoices(items, art).map { (l, n) -> Choice(languageName(l), n, filter.language == l) { onChange(filter.copy(language = l)) } },
                close,
            )
        null -> Unit
    }
}

private class Choice(
    val label: String,
    val count: Int?,
    val picked: Boolean,
    val pick: () -> Unit,
)

/** A list of choices over the grid, the picked one focused; OK picks and closes, Back closes. */
@Composable
private fun ChoiceMenu(
    title: String,
    choices: List<Choice>,
    onClose: () -> Unit,
) {
    val at = choices.indexOfFirst { it.picked }.coerceAtLeast(0)
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (at - 3).coerceAtLeast(0))
    // Whether an OK went down inside the menu (the one that opened it was pressed on the pill)
    val okDown = remember { booleanArrayOf(false) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        for (attempt in 0 until 20) {
            withFrameNanos {}
            if (runCatching { focus.requestFocus() }.getOrDefault(false)) break
        }
    }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(260, easing = CinemaEase)) }
    BackHandler(onBack = onClose)
    Popup(alignment = Alignment.Center, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier
                .graphicsLayer {
                    alpha = enter.value
                    translationY = (1f - enter.value) * 10.dp.toPx()
                }.onPreviewKeyEvent {
                    val ok = it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter
                    when {
                        it.key == Key.Back || it.key == Key.Escape -> {
                            if (it.type == KeyEventType.KeyUp) onClose()
                            true
                        }
                        ok && it.type == KeyEventType.KeyDown -> {
                            okDown[0] = true
                            false
                        }
                        ok && it.type == KeyEventType.KeyUp && !okDown[0] -> true
                        else -> false
                    }
                }.width(380.dp)
                .background(Color(0xF2181818), RoundedCornerShape(14.dp))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                .padding(10.dp),
        ) {
            Text(title, color = InkDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp, end = 12.dp))
            LazyColumn(state = list, modifier = Modifier.heightIn(max = 460.dp), contentPadding = PaddingValues(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(choices) { i, c ->
                    MenuRow(
                        c.label + (c.count?.let { "  ·  $it" } ?: ""),
                        if (c.picked) CheckIcon else null,
                        if (i == at) Modifier.focusRequester(focus) else Modifier,
                    ) {
                        c.pick()
                        onClose()
                    }
                }
            }
        }
    }
}
