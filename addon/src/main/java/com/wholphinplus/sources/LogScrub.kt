package com.wholphinplus.sources

/**
 * Takes sign-in secrets out of a log line. Release builds log INFO and up to logcat, and
 * Wholphin's "Send app logs to your server" uploads logcat to the main server, which may be
 * someone else's: a stream URL of an extra server (`?api_key=…`, `X-Plex-Token=…`) or a request's
 * `Authorization` header must never reach it. Wholphin's log trees run every line through here
 * (hooks in WholphinApplication and DebugLogTree), so addon code may log URLs and exceptions, but
 * should still prefer a server's label to its address.
 */
object LogScrub {
    private const val HIDDEN = "…"

    // Query / form parameters and `name=value` pairs: api_key=, ApiKey=, X-Plex-Token=, token=, …
    private val param =
        Regex("""(?i)\b(api_?key|x-plex-token|x-emby-token|x-mediabrowser-token|access_?token|token)(=)[^&\s"'#,;)}\]]+""")

    // JSON fields: "AccessToken":"…", "ApiKey": "…"
    private val json =
        Regex("""(?i)("(?:api_?key|access_?token|x-plex-token|x-emby-token|token)"\s*:\s*")[^"]*(")""")

    // An authorization header (line or map entry): the whole value goes (MediaBrowser Client="…", Token="…")
    private val authorization = Regex("""(?i)\b((?:x-emby-)?authorization)(\s*[:=]\s*)[^\n]*""")

    // A token header line: `X-Plex-Token: …`
    private val tokenHeader =
        Regex("""(?i)\b(x-emby-token|x-mediabrowser-token|x-plex-token|api_?key)(\s*:\s*)[^\s"',;}\]]+""")

    // `Token="…"` inside an Emby/Jellyfin authorization value, wherever it shows up
    private val quotedToken = Regex("""(?i)\b(token\s*=\s*")[^"]*(")""")

    fun text(s: String?): String {
        if (s.isNullOrEmpty()) return s.orEmpty()
        // Cheap check first: most lines carry none of these words
        val low = s.lowercase()
        if (KEYS.none { it in low }) return s
        return s
            .replace(json, "$1$HIDDEN$2")
            .replace(quotedToken, "$1$HIDDEN$2")
            .replace(param, "$1$2$HIDDEN")
            .replace(tokenHeader, "$1$2$HIDDEN")
            .replace(authorization, "$1$2$HIDDEN")
    }

    private val KEYS = listOf("key", "token", "authorization")
}
