package com.wholphinplus.sources

import org.junit.Assert.assertEquals
import org.junit.Test

class OrcaVersionTest {
    @Test fun `a release title reads as its number`() {
        assertEquals("Orca+ 47", OrcaVersion.release("v1.0.9-47-g1a2b3c4d"))
        assertEquals("Orca+ 48", OrcaVersion.release("1.0.10-48-gabc"))
    }

    @Test fun `a personal build says so`() {
        // Unit tests run the personal (non-public) build
        assertEquals("Orca+ dev 354", OrcaVersion.name("1.0.9-354-gdc1c2c69b"))
        assertEquals("Orca+ dev 354  ·  built on Wholphin 1.0.9", OrcaVersion.full("v1.0.9-354-gdc1c2c69b"))
    }

    @Test fun `anything else is shown as it is`() {
        assertEquals("weird", OrcaVersion.release("weird"))
        assertEquals("Orca+", OrcaVersion.release("v1.0.9"))
    }
}
