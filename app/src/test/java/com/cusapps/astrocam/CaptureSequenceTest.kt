package com.cusapps.astrocam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSequenceTest {
    @Test
    fun `repeated inputs cannot start another countdown or frame`() {
        val sequence = CaptureSequence()
        val id = sequence.start(3)!!

        repeat(10) { assertNull(sequence.start(1)) }
        assertTrue(sequence.beginFrame(id))
        assertFalse(sequence.beginFrame(id))
        assertNull(sequence.start(1))
    }

    @Test
    fun `guard remains held between burst frames and releases after the last`() {
        val sequence = CaptureSequence()
        val id = sequence.start(3)!!

        repeat(2) {
            assertTrue(sequence.beginFrame(id))
            assertTrue(sequence.completeFrame(id, true))
            assertTrue(sequence.isCaptureInProgress)
            assertNull(sequence.start(1))
        }
        assertTrue(sequence.beginFrame(id))
        assertFalse(sequence.completeFrame(id, true))
        assertFalse(sequence.isCaptureInProgress)
        assertTrue(sequence.start(1) != null)
    }

    @Test
    fun `cancelling a countdown rejects its stale finish callback`() {
        val sequence = CaptureSequence()
        val cancelledId = sequence.start(3)!!

        sequence.cancel()
        val newId = sequence.start(1)!!

        assertFalse(sequence.isActive(cancelledId))
        assertFalse(sequence.beginFrame(cancelledId))
        assertTrue(sequence.isActive(newId))
        assertTrue(sequence.beginFrame(newId))
    }

    @Test
    fun `cancelling between frames rejects a queued burst callback`() {
        val sequence = CaptureSequence()
        val id = sequence.start(3)!!
        assertTrue(sequence.beginFrame(id))
        assertTrue(sequence.completeFrame(id, true))

        sequence.cancel()

        assertFalse(sequence.isCaptureInProgress)
        assertFalse(sequence.beginFrame(id))
        assertTrue(sequence.start(1) != null)
    }

    @Test
    fun `cancelling an in-flight frame keeps the guard until it finishes`() {
        val sequence = CaptureSequence()
        val id = sequence.start(3)!!
        assertTrue(sequence.beginFrame(id))

        sequence.cancel()
        sequence.cancel()

        assertTrue(sequence.isCaptureInProgress)
        assertTrue(sequence.isCancellationRequested)
        assertFalse(sequence.isActive(id))
        assertNull(sequence.start(1))
        assertFalse(sequence.completeFrame(id, true))
        assertFalse(sequence.isCaptureInProgress)
        assertTrue(sequence.start(1) != null)
    }

    @Test
    fun `failure or abortion ends the burst and permits another capture`() {
        val sequence = CaptureSequence()
        val id = sequence.start(3)!!
        assertTrue(sequence.beginFrame(id))

        assertFalse(sequence.completeFrame(id, false))

        assertFalse(sequence.isCaptureInProgress)
        assertFalse(sequence.beginFrame(id))
        assertTrue(sequence.start(1) != null)
    }

    @Test
    fun `closing the camera invalidates callbacks without affecting a new capture`() {
        val sequence = CaptureSequence()
        val oldId = sequence.start(3)!!
        assertTrue(sequence.beginFrame(oldId))
        sequence.cancel()

        sequence.reset()
        val newId = sequence.start(2)!!
        assertTrue(sequence.beginFrame(newId))

        assertNotEquals(oldId, newId)
        assertFalse(sequence.completeFrame(oldId, true))
        assertFalse(sequence.beginFrame(oldId))
        assertTrue(sequence.owns(newId))
        assertTrue(sequence.isCaptureInProgress)
        assertTrue(sequence.completeFrame(newId, true))
    }

    @Test
    fun `duplicate completion cannot advance a burst before its next frame`() {
        val sequence = CaptureSequence()
        val id = sequence.start(2)!!
        assertTrue(sequence.beginFrame(id))

        assertTrue(sequence.completeFrame(id, true))
        assertFalse(sequence.completeFrame(id, true))

        assertTrue(sequence.beginFrame(id))
        assertFalse(sequence.completeFrame(id, true))
        assertFalse(sequence.isCaptureInProgress)
    }

    @Test
    fun `idle cancellation and stale completion leave the sequence ready`() {
        val sequence = CaptureSequence()

        sequence.cancel()
        assertFalse(sequence.completeFrame(0, false))
        assertFalse(sequence.isCaptureInProgress)
        assertFalse(sequence.isCancellationRequested)
        assertTrue(sequence.start(1) != null)
    }
}
