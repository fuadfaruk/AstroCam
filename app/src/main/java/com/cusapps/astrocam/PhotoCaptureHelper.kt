package com.cusapps.astrocam

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.media.ImageReader
import android.media.MediaActionSound
import android.os.Handler
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * PhotoCaptureHelper centralizes the logic for saving a photo captured via Camera2's ImageReader.
 */
object PhotoCaptureHelper {
    private const val TAG = "PhotoCaptureHelper"
    private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

    data class CaptureSettings(
        val isManualMode: Boolean,
        val iso: Int,
        val shutterSpeed: Long,
        val focusDistance: Float
    )

    /**
     * Logic to save an Image from ImageReader.
     * Supports both JPEG and RAW_SENSOR formats.
     */
    fun saveImage(
        context: Context,
        image: Image,
        characteristics: CameraCharacteristics? = null,
        captureResult: CaptureResult? = null,
        onImageSaved: (File) -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val outputDirectory = context.getExternalFilesDir(null) ?: context.filesDir
        
        val extension = when (image.format) {
            ImageFormat.JPEG -> "jpg"
            ImageFormat.RAW_SENSOR -> "dng"
            else -> "jpg"
        }
        
        val photoFile = File(outputDirectory, "$name.$extension")

        try {
            if (image.format == ImageFormat.RAW_SENSOR && characteristics != null && captureResult != null) {
                // Use DngCreator for RAW files
                FileOutputStream(photoFile).use { fos ->
                    DngCreator(characteristics, captureResult).use { dngCreator ->
                        dngCreator.writeImage(fos, image)
                    }
                }
            } else {
                // Default to writing bytes (JPEG or other)
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                FileOutputStream(photoFile).use { it.write(bytes) }
            }
            
            StorageUtils.addToMediaStore(context, photoFile, if (extension == "jpg") "image/jpeg" else "image/x-adobe-dng")
            onImageSaved(photoFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image", e)
            onError(e)
        } finally {
            image.close()
        }
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
        
        // High quality settings
        builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        
        return builder
    }
}
