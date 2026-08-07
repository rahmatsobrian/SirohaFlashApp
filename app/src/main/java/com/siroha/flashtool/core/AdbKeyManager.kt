package com.siroha.flashtool.core

import android.content.Context
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher

/**
 * Generates and persists the RSA keypair ADB authentication uses, and
 * encodes the public key into Android's own non-standard wire format.
 *
 * This is the trickiest part of implementing ADB from scratch: the public
 * key isn't sent as PEM/X.509 — it's a custom little-endian C struct
 * (`RSAPublicKey` from AOSP's system/core/libcrypto_utils/android_pubkey.c)
 * containing the modulus, a Montgomery R^2 mod N value, and a 32-bit modular
 * inverse, base64-encoded. That struct's math (n0inv, rr) is reconstructed
 * here with [java.math.BigInteger] rather than hand-rolled bignum code,
 * which is the same approach other from-scratch ADB client reimplementations
 * (Python's adb-shell, various JS ADB-over-WebUSB libraries) use, since
 * Google never documented this format outside the C source.
 *
 * CAVEAT: this has not been exercised against a real device's USB debugging
 * "Allow this computer?" dialog in the environment this was written in (no
 * USB hardware available). The math below is written to match the AOSP
 * algorithm as precisely as I can from memory; if pairing silently never
 * succeeds (device keeps re-sending AUTH TOKEN after a SIGNATURE +
 * RSAPUBLICKEY exchange), this encoding is the first place to check.
 */
object AdbKeyManager {
    private const val KEY_SIZE_BITS = 2048
    private const val KEY_SIZE_WORDS = KEY_SIZE_BITS / 32 // 64
    private const val MODULUS_BYTES = KEY_SIZE_BITS / 8   // 256

    private fun keyDir(context: Context) = File(context.filesDir, "adb_keys").apply { mkdirs() }
    private fun privFile(context: Context) = File(keyDir(context), "adbkey")
    private fun pubFile(context: Context) = File(keyDir(context), "adbkey.pub")

    data class AdbKeyPair(val private: RSAPrivateKey, val public: RSAPublicKey)

    fun loadOrGenerate(context: Context): AdbKeyPair {
        val priv = privFile(context)
        val pub = pubFile(context)
        if (priv.exists() && pub.exists()) {
            runCatching { loadFromDisk(priv, pub) }.getOrNull()?.let { return it }
        }
        return generateAndSave(context)
    }

    private fun loadFromDisk(privFile: File, pubFile: File): AdbKeyPair {
        val kf = KeyFactory.getInstance("RSA")
        val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes())) as RSAPrivateKey
        val modulus = BigInteger(pubFile.readText().substringBefore(':'), 16)
        val exponent = BigInteger(pubFile.readText().substringAfter(':'), 16)
        val publicKey = kf.generatePublic(java.security.spec.RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
        return AdbKeyPair(privateKey, publicKey)
    }

    private fun generateAndSave(context: Context): AdbKeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(KEY_SIZE_BITS)
        val pair = gen.generateKeyPair()
        val privateKey = pair.private as RSAPrivateKey
        val publicKey = pair.public as RSAPublicKey

        privFile(context).writeBytes(privateKey.encoded) // PKCS8 DER
        pubFile(context).writeText("${publicKey.modulus.toString(16)}:${publicKey.publicExponent.toString(16)}")
        return AdbKeyPair(privateKey, publicKey)
    }

    /**
     * Signs [token] (the 20-byte challenge ADB's AUTH packet sends) with the
     * raw RSA private key operation — ADB does NOT hash the token first
     * (it's already a fixed-size random value), so this uses "NONEwithRSA"
     * rather than a normal SHA-then-sign scheme.
     */
    fun signToken(private: RSAPrivateKey, token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, private)
        // ADB pads the 20-byte SHA1-sized token into a full PKCS#1 v1.5
        // signature block itself before this raw RSA op; NoPadding + manual
        // PKCS#1 emsa construction is required since Signature.getInstance
        // ("NONEwithRSA") is not guaranteed available on every Android
        // provider. Build the EMSA-PKCS1-v1_5 block by hand.
        val emsaBlock = buildPkcs1Sha1DigestInfoPadding(token, private.modulus.bitLength() / 8)
        return cipher.doFinal(emsaBlock)
    }

    /** EMSA-PKCS1-v1_5 padding: 0x00 0x01 [0xFF...] 0x00 [ASN.1 SHA1 DigestInfo prefix] [20-byte token]. */
    private fun buildPkcs1Sha1DigestInfoPadding(token: ByteArray, modulusBytes: Int): ByteArray {
        // SHA-1 DigestInfo ASN.1 prefix, as used by ADB's auth signing (this
        // matches AOSP's adb_auth_sign, which always frames the token as if
        // it were a SHA-1 digest even though it's really a random nonce).
        val sha1DigestInfoPrefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06.toByte(), 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00, 0x04, 0x14
        )
        val digestInfo = sha1DigestInfoPrefix + token
        val paddingLen = modulusBytes - digestInfo.size - 3
        require(paddingLen > 0) { "RSA modulus too small for PKCS#1 padding" }
        val block = ByteArray(modulusBytes)
        block[0] = 0x00
        block[1] = 0x01
        for (i in 0 until paddingLen) block[2 + i] = 0xFF.toByte()
        block[2 + paddingLen] = 0x00
        digestInfo.copyInto(block, 3 + paddingLen)
        return block
    }

    /**
     * Encodes the public key into Android's `RSAPublicKey` wire struct
     * (modulus_size_words, n0inv, modulus[256], rr[256], exponent — all
     * little-endian), base64s it, and appends " user@host\u0000" the way
     * real adbd expects (it uses the trailing text as a display label only).
     */
    fun encodeAdbPublicKey(publicKey: RSAPublicKey): ByteArray {
        val n = publicKey.modulus
        val e = publicKey.publicExponent.toInt()

        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0 = n.mod(two32)
        val n0invPositive = n0.modInverse(two32)
        val n0inv = two32.subtract(n0invPositive).mod(two32).toLong() and 0xFFFFFFFFL

        val rTotal = BigInteger.ONE.shiftLeft(32 * KEY_SIZE_WORDS) // R = 2^2048
        val rr = rTotal.multiply(rTotal).mod(n)                    // R^2 mod N

        val modulusLe = bigIntegerToLittleEndian(n, MODULUS_BYTES)
        val rrLe = bigIntegerToLittleEndian(rr, MODULUS_BYTES)

        val buffer = java.nio.ByteBuffer.allocate(4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(KEY_SIZE_WORDS)
        buffer.putInt(n0inv.toInt())
        buffer.put(modulusLe)
        buffer.put(rrLe)
        buffer.putInt(e)

        val encoded = Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
        return "$encoded siroha-flash-tool@android\u0000".toByteArray(Charsets.US_ASCII)
    }

    /** Big-endian BigInteger -> fixed-size little-endian byte array (drops any BigInteger sign byte, zero-pads). */
    private fun bigIntegerToLittleEndian(value: BigInteger, size: Int): ByteArray {
        val be = value.toByteArray().let { raw ->
            if (raw.size > size) raw.copyOfRange(raw.size - size, raw.size) else raw // strip leading sign byte if any
        }
        val out = ByteArray(size)
        // Copy be (big-endian, possibly shorter than size) right-aligned, then reverse to little-endian.
        be.copyInto(out, size - be.size)
        out.reverse()
        return out
    }
}
