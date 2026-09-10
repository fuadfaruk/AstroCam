package com.cusapps.astrocam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.util.Size
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * CameraUtils provides utility functions for low-level camera control using Camera2.
 * This object centralizes manual exposure, ISO, focus, and white balance calculations.
 */
object CameraUtils {
    data class PreviewTransform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float
    )

    /**
     * Per-channel raw white balance gains. Kept as plain floats (rather than an
     * RggbChannelVector) so the conversion math stays unit-testable on the JVM.
     */
    data class WhiteBalanceGains(
        val red: Float,
        val green: Float,
        val blue: Float
    )

    /**
     * User-facing white balance state. When [locked] is false the camera's own AWB
     * algorithm runs freely (the default). When [locked] is true the white balance
     * stops drifting between frames, which is what keeps colour consistent across a
     * long-exposure or nighttime burst.
     *
     * [manualGainsSupported] reflects whether the device can accept explicit colour
     * gains (CONTROL_AWB_MODE_OFF). On devices that cannot, locking still freezes the
     * auto-converged white balance via CONTROL_AWB_LOCK, just without a Kelvin dial.
     */
    data class WhiteBalance(
        val locked: Boolean = false,
        val temperatureK: Int = WB_DEFAULT_TEMPERATURE_K,
        val manualGainsSupported: Boolean = false
    )

    const val PREVIEW_MAX_EXPOSURE_NS = 66_666_666L 
    const val PREVIEW_MIN_FRAME_DURATION_NS = 33_333_333L 

    const val WB_MIN_TEMPERATURE_K = 2000
    const val WB_MAX_TEMPERATURE_K = 10000
    const val WB_DEFAULT_TEMPERATURE_K = 4000
    const val WB_TEMPERATURE_STEP_K = 100

    /** Temperature that maps to unity gains, i.e. the sensor's daylight reference. */
    const val WB_NEUTRAL_TEMPERATURE_K = 5000

    /** Camera2 requires gains >= 1.0; cap the top end so no channel blows out. */
    const val WB_MAX_CHANNEL_GAIN = 8.0f

    const val WB_TEMPERATURE_PROGRESS_MAX =
        (WB_MAX_TEMPERATURE_K - WB_MIN_TEMPERATURE_K) / WB_TEMPERATURE_STEP_K

    // Floor on the modelled channel response so inverting it can never divide by ~0.
    private const val WB_MIN_CHANNEL_RESPONSE = 0.05f

    /** Sensible starting sensitivity before the device reports its own range. */
    const val DEFAULT_ISO = 400

    /**
     * Canonical one-third-stop ISO series.
     *
     * The sensor exposes a continuous SENSOR_INFO_SENSITIVITY_RANGE, but exposing
     * every integer as a track position makes exact values unreachable. Photographers
     * think in stops, so the manual control indexes into this table instead.
     */
    private val ISO_THIRD_STOPS = intArrayOf(
        25, 32, 40, 50, 64, 80,
        100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250,
        1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800,
        16000, 20000, 25600, 32000, 40000, 51200, 64000, 80000, 102400,
        128000, 160000, 204800, 256000, 320000, 409600
    )

    /**
     * Quantizes the device's continuous sensitivity range to the one-third-stop ISO
     * series. The device's exact bounds are appended when they fall between stops so
     * no reachable sensitivity is lost at either end.
     */
    fun calculateIsoStops(minIso: Int, maxIso: Int): IntArray {
        if (maxIso < minIso) return intArrayOf(minIso)

        val stops = ISO_THIRD_STOPS.filter { it in minIso..maxIso }.toMutableList()
        if (stops.isEmpty()) {
            stops.add(minIso)
            if (maxIso > minIso) stops.add(maxIso)
            return stops.toIntArray()
        }
        if (minIso < stops.first()) stops.add(0, minIso)
        if (maxIso > stops.last()) stops.add(maxIso)
        return stops.toIntArray()
    }

    /**
     * Resolution of the manual focus control. Focus is expressed in diopters, and the
     * astro-relevant range near infinity occupies only a sliver of a 0..100 track, so
     * the control uses a finer 0..[FOCUS_PROGRESS_MAX] scale instead.
     */
    const val FOCUS_PROGRESS_MAX = 1000

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

    /**
     * Maps a focus-control position to a lens focus distance in diopters.
     *
     * Progress runs from 0 (closest focus, the largest diopter value) to
     * [FOCUS_PROGRESS_MAX] (infinity, zero diopters).
     */
    fun calculateFocusDistance(progress: Int, minFocus: Float): Float {
        return calculateFocusDistance(progress, FOCUS_PROGRESS_MAX, minFocus)
    }

    fun calculateFocusDistance(progress: Int, progressMax: Int, minFocus: Float): Float {
        if (progressMax <= 0) return 0f
        return (1.0f - progress.coerceIn(0, progressMax).toFloat() / progressMax) * minFocus
    }

    /**
     * Renders a diopter focus distance the way a photographer reads it: infinity,
     * metres for far subjects, centimetres for near ones. This is what makes the
     * infinity end of the focus track legible instead of a raw float.
     */
    fun formatFocusDistance(diopters: Float): String {
        if (diopters <= 0.0001f) return "∞"
        val meters = 1.0f / diopters
        return if (meters >= 1.0f) {
            String.format(Locale.US, "%.1fm", meters)
        } else {
            String.format(Locale.US, "%.0fcm", meters * 100.0f)
        }
    }

    /** Index of the entry closest to [target], for seeding a control from a default. */
    fun indexOfNearest(values: IntArray, target: Int): Int {
        if (values.isEmpty()) return 0
        var bestIndex = 0
        var bestDistance = Long.MAX_VALUE
        values.forEachIndexed { index, value ->
            val distance = kotlin.math.abs(value.toLong() - target)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    /** Index of the entry closest to [target], for seeding a control from a default. */
    fun indexOfNearest(values: LongArray, target: Long): Int {
        if (values.isEmpty()) return 0
        var bestIndex = 0
        var bestDistance = Long.MAX_VALUE
        values.forEachIndexed { index, value ->
            val distance = kotlin.math.abs(value - target)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    fun getPreviewExposureTime(shutterSpeed: Long): Long {
        return shutterSpeed.coerceAtMost(PREVIEW_MAX_EXPOSURE_NS)
    }

    fun getPreviewFrameDuration(previewExposureTime: Long): Long {
        return previewExposureTime.coerceAtLeast(PREVIEW_MIN_FRAME_DURATION_NS)
    }

    /**
     * Maps a white balance slider position to a colour temperature in Kelvin.
     */
    fun calculateColorTemperature(progress: Int): Int {
        val steps = progress.coerceIn(0, WB_TEMPERATURE_PROGRESS_MAX)
        return WB_MIN_TEMPERATURE_K + steps * WB_TEMPERATURE_STEP_K
    }

    /**
     * Inverse of [calculateColorTemperature], used to seed the slider from a
     * temperature that was measured or restored rather than dialled in by hand.
     */
    fun calculateTemperatureProgress(temperatureK: Int): Int {
        val clamped = temperatureK.coerceIn(WB_MIN_TEMPERATURE_K, WB_MAX_TEMPERATURE_K)
        return (clamped - WB_MIN_TEMPERATURE_K) / WB_TEMPERATURE_STEP_K
    }

    fun formatColorTemperature(temperatureK: Int): String {
        return "${temperatureK}K"
    }

    /**
     * Converts a colour temperature to raw per-channel gains.
     *
     * Gains are inversely proportional to the assumed illuminant's response and are
     * expressed relative to [WB_NEUTRAL_TEMPERATURE_K], so the neutral temperature
     * yields unity gains, lower temperatures cool the image, and higher temperatures
     * warm it. The result is normalised so the smallest channel gain is exactly 1.0,
     * as Camera2 requires every gain to be >= 1.0.
     */
    fun calculateWhiteBalanceGains(temperatureK: Int): WhiteBalanceGains {
        val target = planckianResponse(temperatureK)
        val reference = planckianResponse(WB_NEUTRAL_TEMPERATURE_K)

        val red = reference[0] / target[0]
        val green = reference[1] / target[1]
        val blue = reference[2] / target[2]

        val minGain = minOf(red, green, blue)
        return WhiteBalanceGains(
            red = normalizeGain(red, minGain),
            green = normalizeGain(green, minGain),
            blue = normalizeGain(blue, minGain)
        )
    }

    /**
     * Estimates the colour temperature that best explains a set of measured gains.
     *
     * This is the inverse of [calculateWhiteBalanceGains] and is used when the white
     * balance lock engages: the currently converged auto gains are translated back to
     * Kelvin so locking does not visibly shift colour before the user fine-tunes it.
     * Matching happens on log channel ratios, which ignores overall brightness.
     */
    fun estimateColorTemperature(gains: WhiteBalanceGains): Int {
        if (gains.red <= 0f || gains.green <= 0f || gains.blue <= 0f) {
            return WB_DEFAULT_TEMPERATURE_K
        }

        val targetRedRatio = ln((gains.red / gains.green).toDouble())
        val targetBlueRatio = ln((gains.blue / gains.green).toDouble())

        var bestTemperature = WB_NEUTRAL_TEMPERATURE_K
        var bestError = Double.MAX_VALUE
        var temperatureK = WB_MIN_TEMPERATURE_K

        while (temperatureK <= WB_MAX_TEMPERATURE_K) {
            val candidate = calculateWhiteBalanceGains(temperatureK)
            val redError = ln((candidate.red / candidate.green).toDouble()) - targetRedRatio
            val blueError = ln((candidate.blue / candidate.green).toDouble()) - targetBlueRatio
            val error = redError * redError + blueError * blueError
            if (error < bestError) {
                bestError = error
                bestTemperature = temperatureK
            }
            temperatureK += WB_TEMPERATURE_STEP_K
        }

        return bestTemperature
    }

    /**
     * Approximates the RGB response of a black-body illuminant, normalised to 0..1.
     * Based on the widely used piecewise fit to the Planckian locus.
     */
    private fun planckianResponse(temperatureK: Int): FloatArray {
        val temp = temperatureK.coerceIn(WB_MIN_TEMPERATURE_K, WB_MAX_TEMPERATURE_K) / 100.0

        val red = if (temp <= 66.0) {
            255.0
        } else {
            329.698727446 * (temp - 60.0).pow(-0.1332047592)
        }

        val green = if (temp <= 66.0) {
            99.4708025861 * ln(temp) - 161.1195681661
        } else {
            288.1221695283 * (temp - 60.0).pow(-0.0755148492)
        }

        val blue = when {
            temp >= 66.0 -> 255.0
            temp <= 19.0 -> 0.0
            else -> 138.5177312231 * ln(temp - 10.0) - 305.0447927307
        }

        return floatArrayOf(
            normalizeResponse(red),
            normalizeResponse(green),
            normalizeResponse(blue)
        )
    }

    private fun normalizeResponse(value: Double): Float {
        return (value / 255.0).coerceIn(WB_MIN_CHANNEL_RESPONSE.toDouble(), 1.0).toFloat()
    }

    private fun normalizeGain(gain: Float, minGain: Float): Float {
        return (gain / minGain).coerceIn(1f, WB_MAX_CHANNEL_GAIN)
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

    /**
     * Applies the white balance state to a CaptureRequest.Builder.
     *
     * This is deliberately independent of manual exposure mode: a nighttime sequence
     * may want auto exposure but a frozen white balance. Because every still capture
     * builds a fresh request, the lock has to be re-applied per frame — that is what
     * stops AWB from re-converging (and shifting colour) between burst frames.
     */
    fun applyWhiteBalance(builder: CaptureRequest.Builder, whiteBalance: WhiteBalance) {
        if (!whiteBalance.locked) {
            // Restore device-driven colour correction; otherwise a previously locked
            // preview builder would keep applying the stale transform matrix.
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            return
        }

        if (!whiteBalance.manualGainsSupported) {
            // No manual gains on this device: freeze whatever AWB converged on.
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            return
        }

        val gains = calculateWhiteBalanceGains(whiteBalance.temperatureK)
        // AWB_MODE_OFF with explicit gains is already frozen, so CONTROL_AWB_LOCK is
        // not set here: that key is only meaningful while AWB_MODE is AUTO.
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        builder.set(
            CaptureRequest.COLOR_CORRECTION_GAINS,
            RggbChannelVector(gains.red, gains.green, gains.green, gains.blue)
        )
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

    /**
     * Calculates a transform that scales the preview buffer to fill the view width
     * and aligns it to the TOP of the view. Any leftover vertical space collects
     * below the preview (the bottom black area), so nothing is ever centered into
     * a top black bar.
     */
    fun calculatePreviewTransform(viewWidth: Int, viewHeight: Int, previewWidth: Int, previewHeight: Int): PreviewTransform {
        if (viewWidth <= 0 || viewHeight <= 0 || previewWidth <= 0 || previewHeight <= 0) {
            return PreviewTransform(1f, 0f, 0f)
        }

        // Scale so the preview spans the full view width; height follows the
        // preview aspect ratio. Top-aligned: offsetY is always 0.
        val scale = viewWidth.toFloat() / previewWidth
        val scaledWidth = previewWidth * scale
        val scaledHeight = previewHeight * scale

        return PreviewTransform(
            scale = scale,
            offsetX = (viewWidth - scaledWidth) / 2f,
            offsetY = 0f
        )
    }
}
