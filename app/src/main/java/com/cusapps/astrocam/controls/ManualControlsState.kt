package com.cusapps.astrocam.controls

import com.cusapps.astrocam.CameraUtils

/**
 * Single source of truth for the manual exposure controls.
 *
 * The widget is deliberately NOT the source of truth. Camera2 request builders run on
 * the camera background thread, and reading a View from there violates the main-thread
 * contract. Holding the selection here makes it readable from any thread instead.
 */
data class ManualControlsState(
    val manualEnabled: Boolean = false,
    val isoIndex: Int = 0,
    val shutterIndex: Int = 0,
    /** 0 = closest focus, [CameraUtils.FOCUS_PROGRESS_MAX] = infinity. */
    val focusProgress: Int = CameraUtils.FOCUS_PROGRESS_MAX,
    val wbProgress: Int =
        CameraUtils.calculateTemperatureProgress(CameraUtils.WB_DEFAULT_TEMPERATURE_K),
    val wbLocked: Boolean = false,
    /** True once the user has dialled a temperature, so locking must not re-seed it. */
    val wbSetByUser: Boolean = false,
) {
    /** Selected sensitivity, or the default while the device range is still unknown. */
    fun iso(stops: IntArray): Int = stops.getOrNull(isoIndex) ?: CameraUtils.DEFAULT_ISO

    /** Selected exposure time, or 0 while the device range is still unknown. */
    fun shutterSpeed(speeds: LongArray): Long = speeds.getOrNull(shutterIndex) ?: 0L

    fun focusDistance(minFocusDistance: Float): Float =
        CameraUtils.calculateFocusDistance(focusProgress, minFocusDistance)

    fun whiteBalanceTemperature(): Int = CameraUtils.calculateColorTemperature(wbProgress)
}
