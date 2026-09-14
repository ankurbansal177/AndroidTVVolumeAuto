package com.ankur.androidtvvolume.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import remote.Remotemessage.RemoteConfigure
import remote.Remotemessage.RemoteDeviceInfo
import remote.Remotemessage.RemoteDirection
import remote.Remotemessage.RemoteKeyCode
import remote.Remotemessage.RemoteKeyInject
import remote.Remotemessage.RemoteMessage
import remote.Remotemessage.RemotePingResponse
import remote.Remotemessage.RemoteSetActive
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CoroutineScope

private const val REMOTE_PORT = 6466

/** The TV's self-reported volume state, pushed whenever it changes from any source. */
data class VolumeInfo(val level: Int, val max: Int, val muted: Boolean)

/** Bitmask matching the `Feature` flags in the reference implementation. */
private object Features {
    const val PING = 1 shl 0
    const val KEY = 1 shl 1
    const val POWER = 1 shl 5
    const val VOLUME = 1 shl 6
    const val APP_LINK = 1 shl 9
    const val SUPPORTED = PING or KEY or POWER or VOLUME or APP_LINK
}

enum class RemoteConnectionState { CONNECTING, READY, DISCONNECTED }

/**
 * Maintains the long-lived remote-control session on port 6466. Once paired, no PIN is
 * needed: the TV recognizes the client certificate we presented during pairing.
 *
 * The device drives a small handshake first (RemoteConfigure -> RemoteSetActive -> RemoteStart)
 * and pings periodically; [connect] runs a background read loop that answers both, and
 * completes [ready] once the TV reports it's actually accepting commands.
 */
class RemoteClient(private val host: String, private val scope: CoroutineScope) {

    private var socket: SSLSocket? = null
    private var readJob: Job? = null
    private val writeMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private var activeFeatures = Features.SUPPORTED

    var state: RemoteConnectionState = RemoteConnectionState.CONNECTING
        private set

    private val _volumeInfo = MutableStateFlow<VolumeInfo?>(null)
    val volumeInfo: StateFlow<VolumeInfo?> = _volumeInfo

    suspend fun connect() = withContext(Dispatchers.IO) {
        val sslContext = TlsSockets.createSslContext()
        socket = TlsSockets.connect(sslContext, host, REMOTE_PORT)
        readJob = scope.launch(Dispatchers.IO) { readLoop() }
        withTimeout(10_000) { ready.await() }
    }

    suspend fun sendVolumeUp() = sendKey(RemoteKeyCode.KEYCODE_VOLUME_UP)
    suspend fun sendVolumeDown() = sendKey(RemoteKeyCode.KEYCODE_VOLUME_DOWN)
    suspend fun sendMute() = sendKey(RemoteKeyCode.KEYCODE_VOLUME_MUTE)

    private suspend fun sendKey(keyCode: RemoteKeyCode) {
        val message = RemoteMessage.newBuilder()
            .setRemoteKeyInject(
                RemoteKeyInject.newBuilder()
                    .setKeyCode(keyCode)
                    .setDirection(RemoteDirection.SHORT)
                    .build(),
            )
            .build()
        send(message)
    }

    fun close() {
        readJob?.cancel()
        socket?.close()
        socket = null
        state = RemoteConnectionState.DISCONNECTED
    }

    private suspend fun readLoop() {
        val socket = this.socket ?: return
        try {
            while (true) {
                val msg = RemoteMessage.parseDelimitedFrom(socket.inputStream) ?: break
                handleMessage(msg)
            }
        } catch (_: Exception) {
            // Connection dropped; surfaced to the caller as `state` flipping to DISCONNECTED.
        } finally {
            state = RemoteConnectionState.DISCONNECTED
            if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException("Connection closed before handshake finished"))
        }
    }

    private suspend fun handleMessage(msg: RemoteMessage) {
        when {
            msg.hasRemoteConfigure() -> {
                // Only claim features the TV itself reports supporting.
                activeFeatures = Features.SUPPORTED and msg.remoteConfigure.code1
                val response = RemoteMessage.newBuilder()
                    .setRemoteConfigure(
                        RemoteConfigure.newBuilder()
                            .setCode1(activeFeatures)
                            .setDeviceInfo(
                                RemoteDeviceInfo.newBuilder()
                                    .setUnknown1(1)
                                    .setUnknown2("1")
                                    .setPackageName("com.ankur.androidtvvolume")
                                    .setAppVersion("1.0.0")
                                    .build(),
                            )
                            .build(),
                    )
                    .build()
                send(response)
            }
            msg.hasRemoteSetActive() -> {
                val response = RemoteMessage.newBuilder()
                    .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(activeFeatures).build())
                    .build()
                send(response)
            }
            msg.hasRemoteStart() -> {
                state = RemoteConnectionState.READY
                if (!ready.isCompleted) ready.complete(Unit)
            }
            msg.hasRemoteSetVolumeLevel() -> {
                val v = msg.remoteSetVolumeLevel
                _volumeInfo.value = VolumeInfo(level = v.volumeLevel, max = v.volumeMax, muted = v.volumeMuted)
            }
            msg.hasRemotePingRequest() -> {
                val response = RemoteMessage.newBuilder()
                    .setRemotePingResponse(
                        RemotePingResponse.newBuilder()
                            .setVal1(msg.remotePingRequest.val1)
                            .build(),
                    )
                    .build()
                send(response)
            }
        }
    }

    private suspend fun send(message: RemoteMessage) = withContext(Dispatchers.IO) {
        val socket = this@RemoteClient.socket ?: return@withContext
        writeMutex.withLock {
            message.writeDelimitedTo(socket.outputStream)
            socket.outputStream.flush()
        }
    }
}
