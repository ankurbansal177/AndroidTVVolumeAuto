package com.ankur.androidtvvolume.net

import android.util.Log
import java.net.Socket
import java.security.Principal
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Builds the mutual-TLS [SSLContext] used for both the pairing (6467) and remote (6466)
 * sessions.
 *
 * The Android TV Remote protocol authenticates through the PIN-derived secret exchanged
 * over the (already established) TLS channel, not through certificate-chain trust — both
 * sides present self-signed certificates with no common CA. This mirrors the reference
 * implementation, which explicitly disables hostname checks and chain validation
 * (`check_hostname = False`, `verify_mode = CERT_NONE`) and relies on the app-level secret
 * instead. `TrustAllManager` below is the Kotlin equivalent of that, not an oversight.
 */
object TlsSockets {
    private const val TAG = "TlsSockets"

    fun createSslContext(): SSLContext {
        CertManager.ensureClientCertificate()
        val keyStore = CertManager.loadKeyStore()
        Log.d(TAG, "PKCS12 aliases: ${keyStore.aliases().toList()}, containsClientAlias=${keyStore.containsAlias(CertManager.alias())}")
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, CertManager.keyPassword())
        val delegate = kmf.keyManagers.filterIsInstance<X509ExtendedKeyManager>().first()
        Log.d(TAG, "delegate.getClientAliases(RSA) = ${delegate.getClientAliases("RSA", null)?.toList()}")
        Log.d(TAG, "delegate.getCertificateChain(alias) = ${delegate.getCertificateChain(CertManager.alias())?.toList()}")
        Log.d(TAG, "delegate.getPrivateKey(alias) = ${delegate.getPrivateKey(CertManager.alias())}")
        val fixedKeyManager = FixedAliasKeyManager(delegate, CertManager.alias())

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(arrayOf(fixedKeyManager), arrayOf(TrustAllManager), SecureRandom())
        return sslContext
    }

    fun connect(sslContext: SSLContext, host: String, port: Int, timeoutMs: Int = 10_000): SSLSocket {
        // Two independently-written, known-working Kotlin clients for this same protocol
        // (TomasThrawat/AndroidTvRemote, spritelutsk/remote_tv_control_TCL) both layer the
        // SSLSocket over an already-connected plain Socket via
        // socketFactory.createSocket(plainSocket, host, port, true) rather than creating an
        // unconnected SSLSocket and calling connect() on it afterwards. The latter (what this
        // method did before) leaves Conscrypt without the peer host/port at construction time,
        // which changes what it negotiates and produced a handshake_failure alert from the TV.
        val plain = Socket()
        plain.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        val socket = sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        // The TV's embedded TLS stack doesn't support TLS 1.3's client-certificate flow for
        // this protocol; negotiating TLS 1.3 (Android's default preference) causes it to reply
        // with a certificate_required alert. Force TLS 1.2, matching the reference clients.
        socket.enabledProtocols = arrayOf("TLSv1.2")
        // This protocol traces back to Google's ~2011 TV pairing protocol; some embedded TV
        // TLS stacks only offer older, non-forward-secret cipher suites that Android still
        // supports but excludes from its default *enabled* set. Enable everything Android
        // supports so cipher negotiation isn't the reason for a handshake_failure.
        socket.enabledCipherSuites = socket.supportedCipherSuites
        try {
            socket.startHandshake()
        } catch (e: Exception) {
            Log.e(TAG, "handshake failed", e)
            throw e
        }
        return socket
    }

    fun peerCertificate(socket: SSLSocket): X509Certificate =
        socket.session.peerCertificates[0] as X509Certificate

    /**
     * The TV always requests a client certificate for the alias we generated, but since it
     * has no CA in common with us, the platform's default [X509ExtendedKeyManager] may
     * refuse to offer any alias when the server's list of acceptable issuers doesn't match.
     * This wrapper forces our single known alias regardless of what the server asks for.
     */
    private class FixedAliasKeyManager(
        private val delegate: X509ExtendedKeyManager,
        private val alias: String,
    ) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String {
            Log.d(TAG, "chooseClientAlias(keyType=${keyType?.toList()}, issuers=${issuers?.toList()}) -> $alias")
            return alias
        }
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String {
            Log.d(TAG, "chooseEngineClientAlias(keyType=${keyType?.toList()}, issuers=${issuers?.toList()}) -> $alias")
            return alias
        }
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
            val chain = delegate.getCertificateChain(this.alias)
            Log.d(TAG, "getCertificateChain(requested=$alias) -> ${chain?.toList()}")
            return chain
        }
        override fun getPrivateKey(alias: String?): java.security.PrivateKey? {
            val key = delegate.getPrivateKey(this.alias)
            Log.d(TAG, "getPrivateKey(requested=$alias) -> $key")
            return key
        }
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    private object TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
