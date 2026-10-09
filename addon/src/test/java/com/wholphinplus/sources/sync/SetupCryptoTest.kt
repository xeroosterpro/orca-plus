package com.wholphinplus.sources.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import kotlin.io.encoding.Base64

class SetupCryptoTest {
    // Made by the phone page's own WebCrypto calls (cloud/setup.js) in Node, for a fixed TV key
    private val priv = "4141b0489e5f97ec6d8408a69fffcbdf629cfa50cb819a8e9e23d46f58f5bc5f"
    private val pub = "BO0Q1UXZYso8BjH+G8Nm3vVP+xia8IyDnhmBPMLZw8xpkuZMbWm7LI5q3dPPNiTFr/2PjkwQHEejtH+S2WbF4HQ="
    private val code = "ABCD2345"
    private val epk = "BC04IBgEFYCLRtx3Oryy1VQ7XRdET1vQBNnN5AD0uTKRSJITVsEqTgul2Gj7mzfuW5/ScJzmS9T+ebKjKESg4wk="
    private val iv = "VDxPeqNNxMgTlrkM"
    private val ct = "u20V2Old2v3yd70gH69c9qqTSsss+rghziKuqNnuhCgmbU0S/lmVrMaXdaM59DiWQgiXPk1Ekk6q8Bqk7AYDLq25mEZFmNULJisgQVdBUQVNnGgVgs533jJMC8tshk4nDxzt"

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun `the TV opens what a browser sealed for it`() {
        val plain = SetupCrypto.open(SetupCrypto.privateFrom(hex(priv)), code, Base64.decode(epk), Base64.decode(iv), Base64.decode(ct))
        assertEquals("""{"server":"10.0.0.20:8096","username":"mom","password":"pässwörd","pin":"123456"}""", plain.toString(Charsets.UTF_8))
    }

    @Test fun `another code or key can't open it`() {
        val key = SetupCrypto.privateFrom(hex(priv))
        assertTrue(runCatching { SetupCrypto.open(key, "ZZZZ2345", Base64.decode(epk), Base64.decode(iv), Base64.decode(ct)) }.isFailure)
        val other = SetupCrypto.newKeys().private as ECPrivateKey
        assertTrue(runCatching { SetupCrypto.open(other, code, Base64.decode(epk), Base64.decode(iv), Base64.decode(ct)) }.isFailure)
    }

    @Test fun `the public key goes out as the browser reads it`() {
        val keys = SetupCrypto.newKeys()
        val raw = SetupCrypto.rawPublic(keys.public as ECPublicKey)
        assertEquals(65, raw.size)
        assertEquals(4.toByte(), raw[0])
        assertArrayEquals(Base64.decode(pub).copyOfRange(0, 1), byteArrayOf(4))
    }
}
