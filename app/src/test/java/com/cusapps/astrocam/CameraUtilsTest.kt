package com.cusapps.astrocam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `calculateFocusDistance at progress 0 is the closest focus`() {
        assertEquals(10.0f, CameraUtils.calculateFocusDistance(0, 10.0f), 0.001f)
    }

    @Test
    fun `calculateFocusDistance at max progress is infinity`() {
        assertEquals(0.0f, CameraUtils.calculateFocusDistance(CameraUtils.FOCUS_PROGRESS_MAX, 10.0f), 0.001f)
    }

    @Test
    fun `calculateFocusDistance at half progress is half the diopter range`() {
        assertEquals(5.0f, CameraUtils.calculateFocusDistance(CameraUtils.FOCUS_PROGRESS_MAX / 2, 10.0f), 0.001f)
    }

    @Test
    fun `calculateFocusDistance clamps out of range progress`() {
        assertEquals(10.0f, CameraUtils.calculateFocusDistance(-50, 10.0f), 0.001f)
        assertEquals(0.0f, CameraUtils.calculateFocusDistance(CameraUtils.FOCUS_PROGRESS_MAX + 50, 10.0f), 0.001f)
    }

    @Test
    fun `formatFocusDistance shows infinity at zero diopters`() {
        assertEquals("∞", CameraUtils.formatFocusDistance(0f))
    }

    @Test
    fun `formatFocusDistance shows metres for distant subjects`() {
        assertEquals("1.0m", CameraUtils.formatFocusDistance(1.0f))
        assertEquals("2.0m", CameraUtils.formatFocusDistance(0.5f))
    }

    @Test
    fun `formatFocusDistance shows centimetres for near subjects`() {
        assertEquals("50cm", CameraUtils.formatFocusDistance(2.0f))
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

    @Test
    fun `calculatePreviewTransform fills width and aligns preview to top of view`() {
        val transform = CameraUtils.calculatePreviewTransform(1080, 1920, 1440, 1080)

        // Scale so the 1440-wide buffer spans the full 1080-wide view.
        assertEquals(0.75f, transform.scale, 0.001f)
        assertEquals(0f, transform.offsetX, 0.001f)
        // Top-aligned: no black bar above the preview, leftover stays at the bottom.
        assertEquals(0f, transform.offsetY, 0.001f)
    }

    @Test
    fun `calculateColorTemperature maps minimum progress to minimum temperature`() {
        assertEquals(CameraUtils.WB_MIN_TEMPERATURE_K, CameraUtils.calculateColorTemperature(0))
    }

    @Test
    fun `calculateColorTemperature maps maximum progress to maximum temperature`() {
        assertEquals(CameraUtils.WB_MAX_TEMPERATURE_K, CameraUtils.calculateColorTemperature(CameraUtils.WB_TEMPERATURE_PROGRESS_MAX))
    }

    @Test
    fun `calculateColorTemperature clamps out of range progress`() {
        assertEquals(CameraUtils.WB_MIN_TEMPERATURE_K, CameraUtils.calculateColorTemperature(-5))
        assertEquals(CameraUtils.WB_MAX_TEMPERATURE_K, CameraUtils.calculateColorTemperature(9999))
    }

    @Test
    fun `calculateTemperatureProgress is the inverse of calculateColorTemperature`() {
        for (temperatureK in 2000..10000 step 100) {
            val progress = CameraUtils.calculateTemperatureProgress(temperatureK)
            assertEquals(temperatureK, CameraUtils.calculateColorTemperature(progress))
        }
    }

    @Test
    fun `calculateTemperatureProgress clamps out of range temperatures`() {
        assertEquals(0, CameraUtils.calculateTemperatureProgress(500))
        assertEquals(CameraUtils.WB_TEMPERATURE_PROGRESS_MAX, CameraUtils.calculateTemperatureProgress(50000))
    }

    @Test
    fun `calculateWhiteBalanceGains are unity at the neutral temperature`() {
        val gains = CameraUtils.calculateWhiteBalanceGains(CameraUtils.WB_NEUTRAL_TEMPERATURE_K)

        assertEquals(1.0f, gains.red, 0.001f)
        assertEquals(1.0f, gains.green, 0.001f)
        assertEquals(1.0f, gains.blue, 0.001f)
    }

    @Test
    fun `calculateWhiteBalanceGains at low temperature favor blue`() {
        // A low Kelvin target renders the image cooler, so the blue channel is
        // boosted relative to red.
        val coolGains = CameraUtils.calculateWhiteBalanceGains(CameraUtils.WB_MIN_TEMPERATURE_K)

        // Every channel must satisfy Camera2's >= 1.0 requirement.
        assertTrue(coolGains.red >= 1.0f)
        assertTrue(coolGains.green >= 1.0f)
        assertTrue(coolGains.blue >= 1.0f)
        // One channel is normalised to exactly 1.0.
        assertEquals(1.0f, minOf(coolGains.red, coolGains.green, coolGains.blue), 0.001f)
        assertTrue(coolGains.blue > coolGains.red)
    }

    @Test
    fun `calculateWhiteBalanceGains at high temperature favor red`() {
        // A high Kelvin target renders the image warmer, so the red channel is
        // boosted relative to blue.
        val warmGains = CameraUtils.calculateWhiteBalanceGains(CameraUtils.WB_MAX_TEMPERATURE_K)

        assertTrue(warmGains.red > warmGains.blue)
        assertEquals(1.0f, minOf(warmGains.red, warmGains.green, warmGains.blue), 0.001f)
    }

    @Test
    fun `calculateWhiteBalanceGains never exceeds the channel cap`() {
        for (temperatureK in 2000..10000 step 500) {
            val gains = CameraUtils.calculateWhiteBalanceGains(temperatureK)
            assertTrue(gains.red <= CameraUtils.WB_MAX_CHANNEL_GAIN)
            assertTrue(gains.green <= CameraUtils.WB_MAX_CHANNEL_GAIN)
            assertTrue(gains.blue <= CameraUtils.WB_MAX_CHANNEL_GAIN)
        }
    }

    @Test
    fun `estimateColorTemperature recovers the temperature behind measured gains`() {
        for (temperatureK in intArrayOf(3000, 5000, 6500, 8000)) {
            val gains = CameraUtils.calculateWhiteBalanceGains(temperatureK)
            assertEquals(temperatureK, CameraUtils.estimateColorTemperature(gains))
        }
    }

    @Test
    fun `estimateColorTemperature ignores overall gain scaling`() {
        val gains = CameraUtils.calculateWhiteBalanceGains(4000)
        val brighter = CameraUtils.WhiteBalanceGains(
            red = gains.red * 3.0f,
            green = gains.green * 3.0f,
            blue = gains.blue * 3.0f
        )

        // Only channel ratios matter, so a uniformly brighter frame maps to the same
        // temperature instead of drifting with exposure.
        assertEquals(4000, CameraUtils.estimateColorTemperature(brighter))
    }

    @Test
    fun `estimateColorTemperature falls back to default for invalid gains`() {
        val invalid = CameraUtils.WhiteBalanceGains(0f, 0f, 0f)
        assertEquals(CameraUtils.WB_DEFAULT_TEMPERATURE_K, CameraUtils.estimateColorTemperature(invalid))
    }

    @Test
    fun `formatColorTemperature appends the Kelvin unit`() {
        assertEquals("4000K", CameraUtils.formatColorTemperature(4000))
    }

    @Test
    fun `calculateIsoStops stays within the device range`() {
        val stops = CameraUtils.calculateIsoStops(100, 3200)

        assertTrue(stops.isNotEmpty())
        assertTrue(stops.all { it in 100..3200 })
    }

    @Test
    fun `calculateIsoStops includes standard third stops`() {
        val stops = CameraUtils.calculateIsoStops(100, 1600)

        assertTrue(stops.contains(100))
        assertTrue(stops.contains(125))
        assertTrue(stops.contains(160))
        assertTrue(stops.contains(400))
        assertTrue(stops.contains(1600))
    }

    @Test
    fun `calculateIsoStops is strictly ascending`() {
        val stops = CameraUtils.calculateIsoStops(50, 12800)

        for (i in 1 until stops.size) {
            assertTrue("stop $i must exceed the previous", stops[i] > stops[i - 1])
        }
    }

    @Test
    fun `calculateIsoStops appends the exact device maximum`() {
        // 8192 is not a standard stop, so it must still be reachable.
        val stops = CameraUtils.calculateIsoStops(100, 8192)

        assertEquals(8192, stops.last())
    }

    @Test
    fun `calculateIsoStops appends the exact device minimum when below the series`() {
        val stops = CameraUtils.calculateIsoStops(60, 800)

        assertEquals(60, stops.first())
    }

    @Test
    fun `calculateIsoStops falls back to device bounds outside the series`() {
        val stops = CameraUtils.calculateIsoStops(500_000, 800_000)

        assertArrayEquals(intArrayOf(500_000, 800_000), stops)
    }

    @Test
    fun `indexOfNearest picks the closest int entry`() {
        val values = intArrayOf(100, 200, 400, 800)

        assertEquals(2, CameraUtils.indexOfNearest(values, 410))
        assertEquals(0, CameraUtils.indexOfNearest(values, 10))
        assertEquals(3, CameraUtils.indexOfNearest(values, 100000))
    }

    @Test
    fun `indexOfNearest picks the closest long entry`() {
        val values = longArrayOf(1_000_000L, 16_666_666L, 1_000_000_000L)

        assertEquals(1, CameraUtils.indexOfNearest(values, 16_000_000L))
    }
}
