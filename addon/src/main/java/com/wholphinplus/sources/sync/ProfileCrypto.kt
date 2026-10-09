package com.wholphinplus.sources.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cloud profile's keys, all made on the TV. The profile's id comes from the Jellyfin server
 * and user ids; the PIN is stretched into a [seed] that gives the [auth] key (proves the PIN to
 * the cloud, which keeps only a hash of it) and, with the cloud's per-profile "wrap", the key
 * that encrypts the profile. The PIN itself never leaves the TV.
 */
object ProfileCrypto {
    private const val ROUNDS = 150_000
    private const val FORMAT: Byte = 1
    private val random = SecureRandom()

    /** The profile id for a Jellyfin user: 64 hex characters, the same on every TV. */
    fun profileId(
        serverId: String,
        userId: String,
    ): String {
        fun norm(s: String) = s.lowercase().replace("-", "").trim()
        return hex(sha256("orca+profile:v1|${norm(serverId)}|${norm(userId)}".toByteArray()))
    }

    /** The PIN stretched (slow on purpose: about half a second on a TV box). */
    fun seed(
        pin: String,
        profileId: String,
    ): ByteArray = pbkdf2(pin.toByteArray(Charsets.UTF_8), "orca+pin:v1|$profileId".toByteArray(), ROUNDS)

    /**
     * PBKDF2-HMAC-SHA256, one 32-byte block, by hand: Android's "PBKDF2WithHmacSHA256" key factory
     * only exists from Android 8, and Fire TV sticks on Fire OS 6 (Android 7.1) failed to set a
     * PIN with "SecretKeyFactory not available" (owner, 2026-10-08). The same bytes as the factory
     * gave (UTF-8 PIN), so profiles made either way open with both.
     */
    internal fun pbkdf2(
        password: ByteArray,
        salt: ByteArray,
        rounds: Int,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        val u = mac.doFinal()
        val t = u.copyOf()
        repeat(rounds - 1) {
            mac.update(u)
            mac.doFinal(u, 0)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        return t
    }

    fun auth(seed: ByteArray): String = kotlin.io.encoding.Base64.Default.encode(hmac(seed, "auth".toByteArray()))

    private fun key(
        seed: ByteArray,
        wrap: String,
    ): ByteArray = hmac(seed, "enc|$wrap".toByteArray())

    /** The profile's bytes, compressed and encrypted: format byte, 12-byte nonce, AES-GCM. */
    fun seal(
        plain: ByteArray,
        seed: ByteArray,
        wrap: String,
    ): ByteArray {
        val packed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain) } }.toByteArray()
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(seed, wrap), "AES"), GCMParameterSpec(128, nonce)) }
        return byteArrayOf(FORMAT) + nonce + cipher.doFinal(packed)
    }

    /** [seal] undone; throws if the key is wrong or the bytes were changed. */
    fun open(
        sealed: ByteArray,
        seed: ByteArray,
        wrap: String,
    ): ByteArray {
        require(sealed.size > 13 && sealed[0] == FORMAT) { "Unknown profile format" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key(seed, wrap), "AES"), GCMParameterSpec(128, sealed, 1, 12)) }
        val packed = cipher.doFinal(sealed, 13, sealed.size - 13)
        return GZIPInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}
