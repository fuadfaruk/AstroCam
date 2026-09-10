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
    fun `callbacks are optional`() {
        val controller = ManualControlsController()

        assertNull(controller.onStateChanged)
        assertNull(controller.onCommit)
        // Must not throw when nothing is listening.
        controller.scrub { it.copy(isoIndex = 1) }
        controller.commit()
    }
}
