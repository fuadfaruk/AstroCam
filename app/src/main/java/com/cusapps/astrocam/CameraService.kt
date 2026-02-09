package com.cusapps.astrocam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaActionSound
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.media.session.MediaButtonReceiver
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@ExperimentalCamera2Interop
class CameraService : LifecycleService() {

    private var imageCapture: ImageCapture? = null
    private var mediaSession: MediaSessionCompat? = null
    private var notificationManager: NotificationManager? = null
    private var notificationBuilder: NotificationCompat.Builder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isDestroyed = false
    
    private val shutterSound = MediaActionSound()
    
    // Manual settings state
    private var manualMode = false
    private var iso = 400
    private var shutterSpeed = 1_000_000_000L // 1s
    private var focusDistance = 0f
    
    @OptIn(ExperimentalCamera2Interop::class)
    private var cameraControl: Camera2CameraControl? = null

    companion object {
        private const val TAG = "CameraService"
        private const val CHANNEL_ID = "CameraServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        
        const val ACTION_UPDATE_SETTINGS = "com.cusapps.astrocam.UPDATE_SETTINGS"
        const val EXTRA_MANUAL_MODE = "extra_manual_mode"
        const val EXTRA_ISO = "extra_iso"
        const val EXTRA_SHUTTER = "extra_shutter"
        const val EXTRA_FOCUS = "extra_focus"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        val notification = createNotification()
        
        // Preload shutter sound
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AstroCam:CaptureLock")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        startCamera()
        initializeMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_SETTINGS) {
            manualMode = intent.getBooleanExtra(EXTRA_MANUAL_MODE, false)
            iso = intent.getIntExtra(EXTRA_ISO, 400)
            shutterSpeed = intent.getLongExtra(EXTRA_SHUTTER, 1_000_000_000L)
            focusDistance = intent.getFloatExtra(EXTRA_FOCUS, 0f)
            Log.d(TAG, "Settings updated: Manual=$manualMode, ISO=$iso, Shutter=$shutterSpeed")
            applyManualSettingsIfReady()
        }
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return super.onStartCommand(intent, flags, startId)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Camera Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AstroCam Background Mode")
            .setContentText("Ready to capture even when screen is off")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        return notificationBuilder!!.build()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            if (isDestroyed) return@addListener
            
            val cameraProvider: ProcessCameraProvider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
                return@addListener
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            // Check if RAW is supported
            val cameraInfo = cameraProvider.availableCameraInfos.firstOrNull {
                cameraSelector.filter(listOf(it)).isNotEmpty()
            }
            
            val isRawSupported = cameraInfo?.let {
                val characteristics = Camera2CameraInfo.from(it).getCameraCharacteristic(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
                )
                characteristics?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            } ?: false

            val builder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            
            if (isRawSupported) {
                builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                Log.d(TAG, "Enabling RAW+JPEG capture")
            } else {
                Log.d(TAG, "RAW not supported on this device, falling back to JPEG")
            }

            imageCapture = builder.build()

            try {
                // Unbind any previous use cases before binding new ones
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, imageCapture
                )
                cameraControl = Camera2CameraControl.from(camera.cameraControl)
                applyManualSettingsIfReady()
                Log.d(TAG, "Camera bound to service lifecycle")
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyManualSettingsIfReady(forceShutter: Boolean = false) {
        val control = cameraControl ?: return
        if (manualMode) {
            val builder = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            
            if (forceShutter) {
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterSpeed)
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, shutterSpeed)
            } else {
                val safeShutter = CameraUtils.getPreviewExposureTime(shutterSpeed)
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, safeShutter)
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, CameraUtils.getPreviewFrameDuration(safeShutter))
            }
            control.captureRequestOptions = builder.build()
        } else {
            control.captureRequestOptions = CaptureRequestOptions.Builder().build()
        }
    }

    private fun initializeMediaSession() {
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                        takePhoto()
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
                override fun onPlay() = takePhoto()
                override fun onPause() = takePhoto()
            })
            setPlaybackState(PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                .build())
            isActive = true
        }
    }

    private fun takePhoto() {
        wakeLock?.acquire(10 * 60 * 1000L /*10 minutes max*/)
        Log.d(TAG, "Taking photo in background.")
        notificationBuilder?.setContentText("Capturing image...")
        notificationManager?.notify(NOTIFICATION_ID, notificationBuilder?.build())
        capturePhoto()
    }

    private fun capturePhoto() {
        val imageCapture = imageCapture ?: run {
            resetNotification("Camera not ready.")
            wakeLock?.release()
            return
        }

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        
        // Use File-based output for RAW_JPEG as it's more reliable for multiple files
        val outputDirectory = getExternalFilesDir(null) ?: filesDir
        val photoFile = File(outputDirectory, "$name.jpg")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        applyManualSettingsIfReady(true)
        
        // Play shutter sound
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    resetNotification("Capture failed: ${exc.message}")
                    applyManualSettingsIfReady(false)
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Photo capture succeeded. JPEG: ${photoFile.absolutePath}")
                    
                    val rawFile = File(photoFile.absolutePath.replace(".jpg", ".dng"))
                    if (rawFile.exists()) {
                        Log.d(TAG, "RAW file saved: ${rawFile.absolutePath}")
                        resetNotification("Photo saved (RAW+JPEG).")
                        addToMediaStore(rawFile, "image/x-adobe-dng")
                    } else {
                        Log.w(TAG, "RAW file NOT found")
                        resetNotification("Photo saved (JPEG).")
                    }
                    
                    addToMediaStore(photoFile, "image/jpeg")

                    applyManualSettingsIfReady(false)
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                }
            }
        )
    }

    private fun addToMediaStore(file: File, mimeType: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AstroCam")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = contentResolver.insert(collection, values)
        
        uri?.let { targetUri ->
            try {
                contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(targetUri, values, null, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write to MediaStore", e)
            }
        }
    }

    private fun resetNotification(text: String) {
        notificationBuilder?.setContentText(text)
        notificationManager?.notify(NOTIFICATION_ID, notificationBuilder?.build())
        Handler(Looper.getMainLooper()).postDelayed({
            notificationBuilder?.setContentText("Ready to capture even when screen is off")
            notificationManager?.notify(NOTIFICATION_ID, notificationBuilder?.build())
        }, 3000)
    }

    override fun onDestroy() {
        isDestroyed = true
        super.onDestroy()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        mediaSession?.release()
        shutterSound.release()
        
        // Explicitly unbind everything when service is destroyed
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding on destroy", e)
        }
    }
}
