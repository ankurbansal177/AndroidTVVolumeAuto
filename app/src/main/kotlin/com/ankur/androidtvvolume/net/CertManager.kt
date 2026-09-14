package com.ankur.androidtvvolume.net

import android.content.Context
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.security.auth.x500.X500Principal

/**
 * Owns the client identity used for both pairing and remote-control TLS sessions.
 *
 * This used to be an AndroidKeyStore (hardware/TEE-backed) key, but real devices were
 * observed failing the TLS handshake's CertificateVerify signature with a native Conscrypt
 * error ("RSA routines:OPENSSL_internal:internal error") when signing with that key — some
 * OEM Keystore2 backends don't support the exact RSA signing operation Conscrypt needs here.
 * The protocol's own PIN-derived secret (see [PairingSecret]) is what actually authenticates
 * pairing, not certificate-chain trust or hardware key protection, so a plain software key is
 * both sufficient and what every known reference client (Python androidtvremote2, the JS
 * androidtv-remote client, etc.) actually uses. The key + self-signed cert are persisted to a
 * PKCS12 file in app-private storage so they survive restarts — the TV remembers this
 * certificate after the first successful pairing, so it must not change across app runs.
 */
object CertManager {
    private const val ALIAS = "atv_remote_client_key"
    private const val KEYSTORE_FILE_NAME = "atv_remote_identity.p12"
    private val KEYSTORE_PASSWORD = "atvremote-identity".toCharArray()

    private lateinit var appContext: Context

    init {
        // Android already registers its own stripped-down provider under the name "BC", which
        // lacks algorithms the real BouncyCastle needs (e.g. this cert-signing path). addProvider
        // silently no-ops when a provider with that name already exists, so the stub wins unless
        // it's removed first.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun ensureClientCertificate(): X509Certificate {
        val file = keystoreFile()
        if (!file.exists()) {
            generateAndPersist(file)
        }
        return loadKeyStore().getCertificate(ALIAS) as X509Certificate
    }

    /** Alias to hand to [KeyManagerFactory] / [FixedAliasKeyManager]. */
    fun alias(): String = ALIAS

    /** Password protecting both the PKCS12 file and the private key entry inside it. */
    fun keyPassword(): CharArray = KEYSTORE_PASSWORD.copyOf()

    /** Loads the persisted PKCS12 keystore containing our identity key + self-signed cert. */
    fun loadKeyStore(): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        keystoreFile().inputStream().use { ks.load(it, KEYSTORE_PASSWORD) }
        return ks
    }

    private fun keystoreFile(): File = File(appContext.filesDir, KEYSTORE_FILE_NAME)

    private fun generateAndPersist(file: File) {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keyPair = generator.generateKeyPair()

        val now = Date()
        val notAfter = Date(now.time + TimeUnit.DAYS.toMillis(10 * 365))
        val subject = X500Principal("CN=atvremote")

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(1),
            now,
            notAfter,
            subject,
            keyPair.public,
        ).addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC")
            .build(keyPair.private)
        val certHolder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certHolder)

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, keyPair.private, KEYSTORE_PASSWORD, arrayOf(cert))
        file.outputStream().use { ks.store(it, KEYSTORE_PASSWORD) }
    }
}
