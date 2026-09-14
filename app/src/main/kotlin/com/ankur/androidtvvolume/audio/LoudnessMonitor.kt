package com.ankur.androidtvvolume.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

private const val TAG = "LoudnessMonitor"
private const val SAMPLE_RATE = 16_000
private const val WINDOW_MILLIS = 1_500L

/** Floor applied to a fully-silent (all-zero) window to avoid log10(0). */
const val SILENCE_FLOOR_DBFS = -160.0f

/**
 * Wraps [AudioRecord] and continuously exposes a smoothed loudness reading in dBFS.
 *
 * Tries [MediaRecorder.AudioSource.UNPROCESSED] first since Android's stock AGC/noise
 * suppression would otherwise distort the very thing we're trying to measure; falls back to
 * VOICE_RECOGNITION (more widely supported, still lightly processed) if the device rejects it.
 *
 * A constant ambient noise source (e.g. a fan) adds to whatever the TV outputs and would
 * otherwise skew every reading, especially during quiet dialogue where it's a large fraction of
 * what the mic picks up. [measureNoiseFloor] captures that ambient level once (call it with the
 * TV muted/silent) and every subsequent [dbfs] reading has it removed via power-domain
 * subtraction (RMS values combine as sqrt(a^2 + b^2) for uncorrelated sources, so removing one
 * requires sqrt(a^2 - b^2), not a plain dB subtraction).
 */
class LoudnessMonitor(private val scope: CoroutineScope) {
    private val _dbfs = MutableStateFlow(SILENCE_FLOOR_DBFS)
    /** Noise-floor-compensated reading - what everything outside this class should use. */
    val dbfs: StateFlow<Float> = _dbfs

    private val _rawDbfs = MutableStateFlow(SILENCE_FLOOR_DBFS)

    private val _noiseFloorDbfs = MutableStateFlow<Float?>(null)
    /** Null until [measureNoiseFloor] has been called at least once this session. */
    val noiseFloorDbfs: StateFlow<Float?> = _noiseFloorDbfs

    @Volatile private var noiseFloorValue: Float = SILENCE_FLOOR_DBFS
    @Volatile private var running = false
    private var audioRecord: AudioRecord? = null
    private var job: Job? = null

    fun start() {
        if (running) return
        val record = openAudioRecord() ?: run {
            Log.e(TAG, "Could not open AudioRecord with any source")
            return
        }
        audioRecord = record
        running = true
        record.startRecording()
        job = scope.launch(Dispatchers.IO) { captureLoop(record) }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
        audioRecord?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        audioRecord = null
    }

    /** Suspends for ~[durationMillis], returning the average (noise-compensated) dBFS observed. */
    suspend fun averageOver(durationMillis: Long): Float {
        val samples = mutableListOf<Float>()
        val ticks = (durationMillis / 100).coerceAtLeast(1)
        repeat(ticks.toInt()) {
            samples.add(_dbfs.value)
            delay(100)
        }
        return samples.average().toFloat()
    }

    /**
     * Call with the TV muted/silent: averages the *raw* (uncompensated) level over
     * [durationMillis] and stores it as the ambient noise floor to subtract from every reading
     * from now on. Safe to call again later if room conditions change.
     */
    suspend fun measureNoiseFloor(durationMillis: Long = 2_000): Float {
        val samples = mutableListOf<Float>()
        val ticks = (durationMillis / 100).coerceAtLeast(1)
        repeat(ticks.toInt()) {
            samples.add(_rawDbfs.value)
            delay(100)
        }
        val avg = samples.average().toFloat()
        noiseFloorValue = avg
        _noiseFloorDbfs.value = avg
        return avg
    }

    private fun openAudioRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null
        val bufferSize = minBuf * 2

        for (source in intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION)) {
            try {
                val record = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "AudioRecord opened with source=$source")
                    return record
                }
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioSource $source failed: ${e.message}")
            }
        }
        return null
    }

    private fun captureLoop(record: AudioRecord) {
        val windowSamples = (SAMPLE_RATE * WINDOW_MILLIS / 1000).toInt()
        val ring = ShortArray(windowSamples)
        var ringPos = 0
        var filled = 0
        val readBuf = ShortArray(1024)

        while (running) {
            val n = try {
                record.read(readBuf, 0, readBuf.size)
            } catch (e: Exception) {
                Log.w(TAG, "read() failed, stopping capture loop: ${e.message}")
                break
            }
            if (n <= 0) continue
            for (i in 0 until n) {
                ring[ringPos] = readBuf[i]
                ringPos = (ringPos + 1) % windowSamples
                if (filled < windowSamples) filled++
            }
            if (filled >= SAMPLE_RATE / 10) {
                val raw = computeDbfs(ring, filled)
                _rawDbfs.value = raw
                _dbfs.value = subtractNoiseFloor(raw, noiseFloorValue)
            }
        }
    }

    private fun computeDbfs(ring: ShortArray, filled: Int): Float {
        var sumSquares = 0.0
        for (i in 0 until filled) {
            val v = ring[i].toDouble()
            sumSquares += v * v
        }
        val rms = sqrt(sumSquares / filled)
        if (rms < 1.0) return SILENCE_FLOOR_DBFS
        val db = 20.0 * log10(rms / 32768.0)
        return db.toFloat().coerceAtLeast(SILENCE_FLOOR_DBFS)
    }

    /** sqrt(a^2 - b^2) in the dB domain: removes an uncorrelated noise floor from a measured level. */
    private fun subtractNoiseFloor(measuredDb: Float, noiseDb: Float): Float {
        if (measuredDb <= noiseDb + 0.1f) return SILENCE_FLOOR_DBFS
        val measuredRatio = Math.pow(10.0, measuredDb / 20.0)
        val noiseRatio = Math.pow(10.0, noiseDb / 20.0)
        val diffSquared = measuredRatio * measuredRatio - noiseRatio * noiseRatio
        if (diffSquared <= 0.0) return SILENCE_FLOOR_DBFS
        val signalRatio = sqrt(diffSquared)
        return (20.0 * log10(signalRatio)).toFloat().coerceAtLeast(SILENCE_FLOOR_DBFS)
    }
}
