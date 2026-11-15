package com.cusapps.astrocam.controls

import com.cusapps.astrocam.CameraUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualControlsControllerTest {

    @Test
    fun `scrub updates state and notifies the cheap channel`() {
        val controller = ManualControlsController()
        var changes = 0
        controller.onStateChanged = { changes++ }

        controller.scrub { it.copy(isoIndex = 3) }

        assertEquals(3, controller.state.isoIndex)
        assertEquals(1, changes)
    }

    @Test
    fun `commit does not mutate state and notifies only the commit channel`() {
        val controller = ManualControlsController()
        var changes = 0
        var commits = 0
        controller.onStateChanged = { changes++ }
        controller.onCommit = { commits++ }

        controller.commit()

        assertEquals(0, changes)
        assertEquals(1, commits)
    }

    @Test
    fun `a full fling emits one commit for many scrubs`() {
        // This is the contract that keeps Camera2 healthy: a momentum fling can emit
        // hundreds of detents, but only the settle may call setRepeatingRequest.
        val controller = ManualControlsController()
        var commits = 0
        controller.onCommit = { commits++ }

        repeat(500) { step ->
            controller.scrub { it.copy(isoIndex = step + 1) }
        }
        controller.commit()

        assertEquals(500, controller.state.isoIndex)
        assertEquals(1, commits)
    }

    @Test
    fun `scrubAndCommit fires both channels and lands on the new value`() {
        val controller = ManualControlsController()
        val committedIso = mutableListOf<Int>()
        controller.onCommit = { committedIso.add(it.isoIndex) }

        controller.scrubAndCommit { it.copy(isoIndex = 7) }

        assertEquals(7, controller.state.isoIndex)
        assertEquals(listOf(7), committedIso)
    }

    @Test
    fun `state snapshot resolves camera values against the device tables`() {
        val state = ManualControlsState(
            isoIndex = 2,
            shutterIndex = 1,
            focusProgress = CameraUtils.FOCUS_PROGRESS_MAX,
            wbProgress = CameraUtils.calculateTemperatureProgress(5000)
        )
        val isoStops = intArrayOf(100, 200, 400, 800)
        val shutterSpeeds = longArrayOf(1_000_000L, 16_666_666L, 1_000_000_000L)

        assertEquals(400, state.iso(isoStops))
        assertEquals(16_666_666L, state.shutterSpeed(shutterSpeeds))
        assertEquals(0.0f, state.focusDistance(10.0f), 0.001f)
        assertEquals(5000, state.whiteBalanceTemperature())
    }

    @Test
    fun `state falls back safely before the device tables are known`() {
        val state = ManualControlsState(isoIndex = 9, shutterIndex = 9)

        assertEquals(CameraUtils.DEFAULT_ISO, state.iso(intArrayOf()))
        assertEquals(0L, state.shutterSpeed(longArrayOf()))
    }

    @Test
    fun `first camera setup selects the nearest startup defaults`() {
        val state = ManualControlsState()

        val configured = state.withCameraDomains(
            intArrayOf(), longArrayOf(),
            intArrayOf(100, 300, 800),
            longArrayOf(1_000_000L, 20_000_000L, 1_000_000_000L)
        )

        assertEquals(state.copy(isoIndex = 1, shutterIndex = 1), configured)
    }

    @Test
    fun `reopening the same camera preserves manual selections without committing`() {
        val selected = ManualControlsState(
            manualEnabled = true,
            isoIndex = 3,
            shutterIndex = 2,
            focusProgress = 725,
            wbProgress = CameraUtils.calculateTemperatureProgress(5000),
            wbLocked = true,
            wbSetByUser = true
        )
        val isoStops = intArrayOf(100, 400, 800, 1600)
        val shutterSpeeds = longArrayOf(1_000_000L, 16_666_666L, 2_000_000_000L)
        val controller = ManualControlsController(selected)
        var commits = 0
        controller.onCommit = { commits++ }

        repeat(3) {
            controller.scrub {
                it.withCameraDomains(isoStops, shutterSpeeds, isoStops, shutterSpeeds)
            }
            assertEquals(selected, controller.state)
        }

        assertEquals(1600, controller.state.iso(isoStops))
        assertEquals(2_000_000_000L, controller.state.shutterSpeed(shutterSpeeds))
        assertEquals(2.75f, controller.state.focusDistance(10f), 0.001f)
        assertEquals(0, commits)
    }

    @Test
    fun `refreshed camera domains restore nearest values rather than old indices`() {
        val selected = ManualControlsState(isoIndex = 1, shutterIndex = 1, focusProgress = 600)
        val isoStops = intArrayOf(100, 200, 320, 800)
        val shutterSpeeds = longArrayOf(1_000_000L, 16_666_666L, 50_000_000L, 125_000_000L)

        val configured = selected.withCameraDomains(
            intArrayOf(100, 400, 1600),
            longArrayOf(1_000_000L, 100_000_000L, 2_000_000_000L),
            isoStops, shutterSpeeds
        )

        assertEquals(selected.copy(isoIndex = 2, shutterIndex = 3), configured)
        assertEquals(320, configured.iso(isoStops))
        assertEquals(125_000_000L, configured.shutterSpeed(shutterSpeeds))
    }

    @Test
    fun `refreshed camera domains clamp selections to supported endpoints`() {
        val selected = ManualControlsState(isoIndex = 1, shutterIndex = 0, focusProgress = -20)
        val previousIsoStops = intArrayOf(100, 3200)
        val previousShutterSpeeds = longArrayOf(1_000_000L, 2_000_000_000L)
        val isoStops = intArrayOf(100, 400, 800)
        val shutterSpeeds = longArrayOf(16_666_666L, 100_000_000L, 1_000_000_000L)

        val configured = selected.withCameraDomains(
            previousIsoStops, previousShutterSpeeds, isoStops, shutterSpeeds
        )

        assertEquals(selected.copy(isoIndex = 2, shutterIndex = 0, focusProgress = 0), configured)
        val beyondMaximum = selected.copy(focusProgress = CameraUtils.FOCUS_PROGRESS_MAX + 20)
            .withCameraDomains(previousIsoStops, previousShutterSpeeds, isoStops, shutterSpeeds)
        assertEquals(CameraUtils.FOCUS_PROGRESS_MAX, beyondMaximum.focusProgress)
    }

    @Test
    fun `callbacks are optional`() {
        val controller = ManualControlsController()

        assertNull(controller.onStateChanged)
        assertNull(controller.onCommit)
        // Must not throw when nothing is listening.
        controller.scrub { it.copy(isoIndex = 1) }
        controller.commit()
    }
}
