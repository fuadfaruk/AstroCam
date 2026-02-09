package com.cusapps.astrocam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaActionSound
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.cusapps.astrocam.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var currentCameraId: String? = null
    private var currentCharacteristics: CameraCharacteristics? = null

    private lateinit var cameraExecutor: ExecutorService
    private var mediaSession: MediaSessionCompat? = null
    
    private val shutterSound = MediaActionSound()
    
    // Flag to detect if app was minimized by user (Home button, Recents)
    private var userMinimized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Preload shutter sound
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)

        // Set up the listeners for take photo button
        viewBinding.imageCaptureButton.setOnClickListener { startPhotoTimer() }

        // Re-bind camera when mode is toggled (restarting camera to ensure clean state)
        viewBinding.rawModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                Toast.makeText(this, "RAW capture currently disabled, using JPEG", Toast.LENGTH_SHORT).show()
            }
            startCamera()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        initializeMediaSession()
        setupTimerControl()
        setupBurstControl()
        setupRetractableControls()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        userMinimized = true
    }

    override fun onStart() {
        super.onStart()
        userMinimized = false
        
        // Stop the background service when app returns to foreground
        if (!isChangingConfigurations) {
            stopCameraService()
        }
        
        // Re-bind camera to ensure it's fresh and bound to this lifecycle.
        // This fixes the "Not bound to a valid camera" error when returning from background.
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    override fun onStop() {
        super.onStop()
        // Only start background service if:
        // 1. Manual mode is enabled
        // 2. The app is NOT being minimized (userMinimized is false when screen is locked)
        // 3. The app is NOT being exited/finished
        // 4. It's not a configuration change (rotation)
        if (viewBinding.manualModeSwitch.isChecked && !userMinimized && !isFinishing && !isChangingConfigurations) {
            startCameraService()
        }
    }

    private fun startCameraService() {
        val intent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_UPDATE_SETTINGS
            putExtra(CameraService.EXTRA_MANUAL_MODE, viewBinding.manualModeSwitch.isChecked)
            putExtra(CameraService.EXTRA_ISO, viewBinding.isoSeekBar.progress)
            putExtra(CameraService.EXTRA_SHUTTER, getShutterSpeed())
            putExtra(CameraService.EXTRA_FOCUS, getFocusDistance())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // Disable local media session to let service handle button events
        mediaSession?.isActive = false
    }

    private fun stopCameraService() {
        val intent = Intent(this, CameraService::class.java)
        stopService(intent)
        // Re-enable local media session
        mediaSession?.isActive = true
    }

    private fun setupRetractableControls() {
        viewBinding.toggleControlsButton.setOnClickListener {
            viewBinding.controls.isVisible = !viewBinding.controls.isVisible
        }
    }

    private fun setupTimerControl() {
        viewBinding.timerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                viewBinding.timerValueText.text = getString(R.string.timer_seconds, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupBurstControl() {
        viewBinding.burstSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                viewBinding.burstValueText.text = (progress + 1).toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
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
                        startPhotoTimer()
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
                override fun onPlay() = startPhotoTimer()
                override fun onPause() = startPhotoTimer()
            })

            val state = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE)
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                .build()
            setPlaybackState(state)
            isActive = true
        }
    }

    private fun startPhotoTimer() {
        if (imageCapture == null) {
            val msg = "Camera initialization not complete"
            Log.e(TAG, msg)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }

        val timerSeconds = viewBinding.timerSeekBar.progress
        if (timerSeconds == 0) {
            takeBurstPhotos()
            return
        }

        viewBinding.timerText.isVisible = true
        object : CountDownTimer((timerSeconds * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = (millisUntilFinished / 1000) + 1
                viewBinding.timerText.text = secondsRemaining.toString()
            }

            override fun onFinish() {
                viewBinding.timerText.isVisible = false
                takeBurstPhotos()
            }
        }.start()
    }

    private fun takeBurstPhotos() {
        val burstCount = viewBinding.burstSeekBar.progress + 1
        var capturedCount = 0

        fun captureNext() {
            if (capturedCount < burstCount) {
                takePhoto { success ->
                    if (success) {
                        capturedCount++
                        if (capturedCount < burstCount) {
                            // Small delay between burst captures
                            Handler(Looper.getMainLooper()).postDelayed({
                                captureNext()
                            }, 100)
                        }
                    } else {
                        Log.e(TAG, "Burst capture interrupted at $capturedCount")
                    }
                }
            }
        }
        captureNext()
    }

    private fun flashScreen() {
        viewBinding.flashOverlay.isVisible = true
        viewBinding.flashOverlay.alpha = 0.5f
        viewBinding.flashOverlay.animate()
            .alpha(0f)
            .setDuration(100)
            .withEndAction {
                viewBinding.flashOverlay.isVisible = false
            }
            .start()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun takePhoto(onComplete: (Boolean) -> Unit = {}) {
        val imageCapture = imageCapture ?: run {
            Log.e(TAG, "ImageCapture is null")
            onComplete(false)
            return
        }

        if (camera == null) {
            Log.e(TAG, "Camera is not bound to a lifecycle")
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            onComplete(false)
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

        if (viewBinding.manualModeSwitch.isChecked) {
            val shutterSpeed = getShutterSpeed()
            val camera2CameraControl = Camera2CameraControl.from(camera!!.cameraControl)
            applyManualSettings(camera2CameraControl, viewBinding.isoSeekBar.progress, shutterSpeed, getFocusDistance(), true)
        }

        // Play shutter sound
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    Toast.makeText(baseContext, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                    restorePreviewSettings()
                    onComplete(false)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults){
                    flashScreen()
                    Log.d(TAG, "Photo capture succeeded")
                    restorePreviewSettings()
                    onComplete(true)
                }

                private fun restorePreviewSettings() {
                    if (viewBinding.manualModeSwitch.isChecked && camera != null) {
                        val camera2CameraControl = Camera2CameraControl.from(camera!!.cameraControl)
                        applyManualSettings(camera2CameraControl, viewBinding.isoSeekBar.progress, getShutterSpeed(), getFocusDistance(), false)
                    }
                }
            }
        )
    }

    @SuppressLint("RestrictedApi", "ClickableViewAccessibility")
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
                return@addListener
            }
            viewBinding.viewFinder.scaleType = PreviewView.ScaleType.FIT_CENTER

            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .also {
                    it.surfaceProvider = viewBinding.viewFinder.surfaceProvider
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(resolutionSelector)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
                this.camera = camera
                
                viewBinding.viewFinder.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> true
                        MotionEvent.ACTION_UP -> {
                            if (!viewBinding.manualModeSwitch.isChecked) {
                                val factory = viewBinding.viewFinder.meteringPointFactory
                                val point = factory.createPoint(event.x, event.y)
                                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                    .build()
                                
                                camera.cameraControl.startFocusAndMetering(action)
                            }
                            viewBinding.viewFinder.performClick()
                            true
                        }
                        else -> false
                    }
                }

                val cameraInfo = camera.cameraInfo
                currentCameraId = Camera2CameraInfo.from(cameraInfo).cameraId
                val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
                currentCharacteristics = cameraManager.getCameraCharacteristics(currentCameraId!!)

                setupManualControls()
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun getShutterSpeed(): Long {
        val shutterSpeeds = getShutterSpeedsArray()
        if (shutterSpeeds.isEmpty()) return 0L
        val progress = viewBinding.shutterSpeedSeekBar.progress
        return if (progress in shutterSpeeds.indices) shutterSpeeds[progress] else shutterSpeeds.last()
    }

    private fun getFocusDistance(): Float {
        val characteristics = currentCharacteristics ?: return 0f
        val minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        return CameraUtils.calculateFocusDistance(viewBinding.focusDistanceSeekBar.progress, minFocus)
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun setupManualControls() {
        val cameraControl = camera?.cameraControl ?: return
        val characteristics = currentCharacteristics ?: return
        val camera2CameraControl = Camera2CameraControl.from(cameraControl)

        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val minIso = isoRange?.lower ?: 100
        val maxIso = isoRange?.upper ?: 3200

        val shutterSpeeds = getShutterSpeedsArray()
        val shutterSpeedLabels = shutterSpeeds.map { CameraUtils.formatShutterSpeed(it) }.toTypedArray()

        viewBinding.shutterSpeedSeekBar.max = shutterSpeeds.size - 1
        viewBinding.shutterSpeedSeekBar.isEnabled = viewBinding.manualModeSwitch.isChecked

        viewBinding.isoSeekBar.min = minIso
        viewBinding.isoSeekBar.max = maxIso
        viewBinding.isoSeekBar.isEnabled = viewBinding.manualModeSwitch.isChecked

        viewBinding.focusDistanceSeekBar.max = 100
        viewBinding.focusDistanceSeekBar.isEnabled = viewBinding.manualModeSwitch.isChecked

        viewBinding.manualModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.isoSeekBar.isEnabled = isChecked
            viewBinding.shutterSpeedSeekBar.isEnabled = isChecked
            viewBinding.focusDistanceSeekBar.isEnabled = isChecked
            if (isChecked) {
                applyManualSettings(camera2CameraControl, viewBinding.isoSeekBar.progress, getShutterSpeed(), getFocusDistance())
                
                viewBinding.isoValueText.text = viewBinding.isoSeekBar.progress.toString()
                viewBinding.shutterSpeedValueText.text = shutterSpeedLabels[viewBinding.shutterSpeedSeekBar.progress]
                viewBinding.focusDistanceValueText.text = String.format(Locale.US, "%.2f", getFocusDistance())
            } else {
                applyAutoSettings(camera2CameraControl)
                viewBinding.isoValueText.text = getString(R.string.auto)
                viewBinding.shutterSpeedValueText.text = getString(R.string.auto)
                viewBinding.focusDistanceValueText.text = getString(R.string.auto)
            }
        }

        val onManualChanged = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && viewBinding.manualModeSwitch.isChecked) {
                    val iso = viewBinding.isoSeekBar.progress
                    val shutterSpeed = getShutterSpeed()
                    val focusDistance = getFocusDistance()
                    
                    applyManualSettings(camera2CameraControl, iso, shutterSpeed, focusDistance)

                    viewBinding.isoValueText.text = iso.toString()
                    viewBinding.shutterSpeedValueText.text = shutterSpeedLabels[viewBinding.shutterSpeedSeekBar.progress]
                    viewBinding.focusDistanceValueText.text = String.format(Locale.US, "%.2f", focusDistance)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        viewBinding.isoSeekBar.setOnSeekBarChangeListener(onManualChanged)
        viewBinding.shutterSpeedSeekBar.setOnSeekBarChangeListener(onManualChanged)
        viewBinding.focusDistanceSeekBar.setOnSeekBarChangeListener(onManualChanged)
    }
    
    private fun getShutterSpeedsArray(): LongArray {
        val characteristics = currentCharacteristics ?: return longArrayOf()
        val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minExp = exposureTimeRange?.lower ?: 100_000L
        val maxExp = exposureTimeRange?.upper ?: 1_000_000_000L

        return CameraUtils.calculateShutterSpeeds(minExp, maxExp)
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyManualSettings(camera2CameraControl: Camera2CameraControl, iso: Int, shutterSpeed: Long, focusDistance: Float, forceShutter: Boolean = false) {
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
            // For preview, limit exposure to avoid low frame rate (max 1/15s)
            val previewShutter = shutterSpeed.coerceAtMost(66_666_666L)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, previewShutter)
            // Ensure frame duration is at least as long as exposure time
            // Also ensure it's at least 1/30s to keep preview smooth where possible
            val frameDuration = previewShutter.coerceAtLeast(33_333_333L)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration)
        }
        
        camera2CameraControl.captureRequestOptions = builder.build()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyAutoSettings(camera2CameraControl: Camera2CameraControl) {
        camera2CameraControl.captureRequestOptions = CaptureRequestOptions.Builder().build()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        mediaSession?.release()
        shutterSound.release()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                startPhotoTimer()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(Manifest.permission.CAMERA).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
    }
}
