package com.wholphinplus.sources

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlayerPicturesTest {
    /** A BIF with pictures [pics] every [everyMs] (timestamps in seconds, unit 1000 ms). */
    private fun bifFile(
        pics: List<ByteArray>,
        everySec: Int,
    ): File {
        val header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a))
        header.putInt(0).putInt(pics.size).putInt(1000)
        val table = ByteBuffer.allocate((pics.size + 1) * 8).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 64 + (pics.size + 1) * 8
        pics.forEachIndexed { i, p ->
            table.putInt(i * everySec).putInt(offset)
            offset += p.size
        }
        table.putInt(-1).putInt(offset)
        return File.createTempFile("test", ".bif").apply {
            deleteOnExit()
            writeBytes(header.array() + table.array() + pics.fold(ByteArray(0)) { a, b -> a + b })
        }
    }

    @Test fun `a thumbnail track gives the picture showing at any moment`() {
        val pics = listOf(byteArrayOf(1, 1), byteArrayOf(2, 2, 2), byteArrayOf(3))
        val bif = Bif.open(bifFile(pics, everySec = 10))
        assertEquals(3, bif.count)
        assertEquals(0, bif.indexAt(0))
        assertEquals(0, bif.indexAt(9_999))
        assertEquals(1, bif.indexAt(10_000))
        assertEquals(2, bif.indexAt(3_600_000))
        assertArrayEquals(byteArrayOf(2, 2, 2), bif.picture(1))
        assertArrayEquals(byteArrayOf(3), bif.picture(2))
        assertNull(bif.picture(3))
    }

    @Test fun `anything else is not a thumbnail track`() {
        val file = File.createTempFile("test", ".bif").apply { deleteOnExit(); writeBytes(ByteArray(200) { 7 }) }
        assertTrue(runCatching { Bif.open(file) }.isFailure)
    }

    @Test fun `chapters match by start time, within a few seconds`() {
        // The Goonies: the main server says 304.55 s, the Emby copy 304 s
        val borrowed = listOf(0L to "a", 304_000L to "b", 428_000L to "c")
        assertEquals("b", PlayerPictures.nearestChapter(borrowed, 304_554))
        assertEquals("a", PlayerPictures.nearestChapter(borrowed, 0))
        assertNull(PlayerPictures.nearestChapter(borrowed, 360_000))
        assertNull(PlayerPictures.nearestChapter(emptyList(), 0))
    }

    @Test fun `only the same cut lends its pictures`() {
        val min = 60L * 10_000_000
        assertTrue(PlayerPictures.sameCut(114 * min, 114 * min + 20 * 10_000_000))
        assertFalse(PlayerPictures.sameCut(114 * min, 125 * min))
        assertFalse(PlayerPictures.sameCut(0, 114 * min))
    }

    private fun item(
        chapterTags: List<String?>,
        trickplay: Boolean,
    ) = org.jellyfin.sdk.model.api.BaseItemDto(
        id = java.util.UUID.randomUUID(),
        type = org.jellyfin.sdk.model.api.BaseItemKind.MOVIE,
        chapters =
            chapterTags.mapIndexed { i, tag ->
                org.jellyfin.sdk.model.api.ChapterInfo(startPositionTicks = i * 6_000_000_000L, imageDateModified = java.time.LocalDateTime.now(), imageTag = tag)
            },
        trickplay =
            if (trickplay) {
                mapOf("src" to mapOf("320" to org.jellyfin.sdk.model.api.TrickplayInfo(320, 180, 10, 10, 100, 10_000, 1000)))
            } else {
                null
            },
    )

    @Test fun `pictures are borrowed only for what the main server lacks`() {
        assertEquals(false to false, PlayerPictures.needs(item(listOf("a", "b"), trickplay = true)))
        assertEquals(true to false, PlayerPictures.needs(item(listOf("a", null), trickplay = true)))
        assertEquals(false to true, PlayerPictures.needs(item(emptyList(), trickplay = false)))
        assertEquals(true to true, PlayerPictures.needs(item(listOf(null), trickplay = false)))
    }

    @Test fun `a download stops past its size cap`() {
        val data = ByteArray(200_000) { it.toByte() }
        val out = java.io.ByteArrayOutputStream()
        assertTrue(com.wholphinplus.sources.core.copyAtMost(data.inputStream(), out, 200_000))
        assertEquals(200_000, out.size())
        assertFalse(com.wholphinplus.sources.core.copyAtMost(data.inputStream(), java.io.ByteArrayOutputStream(), 100_000))
    }
}
