package com.cusapps.astrocam

/** Capture admission and burst state. All access must stay on the main thread. */
internal class CaptureSequence {
    private class Capture(val id: Long, var remaining: Int) {
        var frameInProgress = false
        var cancelled = false
    }

    private var nextId = 0L
    private var capture: Capture? = null

    val isCaptureInProgress: Boolean get() = capture != null
    val isCancellationRequested: Boolean get() = capture?.cancelled == true

    fun start(burstCount: Int): Long? {
        if (isCaptureInProgress) return null
        require(burstCount > 0)
        val id = ++nextId
        capture = Capture(id, burstCount)
        return id
    }

    fun owns(id: Long): Boolean = capture?.id == id

    fun isActive(id: Long): Boolean = owns(id) && !isCancellationRequested

    fun beginFrame(id: Long): Boolean {
        val current = capture ?: return false
        if (!isActive(id) || current.frameInProgress) return false
        current.frameInProgress = true
        return true
    }

    /** Returns whether another frame should be scheduled. */
    fun completeFrame(id: Long, success: Boolean): Boolean {
        val current = capture ?: return false
        if (!owns(id) || !current.frameInProgress) return false
        current.frameInProgress = false
        if (success) current.remaining--
        if (current.cancelled || !success || current.remaining == 0) {
            capture = null
            return false
        }
        return true
    }

    fun cancel() {
        val current = capture ?: return
        current.cancelled = true
        // A submitted exposure must finish before another sequence is admitted.
        if (!current.frameInProgress) capture = null
    }

    /** Only reset after closing the camera; old callbacks retain their old IDs. */
    fun reset() {
        capture = null
    }
}
