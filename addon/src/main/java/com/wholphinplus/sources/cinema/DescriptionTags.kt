package com.wholphinplus.sources.cinema

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.wholphinplus.sources.core.TitleFacts
import kotlinx.serialization.Serializable
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a title page shows around its description (Settings → Home & Look → Description tags;
 * owner, 2026-10-09: "like Poster tags… 4K labels, ratings, box office, budget"). The defaults
 * are the page as it was; facts (tagline, director, studio, budget, box office, language, a show's
 * status) come from TMDB only when one of them is on. Synced with the profile.
 */
@Serializable
data class DescriptionTags(
    val year: Boolean = true,
    val length: Boolean = true,
    val endsAt: Boolean = false,
    val ageRating: Boolean = true,
    val resolution: Boolean = true,
    val hdr: Boolean = true,
    val audio: Boolean = false,
    val genres: Boolean = true,
    val services: Boolean = true,
    val tagline: Boolean = false,
    val makers: Boolean = false,
    val studio: Boolean = false,
    val budget: Boolean = false,
    val boxOffice: Boolean = false,
    val language: Boolean = false,
    val status: Boolean = false,
) {
    /** Any switch that needs TMDB's details. */
    val wantsFacts: Boolean get() = tagline || makers || studio || budget || boxOffice || language || status

    companion object {
        val CLEAN = DescriptionTags(resolution = false, hdr = false, services = false)
        val STANDARD = DescriptionTags()
        val EVERYTHING = DescriptionTags(true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true)
    }
}

internal val LocalDescriptionTags = staticCompositionLocalOf { DescriptionTags() }

/** One title, as the description block needs it (the title page's data, or the editor's sample). */
internal data class Described(
    val item: CinemaItem,
    val year: String?,
    // "2h 14m", or a show's "3 Seasons"
    val length: String?,
    // Minutes left to watch, for "Ends at"; null for a show
    val minutesLeft: Int?,
    val genres: List<String>,
    // The server's director / creator, when TMDB has none
    val makers: List<String> = emptyList(),
    val series: Boolean = false,
)

/** The facts [tags] wants for [d], from TMDB once per session (null until known, or with none on). */
@Composable
private fun rememberFacts(
    d: Described,
    tags: DescriptionTags,
): TitleFacts? {
    val art = LocalArt.current
    val id = d.item.tmdbId
    val tv = d.item.tmdbTv || d.series
    val f by produceState(if (id != null && tags.wantsFacts) art?.cachedFacts(tv, id, tags.makers) else null, id, tags.wantsFacts, tags.makers) {
        if (id != null && tags.wantsFacts && art != null) value = art.facts(tv, id, tags.makers)
    }
    return f
}

/**
 * The title page's block from the meta line to the description and its facts: what [tags] has
 * on, in the page's own type. [scores] draws the review scores and streaming logos line.
 */
@Composable
internal fun DescriptionBlock(
    d: Described,
    tags: DescriptionTags,
    scores: @Composable (services: Boolean) -> Unit,
) {
    val item = d.item
    val facts = rememberFacts(d, tags)
    val parts =
        listOfNotNull(
            d.year?.takeIf { tags.year },
            d.length?.takeIf { tags.length },
            d.minutesLeft?.takeIf { tags.endsAt && it > 0 }?.let { "Ends at " + LocalTime.now().plusMinutes(it.toLong()).format(EndsAt) },
        )
    val boxes =
        listOfNotNull(
            item.rating?.takeIf { tags.ageRating && it.isNotBlank() },
            item.resolution?.takeIf { tags.resolution && it.isNotBlank() },
            item.hdr?.takeIf { tags.hdr && it.isNotBlank() }?.let { if (it == "DV") "Dolby Vision" else it },
            item.audio?.takeIf { tags.audio && it.isNotBlank() }?.let { if (it == "ATMOS") "Dolby Atmos" else it },
        )
    // Its own column: the lines stack the same wherever it's drawn (title page or preview)
    Column {
        // Wraps to a second line rather than squeezing a box (with every tag on it outgrew the line)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            parts.forEachIndexed { i, m ->
                if (i > 0) Text("•", color = InkDim, fontSize = 13.sp)
                Text(m, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
            }
            boxes.forEach { b ->
                Text(
                    b,
                    color = Ink,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.border(1.dp, InkDim, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        if (tags.genres) {
            // Always takes its line, so the description doesn't jump when the genres arrive
            Spacer(Modifier.height(6.dp))
            Text(d.genres.joinToString("  •  ").ifEmpty { " " }, color = InkDim, fontSize = 13.sp, maxLines = 1)
        }
        // Review scores and streaming logos; no room taken when there are none
        Box(Modifier.padding(top = 10.dp)) { scores(tags.services) }
        facts?.tagline?.takeIf { tags.tagline && it.isNotBlank() }?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Ink.copy(alpha = 0.85f), fontSize = 15.sp, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 580.dp))
        }
        Spacer(Modifier.height(if (tags.tagline && facts?.tagline?.isNotBlank() == true) 6.dp else 14.dp))
        Text(
            item.overview.trim().replace(Regex("\\s+"), " "),
            color = Ink,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 580.dp),
        )
        val lines = factLines(d, tags, facts)
        if (lines.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                lines.forEach { Text(it, color = InkDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 640.dp)) }
            }
        }
    }
}

private val EndsAt: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/** "Directed by … · Studio", then "Budget … · Box office … · Japanese · Returning series · 3 seasons". */
private fun factLines(
    d: Described,
    tags: DescriptionTags,
    f: TitleFacts?,
): List<String> {
    val who =
        listOfNotNull(
            (f?.makers?.takeIf { it.isNotEmpty() } ?: d.makers).takeIf { tags.makers && it.isNotEmpty() }?.let {
                (if (d.series) "Created by " else "Directed by ") + it.joinToString(" & ")
            },
            f?.studio?.takeIf { tags.studio && it.isNotBlank() },
        ).joinToString("  ·  ")
    val numbers =
        listOfNotNull(
            f?.budget?.takeIf { tags.budget && it > 0 }?.let { "Budget ${money(it)}" },
            f?.revenue?.takeIf { tags.boxOffice && it > 0 }?.let { "Box office ${money(it)}" },
            f?.language?.takeIf { tags.language && it.isNotBlank() && it != "en" }?.let { Locale(it).getDisplayLanguage(Locale.US).ifBlank { null } },
            f?.takeIf { tags.status && d.series }?.let { s ->
                val state =
                    when (s.status) {
                        "Returning Series" -> "Returning series"
                        "Ended" -> "Ended"
                        "Canceled" -> "Cancelled"
                        "In Production" -> "In production"
                        else -> null
                    }
                listOfNotNull(
                    state,
                    s.seasons.takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" },
                    s.episodes.takeIf { it > 0 }?.let { "$it episodes" },
                ).joinToString(", ").ifBlank { null }
            },
        ).joinToString("  ·  ")
    return listOf(who, numbers).filter { it.isNotBlank() }
}

/** $185M, $1.2B, $900K. */
internal fun money(dollars: Long): String =
    when {
        dollars >= 1_000_000_000 -> "$" + String.format(Locale.US, "%.1fB", dollars / 1e9).replace(".0B", "B")
        dollars >= 1_000_000 -> "$" + (dollars / 1_000_000) + "M"
        dollars >= 1_000 -> "$" + (dollars / 1_000) + "K"
        else -> "$$dollars"
    }

/**
 * Description tags' preview: a title from your Home (a movie with TMDB facts when there is one),
 * drawn by the title page's own [DescriptionBlock], every switch as it is now.
 */
@Composable
internal fun DescriptionTagsPreview(
    tags: DescriptionTags,
    ratingPrefs: RatingPrefs,
    art: CinemaArt?,
    ratings: RatingsRepository?,
) {
    val sample = androidx.compose.runtime.remember(art) { previewTitle(art) }
    if (sample == null) {
        Text("Open Home once to preview your own titles.", color = InkDim, fontSize = 14.sp)
        return
    }
    val year = sample.meta.firstOrNull { it.length == 4 && it.all(Char::isDigit) }
    val length = sample.meta.firstOrNull { Regex("^(\\d+h )?\\d+m$|Seasons?$").containsMatchIn(it) }
    val minutes = length?.let { l -> Regex("(?:(\\d+)h )?(\\d+)m").find(l)?.let { m -> (m.groupValues[1].toIntOrNull() ?: 0) * 60 + m.groupValues[2].toInt() } }
    val genres = sample.meta.filter { it != year && it != length }
    // Every tag that's on shows, the title's own values where it has them
    val shown =
        sample.copy(
            resolution = sample.resolution?.ifBlank { null } ?: "4K",
            hdr = sample.hdr?.ifBlank { null } ?: "DV",
            audio = sample.audio?.ifBlank { null } ?: "ATMOS",
            rating = sample.rating?.ifBlank { null } ?: "PG-13",
        )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalArt provides art,
        LocalRatingPrefs provides ratingPrefs,
        LocalRatings provides ratings,
    ) {
        // Drawn at a title page's width, then scaled to fit beside the switches
        Scaled(620.dp, 0.62f) {
            Column {
                Text(shown.title, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(14.dp))
                DescriptionBlock(Described(shown, year, length, minutes, genres, series = shown.tmdbTv), tags) { services -> TitleRatings(shown, services) }
            }
        }
    }
}
