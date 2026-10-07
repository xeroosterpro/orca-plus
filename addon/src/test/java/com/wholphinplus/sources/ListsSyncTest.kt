package com.wholphinplus.sources

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListsSyncTest {
    private val ids = List(1000) { "%032x".format(it) }
    private val chart = HomeCollection("c1", "https://cloud/v1/charts#cat-movies-action", "Action", itemIds = ids, ranks = List(1000) { it + 1 }, listSize = 1000, refreshedAt = 5)

    @Test fun `a profile carries what each list is, not its thousand matches`() {
        val synced = HomeCollections.forSync(listOf(chart))
        assertEquals(listOf(chart.copy(itemIds = emptyList(), ranks = emptyList(), listSize = 0, refreshedAt = 0)), synced)
        // Small enough for the cloud whatever the lists hold (its limit is 2 MB a profile)
        val many = HomeCollections.forSync(List(250) { chart.copy(id = "c$it", url = chart.url + it) })
        assertTrue(Json.encodeToString(many).length < 100_000)
    }

    @Test fun `restoring keeps this tv's matches and takes the profile's ids`() {
        val local = listOf(chart.copy(id = "local1"))
        val incoming = HomeCollections.forSync(listOf(chart.copy(id = "fromOtherTv", showOnHome = false), HomeCollection("mine", "https://mdblist.com/lists/x/y", "Mine")))
        val out = HomeCollections.restored(local, incoming)
        // Same link: the other TV's id (layouts name it) with this TV's matches
        assertEquals("fromOtherTv", out[0].id)
        assertEquals(false, out[0].showOnHome)
        assertEquals(ids, out[0].itemIds)
        assertEquals(5L, out[0].refreshedAt)
        // New here: nothing matched yet, so it's matched at the next refresh
        assertEquals(0L, out[1].refreshedAt)
        assertTrue(out[1].itemIds.isEmpty())
    }
}
