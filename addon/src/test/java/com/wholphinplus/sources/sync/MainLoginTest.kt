package com.wholphinplus.sources.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainLoginTest {
    private val day = 24 * 60 * 60 * 1000L
    private val mine = MainLogin("https://jf.example", "abc", "u1", "", "token-here")
    private val theirs = mine.copy(token = "token-there", at = 10 * day)

    @Test
    fun `the cloud's sign-in stays while it is the same account and recent`() {
        assertEquals(theirs, ProfileMerge.mergeLogin(mine, theirs, now = 12 * day))
    }

    @Test
    fun `a week old, this TV's takes over`() {
        assertEquals(mine.copy(at = 18 * day), ProfileMerge.mergeLogin(mine, theirs, now = 18 * day))
    }

    @Test
    fun `another account or address replaces it at once`() {
        assertEquals(mine.copy(at = 11 * day), ProfileMerge.mergeLogin(mine, theirs.copy(userId = "u2"), now = 11 * day))
        assertEquals(mine.copy(at = 11 * day), ProfileMerge.mergeLogin(mine, theirs.copy(url = "https://old.example"), now = 11 * day))
    }

    @Test
    fun `nothing here keeps the cloud's, nothing anywhere stays nothing`() {
        assertEquals(theirs, ProfileMerge.mergeLogin(null, theirs, now = 11 * day))
        assertNull(ProfileMerge.mergeLogin(null, null, now = 11 * day))
    }

    @Test
    fun `a profile without a sign-in (older Orca+) still reads, and one with it round-trips`() {
        val old = ProfileMerge.json.decodeFromString(Profile.serializer(), """{"format":1,"savedAt":5}""")
        assertNull(old.login)
        val p = Profile(login = theirs)
        assertEquals(p, ProfileMerge.json.decodeFromString(Profile.serializer(), ProfileMerge.json.encodeToString(Profile.serializer(), p)))
    }
}
