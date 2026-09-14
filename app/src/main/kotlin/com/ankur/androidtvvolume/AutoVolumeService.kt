package com.ankur.androidtvvolume

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ankur.androidtvvolume.audio.LoudnessMonitor
import com.ankur.androidtvvolume.control.VolumeControlLoop
import com.ankur.androidtvvolume.net.RemoteClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "AutoVolumeService"
private const val CHANNEL_ID = "auto_volume"
private const val NOTIFICATION_ID = 1001

/**
 * Hosts the mic-based loudness monitor and the auto-correction loop as a foreground service,
 * since continuous [android.media.AudioRecord] capture needs to keep running independent of the
 * activity's lifecycle. Owns its own [RemoteClient] connection (separate from the one the
 * activity uses for manual Vol +/-/Mute) so it isn't coupled to the activity being alive.
 */
class AutoVolumeService : Service() {

    inner class LocalBinder : Binder() {
        val service: AutoVolumeService get() = this@AutoVolumeService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var loudnessMonitor: LoudnessMonitor
    private var remoteClient: RemoteClient? = null
    private var controlLoop: VolumeControlLoop? = null

    val dbfs: StateFlow<Float> get() = loudnessMonitor.dbfs
    val noiseFloorDbfs: StateFlow<Float?> get() = loudnessMonitor.noiseFloorDbfs
    val baselineDbfs: Float? get() = controlLoop?.baselineDbfs

    override fun onCreate() {
        super.onCreate()
        loudnessMonitor = LoudnessMonitor(serviceScope)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        val host = intent?.getStringExtra(EXTRA_HOST)
        if (host != null && remoteClient == null) {
            serviceScope.launch {
                val client = RemoteClient(host, serviceScope)
                try {
                    client.connect()
                    remoteClient = client
                    controlLoop = VolumeControlLoop(loudnessMonitor, client)
                    Log.d(TAG, "Auto-mode remote session connected to $host")
                } catch (e: Exception) {
                    Log.e(TAG, "Could not connect auto-mode remote session", e)
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Call once RECORD_AUDIO is confirmed granted. Safe to call more than once. */
    fun startListening() {
        loudnessMonitor.start()
    }

    fun calibrateBaseline(onDone: (Float) -> Unit) {
        serviceScope.launch {
            val avg = loudnessMonitor.averageOver(2_000)
            controlLoop?.setBaseline(avg)
            onDone(avg)
        }
    }

    /** Call with the TV muted/silent so ambient noise (e.g. a fan) is measured and removed from every future reading. */
    fun measureNoiseFloor(onDone: (Float) -> Unit) {
        serviceScope.launch {
            val floor = loudnessMonitor.measureNoiseFloor()
            onDone(floor)
        }
    }

    /** Returns false (and does nothing) if the remote session isn't ready yet. */
    fun setAutoModeEnabled(enabled: Boolean): Boolean {
        val loop = controlLoop
        Log.d(TAG, "setAutoModeEnabled($enabled), remote session ready=${loop != null}")
        if (loop == null) return false
        if (enabled) {
            serviceScope.launch {
                loop.calibrateDbPerStep()
                loop.start(serviceScope)
            }
        } else {
            loop.stop()
        }
        return true
    }

    override fun onDestroy() {
        controlLoop?.stop()
        loudnessMonitor.stop()
        remoteClient?.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Auto Volume", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TV volume auto-adjust running")
            .setContentText("Listening and adjusting TV volume automatically")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val EXTRA_HOST = "host"
    }
}
