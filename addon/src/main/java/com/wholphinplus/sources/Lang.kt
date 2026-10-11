package com.wholphinplus.sources

import org.jellyfin.sdk.model.api.MediaStream
import java.util.Locale

/**
 * One spelling per language for track choices. Wholphin keeps the preferred language as a
 * three-letter code ("eng") and compares it to the server's tracks as text, but the main server
 * labels its tracks with two letters ("en"): with subtitles set to Always, nothing matched and no
 * subtitles came on (owner, 2026-10-11). Codes become ISO 639-2/T, lower case: "en", "eng",
 * "en-US" → "eng"; "ger"/"deu"/"de" → "deu". Anything else (names, "und") stays as it was.
 */
object Lang {
    // ISO 639-2 bibliographic codes and their terminology twins (what Locale gives back)
    private val BIBLIOGRAPHIC =
        mapOf(
            "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho",
            "cze" to "ces", "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu",
            "gre" to "ell", "ice" to "isl", "mac" to "mkd", "mao" to "mri", "may" to "msa",
            "per" to "fas", "rum" to "ron", "slo" to "slk", "tib" to "bod", "wel" to "cym",
        )

    fun code(language: String?): String? {
        val raw = language?.trim()?.takeIf { it.isNotEmpty() } ?: return language
        val base = raw.substringBefore('-').substringBefore('_').lowercase()
        if (!base.all { it in 'a'..'z' }) return raw
        return when (base.length) {
            2 -> runCatching { Locale(base).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() } ?: base
            3 -> BIBLIOGRAPHIC[base] ?: base
            else -> raw
        }
    }

    fun same(
        a: String?,
        b: String?,
    ): Boolean = a != null && b != null && code(a).equals(code(b), ignoreCase = true)

    /** [streams] with their languages in [code]'s spelling (for Wholphin's track choice). */
    fun streams(streams: List<MediaStream>): List<MediaStream> =
        streams.map { s -> code(s.language).let { if (it == s.language) s else s.copy(language = it) } }
}
