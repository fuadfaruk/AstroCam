package com.cusapps.astrocam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaActionSound
import android.os.*
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import java.util.*

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
    private var whiteBalance = CameraUtils.WhiteBalance()
    
    private var currentCameraId: String? = null
    
    @Volatile private var cameraDevice: CameraDevice? = null
    @Volatile private var captureSession: CameraCaptureSession? = null
    @Volatile private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val cameraOwner = CameraOwner(CameraOwnership.Owner.SERVICE)
    private val captureSequence = CaptureSequence()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isStopping = false
    internal var isForegroundReady = false
        private set
    private var isPrepared = false
    private val binder = LocalBinder()

    internal inner class LocalBinder : Binder() {
        val service: CameraService get() = this@CameraService
    }
    private val notificationReset = Runnable {
        updateNotification(
            if (captureSession == null) "Camera not ready."
            else "Ready to capture even when screen is off"
        )
    }

    // Accessed only on the camera background handler.
    private var pendingCapture: PhotoCaptureHelper.PendingCapture? = null

    companion object {
        private const val TAG = "CameraService"
        private const val CHANNEL_ID = "CameraServiceChannel"
        private const val NOTIFICATION_ID = 1
        
        const val ACTION_PREPARE = "com.cusapps.astrocam.PREPARE_CAMERA"
        const val ACTION_CANCEL_CAPTURE = "com.cusapps.astrocam.CANCEL_CAPTURE"
        const val EXTRA_MANUAL_MODE = "extra_manual_mode"
        const val EXTRA_RAW_MODE = "extra_raw_mode"
        const val EXTRA_ISO = "extra_iso"
        const val EXTRA_SHUTTER = "extra_shutter"
        const val EXTRA_FOCUS = "extra_focus"
        const val EXTRA_WB_LOCKED = "extra_wb_locked"
        const val EXTRA_WB_TEMPERATURE = "extra_wb_temperature"
        const val EXTRA_WB_MANUAL_SUPPORTED = "extra_wb_manual_supported"
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

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundReady = true
        } catch (e: RuntimeException) {
            Log.e(TAG, "Camera foreground service is not allowed", e)
            stopSelf()
            return
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
        backgroundThread = null
        backgroundHandler = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isForegroundReady) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PREPARE) {
            isPrepared = true
            return START_NOT_STICKY
        }
        if (!isPrepared) {
            // Neither a restart nor a stale notification/media action may open the camera.
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL_CAPTURE) {
            cancelCapture()
            return START_NOT_STICKY
        }
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_NOT_STICKY
    }

    internal fun activateCamera(intent: Intent) {
        if (!isForegroundReady || isStopping) return
        closeCamera()
        manualMode = intent.getBooleanExtra(EXTRA_MANUAL_MODE, false)
        rawMode = intent.getBooleanExtra(EXTRA_RAW_MODE, false)
        iso = intent.getIntExtra(EXTRA_ISO, 400)
        shutterSpeed = intent.getLongExtra(EXTRA_SHUTTER, 1_000_000_000L)
        focusDistance = intent.getFloatExtra(EXTRA_FOCUS, 0f)

        // The service has no preview, so its AWB never converges on its own. The
        // locked gains must come across from the activity to keep background
        // frames colour-matched to the rest of the sequence.
        whiteBalance = CameraUtils.WhiteBalance(
            locked = intent.getBooleanExtra(EXTRA_WB_LOCKED, false),
            temperatureK = intent.getIntExtra(EXTRA_WB_TEMPERATURE, CameraUtils.WB_DEFAULT_TEMPERATURE_K),
            manualGainsSupported = intent.getBooleanExtra(EXTRA_WB_MANUAL_SUPPORTED, false)
        )

        currentCameraId = intent.getStringExtra(EXTRA_CAMERA_ID)
        mediaSession?.isActive = true
        openCamera()

        Log.d(TAG, "Settings updated: Manual=$manualMode, RAW=$rawMode, ISO=$iso, Shutter=$shutterSpeed, WBLocked=${whiteBalance.locked}, WBTemp=${whiteBalance.temperatureK}")
    }

    internal fun pauseCamera() {
        mediaSession?.isActive = false
        closeCamera()
        updateNotification("Background mode armed; using app preview.")
    }

    private fun openCamera() {
        if (isStopping || !isForegroundReady || cameraOwner.isOpeningOrOpen) return
        val cid = currentCameraId ?: return
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = manager.getCameraCharacteristics(cid)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            
            val isRawSupported = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) ?: false
            
            val format = if (rawMode && isRawSupported) ImageFormat.RAW_SENSOR else ImageFormat.JPEG
            val largest = Collections.max(map.getOutputSizes(format).toList(), CompareSizesByArea())
            
            imageReader = ImageReader.newInstance(largest.width, largest.height, format, 2).apply {
                setOnImageAvailableListener(listener@{ reader ->
                    if (reader !== imageReader) return@listener
                    val image = try {
                        reader.acquireNextImage()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to acquire captured image", e)
                        pendingCapture?.onImageAcquisitionFailed(e)
                        null
                    } ?: return@listener
                    val pending = pendingCapture
                    if (pending == null) {
                        image.close()
                    } else {
                        pending.onImageAvailable(image)
                    }
                }, backgroundHandler)
            }

            cameraOwner.open(manager, cid,
                onOpened = { camera ->
                    cameraDevice = camera
                    createCaptureSession()
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to open camera in service", error)
                    closeCamera()
                    updateNotification("Background camera unavailable: ${error.message}")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera in service", e)
            closeCamera()
            updateNotification("Background camera unavailable: ${e.message}")
        }
    }

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        try {
            device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (device !== cameraDevice) {
                        session.close()
                        return
                    }
                    captureSession = session
                    if (!captureSequence.isCaptureInProgress) {
                        mainHandler.removeCallbacks(notificationReset)
                        updateNotification("Ready to capture even when screen is off")
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (device !== cameraDevice) return
                    Log.e(TAG, "Failed to configure capture session")
                    closeCamera()
                    updateNotification("Background camera configuration failed.")
                }
            }, mainHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session", e)
            closeCamera()
            updateNotification("Background camera configuration failed: ${e.message}")
        }
    }

    private fun closeCamera() {
        captureSequence.cancel()
        mainHandler.removeCallbacks(notificationReset)
        captureSession?.close()
        captureSession = null
        cameraDevice = null
        val reader = imageReader
        imageReader = null
        val cleanup = Runnable {
            pendingCapture?.close()
            pendingCapture = null
            reader?.close()
        }
        val handler = backgroundHandler
        if (handler == null) cleanup.run() else handler.post(cleanup)
        cameraOwner.close()
        captureSequence.reset()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        updateNotification("Camera not ready.")
    }

    private fun initializeMediaSession() {
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                        if (keyEvent.repeatCount == 0) {
                            if (keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_STOP) cancelCapture()
                            else takePhoto()
                        }
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
                override fun onPlay() = takePhoto()
                override fun onPause() = takePhoto()
                override fun onStop() = cancelCapture()
            }, mainHandler)
            setPlaybackState(PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP)
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                .build())
            isActive = false
        }
    }

    private fun takePhoto() {
        val captureId = captureSequence.start(1) ?: return
        captureSequence.beginFrame(captureId)
        mainHandler.removeCallbacks(notificationReset)
        wakeLock?.acquire(10 * 60 * 1000L)
        Log.d(TAG, "Taking photo in background.")
        
        updateNotification("Capturing image...")
        
        capturePhoto(captureId)
    }

    private fun capturePhoto(captureId: Long) {
        val device = cameraDevice
        val session = captureSession
        val reader = imageReader
        val cid = currentCameraId
        val handler = backgroundHandler
        if (device == null || session == null || reader == null || cid == null || handler == null) {
            finishCapture(captureId, false, "Camera not ready.")
            return
        }

        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = manager.getCameraCharacteristics(cid)
            val settings = PhotoCaptureHelper.CaptureSettings(manualMode, iso, shutterSpeed, focusDistance, whiteBalance)
            val captureBuilder = PhotoCaptureHelper.createCaptureRequest(device, listOf(reader.surface), settings)

            val pending = PhotoCaptureHelper.PendingCapture(this, characteristics, handler,
                onCameraFinished = {
                    mainHandler.post {
                        if (session === captureSession && captureSequence.isActive(captureId)) {
                            updateNotification("Saving image...")
                        }
                    }
                }
            ) { error ->
                mainHandler.post {
                    if (session !== captureSession) return@post
                    if (error != null) Log.e(TAG, "Capture or save failed in background", error)
                    finishCapture(captureId, error == null,
                        if (error == null) "Photo saved." else "Capture or save failed: ${error.message}")
                }
            }
            if (!handler.post {
                if (session !== captureSession || reader !== imageReader) {
                    pending.close()
                    return@post
                }
                pendingCapture?.close()
                pendingCapture = pending
                try {
                    shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                    session.capture(captureBuilder.build(), pending, handler)
                } catch (e: Exception) {
                    pending.abort(e)
                }
            }) {
                finishCapture(captureId, false, "Camera handler is not running.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed in background", e)
            finishCapture(captureId, false, "Capture failed: ${e.message}")
        }
    }

    private fun finishCapture(captureId: Long, success: Boolean, text: String) {
        if (!captureSequence.owns(captureId)) return
        val cancelled = captureSequence.isCancellationRequested
        captureSequence.completeFrame(captureId, success)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        resetNotification(if (cancelled && success) "Capture stopped." else text)
    }

    private fun cancelCapture() {
        if (!captureSequence.isCaptureInProgress || captureSequence.isCancellationRequested) return
        captureSequence.cancel()
        updateNotification("Stopping after current photo...")
    }

    private fun updateNotification(text: String) {
        if (isStopping) return
        notificationBuilder?.setContentText(text)
        notificationManager?.notify(NOTIFICATION_ID, notificationBuilder?.build())
    }

    private fun resetNotification(text: String) {
        mainHandler.removeCallbacks(notificationReset)
        updateNotification(text)
        mainHandler.postDelayed(notificationReset, 3000)
    }

    override fun onDestroy() {
        isStopping = true
        isForegroundReady = false
        super.onDestroy()
        closeCamera()
        stopBackgroundThread()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        mediaSession?.release()
        mainHandler.removeCallbacksAndMessages(null)
        shutterSound.release()
    }

    override fun onBind(intent: Intent?): IBinder = binder

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
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, CameraService::class.java).setAction(ACTION_CANCEL_CAPTURE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AstroCam Background Mode")
            .setContentText("Camera not ready.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.cancel_capture), cancelIntent)
        return notificationBuilder!!.build()
    }
}
