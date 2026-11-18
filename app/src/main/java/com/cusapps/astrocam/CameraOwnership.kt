package com.cusapps.astrocam

/** Process-wide camera admission. All events must run on the main thread. */
internal class CameraOwnership {
    enum class Owner { ACTIVITY, SERVICE }

    class Request(val owner: Owner, val onGranted: (Request) -> Unit) {
        internal var closing = false
        internal var closeDevice: (() -> Unit)? = null
    }

    private val waiting = ArrayDeque<Request>()
    private var current: Request? = null
    val currentOwner: Owner? get() = current?.owner

    fun acquire(request: Request) {
        check(!request.closing && request !== current && request !in waiting)
        waiting.addLast(request)
        grantNext()
    }

    /** A cancelled open must close its late device instead of publishing it. */
    fun opened(request: Request, closeDevice: () -> Unit): Boolean {
        request.closeDevice = closeDevice
        if (request.closing || request !== current) {
            closeDevice()
            return false
        }
        return true
    }

    fun close(request: Request) {
        if (request.closing) return
        request.closing = true
        waiting.remove(request)
        request.closeDevice?.invoke()
    }

    /** Release only on CameraDevice.onClosed, or a synchronous openCamera failure. */
    fun release(request: Request) {
        if (request !== current) return
        current = null
        request.closeDevice = null
        grantNext()
    }

    private fun grantNext() {
        if (current != null || waiting.isEmpty()) return
        val next = waiting.removeFirst()
        current = next
        next.onGranted(next)
    }
}
