package com.ankur.androidtvvolume

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ankur.androidtvvolume.net.CertManager
import com.ankur.androidtvvolume.net.PairingClient
import com.ankur.androidtvvolume.net.PairingResult
import com.ankur.androidtvvolume.net.RemoteClient
import com.ankur.androidtvvolume.store.Prefs
import com.ankur.androidtvvolume.ui.LoudnessMeter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CertManager.init(applicationContext)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RemoteScreen()
                }
            }
        }
    }
}

private sealed class UiState {
    object Disconnected : UiState()
    object Connecting : UiState()
    data class AwaitingPin(val pairingClient: PairingClient) : UiState()
    data class Connected(val remoteClient: RemoteClient) : UiState()
    data class Error(val message: String) : UiState()
}

@Composable
private fun RemoteScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()

    var tvIp by remember { mutableStateOf("") }
    var uiState by remember { mutableStateOf<UiState>(UiState.Disconnected) }
    var pinInput by remember { mutableStateOf("") }

    var autoService by remember { mutableStateOf<AutoVolumeService?>(null) }
    var serviceBound by remember { mutableStateOf(false) }
    var baseline by remember { mutableStateOf<Float?>(null) }
    var autoModeOn by remember { mutableStateOf(false) }
    var micPermissionDenied by remember { mutableStateOf(false) }

    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                autoService = (binder as? AutoVolumeService.LocalBinder)?.service
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                autoService = null
            }
        }
    }

    fun hasMicPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // Starting the service claims FOREGROUND_SERVICE_TYPE_MICROPHONE, which Android 14 requires
    // RECORD_AUDIO to already be granted for - so this must only run after permission is granted,
    // never automatically on connect (that's what used to crash the app on launch).
    fun startAutoService() {
        if (serviceBound) return
        serviceBound = true
        val intent = Intent(context, AutoVolumeService::class.java).putExtra(AutoVolumeService.EXTRA_HOST, tvIp)
        ContextCompat.startForegroundService(context, intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            micPermissionDenied = false
            startAutoService()
        } else {
            micPermissionDenied = true
        }
    }

    LaunchedEffect(autoService) {
        if (autoService != null && hasMicPermission()) {
            autoService?.startListening()
        }
    }

    // Start mic monitoring as soon as we're connected, not just once the user taps Calibrate,
    // so the live loudness meter is visible before calibration too.
    LaunchedEffect(uiState) {
        if (uiState is UiState.Connected) {
            if (hasMicPermission()) {
                startAutoService()
            } else {
                permissionLauncher.launch(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        arrayOf(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        val savedIp = prefs.tvIp()
        if (savedIp.isNotBlank()) tvIp = savedIp
        if (savedIp.isNotBlank() && prefs.isPaired()) {
            uiState = UiState.Connecting
            connectRemote(savedIp, scope) { uiState = it }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            (uiState as? UiState.Connected)?.remoteClient?.close()
            if (serviceBound) {
                context.unbindService(serviceConnection)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(text = "TV Volume Remote (phase 1: manual control)", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = tvIp,
            onValueChange = { tvIp = it },
            label = { Text("TV IP address") },
            enabled = uiState !is UiState.Connected && uiState !is UiState.Connecting,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(text = statusText(uiState))
        Spacer(modifier = Modifier.height(16.dp))

        when (val state = uiState) {
            is UiState.Connected -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { scope.launch { state.remoteClient.sendVolumeDown() } }) { Text("Vol -") }
                    Button(onClick = { scope.launch { state.remoteClient.sendMute() } }) { Text("Mute") }
                    Button(onClick = { scope.launch { state.remoteClient.sendVolumeUp() } }) { Text("Vol +") }
                }
                Spacer(modifier = Modifier.height(8.dp))

                val volumeInfo = state.remoteClient.volumeInfo.collectAsState().value
                Text(
                    text = volumeInfo?.let {
                        "TV volume: ${it.level}/${it.max}" + if (it.muted) " (muted)" else ""
                    } ?: "TV volume: —",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))

                val liveDbfs = autoService?.dbfs?.collectAsState()?.value
                LoudnessMeter(currentDbfs = liveDbfs, baselineDbfs = baseline, autoModeActive = autoModeOn)

                val noiseFloor = autoService?.noiseFloorDbfs?.collectAsState()?.value
                Text(
                    text = noiseFloor?.let { "Room noise floor: %.1f dBFS (removed from readings above)".format(it) }
                        ?: "Room noise floor: not measured",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        if (!hasMicPermission()) {
                            permissionLauncher.launch(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    arrayOf(Manifest.permission.RECORD_AUDIO)
                                },
                            )
                        } else {
                            startAutoService()
                            scope.launch {
                                var attempts = 0
                                while (autoService == null && attempts < 20) {
                                    delay(100)
                                    attempts++
                                }
                                autoService?.measureNoiseFloor { }
                            }
                        }
                    }) { Text("Measure room noise") }

                    Button(onClick = {
                        if (!hasMicPermission()) {
                            permissionLauncher.launch(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    arrayOf(Manifest.permission.RECORD_AUDIO)
                                },
                            )
                        } else {
                            startAutoService()
                            scope.launch {
                                var attempts = 0
                                while (autoService == null && attempts < 20) {
                                    delay(100)
                                    attempts++
                                }
                                autoService?.calibrateBaseline { avg -> baseline = avg }
                            }
                        }
                    }) { Text("Calibrate") }

                    Button(
                        enabled = baseline != null,
                        onClick = {
                            val next = !autoModeOn
                            scope.launch {
                                // The service's remote-control session connects asynchronously
                                // after binding, so it may not be ready the instant this is
                                // tapped - retry briefly instead of silently no-op'ing.
                                var attempts = 0
                                var succeeded = false
                                while (!succeeded && attempts < 20) {
                                    succeeded = autoService?.setAutoModeEnabled(next) == true
                                    if (!succeeded) {
                                        delay(150)
                                        attempts++
                                    }
                                }
                                if (succeeded) autoModeOn = next
                            }
                        },
                    ) { Text(if (autoModeOn) "Auto Mode: On" else "Auto Mode: Off") }
                }
                if (micPermissionDenied) {
                    Text(text = "Microphone permission is required for Auto Mode.")
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    state.remoteClient.close()
                    scope.launch { prefs.setPaired(false) }
                    uiState = UiState.Disconnected
                }) { Text("Forget pairing") }
            }
            else -> {
                Button(
                    enabled = tvIp.isNotBlank() && uiState !is UiState.Connecting,
                    onClick = {
                        uiState = UiState.Connecting
                        scope.launch {
                            prefs.setTvIp(tvIp)
                            val client = PairingClient(tvIp)
                            try {
                                client.connectAndAwaitPin()
                                uiState = UiState.AwaitingPin(client)
                            } catch (e: Exception) {
                                Log.e("MainActivity", "connectAndAwaitPin failed", e)
                                uiState = UiState.Error(e.message ?: "Could not reach the TV")
                            }
                        }
                    },
                ) { Text("Pair") }
            }
        }
    }

    val awaitingPin = uiState as? UiState.AwaitingPin
    if (awaitingPin != null) {
        AlertDialog(
            onDismissRequest = { uiState = UiState.Disconnected },
            title = { Text("Enter the PIN shown on the TV") },
            text = {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { if (it.length <= 6) pinInput = it },
                    label = { Text("6-digit PIN") },
                )
            },
            confirmButton = {
                Button(onClick = {
                    val code = pinInput
                    pinInput = ""
                    scope.launch {
                        when (val result = awaitingPin.pairingClient.pairWithCode(code)) {
                            is PairingResult.Success -> {
                                prefs.setPaired(true)
                                uiState = UiState.Connecting
                                connectRemote(tvIp, scope) { uiState = it }
                            }
                            is PairingResult.Failure -> {
                                uiState = UiState.Error(result.reason)
                            }
                        }
                    }
                }) { Text("Submit") }
            },
        )
    }
}

private suspend fun connectRemote(ip: String, scope: kotlinx.coroutines.CoroutineScope, onState: (UiState) -> Unit) {
    val remoteClient = RemoteClient(ip, scope)
    try {
        remoteClient.connect()
        onState(UiState.Connected(remoteClient))
    } catch (e: Exception) {
        onState(UiState.Error(e.message ?: "Could not connect to the TV"))
    }
}

private fun statusText(state: UiState): String = when (state) {
    is UiState.Disconnected -> "Not connected."
    is UiState.Connecting -> "Connecting..."
    is UiState.AwaitingPin -> "Enter the PIN shown on the TV screen."
    is UiState.Connected -> "Connected. Buttons below control the TV volume."
    is UiState.Error -> "Error: ${state.message}"
}
