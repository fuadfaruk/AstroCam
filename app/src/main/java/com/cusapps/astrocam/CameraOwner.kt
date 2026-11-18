package com.cusapps.astrocam

import android.annotation.SuppressLint
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Keeps the ownership lease alive through asynchronous camera open and close callbacks. */
internal class CameraOwner(private val owner: CameraOwnership.Owner) {
    companion object {
        internal val ownership = CameraOwnership()
        private const val TAG = "CameraOwner"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var request: CameraOwnership.Request? = null
    val isOpeningOrOpen: Boolean get() = request != null

    @SuppressLint("MissingPermission")
    fun open(
        manager: CameraManager,
        cameraId: String,
        onOpened: (CameraDevice) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (request != null) return
        val next = CameraOwnership.Request(owner) { lease ->
            try {
                Log.d(TAG, "Opening camera $cameraId for $owner")
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (ownership.opened(lease, camera::close)) onOpened(camera)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        fail(camera, CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        fail(camera, CameraAccessException(CameraAccessException.CAMERA_ERROR,
                            "Camera $cameraId failed (error $error)."))
                    }

                    private fun fail(camera: CameraDevice, error: Exception) {
                        ownership.opened(lease, camera::close)
                        ownership.close(lease)
                        if (request !== lease) return
                        request = null
                        onFailure(error)
                    }

                    override fun onClosed(camera: CameraDevice) {
                        if (request === lease) request = null
                        Log.d(TAG, "Camera $cameraId closed for $owner")
                        ownership.release(lease)
                    }
                }, mainHandler)
            } catch (e: Exception) {
                try {
                    if (request === lease) {
                        request = null
                        onFailure(e)
                    }
                } finally {
                    ownership.release(lease)
                }
            }
        }
        request = next
        ownership.acquire(next)
    }

    fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val previous = request ?: return
        request = null
        ownership.close(previous)
    }
}
