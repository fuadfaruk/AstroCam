package com.cusapps.astrocam

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.net.Uri
import android.os.Handler
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * PhotoCaptureHelper centralizes the logic for saving a photo captured via Camera2's ImageReader.
 */
object PhotoCaptureHelper {
    private const val TAG = "PhotoCaptureHelper"
    private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    private const val IMAGE_TIMEOUT_MILLIS = 30_000L

    data class CaptureSettings(
        val isManualMode: Boolean,
        val iso: Int,
        val shutterSpeed: Long,
        val focusDistance: Float,
        val whiteBalance: CameraUtils.WhiteBalance = CameraUtils.WhiteBalance()
    )

    /** Owns image/result pairing and completion for a single still request on the camera handler. */
    internal class PendingCapture(
        private val context: Context,
        private val characteristics: CameraCharacteristics,
        private val handler: Handler,
        private val onCameraFinished: () -> Unit = {},
        onComplete: (Exception?) -> Unit
    ) : CameraCaptureSession.CaptureCallback() {
        private val images = mutableMapOf<Long, Image>()
        private var captureResult: TotalCaptureResult? = null
        private var imageProcessed = false
        private val completion: CaptureCompletion = CaptureCompletion { error ->
            handler.removeCallbacks(imageTimeout)
            closeImages()
            onComplete(error)
        }
        private val imageTimeout: Runnable = Runnable {
            completion.abort(IOException("Timed out waiting for the captured image."))
        }

        fun onImageAvailable(image: Image) {
            if (completion.isComplete || imageProcessed) {
                image.close()
                return
            }
            val result = captureResult
            if (result == null) {
                images.put(image.timestamp, image)?.close()
            } else if (image.timestamp == result.get(CaptureResult.SENSOR_TIMESTAMP)) {
                save(image, result)
            } else {
                image.close()
            }
        }

        fun onImageAcquisitionFailed(error: Exception) {
            completion.onFailed(error)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (completion.isComplete || captureResult != null) return
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
            if (timestamp == null) {
                completion.onFailed(IOException("Capture result is missing the image timestamp."))
                return
            }
            captureResult = result
            completion.onCaptured()
            val image = images.remove(timestamp)
            closeImages()
            if (image != null) save(image, result)
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            completion.onFailed(IOException("Capture failed (reason ${failure.reason})."))
        }

        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            completion.onFailed(IOException("The captured image buffer was lost."))
        }

        override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
            if (completion.isComplete) return
            onCameraFinished()
            completion.onCameraFinished()
            if (!completion.isComplete) handler.postDelayed(imageTimeout, IMAGE_TIMEOUT_MILLIS)
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            if (completion.isComplete) return
            onCameraFinished()
            abort(IOException("Capture aborted."))
        }

        fun abort(error: Exception) {
            completion.abort(error)
        }

        fun close() {
            completion.discard()
            handler.removeCallbacks(imageTimeout)
            closeImages()
        }

        private fun save(image: Image, result: TotalCaptureResult) {
            imageProcessed = true
            saveImage(context, image, characteristics, result,
                onImageSaved = { completion.onSaved() },
                onError = { completion.onFailed(it) }
            )
        }

        private fun closeImages() {
            images.values.forEach { image ->
                try {
                    image.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to close captured image", e)
                }
            }
            images.clear()
        }
    }

    /**
     * Logic to save an Image from ImageReader.
     * Supports both JPEG and RAW_SENSOR formats.
     * Success supplies the published gallery URI, not the temporary staging file.
     */
    fun saveImage(
        context: Context,
        image: Image,
        characteristics: CameraCharacteristics? = null,
        captureResult: CaptureResult? = null,
        onImageSaved: (Uri) -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        var photoFile: File? = null
        val saved: Result<Uri> = try {
            val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
            val outputDirectory = context.getExternalFilesDir(null) ?: context.filesDir
            val extension = if (image.format == ImageFormat.RAW_SENSOR) "dng" else "jpg"
            val file = File(outputDirectory, "$name.$extension")
            photoFile = file
            if (image.format == ImageFormat.RAW_SENSOR && characteristics != null && captureResult != null) {
                // Use DngCreator for RAW files
                FileOutputStream(file).use { fos ->
                    DngCreator(characteristics, captureResult).use { dngCreator ->
                        dngCreator.writeImage(fos, image)
                    }
                }
            } else {
                // Default to writing bytes (JPEG or other)
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                FileOutputStream(file).use { it.write(bytes) }
            }
            Result.success(StorageUtils.addToMediaStore(context, file, if (extension == "jpg") "image/jpeg" else "image/x-adobe-dng"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image", e)
            Result.failure(e)
        } finally {
            try {
                image.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close saved image", e)
            }
            try {
                photoFile?.let { file ->
                    if (file.exists() && !file.delete()) Log.w(TAG, "Failed to delete staging file: $file")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up staging file", e)
            }
        }
        saved.fold(onImageSaved) { onError(it as Exception) }
    }

    /**
     * Prepares a CaptureRequest for still image capture.
     */
    fun createCaptureRequest(
        cameraDevice: CameraDevice,
        targets: List<android.view.Surface>,
        settings: CaptureSettings
    ): CaptureRequest.Builder {
        val builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        targets.forEach { builder.addTarget(it) }
        
        if (settings.isManualMode) {
            CameraUtils.applyManualSettings(builder, settings.iso, settings.shutterSpeed, settings.focusDistance, true)
        } else {
            CameraUtils.applyAutoSettings(builder)
        }

        // Only override the still template's white balance when the lock is engaged.
        // The unlocked path should keep the device defaults, and re-applying here would
        // force COLOR_CORRECTION_MODE_FAST onto captures that never asked for it.
        if (settings.whiteBalance.locked) {
            CameraUtils.applyWhiteBalance(builder, settings.whiteBalance)
        }

        // High quality settings
        builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        
        return builder
    }
}
