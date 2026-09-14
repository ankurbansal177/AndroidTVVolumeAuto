package com.ankur.androidtvvolume.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "tv_prefs")

/**
 * Persists the last-used TV IP and whether pairing has already succeeded for it, so the
 * app can jump straight to the remote-control session (port 6466) on relaunch instead of
 * re-running the PIN handshake every time.
 */
class Prefs(private val context: Context) {
    private object Keys {
        val TV_IP = stringPreferencesKey("tv_ip")
        val PAIRED = booleanPreferencesKey("paired")
    }

    val tvIpFlow: Flow<String> = context.dataStore.data.map { it[Keys.TV_IP] ?: "" }
    val isPairedFlow: Flow<Boolean> = context.dataStore.data.map { it[Keys.PAIRED] ?: false }

    suspend fun tvIp(): String = tvIpFlow.first()
    suspend fun isPaired(): Boolean = isPairedFlow.first()

    suspend fun setTvIp(ip: String) {
        context.dataStore.edit { it[Keys.TV_IP] = ip }
    }

    suspend fun setPaired(paired: Boolean) {
        context.dataStore.edit { it[Keys.PAIRED] = paired }
    }
}
