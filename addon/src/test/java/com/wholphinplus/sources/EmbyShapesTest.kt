package com.wholphinplus.sources

import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.UserDto
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real Emby answers (bench server, not in git: EMBY_SAMPLES points at a file of "KIND\tjson" lines),
 * fitted and then read with the Jellyfin SDK's own models. Skipped without the file.
 */
class EmbyShapesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val samples: Map<String, String> by lazy {
        val f = System.getenv("EMBY_SAMPLES")?.let(::File)?.takeIf { it.exists() } ?: return@lazy emptyMap()
        f.readLines().filter { '\t' in it }.associate { it.substringBefore('\t') to it.substringAfter('\t') }
    }

    private fun fitted(
        kind: String,
        method: String,
        path: String,
    ): String {
        assumeTrue("no EMBY_SAMPLES", samples.containsKey(kind))
        return EmbyShapes.fit(json.parseToJsonElement(samples.getValue(kind)), EmbyShapes.of(method, path)!!).toString()
    }

    @Test fun `a user record reads as a Jellyfin user`() {
        val u = json.decodeFromString(UserDto.serializer(), fitted("USER", "GET", "/Users/Me"))
        assertTrue(u.name!!.isNotBlank())
    }

    @Test fun `an item list reads, with uuids for emby's numeric ids`() {
        val r = json.decodeFromString(BaseItemDtoQueryResult.serializer(), fitted("ITEMS", "GET", "/Items"))
        assertTrue(r.items.isNotEmpty())
        assertTrue(r.items.all { it.id.toString().startsWith("454d4259") })
    }

    @Test fun `a single item reads`() {
        val i = json.decodeFromString(BaseItemDto.serializer(), fitted("ITEM", "GET", "/Items/454d4259-0000-4000-8000-000000000001"))
        assertTrue(i.id.toString().startsWith("454d4259"))
    }

    @Test fun `playback info reads`() {
        val p = json.decodeFromString(PlaybackInfoResponse.serializer(), fitted("PLAYBACK", "POST", "/Items/454d4259-0000-4000-8000-000000000001/PlaybackInfo"))
        assertTrue(p.mediaSources.isNotEmpty())
    }
}
