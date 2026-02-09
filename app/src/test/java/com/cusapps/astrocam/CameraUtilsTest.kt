package com.cusapps.astrocam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraUtilsTest {

    @Test
    fun `calculateShutterSpeeds includes common speeds within range`() {
        val minExp = 1_000_000L // 1/1000s
        val maxExp = 1_000_000_000L // 1s
        val expected = longArrayOf(
            1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_666_666L, 33_333_333L, 66_666_666L,
            125_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L
        )
        val actual = CameraUtils.calculateShutterSpeeds(minExp, maxExp)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `calculateShutterSpeeds adds max exposure if not present`() {
        val minExp = 100_000L
        val maxExp = 70_000_000L // Between two common speeds
        val actual = CameraUtils.calculateShutterSpeeds(minExp, maxExp)
        assertEquals(70_000_000L, actual.last())
    }
    
    @Test
    fun `calculateShutterSpeeds handles empty common speeds`() {
        val minExp = 70_000_000_000L
        val maxExp = 80_000_000_000L
        val expected = longArrayOf(80_000_000_000L)
        val actual = CameraUtils.calculateShutterSpeeds(minExp, maxExp)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `formatShutterSpeed for seconds`() {
        assertEquals("2s", CameraUtils.formatShutterSpeed(2_000_000_000L))
    }

    @Test
    fun `formatShutterSpeed for fractions of a second`() {
        assertEquals("1/500", CameraUtils.formatShutterSpeed(2_000_000L))
    }

    @Test
    fun `calculateFocusDistance at progress 0`() {
        assertEquals(10.0f, CameraUtils.calculateFocusDistance(0, 10.0f), 0.001f)
    }

    @Test
    fun `calculateFocusDistance at progress 100`() {
        assertEquals(0.0f, CameraUtils.calculateFocusDistance(100, 10.0f), 0.001f)
    }

    @Test
    fun `calculateFocusDistance at progress 50`() {
        assertEquals(5.0f, CameraUtils.calculateFocusDistance(50, 10.0f), 0.001f)
    }

    @Test
    fun `getPreviewExposureTime limits long exposures`() {
        val longExposure = 1_000_000_000L // 1s
        val expected = CameraUtils.PREVIEW_MAX_EXPOSURE_NS
        assertEquals(expected, CameraUtils.getPreviewExposureTime(longExposure))
    }

    @Test
    fun `getPreviewExposureTime allows short exposures`() {
        val shortExposure = 16_666_666L // 1/60s
        assertEquals(shortExposure, CameraUtils.getPreviewExposureTime(shortExposure))
    }

    @Test
    fun `getPreviewFrameDuration ensures minimum duration`() {
        val shortExposure = 16_666_666L // 1/60s
        val expected = CameraUtils.PREVIEW_MIN_FRAME_DURATION_NS
        assertEquals(expected, CameraUtils.getPreviewFrameDuration(shortExposure))
    }

    @Test
    fun `getPreviewFrameDuration uses exposure time if longer`() {
        val longishExposure = 40_000_000L // 1/25s
        assertEquals(longishExposure, CameraUtils.getPreviewFrameDuration(longishExposure))
    }
}
