package com.wholphinplus.sources

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Locale

/**
 * The language list behind Settings → preferred audio/subtitle language. The main server (Silo)
 * has no `/Localization/Cultures`: it answers with its web page, the list failed to read, and the
 * language pickers came up empty (owner, 2026-10-12). Where the answer isn't JSON, the device's own
 * languages stand in, in the server's shape (three-letter codes; [Lang] matches them to any track
 * spelling). A server with a real list keeps it.
 */
internal object Cultures {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun fallback(
        request: Request,
        response: Response,
    ): Response {
        if (request.method != "GET" || !request.url.encodedPath.endsWith("/Localization/Cultures", ignoreCase = true)) return response
        if (response.isSuccessful && response.body.contentType()?.subtype?.contains("json") == true) return response
        response.close()
        return Response
            .Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(list().toResponseBody(JSON))
            .build()
    }

    private val built by lazy {
        Locale
            .getISOLanguages()
            .mapNotNull { two ->
                val locale = Locale(two)
                val three = runCatching { locale.isO3Language }.getOrNull()?.takeIf { it.length == 3 } ?: return@mapNotNull null
                val name = locale.getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && it != two } ?: return@mapNotNull null
                Triple(two, three, name)
            }.distinctBy { it.second }
            .sortedBy { it.third.lowercase() }
            .map { (two, three, name) ->
                JsonObject(
                    mapOf(
                        "Name" to JsonPrimitive(two),
                        "DisplayName" to JsonPrimitive(name),
                        "TwoLetterISOLanguageName" to JsonPrimitive(two),
                        "ThreeLetterISOLanguageName" to JsonPrimitive(three),
                        "ThreeLetterISOLanguageNames" to JsonArray(listOf(JsonPrimitive(three))),
                    ),
                )
            }.let { JsonArray(it).toString() }
    }

    fun list(): String = built
}
