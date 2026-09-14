package com.ankur.androidtvvolume.control

import android.util.Log
import com.ankur.androidtvvolume.audio.LoudnessMonitor
import com.ankur.androidtvvolume.net.RemoteClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "VolumeControlLoop"

/** Below this level we treat the content as silence/paused and never correct against it. */
private const val SILENCE_GATE_DBFS = -50.0f

/** No correction inside this tolerance of the baseline. */
private const val DEADBAND_DB = 3.0f

/** Hard cap on how many volume steps one correction burst can send. */
private const val MAX_STEPS_PER_BURST = 5

/** Delay between individual key presses within a burst, so the TV's own ramp keeps up. */
private const val STEP_DELAY_MILLIS = 180L

/** Extra pause after a correction burst before the next comparison, on top of [TICK_MILLIS]. */
private const val COOLDOWN_MILLIS = 3_500L

/** Regular comparison tick interval outside of cooldown. */
private const val TICK_MILLIS = 1_000L

private const val DEFAULT_DB_PER_STEP = 2.0f
private const val MIN_PLAUSIBLE_DB_PER_STEP = 0.3f

/**
 * Drives the "auto mode" correction loop: compares the live mic reading against a calibrated
 * baseline and nudges TV volume back in line, scaling corrections by a measured per-step dB
 * size (see [calibrateDbPerStep]) so a big gap converges in one burst instead of one step at a
 * time.
 */
class VolumeControlLoop(
    private val loudnessMonitor: LoudnessMonitor,
    private val remoteClient: RemoteClient,
) {
    var baselineDbfs: Float? = null
        private set
    var dbPerStep: Float = DEFAULT_DB_PER_STEP
        private set

    private var job: Job? = null

    fun setBaseline(value: Float) {
        baselineDbfs = value
    }

    /** Sends one test volume step and measures the resulting dB shift; falls back to a default if implausible. */
    suspend fun calibrateDbPerStep() {
        val before = loudnessMonitor.dbfs.value
        if (before < SILENCE_GATE_DBFS) {
            Log.w(TAG, "Skipping dbPerStep calibration during silence; keeping $dbPerStep")
            return
        }
        remoteClient.sendVolumeUp()
        delay(1_000)
        val after = loudnessMonitor.dbfs.value
        val measured = abs(after - before)
        dbPerStep = if (measured < MIN_PLAUSIBLE_DB_PER_STEP) {
            Log.w(TAG, "Measured dbPerStep=$measured implausibly small, using default $DEFAULT_DB_PER_STEP")
            DEFAULT_DB_PER_STEP
        } else {
            measured
        }
        Log.d(TAG, "Calibrated dbPerStep=$dbPerStep")
    }

    fun start(scope: CoroutineScope) {
        if (job != null) {
            Log.d(TAG, "start() called but loop is already running")
            return
        }
        Log.d(TAG, "Auto-correction loop starting (baseline=$baselineDbfs, dbPerStep=$dbPerStep)")
        job = scope.launch {
            while (true) {
                delay(TICK_MILLIS)
                tick()
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Auto-correction loop stopped")
        job?.cancel()
        job = null
    }

    private suspend fun tick() {
        val baseline = baselineDbfs ?: return
        val current = loudnessMonitor.dbfs.value

        if (current < SILENCE_GATE_DBFS) {
            Log.v(TAG, "tick: current=$current below silence gate, skipping")
            return
        }

        val diff = current - baseline
        if (abs(diff) <= DEADBAND_DB) {
            Log.v(TAG, "tick: current=$current baseline=$baseline diff=$diff within deadband, no action")
            return
        }

        val steps = (abs(diff) / dbPerStep).roundToInt().coerceIn(1, MAX_STEPS_PER_BURST)
        val direction = if (diff > 0) "down" else "up"
        Log.d(TAG, "current=$current baseline=$baseline diff=$diff -> $steps step(s) $direction")

        repeat(steps) {
            if (diff > 0) remoteClient.sendVolumeDown() else remoteClient.sendVolumeUp()
            delay(STEP_DELAY_MILLIS)
        }

        delay(COOLDOWN_MILLIS)
    }
}
