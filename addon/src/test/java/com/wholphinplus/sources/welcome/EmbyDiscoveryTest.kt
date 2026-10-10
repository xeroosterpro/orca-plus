package com.wholphinplus.sources.welcome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbyDiscoveryTest {
    @Test fun `an emby server's answer gives its address, id and name`() {
        val f = EmbyDiscovery.parse("""{"Address":"http://10.0.0.30:8096","Id":"abc123","Name":"Living Room","EndpointAddress":null}""")
        assertEquals(EmbyDiscovery.Found("abc123", "Living Room", "http://10.0.0.30:8096"), f)
    }

    @Test fun `an answer without an address or not json is ignored`() {
        assertNull(EmbyDiscovery.parse("""{"Id":"abc123","Name":"x"}"""))
        assertNull(EmbyDiscovery.parse("hello"))
    }
}
