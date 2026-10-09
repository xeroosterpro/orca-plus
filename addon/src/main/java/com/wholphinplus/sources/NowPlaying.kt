package com.wholphinplus.sources

import com.wholphinplus.sources.core.ExternalSource
import com.wholphinplus.sources.core.Labels
import com.wholphinplus.sources.core.qualityLabel

/**
 * The card that says how a title is playing, a moment after it starts (owner, 2026-10-08): direct
 * play or transcode, the picture (resolution, HDR, codec), the sound, and which server. Each part
 * can be switched off (Settings → Playback). Synced with the profile.
 */
@kotlinx.serialization.Serializable
data class PlayInfoPrefs(
    val on: Boolean = true,
    val method: Boolean = true,
    val quality: Boolean = true,
    val audio: Boolean = true,
    val server: Boolean = true,
)

data class PlayInfo(
    val method: String,
    val transcoding: Boolean,
    val quality: String,
    val hdr: String,
    val videoCodec: String,
    val audio: String,
    val server: String,
)

/** What the player itself reports for the chosen video and audio tracks. */
data class PlayedFormats(
    val width: Int = 0,
    val height: Int = 0,
    val videoMime: String = "",
    val hdrTransfer: Int = -1,
    val audioMime: String = "",
    val audioChannels: Int = 0,
)

object NowPlaying {
    fun methodLabel(playMethod: String?): String =
        when (playMethod) {
            "DIRECT_PLAY" -> "Direct play"
            "DIRECT_STREAM" -> "Direct stream"
            "TRANSCODE" -> "Transcode"
            else -> ""
        }

    fun videoCodec(mime: String): String =
        when (mime.lowercase()) {
            "video/hevc", "video/dolby-vision" -> "HEVC"
            "video/avc" -> "H.264"
            "video/av01" -> "AV1"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/mpeg2" -> "MPEG-2"
            "" -> ""
            else -> mime.substringAfter('/').uppercase()
        }

    // Media3's C.COLOR_TRANSFER_ST2084 (PQ) and C.COLOR_TRANSFER_HLG
    fun hdr(
        mime: String,
        transfer: Int,
    ): String =
        when {
            mime.equals("video/dolby-vision", ignoreCase = true) -> "Dolby Vision"
            transfer == 6 -> "HDR10"
            transfer == 7 -> "HLG"
            else -> ""
        }

    fun audioCodec(mime: String): String =
        when (mime.lowercase()) {
            "audio/true-hd" -> "truehd"
            "audio/eac3", "audio/eac3-joc" -> "eac3"
            "audio/ac3" -> "ac3"
            "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.uhd" -> "dts"
            "audio/mp4a-latm" -> "aac"
            "audio/opus" -> "opus"
            "audio/flac" -> "flac"
            "audio/mpeg" -> "mp3"
            "audio/raw" -> "pcm"
            else -> mime.substringAfter('/')
        }

    private fun audioFrom(f: PlayedFormats): String {
        val codec = audioCodec(f.audioMime)
        val atmos = if (f.audioMime.equals("audio/eac3-joc", ignoreCase = true)) "Atmos" else ""
        val dtsHd = if (f.audioMime.equals("audio/vnd.dts.hd", ignoreCase = true)) "DTS-HD" else ""
        return Labels.audio(codec, "", f.audioChannels.takeIf { it > 0 }, "$atmos $dtsHd".trim(), "")
    }

    /**
     * The card's facts. A file played as it is ([copy], direct play) is described by its own
     * labels (Remux, Dolby Vision, TrueHD Atmos); a transcode by what the player receives.
     */
    fun info(
        playMethod: String?,
        copy: ExternalSource?,
        isMain: Boolean,
        played: PlayedFormats,
    ): PlayInfo {
        val transcoding = playMethod == "TRANSCODE"
        val server = if (isMain || copy == null) PlaybackTrouble.MAIN else copy.serverLabel
        if (copy != null && !transcoding) {
            return PlayInfo(
                method = methodLabel(playMethod),
                transcoding = false,
                quality = copy.quality.takeIf { it != "?" } ?: qualityLabel(played.height, played.width, ""),
                hdr = copy.hdr.ifBlank { hdr(played.videoMime, played.hdrTransfer) },
                videoCodec = copy.videoCodec.ifBlank { videoCodec(played.videoMime) },
                audio = copy.audio.ifBlank { audioFrom(played) },
                server = server,
            )
        }
        return PlayInfo(
            method = methodLabel(playMethod),
            transcoding = transcoding,
            quality = qualityLabel(played.height, played.width, ""),
            hdr = hdr(played.videoMime, played.hdrTransfer),
            videoCodec = videoCodec(played.videoMime),
            audio = audioFrom(played),
            server = server,
        )
    }
}
