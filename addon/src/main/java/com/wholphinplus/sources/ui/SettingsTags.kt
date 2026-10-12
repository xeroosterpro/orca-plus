package com.wholphinplus.sources.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.wholphinplus.sources.SourceHook
import com.wholphinplus.sources.core.label
import dagger.hilt.android.EntryPointAccessors

@Composable
internal fun OverlaysScreen(
    hook: SourceHook,
    go: (Screen) -> Unit,
) {
    val o by hook.store.overlays.collectAsState()
    val r by hook.store.ratingPrefs.collectAsState()
    val mdb by hook.store.mdblistKey.collectAsState()
    val context = LocalContext.current
    val entry = remember { EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java) }
    val art = remember { entry.cinemaArt() }
    val ratings = remember { entry.ratings() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val set = { n: com.wholphinplus.sources.cinema.PosterOverlays -> hook.store.setOverlays(n) }
    // A preset keeps your layout (corner, style) and sets every tag, scores on cards included
    val preset = { p: com.wholphinplus.sources.cinema.PosterOverlays, scores: Boolean ->
        set(p.copy(corner = o.corner, style = o.style))
        hook.store.setRatingPrefs(r.copy(onCards = scores))
    }
    val quality = if (o.corner == com.wholphinplus.sources.cinema.OverlayCorner.TOP_RIGHT) "Top right" else "Top left"
    val services = if (o.corner == com.wholphinplus.sources.cinema.OverlayCorner.TOP_RIGHT) "Top left" else "Top right"
    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp), modifier = Modifier.width(470.dp)) {
            // Light boxes: every tag is more work in every card, so say which set runs smoothest
            val light = com.wholphinplus.sources.DeviceClass.light
            item { Section("Quick start", "Set every tag at once, then fine-tune below") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    PresetButton(if (light) "Clean · smoothest" else "Clean", Modifier.focusRequester(first)) { preset(com.wholphinplus.sources.cinema.PosterOverlays.CLEAN, false) }
                    PresetButton("Standard") { preset(com.wholphinplus.sources.cinema.PosterOverlays.STANDARD, false) }
                    PresetButton("Everything") { preset(com.wholphinplus.sources.cinema.PosterOverlays.EVERYTHING, true) }
                }
            }
            if (light) {
                item {
                    Text(
                        "This TV runs in Light mode: every tag is more work in every card, so fewer tags scroll smoother.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    )
                }
            }
            item { Section("Title", "Bottom left") }
            item { Toggle("Title logos", "The title's logo over a clean picture; off: art with the name in it", o.titleLogos) { set(o.copy(titleLogos = it)) } }
            item { Toggle("New labels", "Recently Added and New Episodes along the bottom edge", o.newLabels) { set(o.copy(newLabels = it)) } }
            item { Toggle("Title and date under cards", "The name, release date and length below each card", o.captions) { set(o.copy(captions = it)) } }
            item { Section("Quality", "$quality, for movies once the server knows the file") }
            item { Toggle("Resolution", "4K, HD or SD", o.resolution) { set(o.copy(resolution = it)) } }
            item { Toggle("HDR", "Dolby Vision, HDR10+ or HDR", o.hdr) { set(o.copy(hdr = it)) } }
            item { Toggle("Audio", "Atmos, 7.1 or 5.1", o.audio) { set(o.copy(audio = it)) } }
            item { Toggle("Maturity rating", "PG-13, TV-MA…", o.rating) { set(o.copy(rating = it)) } }
            item { Section("Where it streams", services) }
            item { Toggle("Streaming services", "A show's own network first, then where it streams", o.services) { set(o.copy(services = it)) } }
            if (o.services) {
                item { Choice("Logos per card", if (o.maxServices == 1) "One" else "Up to two") { set(o.copy(maxServices = if (o.maxServices == 1) 2 else 1)) } }
            }
            item { Section("Scores", "Bottom right") }
            item {
                Toggle(
                    "Scores on cards",
                    when {
                        r.sources.isEmpty() -> "No score sources picked"
                        r.sources.all { it.needsKey } && mdb.isBlank() -> "Waiting for an MDBList key (Settings → Keys & Services)"
                        else -> "The first two of " + r.sources.filter { !it.needsKey || mdb.isNotBlank() }.joinToString(", ") { it.label }
                    },
                    r.onCards,
                ) { hook.store.setRatingPrefs(r.copy(onCards = it)) }
            }
            item { MenuItem("Score sources", "IMDb, Rotten Tomatoes, Metacritic and more; the MDBList key; title pages") { go(Screen.Ratings) } }
            item { Section("Status", null) }
            item { Toggle("Top 10", "A red TOP 10 corner on titles in your Top lists", o.top10) { set(o.copy(top10 = it)) } }
            item { Toggle("Watched", "A check on titles you've finished", o.watched) { set(o.copy(watched = it)) } }
            item { Toggle("Progress bar", "How far you got, along the bottom edge", o.progress) { set(o.copy(progress = it)) } }
            item { Section("Layout and style", null) }
            item {
                Choice("Quality badges", "$quality (streaming logos take the other corner)") {
                    set(o.copy(corner = com.wholphinplus.sources.cinema.OverlayCorner.entries.let { it[(o.corner.ordinal + 1) % it.size] }))
                }
            }
            item {
                val next = com.wholphinplus.sources.cinema.OverlayStyle.entries.let { it[(o.style.ordinal + 1) % it.size] }
                Choice("Badge style", if (o.style == com.wholphinplus.sources.cinema.OverlayStyle.MINIMAL) "Minimal: dark glass chips" else "Colour: each badge in its own colour") { set(o.copy(style = next)) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            Text("Preview", style = MaterialTheme.typography.titleMedium)
            com.wholphinplus.sources.cinema.PosterTagsPreview(o, r, art, ratings)
        }
    }
}

/**
 * Settings → Home & Look → Description tags (owner, 2026-10-09): like Poster tags, for the title
 * page's description: presets, a switch per tag, and the page's own block as the preview.
 */
@Composable
internal fun DescriptionTagsScreen(
    hook: SourceHook,
    go: (Screen) -> Unit,
) {
    val t by hook.store.descriptionTags.collectAsState()
    val r by hook.store.ratingPrefs.collectAsState()
    val context = LocalContext.current
    val entry = remember { EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java) }
    val art = remember { entry.cinemaArt() }
    val ratings = remember { entry.ratings() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val set = { n: com.wholphinplus.sources.cinema.DescriptionTags -> hook.store.setDescriptionTags(n) }
    val preset = { p: com.wholphinplus.sources.cinema.DescriptionTags, scores: Boolean ->
        set(p)
        hook.store.setRatingPrefs(r.copy(onTitlePage = scores))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp), modifier = Modifier.width(470.dp)) {
            // Light boxes: every tag is more work in every card, so say which set runs smoothest
            val light = com.wholphinplus.sources.DeviceClass.light
            item { Section("Quick start", "Set every tag at once, then fine-tune below") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    PresetButton(if (light) "Clean · smoothest" else "Clean", Modifier.focusRequester(first)) { preset(com.wholphinplus.sources.cinema.DescriptionTags.CLEAN, false) }
                    PresetButton("Standard") { preset(com.wholphinplus.sources.cinema.DescriptionTags.STANDARD, true) }
                    PresetButton("Everything") { preset(com.wholphinplus.sources.cinema.DescriptionTags.EVERYTHING, true) }
                }
            }
            item { Section("Top line", "Under the title") }
            item { Toggle("Year", "When it came out", t.year) { set(t.copy(year = it)) } }
            item { Toggle("Length", "2h 14m, or a show's seasons", t.length) { set(t.copy(length = it)) } }
            item { Toggle("Ends at", "When it would end if you started now", t.endsAt) { set(t.copy(endsAt = it)) } }
            item { Toggle("Age rating", "PG-13, TV-MA…", t.ageRating) { set(t.copy(ageRating = it)) } }
            item { Section("Quality", "Boxed on the top line, from your server's file") }
            item { Toggle("Resolution", "4K or HD", t.resolution) { set(t.copy(resolution = it)) } }
            item { Toggle("HDR", "Dolby Vision, HDR10+ or HDR", t.hdr) { set(t.copy(hdr = it)) } }
            item { Toggle("Audio", "Dolby Atmos, 7.1 or 5.1", t.audio) { set(t.copy(audio = it)) } }
            item { Section("Under it", null) }
            item { Toggle("Genres", "Up to four", t.genres) { set(t.copy(genres = it)) } }
            item { Toggle("Review scores", "IMDb, Rotten Tomatoes and the other sources you picked", r.onTitlePage) { hook.store.setRatingPrefs(r.copy(onTitlePage = it)) } }
            item { MenuItem("Score sources", "Which scores, in which order; the MDBList key") { go(Screen.Ratings) } }
            item { Toggle("Streaming services", "Where it streams, by the scores", t.services) { set(t.copy(services = it)) } }
            item { Toggle("Tagline", "The poster's one line, above the story", t.tagline) { set(t.copy(tagline = it)) } }
            item { Section("About it", "Below the story, from TMDB") }
            item { Toggle("Director", "Directed by…, or a show's creators", t.makers) { set(t.copy(makers = it)) } }
            item { Toggle("Studio or network", "Who made it, or the channel a show is on", t.studio) { set(t.copy(studio = it)) } }
            item { Toggle("Budget", "What it cost to make", t.budget) { set(t.copy(budget = it)) } }
            item { Toggle("Box office", "What it made in cinemas worldwide", t.boxOffice) { set(t.copy(boxOffice = it)) } }
            item { Toggle("Original language", "When it isn't English", t.language) { set(t.copy(language = it)) } }
            item { Toggle("Show status", "Returning or ended, seasons and episodes", t.status) { set(t.copy(status = it)) } }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            Text("Preview", style = MaterialTheme.typography.titleMedium)
            com.wholphinplus.sources.cinema.DescriptionTagsPreview(t, r, art, ratings)
        }
    }
}

/** A group heading in Poster tags: its name, and where on the card its tags sit. */
@Composable
private fun Section(
    title: String,
    where: String?,
) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp, start = 4.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
        where?.let {
            Text("  ·  $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PresetButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, modifier = modifier) { Text(label) }
}

@Composable
internal fun Toggle(
    title: String,
    summary: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    PlusListItem(
        onClick = { onChange(!checked) },
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(summary) },
        trailingContent = { androidx.tv.material3.Switch(checked = checked, onCheckedChange = null, colors = com.wholphinplus.sources.ui.plusSwitchColors()) },
        modifier = modifier,
    )
}

@Composable
private fun Choice(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    PlusListItem(
        onClick = onClick,
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(value) },
        trailingContent = { Text("⇄", style = MaterialTheme.typography.titleLarge) },
    )
}
