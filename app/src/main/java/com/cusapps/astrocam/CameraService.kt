package com.cusapps.astrocam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.media.session.MediaButtonReceiver
import java.text.SimpleDateFormat
import java.util.Locale

class CameraService : LifecycleService() {

    private var imageCapture: ImageCapture? = null
    private var mediaSession: MediaSessionCompat? = null
    private var notificationManager: NotificationManager? = null
    private var notificationBuilder: NotificationCompat.Builder? = null

    companion object {
        private const val TAG = "CameraService"
        private const val CHANNEL_ID = "CameraServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10 (Q) introduced foreground service types.
            // Android 14 (U) requires them to be explicitly passed if declared in manifest.
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
            .setContentTitle("Camera Service")
            .setContentText("Listening for media button to take photo")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        return notificationBuilder!!.build()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
                return@addListener
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, imageCapture
                )
                Log.d(TAG, "Camera bound to service lifecycle")
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun initializeMediaSession() {
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                        Log.d(TAG, "Media button pressed: ${keyEvent.keyCode}")
                        takePhoto()
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }

                override fun onPlay() {
                    takePhoto()
                }

                override fun onPause() {
                    takePhoto()
                }
            })

            val state = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE)
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                .build()
            setPlaybackState(state)
            isActive = true
        }
    }

    private fun takePhoto() {
        Log.d(TAG, "Starting 3-second timer for photo.")
        object : CountDownTimer(3000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = (millisUntilFinished / 1000) + 1
                Log.d(TAG, "Countdown: $secondsRemaining seconds remaining.")
                notificationBuilder?.setContentText("Taking photo in $secondsRemaining...")
                notificationManager?.notify(NOTIFICATION_ID, notificationBuilder?.build())
            }

            override fun onFinish() {
                Log.d(TAG, "Timer finished. Taking photo.")
                notificationBuilder?.setContentText("Capturing...")
                notificationManager?.notify(NOTIFICATION_ID, notificationBuilder?.build())
                capturePhoto()
            }
        }.start()
    }

    private fun capturePhoto() {
        val imageCapture = imageCapture ?: run {
            Log.e(TAG, "ImageCapture is null")
            resetNotification("ImageCapture not ready.")
            return
        }

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    resetNotification("Capture failed.")
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Photo capture succeeded: ${output.savedUri}")
                    resetNotification("Photo saved.")
                }
            }
        )
    }

    private fun resetNotification(text: String) {
        notificationBuilder?.setContentText(text)
        notificationManager?.notify(NOTIFICATION_ID, notificationBuilder?.build())
        // Reset to default after a bit
        Handler(Looper.getMainLooper()).postDelayed({
            notificationBuilder?.setContentText("Listening for media button to take photo")
            notificationManager?.notify(NOTIFICATION_ID, notificationBuilder?.build())
        }, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession?.release()
    }
}
