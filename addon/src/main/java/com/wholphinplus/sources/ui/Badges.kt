// Contains code adapted from a third-party project under the Apache License 2.0 and modified
// for Orca+. See NOTICE and LICENSES/Apache-2.0.txt.
package com.wholphinplus.sources.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.wholphinplus.sources.core.ExternalSource

/**
 * Source badges: white tag images on a dark row, text pills when an image can't load (or in the
 * shared build, which uses text only).
 */
internal data class Badge(
    val text: String,
    val imageUrl: String? = null,
)

private object BadgeImages {
    private const val WHITE_TAGS =
        "https://raw.githubusercontent.com/nobnobz/Omni-Template-Bot-Bid-Raiser/main/Other/white%20regex%20tags"
    const val UHD_4K = "$WHITE_TAGS/white_4k.png"
    const val FULL_HD_1080 = "$WHITE_TAGS/white_1080p.png"
    const val HD_720 = "$WHITE_TAGS/white_720p.png"
    const val REMUX = "https://raw.githubusercontent.com/9mousaa/BetterFormatter/main/images/mono-remux.png"
    const val BLURAY = "https://raw.githubusercontent.com/9mousaa/BetterFormatter/main/images/mono-bluray.png"
    const val IMAX = "$WHITE_TAGS/white_imax.png"
    const val DOLBY_VISION = "$WHITE_TAGS/white_DV.png"
    const val HDR10_PLUS = "$WHITE_TAGS/white_HDR10Plus.png"
    const val HDR10 = "$WHITE_TAGS/white_HDR10.png"
    const val HDR = "$WHITE_TAGS/white_HDR.png"
    const val ATMOS = "$WHITE_TAGS/white_Atmos.png"
    const val TRUEHD = "$WHITE_TAGS/white_TrueHD.png"
    const val DOLBY_DIGITAL_PLUS = "$WHITE_TAGS/white_DDPLUS.png"
    const val DOLBY_DIGITAL = "$WHITE_TAGS/white_DD.png"
    const val DTS_X = "$WHITE_TAGS/white_dtsx.png"
    const val DTS_HD_MA = "$WHITE_TAGS/white_dtsHDMA.png"
    const val DTS_HD = "$WHITE_TAGS/white_dtsHD.png"
    const val DTS = "$WHITE_TAGS/white_dts.png"
    const val AUDIO_7_1 = "$WHITE_TAGS/white_71.png"
    const val AUDIO_5_1 = "$WHITE_TAGS/white_51.png"
}

private val REMUX = Regex("""\bREMUX\b""", RegexOption.IGNORE_CASE)
private val BLURAY = Regex("""\b(BLU-?RAY|BDRIP|BRRIP)\b""", RegexOption.IGNORE_CASE)
private val WEBDL = Regex("""\bWEB[-_. ]?DL\b""", RegexOption.IGNORE_CASE)
private val WEBRIP = Regex("""\bWEB[-_. ]?RIP\b""", RegexOption.IGNORE_CASE)
private val IMAX = Regex("""\bIMAX\b""", RegexOption.IGNORE_CASE)
private val HDR10_IN_NAME = Regex("""\bHDR10\b""", RegexOption.IGNORE_CASE)

internal fun badgesFor(s: ExternalSource): List<Badge> =
    buildList<Badge> {
        when (s.quality) {
            "4K" -> add(Badge("4K", BadgeImages.UHD_4K))
            "1080p" -> add(Badge("1080p", BadgeImages.FULL_HD_1080))
            "720p" -> add(Badge("720p", BadgeImages.HD_720))
            "?" -> Unit
            else -> add(Badge(s.quality))
        }
        when {
            REMUX.containsMatchIn(s.fileName) -> add(Badge("REMUX", BadgeImages.REMUX))
            BLURAY.containsMatchIn(s.fileName) -> add(Badge("BluRay", BadgeImages.BLURAY))
            WEBDL.containsMatchIn(s.fileName) -> add(Badge("WEB-DL"))
            WEBRIP.containsMatchIn(s.fileName) -> add(Badge("WEBRip"))
        }
        when (s.videoCodec) {
            "HEVC" -> add(Badge("HEVC"))
            "H.264" -> add(Badge("AVC"))
            "AV1" -> add(Badge("AV1"))
        }
        // DV releases usually carry an HDR10 base layer too; show both
        val dv = s.hdr == "Dolby Vision"
        if (dv) add(Badge("DV", BadgeImages.DOLBY_VISION))
        when {
            s.hdr == "HDR10+" -> add(Badge("HDR10+", BadgeImages.HDR10_PLUS))
            s.hdr == "HDR10" || (dv && HDR10_IN_NAME.containsMatchIn(s.fileName)) -> add(Badge("HDR10", BadgeImages.HDR10))
            s.hdr == "HLG" -> add(Badge("HLG"))
            !dv && s.hdr.isNotBlank() -> add(Badge("HDR", BadgeImages.HDR))
        }
        if (IMAX.containsMatchIn(s.fileName)) add(Badge("IMAX", BadgeImages.IMAX))

        // Audio label is "<family>[ Atmos][ <layout>]", e.g. "TrueHD Atmos 7.1"
        val audio = s.audio
        when {
            audio.startsWith("TrueHD") -> add(Badge("TrueHD", BadgeImages.TRUEHD))
            audio.startsWith("DTS:X") -> add(Badge("DTS:X", BadgeImages.DTS_X))
            audio.startsWith("DTS-HD MA") -> add(Badge("DTS-HD MA", BadgeImages.DTS_HD_MA))
            audio.startsWith("DTS-HD") -> add(Badge("DTS-HD", BadgeImages.DTS_HD))
            audio.startsWith("DTS") -> add(Badge("DTS", BadgeImages.DTS))
            audio.startsWith("DD+") -> add(Badge("DD+", BadgeImages.DOLBY_DIGITAL_PLUS))
            audio.startsWith("DD") -> add(Badge("DD", BadgeImages.DOLBY_DIGITAL))
            audio.isNotBlank() -> add(Badge(audio.substringBefore(' ')))
        }
        if (" Atmos" in audio) add(Badge("Atmos", BadgeImages.ATMOS))
        when {
            audio.endsWith(" 7.1") -> add(Badge("7.1", BadgeImages.AUDIO_7_1))
            audio.endsWith(" 5.1") -> add(Badge("5.1", BadgeImages.AUDIO_5_1))
            audio.endsWith(" 2.0") -> add(Badge("2.0"))
        }
    }.distinctBy { it.text }
        // Shared builds draw the same badges as text: no third-party images or trademark logos.
        .let { list -> if (com.wholphinplus.sources.BuildConfig.PUBLIC_BUILD) list.map { it.copy(imageUrl = null) } else list }

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
        trailing.filter { it.isNotBlank() }.forEach { TextBadge(it, inverted) }
    }
}

@Composable
private fun BadgeView(
    badge: Badge,
    inverted: Boolean,
) {
    // Text instead of a white image where the background is light
    var failed by remember(badge.imageUrl) { mutableStateOf(false) }
    if (badge.imageUrl != null && !inverted && !failed) {
        AsyncImage(
            model = badge.imageUrl,
            contentDescription = badge.text,
            contentScale = ContentScale.Fit,
            onError = { failed = true },
            modifier =
                Modifier
                    .width(badgeWidth(badge.text))
                    .height(20.dp),
        )
    } else {
        TextBadge(badge.text, inverted)
    }
}

@Composable
private fun TextBadge(
    text: String,
    inverted: Boolean,
) {
    Box(
        modifier =
            Modifier
                .background(if (inverted) Color.Black.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.9f), RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
            color = if (inverted) Color.Black else Color.White,
            maxLines = 1,
        )
    }
}

private fun badgeWidth(text: String): Dp =
    when (text) {
        "4K" -> 42.dp
        "1080p" -> 56.dp
        "720p" -> 50.dp
        "REMUX", "BluRay" -> 62.dp
        "Atmos" -> 66.dp
        "TrueHD" -> 62.dp
        "DTS-HD MA" -> 78.dp
        "DTS-HD" -> 64.dp
        "DTS:X" -> 58.dp
        "DD+" -> 48.dp
        "DD" -> 42.dp
        "DV" -> 76.dp
        "IMAX" -> 54.dp
        "7.1", "5.1" -> 40.dp
        "HDR10+" -> 64.dp
        "HDR10" -> 58.dp
        "HDR" -> 48.dp
        else -> 52.dp
    }
