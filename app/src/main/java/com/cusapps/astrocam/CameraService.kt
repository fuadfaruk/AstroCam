package com.cusapps.astrocam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.MediaActionSound
import android.os.*
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * CameraService is a Foreground Service that allows the app to keep the camera active
 * even when the screen is off. Reconstructed with Camera2 API.
 * Now supports RAW capture.
 */
class CameraService : Service() {

    private var mediaSession: MediaSessionCompat? = null
    private var notificationManager: NotificationManager? = null
    private var notificationBuilder: NotificationCompat.Builder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val shutterSound = MediaActionSound()
    
    private var manualMode = false
    private var rawMode = false
    private var iso = 400
    private var shutterSpeed = 1_000_000_000L
    private var focusDistance = 0f
    
    private var currentCameraId: String? = null
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val cameraOpenCloseLock = Semaphore(1)

    // Maps to link images with their capture results for RAW DNG creation
    private val captureResults = mutableMapOf<Long, TotalCaptureResult>()
    private val capturedImages = mutableMapOf<Long, Image>()

    companion object {
        private const val TAG = "CameraService"
        private const val CHANNEL_ID = "CameraServiceChannel"
        private const val NOTIFICATION_ID = 1
        
        const val ACTION_UPDATE_SETTINGS = "com.cusapps.astrocam.UPDATE_SETTINGS"
        const val EXTRA_MANUAL_MODE = "extra_manual_mode"
        const val EXTRA_RAW_MODE = "extra_raw_mode"
        const val EXTRA_ISO = "extra_iso"
        const val EXTRA_SHUTTER = "extra_shutter"
        const val EXTRA_FOCUS = "extra_focus"
        const val EXTRA_CAMERA_ID = "extra_camera_id"
    }

    override fun onCreate() {
        super.onCreate()
        
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AstroCam:CaptureWakeLock")
        
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        val notification = createNotification()
        
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        startBackgroundThread()
        initializeMediaSession()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraServiceBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while stopping background thread", e)
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_SETTINGS) {
            manualMode = intent.getBooleanExtra(EXTRA_MANUAL_MODE, false)
            rawMode = intent.getBooleanExtra(EXTRA_RAW_MODE, false)
            iso = intent.getIntExtra(EXTRA_ISO, 400)
            shutterSpeed = intent.getLongExtra(EXTRA_SHUTTER, 1_000_000_000L)
            focusDistance = intent.getFloatExtra(EXTRA_FOCUS, 0f)
            
            val newCameraId = intent.getStringExtra(EXTRA_CAMERA_ID)
            
            if (newCameraId != null && newCameraId != currentCameraId) {
                currentCameraId = newCameraId
                closeCamera()
                openCamera()
            }

            Log.d(TAG, "Settings updated: Manual=$manualMode, RAW=$rawMode, ISO=$iso, Shutter=$shutterSpeed")
        }
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_NOT_STICKY
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun openCamera() {
        val cid = currentCameraId ?: return
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                return
            }
            
            val characteristics = manager.getCameraCharacteristics(cid)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            
            val isRawSupported = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) ?: false
            
            val format = if (rawMode && isRawSupported) ImageFormat.RAW_SENSOR else ImageFormat.JPEG
            val largest = Collections.max(map.getOutputSizes(format).toList(), CompareSizesByArea())
            
            imageReader = ImageReader.newInstance(largest.width, largest.height, format, 2).apply {
                setOnImageAvailableListener({ reader ->
                    backgroundHandler?.post {
                        val image = try { reader.acquireNextImage() } catch (e: Exception) { null } ?: return@post
                        val timestamp = image.timestamp
                        val result = captureResults.remove(timestamp)
                        if (result != null) {
                            processImage(image, result, characteristics)
                        } else {
                            capturedImages[timestamp] = image
                        }
                    }
                }, backgroundHandler)
            }

            manager.openCamera(cid, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    createCaptureSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera in service", e)
            cameraOpenCloseLock.release()
        }
    }

    private fun processImage(image: Image, result: TotalCaptureResult, characteristics: CameraCharacteristics) {
        PhotoCaptureHelper.saveImage(this@CameraService, image, 
            characteristics = characteristics,
            captureResult = result,
            onImageSaved = { Log.d(TAG, "Photo saved in background") },
            onError = { e -> Log.e(TAG, "Error saving photo in background", e) }
        )
    }

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        try {
            device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Failed to configure capture session")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session", e)
        }
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            captureResults.clear()
            capturedImages.values.forEach { it.close() }
            capturedImages.clear()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while closing camera", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun initializeMediaSession() {
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
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
        wakeLock?.acquire(10 * 60 * 1000L)
        Log.d(TAG, "Taking photo in background.")
        
        notificationBuilder?.setContentText("Capturing image...")
        notificationManager?.notify(NOTIFICATION_ID, notificationBuilder?.build())
        
        capturePhoto()
    }

    private fun capturePhoto() {
        val device = cameraDevice ?: run {
            resetNotification("Camera not ready.")
            if (wakeLock?.isHeld == true) wakeLock?.release()
            return
        }
        val session = captureSession ?: run {
            resetNotification("Session not ready.")
            if (wakeLock?.isHeld == true) wakeLock?.release()
            return
        }
        val reader = imageReader ?: return

        try {
            val settings = PhotoCaptureHelper.CaptureSettings(manualMode, iso, shutterSpeed, focusDistance)
            val captureBuilder = PhotoCaptureHelper.createCaptureRequest(device, listOf(reader.surface), settings)

            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)!!
                    val image = capturedImages.remove(timestamp)
                    if (image != null) {
                        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                        val characteristics = manager.getCameraCharacteristics(currentCameraId!!)
                        processImage(image, result, characteristics)
                    } else {
                        captureResults[timestamp] = result
                    }
                    resetNotification("Photo saved.")
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    resetNotification("Capture failed.")
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Capture failed in background", e)
            resetNotification("Capture failed: ${e.message}")
            if (wakeLock?.isHeld == true) wakeLock?.release()
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
        super.onDestroy()
        closeCamera()
        stopBackgroundThread()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        mediaSession?.release()
        shutterSound.release()
    }

    override fun onBind(intent: Intent?) = null

    private class CompareSizesByArea : Comparator<android.util.Size> {
        override fun compare(lhs: android.util.Size, rhs: android.util.Size): Int =
            java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Camera Service Channel", NotificationManager.IMPORTANCE_LOW)
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
}
