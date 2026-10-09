package com.wholphinplus.sources.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class ProfileCryptoTest {
    /** What Android 8+ (and the JVM) give: the seed every existing cloud profile was made with. */
    private fun factory(
        pin: String,
        profileId: String,
    ): ByteArray =
        SecretKeyFactory
            .getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), "orca+pin:v1|$profileId".toByteArray(), 150_000, 256))
            .encoded

    @Test fun `the hand-made stretch gives the key factory's bytes, so old profiles still open`() {
        val id = ProfileCrypto.profileId("11111111-2222-3333-4444-555555555555", "66666666-7777-8888-9999-000000000000")
        listOf("123456", "000000", "9876543210", "pa55wörd!").forEach { pin ->
            assertArrayEquals(pin, factory(pin, id), ProfileCrypto.seed(pin, id))
        }
    }

    @Test fun `a sealed profile opens again`() {
        val seed = ProfileCrypto.seed("424242", "x".repeat(64))
        val plain = "settings".repeat(50).toByteArray()
        assertArrayEquals(plain, ProfileCrypto.open(ProfileCrypto.seal(plain, seed, "wrap"), seed, "wrap"))
    }
}
