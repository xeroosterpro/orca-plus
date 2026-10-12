package com.wholphinplus.sources.welcome

import com.wholphinplus.sources.core.cancellable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.wholphinplus.sources.cinema.Ink
import com.wholphinplus.sources.cinema.InkDim
import com.wholphinplus.sources.cinema.Plus
import com.wholphinplus.sources.cinema.PosterOverlays
import com.wholphinplus.sources.sync.PairStart
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

/** The tour's stops, in order. Poster tags and pages are Cinema's only; saving with a PIN follows the tour. */
internal enum class TourStop { SERVERS, DEVICE, TAGS }

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
                "The Orca+ cloud can't be reached right now, so your saved setup can't come over. Set this TV up with a short tour; once the cloud is back, Settings → Account brings your setup here with your PIN."
            } else {
                "This account doesn't have an Orca+ setup in the cloud yet. A short tour sets everything up, and at the end you can save it with a PIN for your other TVs."
            },
            Modifier.width(640.dp),
        )
        Spacer(Modifier.height(28.dp))
        PillButton("Start the tour", modifier = Modifier.focusRequester(go), onClick = onNew)
    }
}

/**
 * This TV: what Orca+ found on the box and how much it should do (owner, 2026-10-11: "showing what
 * device was detected and what profile to pick… adjust settings and profile to fit the device").
 * The pick is Settings → This TV → Performance; Light also suggests the Clean poster look next.
 */
@Composable
internal fun DeviceStep(
    hook: SourceHook,
    onNext: () -> Unit,
    // The one-time prompt on an older install ([DevicePrompt]) has no tour around it
    eyebrow: String? = null,
    body: String = "Orca+ looked at this box and picked how much it does. Keep that, or choose; Settings → This TV changes it any time.",
) {
    val device = com.wholphinplus.sources.DeviceClass
    // The decoders and the screen are read on a background thread at start: shown as they come
    var facts by remember { mutableStateOf(device.facts) }
    LaunchedEffect(Unit) {
        var tries = 0
        while (facts.decoders.isEmpty() && facts.screenHeight == 0 && tries++ < 24) {
            delay(250)
            facts = device.facts
        }
    }
    val mode by hook.store.deviceMode.collectAsState()
    val recommended = device.auto
    val choices =
        listOf(
            Triple(device.AUTO, "Auto  ·  ${recommended.label}", "Recommended, and it adjusts itself"),
            Triple(com.wholphinplus.sources.DeviceClass.Tier.FULL.name, "Full", "Every effect, the most kept ready"),
            Triple(com.wholphinplus.sources.DeviceClass.Tier.BALANCED.name, "Balanced", "Less kept ready, for mid-range boxes"),
            Triple(com.wholphinplus.sources.DeviceClass.Tier.LIGHT.name, "Light", "Quicker motion, lighter pictures, for sticks"),
        )
    var shown by remember { mutableStateOf(choices.indexOfFirst { it.first == mode }.coerceAtLeast(0)) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Row(Modifier.fillMaxSize().padding(horizontal = StepGutter, vertical = 40.dp), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(0.46f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StepHeader(eyebrow ?: tourStep(TourStop.DEVICE), "Tuned for this TV", body)
            Spacer(Modifier.height(2.dp))
            choices.forEachIndexed { i, (key, name, about) ->
                ChoiceCard(
                    name,
                    about,
                    listOf("A", "F", "B", "L")[i],
                    listOf(Violet, Color(0xFF3E8E7E), Color(0xFF5A5A66), Rose)[i],
                    modifier = Modifier.onFocusChanged { if (it.isFocused) shown = i }.let { if (i == shown) it.focusRequester(first) else it },
                    trailing = if (key == mode) "✓" else null,
                    compact = true,
                ) {
                    hook.store.setDeviceMode(key)
                    hook.store.markDevicePrompted()
                    onNext()
                }
            }
        }
        val tier = if (choices[shown].first == device.AUTO) recommended else com.wholphinplus.sources.DeviceClass.Tier.valueOf(choices[shown].first)
        DevicePanel(facts, recommended, tier, Modifier.weight(0.54f))
    }
}

/**
 * "Tuned for this TV" once on a TV set up before the tour had that stop (owner, 2026-10-11), a few
 * seconds after the app opens and after the cloud offer has had its turn. Skipped where the
 * performance was already picked by hand. Any choice, or Back, and it isn't asked again.
 */
@Composable
internal fun DevicePrompt(hook: SourceHook) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val store = hook.store
        if (store.devicePrompted) return@LaunchedEffect
        if (store.deviceMode.value != com.wholphinplus.sources.DeviceClass.AUTO) {
            store.markDevicePrompted()
            return@LaunchedEffect
        }
        delay(4_000)
        // Never on top of the cloud offer: wait until it's answered or won't come. (It used to wait
        // for the offer's answer alone, so a TV where the offer never appeared never saw this.)
        while (!com.wholphinplus.sources.ui.PromptTurn.cloudDone) delay(500)
        delay(1_500)
        if (!store.devicePrompted) open = true
    }
    if (!open) return
    val close = {
        hook.store.markDevicePrompted()
        open = false
    }
    com.wholphinplus.sources.ui.PadDialog(onClose = close) {
        Box(Modifier.fillMaxSize()) {
            WelcomeBackdrop(emptyList())
            DeviceStep(
                hook,
                onNext = close,
                eyebrow = "NEW IN ORCA+",
                body = "Orca+ can now fit itself to the box it runs on. Here's what it found on this one; keep its pick or choose. Settings → This TV changes it any time.",
            )
        }
    }
}

/** The box's facts, why Orca+ recommends its class, and what the focused choice changes. */
@Composable
private fun DevicePanel(
    f: com.wholphinplus.sources.DeviceClass.Facts,
    recommended: com.wholphinplus.sources.DeviceClass.Tier,
    tier: com.wholphinplus.sources.DeviceClass.Tier,
    modifier: Modifier = Modifier,
) {
    val device = com.wholphinplus.sources.DeviceClass
    val screen =
        (
            listOfNotNull(
                when {
                    f.screenHeight >= 2160 -> "4K"
                    f.screenHeight > 0 -> "${f.screenHeight}p"
                    else -> null
                },
            ) + listOf("Dolby Vision", "HDR10+", "HDR10", "HLG").filter { it in f.screenHdr }
        ).joinToString("  ·  ").ifBlank { "Reading…" }
    val facts =
        listOf(
            "Processor" to listOfNotNull(f.soc.takeIf { it.isNotBlank() }, if (f.maxGhz >= 0.3f) "%d cores, %.1f GHz".format(java.util.Locale.US, f.cores, f.maxGhz) else "${f.cores} cores").joinToString("  ·  "),
            "Memory" to if (f.ramMb > 0) "%.1f GB".format(java.util.Locale.US, f.ramMb / 1024f) else "Unknown",
            "Screen" to screen,
            "Plays in hardware" to
                when {
                    f.decoders.isNotEmpty() -> f.decoders.keys.sorted().joinToString("  ·  ")
                    f.screenHeight > 0 -> "Software only"
                    else -> "Reading…"
                },
        )
    val changes =
        when (tier) {
            com.wholphinplus.sources.DeviceClass.Tier.FULL -> listOf("Every glide, fade and zoom as designed", "About three rows kept ready each way", "Sharp card pictures")
            com.wholphinplus.sources.DeviceClass.Tier.BALANCED -> listOf("Glides and fades as designed", "Fewer rows kept ready", "Sharp pictures, smaller if they load slowly")
            com.wholphinplus.sources.DeviceClass.Tier.LIGHT -> listOf("Quicker glides, simpler focus", "Cards fill in once you stop", "Smaller, lighter pictures", "Next: the Clean poster look is suggested")
        }
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Glass)
            .border(1.dp, GlassLine, RoundedCornerShape(22.dp))
            .padding(horizontal = 30.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text("THIS TV", color = Plus, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Text(device.nameOf(f), color = Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        facts.forEach { (label, value) ->
            Row(verticalAlignment = Alignment.Top) {
                Text(label, color = InkDim, fontSize = 14.sp, modifier = Modifier.width(150.dp))
                Text(value, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(GlassLine))
        Text("Recommended: ${recommended.label}", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(device.reasonOf(f), color = InkDim, fontSize = 14.sp)
        Spacer(Modifier.height(2.dp))
        Text("WITH ${tier.label.uppercase()}", color = InkDim, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        changes.forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Plus))
                Spacer(Modifier.width(10.dp))
                Text(line, color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
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
    // A Light box (picked a step earlier) starts on Clean: fewer layers on every card
    val light = com.wholphinplus.sources.DeviceClass.light
    val presets =
        listOf(
            Triple("Clean", if (light) "Suggested here: picture and title only" else "Just the picture and the title", PosterOverlays.CLEAN),
            Triple("Standard", "Adds Top 10, watched, new and services", PosterOverlays.STANDARD),
            Triple("Everything", "Every tag: 4K, HDR, audio and scores", PosterOverlays.EVERYTHING),
        )
    val start = if (light) 0 else 1
    var shown by remember { mutableStateOf(start) }
    // Home loads behind the tour: the preview then wears a real title, and Home is ready at the end
    var homeReady by remember { mutableStateOf(0) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LaunchedEffect(Unit) {
        runCatching { com.wholphinplus.sources.cinema.preloadHome(CinemaRepository(hook, hook.collections), art) }.cancellable().onFailure { Timber.w(it, "Home for the tour") }
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
                    modifier = Modifier.onFocusChanged { if (it.isFocused) shown = i }.let { if (i == start) it.focusRequester(first) else it },
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

private const val SHOWN_ROWS = 8

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
            val p = runCatching { hook.profileSync.pairStart() }.cancellable().getOrNull()
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
                val got = runCatching { hook.profileSync.pairCollect(p) }.cancellable().getOrNull() ?: continue
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
            // Not added yet: an outlined ring, visible over the poster wall (a faint fill alone wasn't)
            Modifier.size(26.dp).clip(CircleShape).background(if (added) Color(0xFF2E7D32) else Color(0x33000000))
                .then(if (added) Modifier else Modifier.border(1.5.dp, Color(0xB3FFFFFF), CircleShape)),
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
