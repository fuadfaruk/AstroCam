package com.cusapps.astrocam.controls

import com.cusapps.astrocam.CameraUtils

/**
 * A finite, ordered set of selectable values addressed by index.
 *
 * Keeping the domain separate from the widget is what lets a wheel hold a constant
 * control-to-display gain no matter how many entries the camera reports. The old
 * sliders mapped the whole domain onto one track, so a 1001-step focus scale ended
 * up with 0.34dp per step and needed stepper buttons to be usable at all.
 */
interface ControlDomain<out T> {
    val size: Int
    fun valueAt(index: Int): T
    fun labelAt(index: Int): String
}

/** Domain backed by an explicit table plus a formatter (ISO stops, shutter speeds, Kelvin). */
class IndexedDomain<T>(
    private val values: List<T>,
    private val formatter: (T) -> String,
) : ControlDomain<T> {

    override val size: Int get() = values.size.coerceAtLeast(1)

    private fun clamp(index: Int) = index.coerceIn(0, values.lastIndex.coerceAtLeast(0))

    override fun valueAt(index: Int): T = values[clamp(index)]

    override fun labelAt(index: Int): String = formatter(values[clamp(index)])

    companion object {
        /** ISO is already quantized to the one-third-stop series by [CameraUtils]. */
        fun iso(stops: IntArray): IndexedDomain<Int> =
            IndexedDomain(stops.toList()) { it.toString() }

        fun shutter(speeds: LongArray): IndexedDomain<Long> =
            IndexedDomain(speeds.toList(), CameraUtils::formatShutterSpeed)

        /** White balance is already quantized to [CameraUtils.WB_TEMPERATURE_STEP_K] steps. */
        fun whiteBalance(): IndexedDomain<Int> = IndexedDomain(
            (0..CameraUtils.WB_TEMPERATURE_PROGRESS_MAX)
                .map(CameraUtils::calculateColorTemperature),
            CameraUtils::formatColorTemperature
        )
    }
}

/**
 * Continuous domain for focus.
 *
 * Progress runs 0 (closest focus) to [CameraUtils.FOCUS_PROGRESS_MAX] (infinity),
 * exactly matching [CameraUtils.calculateFocusDistance]. Because that mapping is linear
 * in diopters, equal progress steps stay perceptually even all the way to infinity,
 * and labels are rendered the way a photographer reads them (∞ / metres / centimetres).
 */
class FocusDomain(private val minFocusDistance: Float) : ControlDomain<Float> {

    override val size: Int get() = CameraUtils.FOCUS_PROGRESS_MAX + 1

    override fun valueAt(index: Int): Float =
        CameraUtils.calculateFocusDistance(index, minFocusDistance)

    override fun labelAt(index: Int): String = CameraUtils.formatFocusDistance(valueAt(index))
}
