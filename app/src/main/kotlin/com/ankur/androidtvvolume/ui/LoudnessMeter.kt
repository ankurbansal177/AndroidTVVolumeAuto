package com.ankur.androidtvvolume.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Silence floor / full-scale ceiling the meter maps onto 0f..1f. Mirrors [com.ankur.androidtvvolume.control] tuning. */
private const val METER_MIN_DBFS = -60f
private const val METER_MAX_DBFS = 0f

/** Mirrors VolumeControlLoop's DEADBAND_DB - kept as a separate constant since that one is private to its file. */
private const val DEADBAND_DB = 3.0f
private const val WARNING_DB = 8.0f

private enum class Severity { NONE, GOOD, WARNING, CRITICAL }

private fun severityOf(diff: Float?): Severity {
    if (diff == null) return Severity.NONE
    val absDiff = abs(diff)
    return when {
        absDiff <= DEADBAND_DB -> Severity.GOOD
        absDiff <= WARNING_DB -> Severity.WARNING
        else -> Severity.CRITICAL
    }
}

private fun severityColor(severity: Severity): Color = when (severity) {
    Severity.NONE -> Color(0xFF898781) // muted ink - no baseline yet
    Severity.GOOD -> Color(0xFF0CA30C)
    Severity.WARNING -> Color(0xFFFAB219)
    Severity.CRITICAL -> Color(0xFFD03B3B)
}

/**
 * [autoModeActive] controls the verb: only say "correcting" when Auto Mode is actually running
 * the control loop. Otherwise this just describes the gap - it must never claim an action that
 * isn't happening (that was a real bug: this label used to say "Correcting" regardless of
 * whether Auto Mode was even on, which read as the app acting when it wasn't).
 */
private fun severityLabel(severity: Severity, diff: Float?, autoModeActive: Boolean): String = when (severity) {
    Severity.NONE -> "Not calibrated yet"
    Severity.GOOD -> "In range"
    Severity.WARNING -> {
        val direction = if ((diff ?: 0f) > 0) "louder than target" else "quieter than target"
        if (autoModeActive) "Correcting - $direction" else "Above deadband - $direction (Auto Mode off)"
    }
    Severity.CRITICAL -> {
        val direction = if ((diff ?: 0f) > 0) "louder than target" else "quieter than target"
        if (autoModeActive) "Large gap - correcting ($direction)" else "Large gap - $direction (Auto Mode off)"
    }
}

private fun dbfsToFraction(db: Float): Float =
    ((db - METER_MIN_DBFS) / (METER_MAX_DBFS - METER_MIN_DBFS)).coerceIn(0f, 1f)

/**
 * Horizontal loudness meter: bar length is the current mic level (mapped from
 * [METER_MIN_DBFS]..[METER_MAX_DBFS]), bar color is severity (how far current sits from the
 * calibrated baseline), and a tick above the bar marks the baseline ("maintain") position so
 * both values are visible at once.
 */
@Composable
fun LoudnessMeter(
    currentDbfs: Float?,
    baselineDbfs: Float?,
    autoModeActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val trackBase = if (isDark) Color(0xFF2C2C2A) else Color(0xFFE1E0D9)
    val tickColor = if (isDark) Color.White else Color(0xFF0B0B0B)

    val diff = if (currentDbfs != null && baselineDbfs != null) currentDbfs - baselineDbfs else null
    val severity = severityOf(diff)
    val fillColor = severityColor(severity)
    val trackColor = if (severity == Severity.NONE) trackBase else fillColor.copy(alpha = 0.18f)

    val currentFraction = currentDbfs?.let { dbfsToFraction(it) } ?: 0f
    val baselineFraction = baselineDbfs?.let { dbfsToFraction(it) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Baseline ("maintain") tick, drawn above the bar so it stays visible over any fill color.
        Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
            if (baselineFraction != null) {
                val x = size.width * baselineFraction
                val path = Path().apply {
                    moveTo(x - 5.dp.toPx(), 0f)
                    lineTo(x + 5.dp.toPx(), 0f)
                    lineTo(x, size.height)
                    close()
                }
                drawPath(path, color = tickColor)
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
        ) {
            drawRoundRect(color = trackColor, cornerRadius = CornerRadius(10.dp.toPx()))
            if (currentDbfs != null) {
                val fillWidth = size.width * currentFraction
                drawRoundRect(
                    color = fillColor,
                    size = Size(fillWidth, size.height),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                )
            }
            if (baselineFraction != null) {
                val x = size.width * baselineFraction
                drawLine(
                    color = tickColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = currentDbfs?.let { "Current: %.1f dBFS".format(it) } ?: "Current: —",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = baselineDbfs?.let { "Target: %.1f dBFS".format(it) } ?: "Target: not set",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(color = fillColor)
            }
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = severityLabel(severity, diff, autoModeActive),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
