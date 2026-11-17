package com.cusapps.astrocam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CaptureCompletionTest {
    @Test
    fun `camera completion does not report success before saving`() {
        val results = mutableListOf<Exception?>()
        val completion = CaptureCompletion { results.add(it) }

        completion.onCaptured()
        completion.onCameraFinished()

        assertFalse(completion.isComplete)
        assertTrue(results.isEmpty())

        completion.onSaved()

        assertTrue(completion.isComplete)
        assertEquals(1, results.size)
        assertNull(results.single())
    }

    @Test
    fun `saving first still waits for camera termination`() {
        val results = mutableListOf<Exception?>()
        val completion = CaptureCompletion { results.add(it) }

        completion.onCaptured()
        completion.onSaved()

        assertFalse(completion.isComplete)
        assertTrue(results.isEmpty())

        completion.onCameraFinished()

        assertTrue(completion.isComplete)
        assertNull(results.single())
    }

    @Test
    fun `save failure after capture stops the burst instead of advancing it`() {
        val sequence = CaptureSequence()
        val id = sequence.start(3)!!
        assertTrue(sequence.beginFrame(id))
        val results = mutableListOf<Exception?>()
        var shouldContinue = false
        val completion = CaptureCompletion {
            results.add(it)
            shouldContinue = sequence.completeFrame(id, it == null)
        }
        val error = IOException("MediaStore write failed")

        completion.onCaptured()
        completion.onCameraFinished()

        assertTrue(sequence.isCaptureInProgress)
        assertFalse(sequence.beginFrame(id))
        assertNull(sequence.start(1))

        completion.onFailed(error)

        assertSame(error, results.single())
        assertFalse(shouldContinue)
        assertFalse(sequence.isCaptureInProgress)
        assertTrue(sequence.start(1) != null)
    }

    @Test
    fun `save failure before camera termination keeps capture admission held`() {
        val sequence = CaptureSequence()
        val id = sequence.start(2)!!
        assertTrue(sequence.beginFrame(id))
        val results = mutableListOf<Exception?>()
        val completion = CaptureCompletion {
            results.add(it)
            sequence.completeFrame(id, it == null)
        }
        val error = IOException("Disk full")

        completion.onCaptured()
        completion.onFailed(error)

        assertTrue(results.isEmpty())
        assertNull(sequence.start(1))

        completion.onCameraFinished()

        assertSame(error, results.single())
        assertFalse(sequence.isCaptureInProgress)
    }

    @Test
    fun `cancelling while saving finishes the current photo without another frame`() {
        val sequence = CaptureSequence()
        val id = sequence.start(3)!!
        assertTrue(sequence.beginFrame(id))
        var shouldContinue = true
        val completion = CaptureCompletion { shouldContinue = sequence.completeFrame(id, it == null) }

        completion.onCaptured()
        completion.onCameraFinished()
        sequence.cancel()

        assertTrue(sequence.isCaptureInProgress)
        assertNull(sequence.start(1))

        completion.onSaved()

        assertFalse(shouldContinue)
        assertFalse(sequence.isCaptureInProgress)
    }

    @Test
    fun `duplicate callbacks from a saved frame cannot finish the next burst frame`() {
        val sequence = CaptureSequence()
        val id = sequence.start(2)!!
        assertTrue(sequence.beginFrame(id))
        var completions = 0
        val first = CaptureCompletion {
            completions++
            assertTrue(sequence.completeFrame(id, it == null))
        }
        first.onCaptured()
        first.onCameraFinished()
        first.onSaved()
        assertTrue(sequence.beginFrame(id))
        val second = CaptureCompletion {
            completions++
            assertFalse(sequence.completeFrame(id, it == null))
        }

        first.onSaved()
        first.onCameraFinished()
        first.abort(IOException("Late abort"))

        assertEquals(1, completions)
        assertTrue(sequence.isCaptureInProgress)

        second.onCaptured()
        second.onSaved()
        second.onCameraFinished()

        assertEquals(2, completions)
        assertFalse(sequence.isCaptureInProgress)
    }

    @Test
    fun `missing capture metadata is not saved success`() {
        val results = mutableListOf<Exception?>()
        val completion = CaptureCompletion { results.add(it) }

        completion.onCameraFinished()
        completion.onCaptured()
        completion.onSaved()

        assertTrue(completion.isComplete)
        assertEquals(1, results.size)
        assertTrue(results.single() is IllegalStateException)
    }

    @Test
    fun `aborted capture finishes exactly once without waiting for saving`() {
        val results = mutableListOf<Exception?>()
        val completion = CaptureCompletion { results.add(it) }
        val error = IOException("Capture aborted")

        completion.abort(error)
        completion.onCameraFinished()
        completion.onSaved()
        completion.abort(IOException("Duplicate abort"))

        assertTrue(completion.isComplete)
        assertSame(error, results.single())
    }

    @Test
    fun `missing image timeout releases capture admission as a failure`() {
        val sequence = CaptureSequence()
        val id = sequence.start(2)!!
        assertTrue(sequence.beginFrame(id))
        val results = mutableListOf<Exception?>()
        val completion = CaptureCompletion {
            results.add(it)
            sequence.completeFrame(id, it == null)
        }
        val error = IOException("Image timeout")

        completion.onCaptured()
        completion.onCameraFinished()
        completion.abort(error)
        completion.onSaved()

        assertSame(error, results.single())
        assertFalse(sequence.isCaptureInProgress)
    }

    @Test
    fun `first failure is preserved even if a late save succeeds`() {
        val results = mutableListOf<Exception?>()
        val completion = CaptureCompletion { results.add(it) }
        val error = IOException("Image buffer lost")

        completion.onCaptured()
        completion.onFailed(error)
        completion.onFailed(IOException("Another failure"))
        completion.onSaved()
        completion.onCameraFinished()

        assertSame(error, results.single())
    }

    @Test
    fun `camera failure after saving cannot report success`() {
        val results = mutableListOf<Exception?>()
        val completion = CaptureCompletion { results.add(it) }
        val error = IOException("Camera failure")

        completion.onCaptured()
        completion.onSaved()
        completion.onFailed(error)
        completion.onCameraFinished()

        assertSame(error, results.single())
    }

    @Test
    fun `discarded camera callbacks cannot affect a replacement capture`() {
        val results = mutableListOf<Exception?>()
        val old = CaptureCompletion { results.add(it) }
        old.onCaptured()
        old.discard()
        val replacement = CaptureCompletion { results.add(it) }

        old.onCameraFinished()
        old.onSaved()
        old.abort(IOException("Stale failure"))

        assertTrue(results.isEmpty())
        assertFalse(replacement.isComplete)

        replacement.onCaptured()
        replacement.onCameraFinished()
        replacement.onSaved()

        assertNull(results.single())
    }
}
