package com.cusapps.astrocam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.MediaActionSound
import android.os.*
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.cusapps.astrocam.databinding.ActivityMainBinding
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewRequest: CaptureRequest? = null
    
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val cameraOpenCloseLock = Semaphore(1)

    private var cameraIndex = 0
    private var cameraIds = listOf<String>()
    
    private var mediaSession: MediaSessionCompat? = null
    private val shutterSound = MediaActionSound()
    private var userMinimized = false
    
    // Logic to match images with capture results for RAW DNG creation
    private val captureResults = mutableMapOf<Long, TotalCaptureResult>()
    private val capturedImages = mutableMapOf<Long, Image>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)

        viewBinding.imageCaptureButton.setOnClickListener { startPhotoTimer() }
        viewBinding.rawModeSwitch.setOnCheckedChangeListener { _, _ -> 
            closeCamera()
            openCamera(viewBinding.viewFinder.width, viewBinding.viewFinder.height)
        }

        initializeMediaSession()
        setupTimerControl()
        setupBurstControl()
        setupRetractableControls()
        setupCameraSwitch()
        setupManualControls()
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            createCameraPreviewSession()
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
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        userMinimized = false
        stopCameraService()
        startBackgroundThread()
        if (viewBinding.viewFinder.isAvailable) {
            openCamera(viewBinding.viewFinder.width, viewBinding.viewFinder.height)
        } else {
            viewBinding.viewFinder.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onStop() {
        if (viewBinding.manualModeSwitch.isChecked && !userMinimized && !isFinishing && !isChangingConfigurations) {
            startCameraService()
        }
        closeCamera()
        stopBackgroundThread()
        super.onStop()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
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

    private fun openCamera(width: Int, height: Int) {
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            return
        }

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            
            cameraIds = manager.cameraIdList.toList()
            if (cameraIds.isEmpty()) return
            
            if (cameraIndex >= cameraIds.size) cameraIndex = 0
            cameraId = cameraIds[cameraIndex]

            val characteristics = manager.getCameraCharacteristics(cameraId!!)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            
            val isRawSupported = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) ?: false
            
            viewBinding.rawModeSwitch.isEnabled = isRawSupported
            viewBinding.rawModeSwitch.alpha = if (isRawSupported) 1.0f else 0.5f

            val outputFormat = if (viewBinding.rawModeSwitch.isChecked && isRawSupported) ImageFormat.RAW_SENSOR else ImageFormat.JPEG
            val largest = Collections.max(map.getOutputSizes(outputFormat).toList(), CompareSizesByArea())
            
            imageReader = ImageReader.newInstance(largest.width, largest.height, outputFormat, 2).apply {
                setOnImageAvailableListener({ reader ->
                    backgroundHandler?.post {
                        val image = reader.acquireNextImage()
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

            manager.openCamera(cameraId!!, stateCallback, backgroundHandler)
            updateManualControlsUI(characteristics)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            cameraOpenCloseLock.release()
        }
    }

    private fun processImage(image: Image, result: TotalCaptureResult, characteristics: CameraCharacteristics) {
        PhotoCaptureHelper.saveImage(this@MainActivity, image, 
            characteristics = characteristics,
            captureResult = result,
            onImageSaved = { runOnUiThread { flashScreen() } },
            onError = { e -> runOnUiThread { Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show() } }
        )
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = viewBinding.viewFinder.surfaceTexture!!
            val characteristics = (getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(cameraId!!)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val previewSize = CameraUtils.chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java), viewBinding.viewFinder.width, viewBinding.viewFinder.height, viewBinding.viewFinder.width, viewBinding.viewFinder.height, Size(4, 3))
            
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(texture)

            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder!!.addTarget(surface)

            cameraDevice!!.createCaptureSession(listOf(surface, imageReader?.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        applyCurrentSettingsToPreview()
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "Failed to set repeating request", e)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@MainActivity, "Failed to configure camera", Toast.LENGTH_SHORT).show()
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception", e)
        }
    }

    private fun applyCurrentSettingsToPreview() {
        val builder = previewRequestBuilder ?: return
        if (viewBinding.manualModeSwitch.isChecked) {
            CameraUtils.applyManualSettings(builder, viewBinding.isoSeekBar.progress, getShutterSpeed(), getFocusDistance(), false)
        } else {
            CameraUtils.applyAutoSettings(builder)
        }
        previewRequest = builder.build()
        captureSession?.setRepeatingRequest(previewRequest!!, null, backgroundHandler)
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
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun startCameraService() {
        val intent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_UPDATE_SETTINGS
            putExtra(CameraService.EXTRA_MANUAL_MODE, viewBinding.manualModeSwitch.isChecked)
            putExtra(CameraService.EXTRA_RAW_MODE, viewBinding.rawModeSwitch.isChecked)
            putExtra(CameraService.EXTRA_ISO, viewBinding.isoSeekBar.progress)
            putExtra(CameraService.EXTRA_SHUTTER, getShutterSpeed())
            putExtra(CameraService.EXTRA_FOCUS, getFocusDistance())
            putExtra(CameraService.EXTRA_CAMERA_ID, cameraId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        mediaSession?.isActive = false
    }

    private fun stopCameraService() {
        stopService(Intent(this, CameraService::class.java))
        mediaSession?.isActive = true
    }

    private fun takePhoto(onComplete: (Boolean) -> Unit = {}) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return

        try {
            val captureBuilder = PhotoCaptureHelper.createCaptureRequest(
                device, 
                listOf(reader.surface),
                PhotoCaptureHelper.CaptureSettings(
                    viewBinding.manualModeSwitch.isChecked,
                    viewBinding.isoSeekBar.progress,
                    getShutterSpeed(),
                    getFocusDistance()
                )
            )

            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
            session.stopRepeating()
            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)!!
                    val image = capturedImages.remove(timestamp)
                    if (image != null) {
                        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                        val characteristics = manager.getCameraCharacteristics(cameraId!!)
                        processImage(image, result, characteristics)
                    } else {
                        captureResults[timestamp] = result
                    }
                    applyCurrentSettingsToPreview()
                    onComplete(true)
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    applyCurrentSettingsToPreview()
                    onComplete(false)
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Capture failed", e)
            onComplete(false)
        }
    }

    private fun startPhotoTimer() {
        val timerSeconds = viewBinding.timerSeekBar.progress
        if (timerSeconds == 0) {
            takeBurstPhotos()
            return
        }

        viewBinding.timerText.isVisible = true
        object : CountDownTimer((timerSeconds * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                viewBinding.timerText.text = ((millisUntilFinished / 1000) + 1).toString()
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
                            Handler(Looper.getMainLooper()).postDelayed({ captureNext() }, 100)
                        }
                    }
                }
            }
        }
        captureNext()
    }

    private fun flashScreen() {
        viewBinding.flashOverlay.isVisible = true
        viewBinding.flashOverlay.alpha = 0.5f
        viewBinding.flashOverlay.animate().alpha(0f).setDuration(100).withEndAction { viewBinding.flashOverlay.isVisible = false }.start()
    }

    private fun getShutterSpeed(): Long {
        val speeds = getShutterSpeedsArray()
        if (speeds.isEmpty()) return 0L
        val progress = viewBinding.shutterSpeedSeekBar.progress
        return if (progress in speeds.indices) speeds[progress] else speeds.last()
    }

    private fun getFocusDistance(): Float {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(cameraId ?: return 0f)
        val minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        return CameraUtils.calculateFocusDistance(viewBinding.focusDistanceSeekBar.progress, minFocus)
    }

    private fun setupManualControls() {
        val onManualChanged = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && viewBinding.manualModeSwitch.isChecked) {
                    applyCurrentSettingsToPreview()
                    updateSettingsTexts()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        viewBinding.isoSeekBar.setOnSeekBarChangeListener(onManualChanged)
        viewBinding.shutterSpeedSeekBar.setOnSeekBarChangeListener(onManualChanged)
        viewBinding.focusDistanceSeekBar.setOnSeekBarChangeListener(onManualChanged)

        viewBinding.manualModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.isoSeekBar.isEnabled = isChecked
            viewBinding.shutterSpeedSeekBar.isEnabled = isChecked
            viewBinding.focusDistanceSeekBar.isEnabled = isChecked
            applyCurrentSettingsToPreview()
            updateSettingsTexts()
        }
    }

    private fun updateManualControlsUI(characteristics: CameraCharacteristics) {
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val shutterRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val shutterSpeeds = CameraUtils.calculateShutterSpeeds(shutterRange?.lower ?: 100_000L, shutterRange?.upper ?: 1_000_000_000L)
        
        runOnUiThread {
            viewBinding.shutterSpeedSeekBar.max = if (shutterSpeeds.isNotEmpty()) shutterSpeeds.size - 1 else 0
            viewBinding.isoSeekBar.min = isoRange?.lower ?: 100
            viewBinding.isoSeekBar.max = isoRange?.upper ?: 3200
            viewBinding.focusDistanceSeekBar.max = 100
            updateSettingsTexts()
        }
    }

    private fun updateSettingsTexts() {
        if (viewBinding.manualModeSwitch.isChecked) {
            viewBinding.isoValueText.text = viewBinding.isoSeekBar.progress.toString()
            val speeds = getShutterSpeedsArray()
            if (speeds.isNotEmpty()) {
                viewBinding.shutterSpeedValueText.text = CameraUtils.formatShutterSpeed(getShutterSpeed())
            }
            viewBinding.focusDistanceValueText.text = String.format(Locale.US, "%.2f", getFocusDistance())
        } else {
            viewBinding.isoValueText.text = getString(R.string.auto)
            viewBinding.shutterSpeedValueText.text = getString(R.string.auto)
            viewBinding.focusDistanceValueText.text = getString(R.string.auto)
        }
    }

    private fun getShutterSpeedsArray(): LongArray {
        if (cameraId == null) return longArrayOf()
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(cameraId!!)
        val range = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        return CameraUtils.calculateShutterSpeeds(range?.lower ?: 100_000L, range?.upper ?: 1_000_000_000L)
    }

    private fun setupRetractableControls() {
        viewBinding.toggleControlsButton.setOnClickListener { viewBinding.controls.isVisible = !viewBinding.controls.isVisible }
    }

    private fun setupCameraSwitch() {
        viewBinding.switchCameraButton.setOnClickListener {
            if (cameraIds.size > 1) {
                cameraIndex = (cameraIndex + 1) % cameraIds.size
                closeCamera()
                openCamera(viewBinding.viewFinder.width, viewBinding.viewFinder.height)
            }
        }
    }

    private fun setupTimerControl() {
        viewBinding.timerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { viewBinding.timerValueText.text = getString(R.string.timer_seconds, progress) }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupBurstControl() {
        viewBinding.burstSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { viewBinding.burstValueText.text = (progress + 1).toString() }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun initializeMediaSession() {
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (keyEvent?.action == KeyEvent.ACTION_DOWN) { startPhotoTimer(); return true }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
                override fun onPlay() = startPhotoTimer()
                override fun onPause() = startPhotoTimer()
            })
            setPlaybackState(PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE).setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f).build())
            isActive = true
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession?.release()
        shutterSound.release()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> { startPhotoTimer(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) {
            if (viewBinding.viewFinder.isAvailable) openCamera(viewBinding.viewFinder.width, viewBinding.viewFinder.height)
        } else finish()
    }

    class CompareSizesByArea : Comparator<Size> {
        override fun compare(lhs: Size, rhs: Size): Int = java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
    }

    companion object {
        private const val TAG = "AstroCamMain"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
}
