package com.wholphinplus.sources.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.wholphinplus.sources.core.ExternalSource

/**
 * One tag on a source row. [mark]: a headline format (resolution, Remux, HDR, the audio format and
 * layout), drawn as an outlined stamp; the rest are filled pills. All drawn here, in Orca+'s own
 * type: no artwork is downloaded, and no company's logo is used, only its format's name.
 */
internal data class Badge(
    val text: String,
    val mark: Boolean = false,
)

/** What a mark says on screen, where the short tag reads poorly. */
private val spelledOut = mapOf("DV" to "Dolby Vision")

private fun word(body: String) = Regex("(?<![A-Za-z0-9_])(?:$body)(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)

private val remux = word("REMUX")
private val bluRay = word("BLURAY|BLU-RAY|BDRIP|BRRIP")
private val webDl = word("WEB[-_. ]?DL")
private val webRip = word("WEB[-_. ]?RIP")
private val imax = word("IMAX")
private val hdr10Word = word("HDR10")

// Prefixes of the audio label (see Labels.audio); longer names must come before their prefixes
private val audioFamilies = listOf("TrueHD", "DTS:X", "DTS-HD MA", "DTS-HD", "DTS", "DD+", "DD")

internal fun badgesFor(s: ExternalSource): List<Badge> {
    val out = mutableListOf<Badge>()
    fun image(text: String) = out.add(Badge(text, mark = true))
    fun plain(text: String) = out.add(Badge(text))

    when (s.quality) {
        "4K", "1080p", "720p" -> image(s.quality)
        "?" -> Unit
        else -> plain(s.quality)
    }

    val name = s.fileName
    when {
        remux.containsMatchIn(name) -> image("REMUX")
        bluRay.containsMatchIn(name) -> image("BluRay")
        webDl.containsMatchIn(name) -> plain("WEB-DL")
        webRip.containsMatchIn(name) -> plain("WEBRip")
    }

    when (s.videoCodec) {
        "HEVC" -> plain("HEVC")
        "H.264" -> plain("AVC")
        "AV1" -> plain("AV1")
    }

    // Dual-layer Dolby Vision files often carry HDR10 too; say so when the name does
    val dolbyVision = s.hdr == "Dolby Vision"
    if (dolbyVision) image("DV")
    when {
        s.hdr == "HDR10+" -> image("HDR10+")
        s.hdr == "HDR10" || (dolbyVision && hdr10Word.containsMatchIn(name)) -> image("HDR10")
        s.hdr == "HLG" -> plain("HLG")
        !dolbyVision && s.hdr.isNotBlank() -> image("HDR")
    }

    if (imax.containsMatchIn(name)) image("IMAX")

    val audio = s.audio
    val family = audioFamilies.firstOrNull { audio.startsWith(it) }
    when {
        family != null -> image(family)
        audio.isNotBlank() -> plain(audio.substringBefore(' '))
    }
    if (audio.contains(" Atmos")) image("Atmos")
    when {
        audio.endsWith(" 7.1") -> image("7.1")
        audio.endsWith(" 5.1") -> image("5.1")
        audio.endsWith(" 2.0") -> plain("2.0")
    }

    return out.distinctBy { it.text }
}

@Composable
internal fun BadgeRow(
    badges: List<Badge>,
    trailing: List<String>,
    inverted: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        badges.forEach { BadgeView(it, inverted) }
        trailing.filter { it.isNotBlank() }.forEach { TextPill(it, inverted) }
    }
}

@Composable
private fun BadgeView(
    badge: Badge,
    inverted: Boolean,
) {
    if (badge.mark) Mark(spelledOut[badge.text] ?: badge.text, inverted) else TextPill(badge.text, inverted)
}

/** A headline format: bold, outlined, no fill, so it reads before the pills. */
@Composable
private fun Mark(
    text: String,
    inverted: Boolean,
) {
    val ink = if (inverted) Color.Black else Color.White
    Text(
        text,
        color = ink,
        fontSize = 12.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.4.sp,
        maxLines = 1,
        modifier =
            Modifier
                .border(1.dp, ink.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun TextPill(
    text: String,
    inverted: Boolean,
) {
    Text(
        text,
        color = if (inverted) Color.Black else Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.3.sp,
        maxLines = 1,
        modifier =
            Modifier
                .background(Color.Black.copy(alpha = if (inverted) 0.08f else 0.9f), RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}
