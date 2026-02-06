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
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

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

    private fun setupRetractableControls() {
        viewBinding.toggleControlsButton.setOnClickListener {
            if (viewBinding.controls.visibility == View.VISIBLE) {
                viewBinding.controls.visibility = View.GONE
            } else {
                viewBinding.controls.visibility = View.VISIBLE
            }
        }
    }

    private fun setupTimerControl() {
        viewBinding.timerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                viewBinding.timerValueText.text = "${progress}s"
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
                    val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                        startPhotoTimer()
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }

                override fun onPlay() {
                    startPhotoTimer()
                }

                override fun onPause() {
                    startPhotoTimer()
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

        viewBinding.timerText.visibility = View.VISIBLE
        object : CountDownTimer((timerSeconds * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = (millisUntilFinished / 1000) + 1
                viewBinding.timerText.text = secondsRemaining.toString()
            }

            override fun onFinish() {
                viewBinding.timerText.visibility = View.GONE
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
        viewBinding.flashOverlay.visibility = View.VISIBLE
        viewBinding.flashOverlay.alpha = 0.5f
        viewBinding.flashOverlay.animate()
            .alpha(0f)
            .setDuration(100)
            .withEndAction {
                viewBinding.flashOverlay.visibility = View.GONE
            }
            .start()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun takePhoto(onComplete: (Boolean) -> Unit = {}) {
        val imageCapture = imageCapture ?: run {
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

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        // Apply manual settings BEFORE capture if manual mode is on
        // This ensures the capture request uses the intended long exposure
        if (viewBinding.manualModeSwitch.isChecked) {
            val shutterSpeed = getShutterSpeed()
            val camera2CameraControl = Camera2CameraControl.from(camera!!.cameraControl)
            applyManualSettings(camera2CameraControl, viewBinding.isoSeekBar.progress, shutterSpeed, getFocusDistance(), true)
        }

        // Flash the screen
        flashScreen()

        // Set up image capture listener, which is triggered after photo has been taken
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

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Log.d(TAG, msg)
                    restorePreviewSettings()
                    onComplete(true)
                }

                private fun restorePreviewSettings() {
                    // Revert to preview-friendly settings (capped shutter speed) after capture
                    if (viewBinding.manualModeSwitch.isChecked) {
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
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
                return@addListener
            }
            viewBinding.viewFinder.scaleType = PreviewView.ScaleType.FIT_CENTER

            // Preview
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            // ImageCapture - Maximize quality for astro shots
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
                this.camera = camera
                
                // Add Tap to Focus
                viewBinding.viewFinder.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> true
                        MotionEvent.ACTION_UP -> {
                            Log.d(TAG, "Tap detected at ${event.x}, ${event.y}")
                            // If manual mode is on, we don't want to override focus
                            if (!viewBinding.manualModeSwitch.isChecked) {
                                val factory = viewBinding.viewFinder.meteringPointFactory
                                val point = factory.createPoint(event.x, event.y)
                                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                    .build()
                                
                                Log.d(TAG, "Starting focus and metering...")
                                camera.cameraControl.startFocusAndMetering(action).addListener({
                                    try {
                                        Log.d(TAG, "Focus and metering completed")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Focus and metering failed", e)
                                    }
                                }, ContextCompat.getMainExecutor(this))
                            } else {
                                Log.d(TAG, "Tap to focus ignored: Manual mode is active")
                            }
                            viewBinding.viewFinder.performClick()
                            true
                        }
                        else -> false
                    }
                }

                // Fetch and store native camera properties for manual controls
                val cameraInfo = camera.cameraInfo
                currentCameraId = Camera2CameraInfo.from(cameraInfo).cameraId
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                currentCharacteristics = cameraManager.getCameraCharacteristics(currentCameraId!!)

                setupManualControls()
                Log.d(TAG, "Camera preview and capture bound successfully")
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, "Error starting camera: ${exc.message}", Toast.LENGTH_SHORT).show()
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
        return (1.0f - viewBinding.focusDistanceSeekBar.progress / 100f) * minFocus
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun setupManualControls() {
        val cameraControl = camera?.cameraControl ?: return
        val characteristics = currentCharacteristics ?: return
        val camera2CameraControl = Camera2CameraControl.from(cameraControl)

        // Range Queries using native android.hardware.camera2 API
        val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

        val minIso = isoRange?.lower ?: 100
        val maxIso = isoRange?.upper ?: 3200

        // Shutter Speed setup
        val shutterSpeeds = getShutterSpeedsArray()
        val shutterSpeedLabels = shutterSpeeds.map { ns ->
            if (ns >= 1_000_000_000L) "${ns / 1_000_000_000L}s" else "1/${1_000_000_000L / ns}"
        }.toTypedArray()

        viewBinding.shutterSpeedSeekBar.max = shutterSpeeds.size - 1
        viewBinding.shutterSpeedSeekBar.isEnabled = viewBinding.manualModeSwitch.isChecked

        viewBinding.isoSeekBar.min = minIso
        viewBinding.isoSeekBar.max = maxIso
        viewBinding.isoSeekBar.isEnabled = viewBinding.manualModeSwitch.isChecked

        // Focus setup (0.0 is infinity, minFocus is closest)
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
                viewBinding.isoValueText.text = "Auto"
                viewBinding.shutterSpeedValueText.text = "Auto"
                viewBinding.focusDistanceValueText.text = "Auto"
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

        val commonSpeeds = longArrayOf(
            1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_666_666L, 33_333_333L, 66_666_666L,
            125_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L, 2_000_000_000L, 4_000_000_000L,
            8_000_000_000L, 15_000_000_000L, 30_000_000_000L, 60_000_000_000L
        )
        val supportedSpeedsList = commonSpeeds.filter { it in minExp..maxExp }.toMutableList()
        if (supportedSpeedsList.isEmpty() || maxExp > (supportedSpeedsList.lastOrNull() ?: 0L)) {
            supportedSpeedsList.add(maxExp)
        }
        return supportedSpeedsList.toLongArray()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyManualSettings(camera2CameraControl: Camera2CameraControl, iso: Int, shutterSpeed: Long, focusDistance: Float, forceShutter: Boolean = false) {
        val builder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)

        // For long exposures, we need to set both exposure time and frame duration.
        // We cap the preview (non-forced) at ~1/15s to keep it responsive.
        if (forceShutter || shutterSpeed < 66_666_666L) { 
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterSpeed)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, shutterSpeed)
        } else {
            // Cap preview shutter speed to ~1/15s if the actual setting is longer
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, 66_666_666L)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, 66_666_666L)
        }
        
        camera2CameraControl.captureRequestOptions = builder.build()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyAutoSettings(camera2CameraControl: Camera2CameraControl) {
        // Clearing Interop options by setting an empty builder
        // This allows CameraX to take back control of AE/AF
        camera2CameraControl.captureRequestOptions = CaptureRequestOptions.Builder().build()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        mediaSession?.release()
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
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}
