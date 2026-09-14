package com.ankur.androidtvvolume.net

import com.google.polo.wire.protobuf.PoloProto.Configuration
import com.google.polo.wire.protobuf.PoloProto.OuterMessage
import com.google.polo.wire.protobuf.PoloProto.Options
import com.google.polo.wire.protobuf.PoloProto.PairingRequest
import com.google.polo.wire.protobuf.PoloProto.Secret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.net.ssl.SSLSocket

private const val PAIR_PORT = 6467
private const val CLIENT_NAME = "AndroidTVVolume"
private const val SERVICE_NAME = "atvremote"

sealed class PairingResult {
    object Success : PairingResult()
    data class Failure(val reason: String) : PairingResult()
}

/**
 * Drives the pairing handshake on port 6467: PairingRequest -> Options -> Configuration ->
 * Secret, per polo.proto. The TV shows a 6-digit hex PIN on screen once the Configuration
 * step completes; [pairWithCode] must be called with that PIN to finish.
 */
class PairingClient(private val host: String) {

    private var socket: SSLSocket? = null

    /** Opens the connection and drives the handshake up to (and including) Configuration. */
    suspend fun connectAndAwaitPin(): Unit = withContext(Dispatchers.IO) {
        val sslContext = TlsSockets.createSslContext()
        val socket = TlsSockets.connect(sslContext, host, PAIR_PORT)
        this@PairingClient.socket = socket

        send(socket, baseMessage {
            setPairingRequest(
                PairingRequest.newBuilder()
                    .setServiceName(SERVICE_NAME)
                    .setClientName(CLIENT_NAME)
                    .build(),
            )
        })
        val ack = receive(socket)
        check(ack.hasPairingRequestAck()) { "Expected PairingRequestAck, got: $ack" }

        send(socket, baseMessage {
            setOptions(
                Options.newBuilder()
                    .setPreferredRole(Options.RoleType.ROLE_TYPE_INPUT)
                    .addInputEncodings(
                        Options.Encoding.newBuilder()
                            .setType(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                            .setSymbolLength(6),
                    )
                    .build(),
            )
        })
        val optionsResponse = receive(socket)
        check(optionsResponse.hasOptions()) { "Expected Options, got: $optionsResponse" }

        send(socket, baseMessage {
            setConfiguration(
                Configuration.newBuilder()
                    .setClientRole(Options.RoleType.ROLE_TYPE_INPUT)
                    .setEncoding(
                        Options.Encoding.newBuilder()
                            .setType(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                            .setSymbolLength(6),
                    )
                    .build(),
            )
        })
        val configAck = receive(socket)
        check(configAck.hasConfigurationAck()) { "Expected ConfigurationAck, got: $configAck" }
        // The TV displays the PIN only once this ack arrives.
    }

    /** Completes pairing once the user has typed in the 6-digit PIN shown on the TV. */
    suspend fun pairWithCode(pairingCode: String): PairingResult = withContext(Dispatchers.IO) {
        val socket = this@PairingClient.socket ?: return@withContext PairingResult.Failure("Not connected")
        if (pairingCode.length != 6 || pairingCode.toIntOrNull(16) == null) {
            return@withContext PairingResult.Failure("PIN must be 6 hex digits")
        }
        return@withContext try {
            val clientCert = CertManager.ensureClientCertificate()
            val serverCert = TlsSockets.peerCertificate(socket)
            val hash = PairingSecret.compute(clientCert, serverCert, pairingCode)
            if (!PairingSecret.matchesCheckByte(hash, pairingCode)) {
                return@withContext PairingResult.Failure("Incorrect PIN")
            }

            send(socket, baseMessage {
                setSecret(
                    Secret.newBuilder()
                        .setSecret(com.google.protobuf.ByteString.copyFrom(hash))
                        .build(),
                )
            })
            val secretAck = receive(socket)
            if (secretAck.hasSecretAck()) {
                PairingResult.Success
            } else {
                PairingResult.Failure("TV rejected the PIN")
            }
        } catch (e: Exception) {
            android.util.Log.e("PairingClient", "pairWithCode failed", e)
            PairingResult.Failure(e.message ?: "Pairing failed")
        } finally {
            close()
        }
    }

    fun close() {
        socket?.close()
        socket = null
    }

    private fun send(socket: SSLSocket, message: OuterMessage) {
        message.writeDelimitedTo(socket.outputStream)
        socket.outputStream.flush()
    }

    private fun receive(socket: SSLSocket): OuterMessage {
        val msg = OuterMessage.parseDelimitedFrom(socket.inputStream)
            ?: error("Connection closed while waiting for a response")
        check(msg.status == OuterMessage.Status.STATUS_OK) { "TV returned status ${msg.status}" }
        return msg
    }

    private inline fun baseMessage(block: OuterMessage.Builder.() -> Unit): OuterMessage =
        OuterMessage.newBuilder()
            .setProtocolVersion(2)
            .setStatus(OuterMessage.Status.STATUS_OK)
            .apply(block)
            .build()
}
