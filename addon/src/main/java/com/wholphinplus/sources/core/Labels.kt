// Contains code adapted from a third-party project under the Apache License 2.0 and modified
// for Orca+. See NOTICE and LICENSES/Apache-2.0.txt.
package com.wholphinplus.sources.core

import java.util.Locale

/**
 * Display labels: Atmos, TrueHD, DTS:X, DTS-HD MA, DTS-HD, DTS, DD+, DD. The release file name
 * fills in what the stream metadata leaves out.
 */
object Labels {
    private val ATMOS = Regex("""\bATMOS\b|eac3[-_]?joc""", RegexOption.IGNORE_CASE)
    private val TRUEHD = Regex("""\bTRUEHD\b""", RegexOption.IGNORE_CASE)
    private val DTS_X = Regex("""\bDTS[-_.: ]?X\b""", RegexOption.IGNORE_CASE)
    private val DTS_HD_MA = Regex("""\bDTS[-_. ]?(?:HD[-_. ]?)?(?:MA|MASTER)\b""", RegexOption.IGNORE_CASE)
    private val DTS_HD = Regex("""\bDTS[-_. ]?HD\b""", RegexOption.IGNORE_CASE)
    private val DTS = Regex("""\b(DTS|DCA)\b""", RegexOption.IGNORE_CASE)
    private val DD_PLUS = Regex("""\b(DDP|DD\+|EAC-?3|E-?AC-?3)""", RegexOption.IGNORE_CASE)
    private val DD = Regex("""\b(AC-?3|DD(?:[ ._-]?5[ ._-]?1)?|DOLBY[ ._-]?DIGITAL)\b""", RegexOption.IGNORE_CASE)
    private val DV = Regex("""\b(DV|DoVi|Dolby[\s._-]*Vision)\b""", RegexOption.IGNORE_CASE)
    private val HDR10_PLUS = Regex("""(HDR10\+|HDR10\s*PLUS|HDR\s*10\s*\+)""", RegexOption.IGNORE_CASE)
    private val HDR = Regex("""\bHDR(10)?\b""", RegexOption.IGNORE_CASE)

    /** e.g. "TrueHD Atmos 7.1", "DD+ 5.1", "DTS-HD MA 5.1". */
    fun audio(
        codec: String,
        profile: String,
        channels: Int?,
        trackTitle: String,
        fileName: String,
    ): String {
        // The track's own codec decides the family; the file name only fills gaps.
        val track = "$codec $profile $trackTitle"
        val blob = "$track $fileName"
        val c = codec.lowercase(Locale.US)
        val family =
            when {
                c == "truehd" || TRUEHD.containsMatchIn(track) -> "TrueHD"
                c in setOf("dts", "dca") || DTS.containsMatchIn(track) ->
                    when {
                        DTS_X.containsMatchIn(blob) -> "DTS:X"
                        DTS_HD_MA.containsMatchIn(blob) -> "DTS-HD MA"
                        DTS_HD.containsMatchIn(blob) -> "DTS-HD"
                        else -> "DTS"
                    }
                c == "eac3" || DD_PLUS.containsMatchIn(track) -> "DD+"
                c == "ac3" || DD.containsMatchIn(track) -> "DD"
                c == "aac" -> "AAC"
                c == "flac" -> "FLAC"
                c == "opus" -> "Opus"
                c == "mp3" -> "MP3"
                c.startsWith("pcm") -> "PCM"
                c.isNotBlank() -> c.uppercase(Locale.US)
                // No codec at all: fall back to the release name.
                TRUEHD.containsMatchIn(fileName) -> "TrueHD"
                DTS_X.containsMatchIn(fileName) -> "DTS:X"
                DTS_HD_MA.containsMatchIn(fileName) -> "DTS-HD MA"
                DTS_HD.containsMatchIn(fileName) -> "DTS-HD"
                DTS.containsMatchIn(fileName) -> "DTS"
                DD_PLUS.containsMatchIn(fileName) -> "DD+"
                DD.containsMatchIn(fileName) -> "DD"
                else -> return ""
            }
        // Atmos rides on TrueHD and DD+ only.
        val atmos = family in setOf("TrueHD", "DD+") && ATMOS.containsMatchIn(blob)
        val layout =
            when (channels) {
                null, 0 -> ""
                1 -> "1.0"
                2 -> "2.0"
                3 -> "2.1"
                6 -> "5.1"
                7 -> "6.1"
                8 -> "7.1"
                else -> "${channels}ch"
            }
        return listOf(family, if (atmos) "Atmos" else "", layout).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun videoCodec(codec: String): String =
        when (codec.lowercase(Locale.US)) {
            "hevc", "h265", "h.265" -> "HEVC"
            "h264", "avc", "h.264" -> "H.264"
            "av1" -> "AV1"
            "vp9" -> "VP9"
            "mpeg2video" -> "MPEG-2"
            "vc1" -> "VC-1"
            "" -> ""
            else -> codec.uppercase(Locale.US)
        }

    /** HDR from the release name when the server didn't report it. */
    fun hdrFromName(fileName: String): String =
        when {
            DV.containsMatchIn(fileName) -> "Dolby Vision"
            HDR10_PLUS.containsMatchIn(fileName) -> "HDR10+"
            HDR.containsMatchIn(fileName) -> "HDR10"
            else -> ""
        }
}
