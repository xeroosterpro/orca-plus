package com.wholphinplus.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InboxTest {
    @Before fun empty() = Inbox.clear()

    @Test fun `a keyed message replaces the one before it and the newest comes first`() {
        Inbox.post(Inbox.Kind.SYNC, "Synced", key = "sync", unread = false)
        Inbox.post(Inbox.Kind.WATCHING, "Stopped Heat at 1:00:00")
        Inbox.post(Inbox.Kind.SYNC, "Cloud sync didn't work", "No network", Inbox.Level.WARN, key = "sync")
        val all = Inbox.messages.value
        assertEquals(listOf("Cloud sync didn't work", "Stopped Heat at 1:00:00"), all.map { it.title })
        assertFalse(all[0].read)
    }

    @Test fun `the same news again is not new`() {
        Inbox.post(Inbox.Kind.SERVERS, "Server B is offline", "x", Inbox.Level.WARN, key = "server:1")
        Inbox.markAllRead()
        Inbox.post(Inbox.Kind.SERVERS, "Server B is offline", "x", Inbox.Level.WARN, key = "server:1")
        assertTrue(Inbox.messages.value.single().read)
    }

    @Test fun `a quiet status keeps an unread mark it replaces`() {
        Inbox.post(Inbox.Kind.SERVERS, "Server B is offline", level = Inbox.Level.WARN, key = "server:1")
        Inbox.post(Inbox.Kind.SERVERS, "Server B is back", level = Inbox.Level.GOOD, key = "server:1", popUp = false, unread = false)
        assertFalse(Inbox.messages.value.single().read)
    }

    @Test fun `an update is offered once per version and replaces an older offer`() {
        Inbox.update("1.0.8-r41", "1.0.8-r42")
        Inbox.update("1.0.8-r41", "1.0.8-r42")
        Inbox.update("1.0.8-r41", "1.0.8-r43")
        val all = Inbox.messages.value
        assertEquals(1, all.size)
        assertTrue(all[0].title.endsWith("r43"))
        assertTrue(all[0].action is Inbox.Action.Update)
    }

    @Test fun `a play says where it stopped, an episode by its number, a short one not at all`() {
        Inbox.played("e1", "s1", "The Sopranos", "S4:E6 · Watching Too Much Television", 23 * 60_000L + 10_000L, 55 * 60_000L, "Home Server")
        val m = Inbox.messages.value.single()
        assertEquals("The Sopranos · S4:E6 · Watching Too Much Television", m.title)
        assertEquals("Stopped at 23:10 of 55:00 (42%) · from Home Server", m.body)
        assertEquals(Inbox.Action.OpenTitle("s1", series = true, label = "Resume"), m.action)
        Inbox.played("m1", null, "Heat", null, 30_000L, 10_000_000L, null)
        assertEquals(1, Inbox.messages.value.size)
        Inbox.played("m1", null, "Heat", null, 9_500_000L, 10_000_000L, null)
        assertEquals("Heat" to "Finished", Inbox.messages.value.first().let { it.title to it.body })
    }
}
