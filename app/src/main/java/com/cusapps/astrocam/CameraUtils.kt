package com.cusapps.astrocam

import java.util.Locale

object CameraUtils {
    const val PREVIEW_MAX_EXPOSURE_NS = 66_666_666L // 1/15s
    const val PREVIEW_MIN_FRAME_DURATION_NS = 33_333_333L // 1/30s

    /**
     * Calculates available shutter speeds based on the sensor's exposure time range.
     * Includes common speeds and ensures the maximum supported speed is included.
     */
    fun calculateShutterSpeeds(minExp: Long, maxExp: Long): LongArray {
        val commonSpeeds = longArrayOf(
            1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_666_666L, 33_333_333L, 66_666_666L,
            125_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L, 2_000_000_000L, 4_000_000_000L,
            8_000_000_000L, 15_000_000_000L, 30_000_000_000L, 60_000_000_000L
        )
        val supportedSpeedsList = commonSpeeds.filter { it in minExp..maxExp }.toMutableList()
        if (supportedSpeedsList.isEmpty() || maxExp > (supportedSpeedsList.lastOrNull() ?: 0L)) {
            supportedSpeedsList.add(maxExp)
        }
        return supportedSpeedsList.toLongArray()
    }

    /**
     * Formats shutter speed in nanoseconds to a human-readable string (e.g., "1/50" or "2s").
     */
    fun formatShutterSpeed(ns: Long): String {
        return if (ns >= 1_000_000_000L) {
            "${ns / 1_000_000_000L}s"
        } else {
            val denominator = 1_000_000_000L / ns
            "1/$denominator"
        }
    }

    /**
     * Maps seek bar progress (0-100) to lens focus distance.
     */
    fun calculateFocusDistance(progress: Int, minFocus: Float): Float {
        return (1.0f - progress.coerceIn(0, 100) / 100f) * minFocus
    }

    /**
     * Calculates the exposure time for preview to avoid low frame rates.
     */
    fun getPreviewExposureTime(shutterSpeed: Long): Long {
        return shutterSpeed.coerceAtMost(PREVIEW_MAX_EXPOSURE_NS)
    }

    /**
     * Calculates the frame duration for preview to keep it smooth.
     */
    fun getPreviewFrameDuration(previewExposureTime: Long): Long {
        return previewExposureTime.coerceAtLeast(PREVIEW_MIN_FRAME_DURATION_NS)
    }
}
