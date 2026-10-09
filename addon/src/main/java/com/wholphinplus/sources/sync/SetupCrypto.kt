package com.wholphinplus.sources.sync

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * "Set up with your phone": the TV's half of the encryption the phone page does with WebCrypto
 * (cloud/setup.js). The TV makes a P-256 key pair and shows its public key's code; the phone
 * encrypts the sign-in with an ephemeral key of its own: ECDH → HKDF-SHA256 (salt
 * "orca+setup:v1", info = the code) → AES-256-GCM. Only this TV's private key opens it; the
 * cloud relays a box it can't read. Plain JCA (EC, ECDH, HmacSHA256, AES/GCM), on every Android
 * this app runs on.
 */
object SetupCrypto {
    private const val SALT = "orca+setup:v1"

    fun newKeys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** The public key as the browser takes it: 0x04, x, y (65 bytes). */
    fun rawPublic(key: ECPublicKey): ByteArray = byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)

    /** What the phone sealed for [code]: the JSON's bytes. Throws if it isn't for this key. */
    fun open(
        private: ECPrivateKey,
        code: String,
        epk: ByteArray,
        iv: ByteArray,
        ct: ByteArray,
    ): ByteArray {
        require(epk.size == 65 && epk[0] == 4.toByte()) { "bad key" }
        val point = ECPoint(BigInteger(1, epk.copyOfRange(1, 33)), BigInteger(1, epk.copyOfRange(33, 65)))
        val phone = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, private.params))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(private)
            doPhase(phone, true)
            generateSecret()
        }
        val key = hkdf(shared, SALT.toByteArray(), code.toByteArray())
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            doFinal(ct)
        }
    }

    /** HKDF-SHA256, one 32-byte block (RFC 5869). */
    internal fun hkdf(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
    ): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(ikm)
        }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(prk, "HmacSHA256"))
            update(info)
            doFinal(byteArrayOf(1))
        }
    }

    /** A private key from its 32-byte scalar (tests: a vector the phone's code made). */
    internal fun privateFrom(scalar: ByteArray): ECPrivateKey {
        val params = (newKeys().private as ECPrivateKey).params
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), params)) as ECPrivateKey
    }

    private fun fixed(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }
}
