package com.wholphinplus.sources.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.wholphinplus.sources.SourceHook
import com.wholphinplus.sources.cinema.CinemaRepository
import com.wholphinplus.sources.cinema.HomeRowType
import com.wholphinplus.sources.cinema.Ink
import com.wholphinplus.sources.cinema.InkDim
import com.wholphinplus.sources.cinema.Plus
import com.wholphinplus.sources.cinema.PosterOverlays
import com.wholphinplus.sources.cinema.RowsPage
import com.wholphinplus.sources.sync.PairStart
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

/** The tour's stops, in order. Poster tags and pages are Cinema's only; saving with a PIN follows the tour. */
internal enum class TourStop { SERVERS, LOOK, TAGS, PAGES, POWERUPS }

/** The stops this person's tour has (Classic skips Cinema's). */
internal val LocalTourStops = compositionLocalOf { TourStop.entries.toList() }

/** The tour's place, over its headline: "YOUR TOUR · 3 OF 5". */
@Composable
internal fun tourStep(
    stop: TourStop,
    extra: String? = null,
): String {
    val stops = LocalTourStops.current
    return listOfNotNull("YOUR TOUR  ·  ${stops.indexOf(stop) + 1} OF ${stops.size}", extra).joinToString("  ·  ")
}

/** "I have an Orca+ account", but the cloud has nothing for this one: set it up as new. */
@Composable
internal fun NoProfileStep(
    offline: Boolean,
    onNew: () -> Unit,
) {
    val go = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { go.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 56.dp), verticalArrangement = Arrangement.Center) {
        StepHeader(
            if (offline) "CLOUD OUT OF REACH" else "NO SAVED SETUP YET",
            "Let's set this TV up",
            if (offline) {
                "The Orca+ cloud can't be reached right now, so your saved setup can't come over. Set this TV up with a short tour; once the cloud is back, Settings → Profile & Cloud → Cloud sync brings your setup here with your PIN."
            } else {
                "This account doesn't have an Orca+ setup in the cloud yet. A short tour sets everything up, and at the end you can save it with a PIN for your other TVs."
            },
            Modifier.width(640.dp),
        )
        Spacer(Modifier.height(28.dp))
        PillButton("Start the tour", modifier = Modifier.focusRequester(go), onClick = onNew)
    }
}

/** Poster tags: three looks for the cards, with the real card changing as you move between them. */
@Composable
internal fun TagsStep(
    hook: SourceHook,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    val entry = remember { EntryPointAccessors.fromApplication(context.applicationContext, com.wholphinplus.sources.ui.SourcesEntryPoint::class.java) }
    val art = remember { entry.cinemaArt() }
    val ratings = remember { entry.ratings() }
    val r by hook.store.ratingPrefs.collectAsState()
    val presets =
        listOf(
            Triple("Clean", "Just the picture and the title", PosterOverlays.CLEAN),
            Triple("Standard", "Adds Top 10, watched, new and services", PosterOverlays.STANDARD),
            Triple("Everything", "Every tag: 4K, HDR, audio and scores", PosterOverlays.EVERYTHING),
        )
    var shown by remember { mutableStateOf(1) }
    // Home loads behind the tour: the preview then wears a real title, and Home is ready at the end
    var homeReady by remember { mutableStateOf(0) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LaunchedEffect(Unit) {
        runCatching { com.wholphinplus.sources.cinema.preloadHome(CinemaRepository(hook, hook.collections), art) }.onFailure { Timber.w(it, "Home for the tour") }
        homeReady++
    }
    Row(Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(0.46f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StepHeader(tourStep(TourStop.TAGS), "How should posters look?", "Tags on each card tell you more at a glance. Pick a starting point; every tag can be switched on or off later in Settings → Home & Look → Poster tags.")
            Spacer(Modifier.height(4.dp))
            presets.forEachIndexed { i, (name, about, _) ->
                ChoiceCard(
                    name,
                    about,
                    "${i + 1}",
                    listOf(Color(0xFF5A5A66), Violet, Rose)[i],
                    modifier = Modifier.onFocusChanged { if (it.isFocused) shown = i }.let { if (i == 1) it.focusRequester(first) else it },
                    compact = true,
                ) {
                    hook.store.setOverlays(presets[i].third)
                    hook.store.setRatingPrefs(r.copy(onCards = i == 2))
                    onNext()
                }
            }
        }
        Box(Modifier.weight(0.54f), contentAlignment = Alignment.Center) {
            com.wholphinplus.sources.cinema.PosterTagsPreview(presets[shown].third, r.copy(onCards = shown == 2), art, ratings, sampleKey = homeReady)
        }
    }
}

/** What each page comes with: the rows that start on, as they'll appear. */
@Composable
internal fun PagesStep(
    hook: SourceHook,
    onNext: () -> Unit,
) {
    val go = remember { FocusRequester() }
    var pages by remember { mutableStateOf<List<Pair<RowsPage, List<String>>>?>(null) }
    LaunchedEffect(Unit) {
        val repo = CinemaRepository(hook, hook.collections)
        // The cloud's charts by name (their titles are still being matched to the library)
        withContext(Dispatchers.IO) { runCatching { hook.collections.syncCharts() }.onFailure { Timber.w(it, "Charts for the tour") } }
        val libs = repo.libraries()
        val names = hook.collections.lists.value.associate { it.id to it.name }
        pages =
            RowsPage.entries.map { page ->
                page to
                    repo.layoutFor(page, libs).rows.filter { it.on }.map { spec ->
                        when (spec.type) {
                            HomeRowType.CONTINUE_WATCHING -> "Continue Watching"
                            HomeRowType.SERVICES -> "Streaming services"
                            HomeRowType.GENRES -> "Browse by genre"
                            HomeRowType.DECADES -> "Browse by decade"
                            else -> spec.title.ifBlank { spec.defaultName(libs, names) }
                        }
                    }
            }
    }
    // Once the button is on screen (asking before it's drawn does nothing)
    LaunchedEffect(pages != null) { if (pages != null) runCatching { go.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepHeader(
            tourStep(TourStop.PAGES),
            "Your pages come ready",
            "What's trending, the streaming Top 10s, a page for each service and genre, and your own newest titles, refreshed daily from your library. Rearrange them in Settings → Home & Look → Rows.",
            Modifier.width(760.dp),
        )
        val p = pages
        if (p == null) {
            Text("Putting your pages together…", color = InkDim, fontSize = 15.sp)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                p.forEach { (page, rows) ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Glass).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(page.label.uppercase(), color = Plus, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Spacer(Modifier.height(2.dp))
                        rows.take(SHOWN_ROWS).forEach { name ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(5.dp).clip(CircleShape).background(InkDim))
                                Spacer(Modifier.width(8.dp))
                                Text(name, color = Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (rows.size > SHOWN_ROWS) Text("+ ${rows.size - SHOWN_ROWS} more", color = InkDim, fontSize = 12.sp)
                    }
                }
            }
            PillButton("Looks good", modifier = Modifier.focusRequester(go), onClick = onNext)
        }
    }
}

private const val SHOWN_ROWS = 8

/** Power-ups: keys added from a phone, by scanning the TV's QR code. */
@Composable
internal fun PowerUpsStep(
    hook: SourceHook,
    onNext: () -> Unit,
) {
    val go = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { go.requestFocus() } }
    Row(Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 48.dp), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(0.34f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            StepHeader(
                tourStep(TourStop.POWERUPS, "OPTIONAL"),
                "Power-ups, from your phone",
                "Everything already works without these. Have an MDBList, Trakt, Top Streaming or TMDB account of your own? Scan the code, paste your keys on your phone, and they appear here. No typing with the remote.",
            )
            PillButton("Continue", modifier = Modifier.focusRequester(go), onClick = onNext)
        }
        KeysFromPhone(hook, Modifier.weight(0.66f))
    }
}

/**
 * The QR code for the phone, the code and address to type instead, and what each key unlocks
 * (ticked once it's in). Keys sent from the phone are applied here as they arrive.
 */
@Composable
internal fun KeysFromPhone(
    hook: SourceHook,
    modifier: Modifier = Modifier,
) {
    val tmdb by hook.store.tmdbKey.collectAsState()
    val mdb by hook.store.mdblistKey.collectAsState()
    val trakt by hook.collections.traktClientId.collectAsState()
    val ts by hook.collections.topStreamingAccount.collectAsState()
    var pair by remember { mutableStateOf<PairStart?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // A code lasts 15 minutes; after the phone sends, a fresh one lets it send more
        while (true) {
            val p = runCatching { hook.profileSync.pairStart() }.getOrNull()
            if (p == null) {
                note = "Can't reach the Orca+ cloud right now."
                delay(10_000)
                continue
            }
            note = null
            pair = p
            val until = System.currentTimeMillis() + 14 * 60 * 1000
            while (System.currentTimeMillis() < until) {
                delay(2_000)
                val got = runCatching { hook.profileSync.pairCollect(p) }.getOrNull() ?: continue
                note = "Got it: " + applyKeys(hook, got).joinToString(", ") + " ✓"
                break
            }
        }
    }
    Row(modifier.clip(RoundedCornerShape(22.dp)).background(Glass).padding(22.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(176.dp).clip(RoundedCornerShape(14.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                val p = pair
                if (p != null) AsyncImage(model = p.qr, contentDescription = "QR code", modifier = Modifier.size(166.dp)) else Text("…", color = Color.Black)
            }
            Text(pair?.url?.removePrefix("https://")?.substringBefore("/p/")?.let { "Or open $it/p" } ?: " ", color = InkDim, fontSize = 11.sp)
            Text(pair?.code ?: " ", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PowerUp("MDBList", "IMDb, Rotten Tomatoes and Letterboxd scores", mdb.isNotBlank())
            PowerUp("Trakt", "Your Trakt lists as rows", trakt.isNotBlank())
            PowerUp("Top Streaming", "Your countries' streaming Top 10s", ts.isNotBlank())
            PowerUp("TMDB", "Optional: your own key for art and search", tmdb.isNotBlank())
            note?.let { Text(it, color = if (it.startsWith("Got")) Color(0xFF7BD88F) else InkDim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun PowerUp(
    name: String,
    unlocks: String,
    added: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(if (added) Color(0xFF2E7D32) else Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center,
        ) { Text(if (added) "✓" else "", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(if (added) "Added" else unlocks, color = InkDim, fontSize = 12.5.sp, lineHeight = 16.sp, maxLines = 2)
        }
    }
}

/** Keys from the phone, saved where Orca+ keeps them; the names of the ones that arrived. */
private suspend fun applyKeys(
    hook: SourceHook,
    data: Map<String, String>,
): List<String> =
    withContext(Dispatchers.IO) {
        val names = mutableListOf<String>()
        data["tmdb"]?.let {
            hook.store.setTmdbKey(it)
            names += "TMDB"
        }
        data["mdblist"]?.let {
            hook.store.setMdblistKey(it)
            names += "MDBList"
        }
        data["trakt"]?.let {
            hook.collections.setTraktClientId(it)
            names += "Trakt"
        }
        data["topStreaming"]?.let {
            if (hook.collections.setTopStreamingAccount(it).isNotEmpty()) {
                runCatching { hook.collections.syncTopStreaming() }.onFailure { e -> Timber.w(e, "Top Streaming from the phone") }
                names += "Top Streaming"
            }
        }
        if (names.isNotEmpty()) hook.collections.refreshStale(hook)
        names
    }
