package com.ankur.androidtvvolume.net

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

/**
 * Computes the pairing secret exchanged as the final step of the handshake.
 *
 * This reproduces, byte for byte, the algorithm used by the reference `androidtvremote2`
 * implementation (and, before it, the original Google TV pairing protocol): SHA-256 over
 * the client's RSA modulus/exponent, the server's RSA modulus/exponent, and the last two
 * bytes of the 6-hex-digit PIN shown on the TV. The PIN's first byte is a self-check
 * against the hash rather than input to it.
 */
object PairingSecret {

    fun compute(clientCert: X509Certificate, serverCert: X509Certificate, pairingCode: String): ByteArray {
        require(pairingCode.length == 6) { "Pairing code must be exactly 6 hex digits" }

        val clientKey = clientCert.publicKey as RSAPublicKey
        val serverKey = serverCert.publicKey as RSAPublicKey

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(hexToBytes(clientKey.modulus.toString(16)))
        digest.update(hexToBytes("0" + clientKey.publicExponent.toString(16)))
        digest.update(hexToBytes(serverKey.modulus.toString(16)))
        digest.update(hexToBytes("0" + serverKey.publicExponent.toString(16)))
        digest.update(hexToBytes(pairingCode.substring(2)))
        return digest.digest()
    }

    /** True if [hash] (as produced by [compute]) matches the check byte encoded in [pairingCode]. */
    fun matchesCheckByte(hash: ByteArray, pairingCode: String): Boolean {
        val expected = pairingCode.substring(0, 2).toInt(16)
        return (hash[0].toInt() and 0xFF) == expected
    }

    private fun hexToBytes(hex: String): ByteArray {
        val even = if (hex.length % 2 == 0) hex else "0$hex"
        return ByteArray(even.length / 2) { i ->
            even.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
