package com.wholphinplus.sources.core

import java.util.Locale

/**
 * Short display labels for audio, video codec and HDR. The server's stream metadata comes first;
 * the release file name fills in what it leaves out (Atmos, DTS:X, DTS-HD MA).
 */
object Labels {
    // Explicit ASCII word edges: a letter, digit or underscore next to the match breaks it
    private const val START = "(?<![A-Za-z0-9_])"
    private const val END = "(?![A-Za-z0-9_])"

    private fun pattern(body: String) = Regex(body, RegexOption.IGNORE_CASE)

    private val atmos = pattern("${START}ATMOS$END|EAC3[-_]?JOC")
    private val trueHd = pattern("${START}TRUEHD$END")
    private val dtsX = pattern("${START}DTS[-_.: ]?X$END")
    private val dtsHdMa = pattern("${START}DTS[-_. ]?(?:HD[-_. ]?)?(?:MASTER|MA)$END")
    private val dtsHd = pattern("${START}DTS[-_. ]?HD$END")
    private val dts = pattern("$START(?:DTS|DCA)$END")
    private val ddPlus = pattern("$START(?:DDP|DD\\+|E-?AC-?3)")
    private val dd = pattern("$START(?:AC-?3|DD(?:[ ._-]?5[ ._-]?1)?|DOLBY[ ._-]?DIGITAL)$END")

    private val dolbyVision = pattern("$START(?:DV|DOVI)$END|DOLBY[\\s._-]*VISION$END")
    private val hdr10Plus = pattern("HDR10\\+|HDR10\\s*PLUS|HDR\\s*10\\s*\\+")
    private val hdr = pattern("${START}HDR(?:10)?$END")

    fun audio(
        codec: String,
        profile: String,
        channels: Int?,
        trackTitle: String,
        fileName: String,
    ): String {
        val track = "$codec $profile $trackTitle"
        val everything = "$track $fileName"
        val c = codec.lowercase(Locale.US)

        val family =
            when {
                c == "truehd" || trueHd.containsMatchIn(track) -> "TrueHD"
                c == "dts" || c == "dca" || dts.containsMatchIn(track) -> dtsVariant(everything)
                c == "eac3" || ddPlus.containsMatchIn(track) -> "DD+"
                c == "ac3" || dd.containsMatchIn(track) -> "DD"
                c == "aac" -> "AAC"
                c == "flac" -> "FLAC"
                c == "opus" -> "Opus"
                c == "mp3" -> "MP3"
                c.startsWith("pcm") -> "PCM"
                c.isNotBlank() -> codec.uppercase(Locale.US)
                else -> familyFromName(fileName)
            }
        if (family.isEmpty()) return ""

        val withAtmos = (family == "TrueHD" || family == "DD+") && atmos.containsMatchIn(everything)
        return listOf(family, if (withAtmos) "Atmos" else "", layout(channels))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    private fun dtsVariant(text: String): String =
        when {
            dtsX.containsMatchIn(text) -> "DTS:X"
            dtsHdMa.containsMatchIn(text) -> "DTS-HD MA"
            dtsHd.containsMatchIn(text) -> "DTS-HD"
            else -> "DTS"
        }

    private fun familyFromName(name: String): String =
        when {
            trueHd.containsMatchIn(name) -> "TrueHD"
            dtsX.containsMatchIn(name) -> "DTS:X"
            dtsHdMa.containsMatchIn(name) -> "DTS-HD MA"
            dtsHd.containsMatchIn(name) -> "DTS-HD"
            dts.containsMatchIn(name) -> "DTS"
            ddPlus.containsMatchIn(name) -> "DD+"
            dd.containsMatchIn(name) -> "DD"
            else -> ""
        }

    private fun layout(channels: Int?): String =
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

    fun videoCodec(codec: String): String =
        when (codec.lowercase(Locale.US)) {
            "" -> ""
            "hevc", "h265", "h.265" -> "HEVC"
            "h264", "avc", "h.264" -> "H.264"
            "av1" -> "AV1"
            "vp9" -> "VP9"
            "mpeg2video" -> "MPEG-2"
            "vc1" -> "VC-1"
            else -> codec.uppercase(Locale.US)
        }

    /** Only used when the server didn't report an HDR format. */
    fun hdrFromName(fileName: String): String =
        when {
            dolbyVision.containsMatchIn(fileName) -> "Dolby Vision"
            hdr10Plus.containsMatchIn(fileName) -> "HDR10+"
            hdr.containsMatchIn(fileName) -> "HDR10"
            else -> ""
        }
}
