package com.wholphinplus.sources

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbyBridgeTest {
    private val u = "0123456789ab4cdeaf0123456789abcd"
    private val dashed = "01234567-89ab-4cde-af01-23456789abcd"

    private fun get(url: String) = Request.Builder().url(url).build()

    private fun path(r: Request?) = r?.url?.encodedPath

    @Test fun `ids travel as marked uuids and come back exactly`() {
        val id = EmbyBridge.toUuid("596545")
        assertEquals("454d4259-0000-4000-8000-000000091a41", id)
        assertEquals("/Items/596545/Images/Primary", EmbyBridge.toEmby("/Items/$id/Images/Primary"))
        assertEquals("ids=596545,3", EmbyBridge.toEmby("ids=${id.replace("-", "")},${EmbyBridge.toUuid("3")}"))
    }

    @Test fun `answers get uuids for numeric ids only`() {
        val json = """{"Id":"596545","ServerId":"fedcba9876544321abcdef0123456789","SeriesId":"475547","UserId":"$u","LocalTrailerIds":["12","34"],"ProviderIds":{"Tmdb":"550"},"IndexNumber":3}"""
        val out = EmbyBridge.toJellyfin(json)
        assertEquals(
            """{"Id":"${EmbyBridge.toUuid("596545")}","ServerId":"fedcba9876544321abcdef0123456789","SeriesId":"${EmbyBridge.toUuid("475547")}","UserId":"$u","LocalTrailerIds":["${EmbyBridge.toUuid("12")}","${EmbyBridge.toUuid("34")}"],"ProviderIds":{"Tmdb":"550"},"IndexNumber":3}""",
            out,
        )
    }

    @Test fun `jellyfin's newer paths become emby's`() {
        assertEquals("/Users/$u/Views", path(EmbyBridge.rewrite(get("https://e.x/UserViews?userId=$dashed"))))
        assertEquals("/Users/$u/Items/Resume", path(EmbyBridge.rewrite(get("https://e.x/UserItems/Resume?userId=$u"))))
        assertEquals("/Users/$u/Items/Latest", path(EmbyBridge.rewrite(get("https://e.x/Items/Latest?userId=$u"))))
        assertEquals("/Users/$u/PlayedItems/5", path(EmbyBridge.rewrite(Request.Builder().url("https://e.x/UserPlayedItems/5?userId=$u").post(okhttp3.RequestBody.create(null, ByteArray(0))).build())))
        val item = EmbyBridge.toUuid("596545")
        assertEquals("/Users/$u/Items/$item", path(EmbyBridge.rewrite(get("https://e.x/Items/$item?userId=$u"))))
        // A server under a path keeps it
        assertEquals("/emby/Users/$u/Views", path(EmbyBridge.rewrite(get("https://e.x/emby/UserViews?userId=$u"))))
    }

    @Test fun `left alone what emby already has`() {
        assertNull(EmbyBridge.rewrite(get("https://e.x/Items?userId=$u&ParentId=3")))
        assertNull(EmbyBridge.rewrite(get("https://e.x/Items/Filters?userId=$u")))
        assertNull(EmbyBridge.rewrite(get("https://e.x/Shows/NextUp?userId=$u")))
        assertNull(EmbyBridge.rewrite(Request.Builder().url("https://e.x/Items/5?userId=$u").delete().build()))
    }

    @Test fun `media segments and quick connect are answered here`() {
        assertEquals(200, EmbyBridge.local(get("https://e.x/MediaSegments/5"))?.code)
        assertEquals("false", EmbyBridge.local(get("https://e.x/QuickConnect/Enabled"))?.body?.string())
        assertNull(EmbyBridge.local(get("https://e.x/Items/5")))
    }
}

class EmbyBridgeListRowsTest {
    @Test fun `orca's list-row tags never reach an emby server`() {
        val r = EmbyBridge.local(Request.Builder().url("https://e.x/Items?userId=u&recursive=true&tags=wholphinplus-list-9ad069f").build())
        assertEquals(200, r?.code)
        assertEquals("""{"Items":[],"TotalRecordCount":0,"StartIndex":0}""", r?.body?.string())
        assertNull(EmbyBridge.local(Request.Builder().url("https://e.x/Items?userId=u&tags=other").build()))
    }
}

class EmbyBridgePlayerUrlTest {
    @Test fun `the player's address carries emby's ids and key name`() {
        EmbyBridge.markEmby("https://p.x/")
        val url = "https://p.x/Videos/${EmbyBridge.toUuid("6250989")}/stream?static=true&mediaSourceId=mediasource_6250989&streamOptions=%7B%7D"
        assertEquals("https://p.x/Videos/6250989/stream?static=true&mediaSourceId=mediasource_6250989&api_key=t", EmbyBridge.forPlayer(url, "t"))
        assertEquals("https://p.x/Videos/1/stream?api_key=k", EmbyBridge.forPlayer("https://p.x/Videos/1/stream?ApiKey=k", "t"))
        assertEquals("https://j.x/Videos/a/stream?streamOptions=%7B%7D", EmbyBridge.forPlayer("https://j.x/Videos/a/stream?streamOptions=%7B%7D", "t"))
    }
}

class EmbyBridgeForeignIdsTest {
    private val ours = EmbyBridge.toUuid("596545")
    private val theirs = "02c10305-a5fa-0400-0000-000000000000"

    @Test fun `another server's ids never reach emby`() {
        val out = EmbyBridge.outgoing(Request.Builder().url("https://e.x/Items?userId=u&ids=$theirs,$ours").build())
        assertEquals("596545", out.url.queryParameter("ids"))
    }

    @Test fun `only another server's ids are answered here, not with a whole-library search`() {
        assertEquals(200, EmbyBridge.local(Request.Builder().url("https://e.x/Items?userId=u&ids=$theirs").build())?.code)
        assertNull(EmbyBridge.local(Request.Builder().url("https://e.x/Items?userId=u&ids=$ours").build()))
    }
}

class EmbyVideoRangeTest {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun read(stream: String): org.jellyfin.sdk.model.api.MediaStream {
        val d = org.jellyfin.sdk.model.api.MediaStream.serializer()
        return json.decodeFromJsonElement(d, EmbyShapes.fit(json.parseToJsonElement(stream), d.descriptor))
    }

    private fun video(range: String, ext: String, sub: String) =
        read("""{"Type":"Video","Index":0,"Codec":"hevc","VideoRange":"$range","ExtendedVideoType":"$ext","ExtendedVideoSubType":"$sub","IsDefault":true,"IsForced":false,"IsInterlaced":false,"IsExternal":false,"IsTextSubtitleStream":false,"SupportsExternalStream":false}""")

    @Test fun `emby's ranges read as jellyfin's`() {
        assertEquals("DOVI", video("DolbyVision", "DolbyVision", "DoviProfile76").videoRangeType.name)
        assertEquals("DOVI_WITH_HDR10", video("DolbyVision", "DolbyVision", "DoviProfile81").videoRangeType.name)
        assertEquals("HLG", video("HLG", "HyperLogGamma", "HyperLogGamma").videoRangeType.name)
        assertEquals("HDR10", video("HDR 10", "Hdr10", "Hdr10").videoRangeType.name)
        assertEquals("HDR", video("HDR 10", "Hdr10", "Hdr10").videoRange.name)
        assertEquals("SDR", video("SDR", "None", "None").videoRangeType.name)
    }

    @Test fun `emby lists skip the total count unless asked and id lookups skip sorting`() {
        fun q(url: String) = EmbyBridge.lighter(okhttp3.Request.Builder().url(url).build()).url
        val list = q("http://emby.local/Users/u/Items?Recursive=true&Limit=40")
        org.junit.Assert.assertEquals("false", list.queryParameter("EnableTotalRecordCount"))
        val asked = q("http://emby.local/Items?startIndex=0&enableTotalRecordCount=true")
        org.junit.Assert.assertEquals(listOf("true"), asked.queryParameterValues("enableTotalRecordCount"))
        org.junit.Assert.assertNull(asked.queryParameter("EnableTotalRecordCount"))
        val ids = q("http://emby.local/Items?Ids=1,2,3")
        org.junit.Assert.assertEquals("None", ids.queryParameter("SortBy"))
        org.junit.Assert.assertEquals("DateCreated", q("http://emby.local/Items?Ids=1&SortBy=DateCreated").queryParameter("SortBy"))
        // A single title and other paths are left alone
        org.junit.Assert.assertNull(q("http://emby.local/Users/u/Items/123").queryParameter("EnableTotalRecordCount"))
        org.junit.Assert.assertNull(q("http://emby.local/Shows/9/Seasons").queryParameter("EnableTotalRecordCount"))
    }
}
