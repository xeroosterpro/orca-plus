package com.wholphinplus.sources

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Whether a request goes to the main server: same scheme, host and port, both read by OkHttp
 * (host lower-cased, IPv6 without brackets, default ports filled in). Wholphin's image client
 * adds the main server's sign-in only then: an extra Emby or Jellyfin on the same NAS (another
 * port) must not get it, and an IPv6 or `_` host must still match itself (java.net.URI read
 * those as no host, so the main server's own pictures went without it).
 */
object SameOrigin {
    fun matches(
        base: String?,
        request: HttpUrl,
    ): Boolean {
        val main = base?.trim()?.toHttpUrlOrNull() ?: return false
        return main.scheme == request.scheme && main.host.equals(request.host, ignoreCase = true) && main.port == request.port
    }
}
