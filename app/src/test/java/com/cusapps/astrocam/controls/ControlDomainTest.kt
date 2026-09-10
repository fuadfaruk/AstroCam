package com.cusapps.astrocam.controls

import com.cusapps.astrocam.CameraUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlDomainTest {

    private val isoStops = intArrayOf(100, 200, 400, 800, 1600)
    private val shutterSpeeds = longArrayOf(1_000_000L, 16_666_666L, 1_000_000_000L)

    @Test
    fun `iso domain exposes every stop as a labelled selectable value`() {
        val domain = IndexedDomain.iso(isoStops)

        assertEquals(isoStops.size, domain.size)
        assertEquals(100, domain.valueAt(0))
        assertEquals("1600", domain.labelAt(4))
    }

    @Test
    fun `shutter domain formats labels through CameraUtils`() {
        val domain = IndexedDomain.shutter(shutterSpeeds)

        assertEquals("1/1000", domain.labelAt(0))
        assertEquals("1/60", domain.labelAt(1))
        assertEquals("1s", domain.labelAt(2))
    }

    @Test
    fun `white balance domain spans the full quantized Kelvin range`() {
        val domain = IndexedDomain.whiteBalance()

        assertEquals(CameraUtils.WB_TEMPERATURE_PROGRESS_MAX + 1, domain.size)
        assertEquals("${CameraUtils.WB_MIN_TEMPERATURE_K}K", domain.labelAt(0))
        assertEquals("${CameraUtils.WB_MAX_TEMPERATURE_K}K", domain.labelAt(domain.size - 1))
    }

    @Test
    fun `domains clamp out of range indices instead of throwing`() {
        val domain = IndexedDomain.iso(isoStops)

        assertEquals(100, domain.valueAt(-5))
        assertEquals(1600, domain.valueAt(99))
        assertEquals("100", domain.labelAt(-1))
    }

    @Test
    fun `focus domain maps endpoints to closest focus and infinity`() {
        val domain = FocusDomain(10.0f)

        assertEquals(CameraUtils.FOCUS_PROGRESS_MAX + 1, domain.size)
        assertEquals(10.0f, domain.valueAt(0), 0.001f)
        assertEquals(0.0f, domain.valueAt(CameraUtils.FOCUS_PROGRESS_MAX), 0.001f)
        assertEquals("∞", domain.labelAt(CameraUtils.FOCUS_PROGRESS_MAX))
    }

    @Test
    fun `focus domain keeps a constant step count regardless of range density`() {
        // The whole point of the wheel: 1001 selectable positions, but the control's
        // precision comes from its gain, not from the domain size.
        val domain = FocusDomain(5.0f)

        assertTrue(domain.size > 1000)
    }
}
