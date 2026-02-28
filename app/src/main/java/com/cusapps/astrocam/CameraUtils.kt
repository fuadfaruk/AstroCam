package com.cusapps.astrocam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Size
import java.util.Locale
import kotlin.math.sqrt

/**
 * CameraUtils provides utility functions for low-level camera control using Camera2.
 * This object centralizes manual exposure, ISO, and focus calculations.
 */
object CameraUtils {
    const val PREVIEW_MAX_EXPOSURE_NS = 66_666_666L 
    const val PREVIEW_MIN_FRAME_DURATION_NS = 33_333_333L 

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

    fun formatShutterSpeed(ns: Long): String {
        return if (ns >= 1_000_000_000L) {
            "${ns / 1_000_000_000L}s"
        } else {
            val denominator = if (ns > 0) 1_000_000_000L / ns else 1
            "1/$denominator"
        }
    }

    fun calculateFocusDistance(progress: Int, minFocus: Float): Float {
        return (1.0f - progress.coerceIn(0, 100) / 100f) * minFocus
    }

    fun getPreviewExposureTime(shutterSpeed: Long): Long {
        return shutterSpeed.coerceAtMost(PREVIEW_MAX_EXPOSURE_NS)
    }

    fun getPreviewFrameDuration(previewExposureTime: Long): Long {
        return previewExposureTime.coerceAtLeast(PREVIEW_MIN_FRAME_DURATION_NS)
    }

    /**
     * Applies manual settings to a CaptureRequest.Builder.
     */
    fun applyManualSettings(
        builder: CaptureRequest.Builder, 
        iso: Int, 
        shutterSpeed: Long, 
        focusDistance: Float, 
        forceShutter: Boolean = false
    ) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)

        if (forceShutter) {
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterSpeed)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, shutterSpeed)
        } else {
            val previewShutter = getPreviewExposureTime(shutterSpeed)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, previewShutter)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, getPreviewFrameDuration(previewShutter))
        }
    }

    fun applyAutoSettings(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    }

    fun getLensDescription(characteristics: CameraCharacteristics, cameraId: String): String {
        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
        
        val lensLabel = when (lensFacing) {
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
            else -> "Aux"
        }

        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        var focalLabel = ""
        
        if (focalLengths != null && focalLengths.isNotEmpty() && sensorSize != null) {
            val focalLength = focalLengths[0]
            val diagonal = sqrt((sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height).toDouble()).toFloat()
            if (diagonal > 0) {
                val eqFocalLength = focalLength * (43.27f / diagonal)
                focalLabel = when {
                    eqFocalLength < 21f -> " (UW)"
                    eqFocalLength < 35f -> " (W)"
                    eqFocalLength < 70f -> " (S)"
                    else -> " (T)"
                }
            }
        }

        return "$lensLabel $cameraId$focalLabel"
    }
    
    fun chooseOptimalSize(choices: Array<Size>, textureViewWidth: Int, textureViewHeight: Int, maxWidth: Int, maxHeight: Int, aspectRatio: Size): Size {
        val bigEnough = mutableListOf<Size>()
        val notBigEnough = mutableListOf<Size>()
        val w = aspectRatio.width
        val h = aspectRatio.height
        for (option in choices) {
            if (option.width <= maxWidth && option.height <= maxHeight &&
                option.height == option.width * h / w) {
                if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                    bigEnough.add(option)
                } else {
                    notBigEnough.add(option)
                }
            }
        }

        return when {
            bigEnough.size > 0 -> bigEnough.minByOrNull { it.width * it.height }!!
            notBigEnough.size > 0 -> notBigEnough.maxByOrNull { it.width * it.height }!!
            else -> choices[0]
        }
    }
}
