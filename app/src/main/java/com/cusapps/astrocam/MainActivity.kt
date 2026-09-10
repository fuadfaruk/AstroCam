package com.cusapps.astrocam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraMetadata
import android.media.Image
import android.media.ImageReader
import android.media.MediaActionSound
import android.os.*
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.cusapps.astrocam.controls.DiscreteWheelView
import com.cusapps.astrocam.controls.FocusDomain
import com.cusapps.astrocam.controls.FocusWheelView
import com.cusapps.astrocam.controls.IndexedDomain
import com.cusapps.astrocam.controls.ManualControlsController
import com.cusapps.astrocam.controls.ManualControlsState
import com.cusapps.astrocam.databinding.ActivityMainBinding
import com.google.android.material.slider.Slider
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
    private var previewSize: Size? = null
    private var displayRotation = Surface.ROTATION_0
    private val displaySize = Point()
    private var viewFinderWidth = 0
    private var viewFinderHeight = 0
    
    private var mediaSession: MediaSessionCompat? = null
    private val shutterSound = MediaActionSound()
    private var userMinimized = false
    
    // Logic to match images with capture results for RAW DNG creation
    private val captureResults = mutableMapOf<Long, TotalCaptureResult>()
    private val capturedImages = mutableMapOf<Long, Image>()

    // White balance state. Written from the camera background handler, read from the
    // UI thread when the lock engages, hence @Volatile.
    @Volatile private var latestAwbGains: CameraUtils.WhiteBalanceGains? = null
    @Volatile private var manualWhiteBalanceSupported = false
    @Volatile private var whiteBalanceLockSupported = false

    // Manual control domains. These are camera-specific, so they are cached when the
    // camera opens instead of being re-read from CameraManager on every slider step.
    // @Volatile because the capture session can configure on the camera thread and
    // resolves them through the getters.
    @Volatile private var isoStops: IntArray = intArrayOf()
    @Volatile private var shutterSpeeds: LongArray = longArrayOf()
    @Volatile private var minFocusDistance = 0f
    @Volatile private var focusAvailable = false

    // Stepper-backed values, replacing the old timer/burst sliders.
    private var timerSeconds = 0
    private var burstCount = 1

    // Manual control state lives here rather than in the widgets. The camera background
    // thread reads it through applyCurrentSettingsToPreview(), and reading a View from
    // there would violate the main-thread contract openCamera() documents.
    private val controls = ManualControlsController()
    private lateinit var preferences: SharedPreferences
    private var classicSliders = false

    /**
     * Samples the auto white balance the sensor converges on so that engaging the
     * lock can start from the colour the user is already previewing.
     */
    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)
            if (awbState != null &&
                awbState != CameraMetadata.CONTROL_AWB_STATE_CONVERGED &&
                awbState != CameraMetadata.CONTROL_AWB_STATE_LOCKED
            ) {
                return
            }
            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return
            latestAwbGains = CameraUtils.WhiteBalanceGains(gains.red, gains.greenEven, gains.blue)
        }
    }

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
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        classicSliders = preferences.getBoolean(KEY_CLASSIC_SLIDERS, false)
        setupTimerControl()
        setupBurstControl()
        setupRetractableControls()
        setupCameraSwitch()
        setupManualControls()
        setupWhiteBalanceControls()
        setupTapToFocus()
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            updatePreviewTransform(width, height)
        }
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
        if (controls.state.manualEnabled && !userMinimized && !isFinishing && !isChangingConfigurations) {
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

        // Capture display metrics on the UI thread; createCameraPreviewSession()
        // later runs on the background handler and must not touch views.
        displayRotation = windowManager.defaultDisplay.rotation
        windowManager.defaultDisplay.getSize(displaySize)
        viewFinderWidth = viewBinding.viewFinder.width
        viewFinderHeight = viewBinding.viewFinder.height

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

    private fun applyPreviewTransformForCurrentSize(previewSize: Size) {
        // No transform matrix: the SurfaceTexture already displays the buffer
        // upright in portrait. The view itself is sized to the preview aspect
        // ratio via setViewFinderRatio(), so the buffer fills it exactly.
        runOnUiThread { viewBinding.viewFinder.setTransform(Matrix()) }
    }

    private fun setViewFinderRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        runOnUiThread {
            val params = viewBinding.viewFinder.layoutParams as
                    androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.dimensionRatio = "$width:$height"
            viewBinding.viewFinder.layoutParams = params
            viewBinding.viewFinder.requestLayout()
        }
    }

    private fun updatePreviewTransform(width: Int, height: Int) {
        // Kept for the SurfaceTexture size listener; the view ratio is set in
        // createCameraPreviewSession() once the preview size is known.
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = viewBinding.viewFinder.surfaceTexture!!
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = manager.getCameraCharacteristics(cameraId!!)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

            // Choose the preview size relative to the sensor coordinate system,
            // mirroring the official Camera2 sample: swap the view dimensions when
            // the display (portrait) and sensor orientation disagree.
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val swappedDimensions = when (displayRotation) {
                Surface.ROTATION_0, Surface.ROTATION_180 ->
                    sensorOrientation == 90 || sensorOrientation == 270
                Surface.ROTATION_90, Surface.ROTATION_270 ->
                    sensorOrientation == 0 || sensorOrientation == 180
                else -> false
            }

            var rotatedPreviewWidth = viewBinding.viewFinder.width
            var rotatedPreviewHeight = viewBinding.viewFinder.height
            var maxPreviewWidth = displaySize.x
            var maxPreviewHeight = displaySize.y
            if (swappedDimensions) {
                rotatedPreviewWidth = viewFinderHeight
                rotatedPreviewHeight = viewFinderWidth
                maxPreviewWidth = displaySize.y
                maxPreviewHeight = displaySize.x
            }
            maxPreviewWidth = minOf(maxPreviewWidth, MAX_PREVIEW_WIDTH)
            maxPreviewHeight = minOf(maxPreviewHeight, MAX_PREVIEW_HEIGHT)

            val chosenPreviewSize = CameraUtils.chooseOptimalSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                rotatedPreviewWidth,
                rotatedPreviewHeight,
                maxPreviewWidth,
                maxPreviewHeight,
                Size(4, 3)
            )
            previewSize = chosenPreviewSize

            // Size the view itself to the preview's display aspect ratio so the
            // buffer fills it exactly; leftover screen space stays black below.
            val displayRatioWidth: Int
            val displayRatioHeight: Int
            if (swappedDimensions) {
                displayRatioWidth = chosenPreviewSize.height
                displayRatioHeight = chosenPreviewSize.width
            } else {
                displayRatioWidth = chosenPreviewSize.width
                displayRatioHeight = chosenPreviewSize.height
            }
            setViewFinderRatio(displayRatioWidth, displayRatioHeight)

            applyPreviewTransformForCurrentSize(chosenPreviewSize)

            texture.setDefaultBufferSize(chosenPreviewSize.width, chosenPreviewSize.height)
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
        if (controls.state.manualEnabled) {
            CameraUtils.applyManualSettings(builder, getIso(), getShutterSpeed(), getFocusDistance(), false)
        } else {
            CameraUtils.applyAutoSettings(builder)
        }
        CameraUtils.applyWhiteBalance(builder, getWhiteBalanceSettings())
        previewRequest = builder.build()
        captureSession?.setRepeatingRequest(previewRequest!!, previewCaptureCallback, backgroundHandler)
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
            latestAwbGains = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun startCameraService() {
        val whiteBalance = getWhiteBalanceSettings()
        val intent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_UPDATE_SETTINGS
            putExtra(CameraService.EXTRA_MANUAL_MODE, controls.state.manualEnabled)
            putExtra(CameraService.EXTRA_RAW_MODE, viewBinding.rawModeSwitch.isChecked)
            putExtra(CameraService.EXTRA_ISO, getIso())
            putExtra(CameraService.EXTRA_SHUTTER, getShutterSpeed())
            putExtra(CameraService.EXTRA_FOCUS, getFocusDistance())
            putExtra(CameraService.EXTRA_WB_LOCKED, whiteBalance.locked)
            putExtra(CameraService.EXTRA_WB_TEMPERATURE, whiteBalance.temperatureK)
            putExtra(CameraService.EXTRA_WB_MANUAL_SUPPORTED, whiteBalance.manualGainsSupported)
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
                    controls.state.manualEnabled,
                    getIso(),
                    getShutterSpeed(),
                    getFocusDistance(),
                    getWhiteBalanceSettings()
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

    private fun getIso(): Int = controls.state.iso(isoStops)

    private fun getShutterSpeed(): Long = controls.state.shutterSpeed(shutterSpeeds)

    private fun getFocusDistance(): Float = controls.state.focusDistance(minFocusDistance)

    private fun getWhiteBalanceSettings(): CameraUtils.WhiteBalance {
        val state = controls.state
        return CameraUtils.WhiteBalance(
            locked = state.wbLocked,
            temperatureK = state.whiteBalanceTemperature(),
            manualGainsSupported = manualWhiteBalanceSupported
        )
    }

    private fun setupWhiteBalanceControls() {
        val wbDomain = IndexedDomain.whiteBalance()
        viewBinding.whiteBalanceWheel.setControlLabel("White balance")
        viewBinding.whiteBalanceWheel.setDomain(wbDomain.size) { wbDomain.labelAt(it) }
        viewBinding.whiteBalanceWheel.onSelectionChanged = { index ->
            controls.scrub { it.copy(wbProgress = index, wbSetByUser = true) }
        }
        viewBinding.whiteBalanceWheel.onSettle = { controls.commit() }

        // Seed the default from the shared constant so the UI cannot drift from the
        // temperature the code assumes when no measurement is available.
        configureSlider(
            viewBinding.whiteBalanceSlider,
            CameraUtils.WB_TEMPERATURE_PROGRESS_MAX + 1,
            CameraUtils.calculateTemperatureProgress(CameraUtils.WB_DEFAULT_TEMPERATURE_K),
            enabled = false
        )

        viewBinding.whiteBalanceSlider.addOnChangeListener(object : Slider.OnChangeListener {
            override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
                if (!fromUser) return
                controls.scrub { it.copy(wbProgress = value.toInt(), wbSetByUser = true) }
            }
        })
        viewBinding.whiteBalanceSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                // Commit once per gesture rather than once per Kelvin step.
                if (slider.isEnabled) controls.commit()
            }
        })

        // The lock is intentionally independent of the manual exposure switch: a
        // nighttime sequence may run auto exposure but still needs frozen colour.
        viewBinding.whiteBalanceLockSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !controls.state.wbSetByUser) {
                seedWhiteBalanceFromAuto()
            }
            controls.scrub { it.copy(wbLocked = isChecked) }
            val wbEnabled = isChecked && manualWhiteBalanceSupported
            viewBinding.whiteBalanceWheel.isEnabled = wbEnabled
            viewBinding.whiteBalanceSlider.isEnabled = wbEnabled
            applyCurrentSettingsToPreview()
            updateSettingsTexts()
        }
    }

    /**
     * Positions the control at the temperature closest to the auto white balance the
     * preview has converged on, so engaging the lock holds the current colour instead
     * of jumping to an arbitrary default.
     */
    private fun seedWhiteBalanceFromAuto() {
        val gains = latestAwbGains
        val temperature = if (gains != null) {
            CameraUtils.estimateColorTemperature(gains)
        } else {
            CameraUtils.WB_DEFAULT_TEMPERATURE_K
        }
        controls.scrub {
            it.copy(wbProgress = CameraUtils.calculateTemperatureProgress(temperature))
        }
    }

    private fun setupManualControls() {
        // The controller is the source of truth; both presentation styles mirror it and
        // push user input back into it. Cheap scrubs refresh labels only, and the single
        // commit channel keeps camera work to one request per completed gesture.
        controls.onStateChanged = { state ->
            mirrorStateIntoWidgets(state)
            updateSettingsTexts()
        }
        controls.onCommit = {
            updateSettingsTexts()
            applyCurrentSettingsToPreview()
        }

        setupClassicSliderPreference()

        viewBinding.isoWheel.setControlLabel("ISO")
        viewBinding.isoWheel.onSelectionChanged = { index ->
            controls.scrub { it.copy(isoIndex = index) }
        }
        viewBinding.isoWheel.onSettle = { controls.commit() }

        viewBinding.shutterWheel.setControlLabel("Shutter speed")
        viewBinding.shutterWheel.onSelectionChanged = { index ->
            controls.scrub { it.copy(shutterIndex = index) }
        }
        viewBinding.shutterWheel.onSettle = { controls.commit() }

        viewBinding.focusWheel.onSelectionChanged = { progress ->
            controls.scrub { it.copy(focusProgress = progress) }
        }
        viewBinding.focusWheel.onSettle = { controls.commit() }
        viewBinding.focusFineModeButton.setOnClickListener {
            viewBinding.focusWheel.setFineMode(!viewBinding.focusWheel.isFineMode())
            updateFocusFineModeAppearance()
            viewBinding.focusFineModeButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }

        // Label formatters render the selected stop in the drag bubble, which is what
        // makes the classic sliders usable without reading the tiny value row.
        viewBinding.isoSlider.setLabelFormatter { value ->
            isoStops.getOrNull(value.toInt())?.toString() ?: ""
        }
        viewBinding.shutterSlider.setLabelFormatter { value ->
            shutterSpeeds.getOrNull(value.toInt())
                ?.let { CameraUtils.formatShutterSpeed(it) } ?: ""
        }
        viewBinding.focusSlider.setLabelFormatter { value ->
            CameraUtils.formatFocusDistance(
                CameraUtils.calculateFocusDistance(value.toInt(), minFocusDistance)
            )
        }

        viewBinding.isoSlider.addOnChangeListener(sliderScrubListener { index ->
            controls.scrub { it.copy(isoIndex = index) }
        })
        viewBinding.shutterSlider.addOnChangeListener(sliderScrubListener { index ->
            controls.scrub { it.copy(shutterIndex = index) }
        })
        viewBinding.focusSlider.addOnChangeListener(sliderScrubListener { index ->
            controls.scrub { it.copy(focusProgress = index) }
        })

        // Commit once per gesture. A full drag emits a handful of requests instead of
        // one setRepeatingRequest per pixel of finger travel.
        val commitListener = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                if (slider.isEnabled) controls.commit()
            }
        }
        viewBinding.isoSlider.addOnSliderTouchListener(commitListener)
        viewBinding.shutterSlider.addOnSliderTouchListener(commitListener)
        viewBinding.focusSlider.addOnSliderTouchListener(commitListener)

        viewBinding.manualModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            controls.scrub { it.copy(manualEnabled = isChecked) }
            updateManualControlEnabledState()
            applyCurrentSettingsToPreview()
            updateSettingsTexts()
        }

        // Infinity is a single tap; the wheel's top end is also a hard detent.
        viewBinding.focusInfinityButton.setOnClickListener {
            viewBinding.focusWheel.setProgress(CameraUtils.FOCUS_PROGRESS_MAX)
            controls.scrubAndCommit { it.copy(focusProgress = CameraUtils.FOCUS_PROGRESS_MAX) }
        }

        updateManualControlEnabledState()
    }

    private fun sliderScrubListener(onIndex: (Int) -> Unit) = object : Slider.OnChangeListener {
        override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
            if (fromUser) onIndex(value.toInt())
        }
    }

    /** Keeps both presentations in sync without re-entering the user's own gesture. */
    private fun mirrorStateIntoWidgets(state: ManualControlsState) {
        viewBinding.isoWheel.setSelection(state.isoIndex)
        viewBinding.shutterWheel.setSelection(state.shutterIndex)
        viewBinding.focusWheel.setProgress(state.focusProgress)
        viewBinding.whiteBalanceWheel.setSelection(state.wbProgress)

        setSliderValue(viewBinding.isoSlider, state.isoIndex)
        setSliderValue(viewBinding.shutterSlider, state.shutterIndex)
        setSliderValue(viewBinding.focusSlider, state.focusProgress)
        setSliderValue(viewBinding.whiteBalanceSlider, state.wbProgress)
    }

    private fun setSliderValue(slider: Slider, index: Int) {
        val clamped = index.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        if (slider.value != clamped) slider.value = clamped
    }

    private fun setupClassicSliderPreference() {
        viewBinding.classicSlidersSwitch.isChecked = classicSliders
        applyControlsPresentation()
        viewBinding.classicSlidersSwitch.setOnCheckedChangeListener { _, isChecked ->
            classicSliders = isChecked
            preferences.edit().putBoolean(KEY_CLASSIC_SLIDERS, isChecked).apply()
            applyControlsPresentation()
        }
    }

    /** Wheels by default; the legacy sliders remain as an accessibility escape hatch. */
    private fun applyControlsPresentation() {
        viewBinding.isoWheel.isVisible = !classicSliders
        viewBinding.shutterWheel.isVisible = !classicSliders
        viewBinding.focusWheel.isVisible = !classicSliders
        viewBinding.whiteBalanceWheel.isVisible = !classicSliders
        viewBinding.isoSlider.isVisible = classicSliders
        viewBinding.shutterSlider.isVisible = classicSliders
        viewBinding.focusSlider.isVisible = classicSliders
        viewBinding.whiteBalanceSlider.isVisible = classicSliders
    }

    private fun updateManualControlEnabledState() {
        val manual = controls.state.manualEnabled
        val isoEnabled = manual && isoStops.size > 1
        val shutterEnabled = manual && shutterSpeeds.size > 1
        viewBinding.isoWheel.isEnabled = isoEnabled
        viewBinding.isoSlider.isEnabled = isoEnabled
        viewBinding.shutterWheel.isEnabled = shutterEnabled
        viewBinding.shutterSlider.isEnabled = shutterEnabled
        setFocusControlsEnabled(manual && focusAvailable)
    }

    private fun setFocusControlsEnabled(enabled: Boolean) {
        viewBinding.focusWheel.isEnabled = enabled
        viewBinding.focusSlider.isEnabled = enabled
        viewBinding.focusInfinityButton.isEnabled = enabled
        viewBinding.focusInfinityButton.alpha = if (enabled) 1.0f else 0.5f
        viewBinding.focusFineModeButton.isEnabled = enabled
        updateFocusFineModeAppearance()
    }

    /** Fine gain reads brighter when engaged, so the mode is visible at a glance. */
    private fun updateFocusFineModeAppearance() {
        val enabled = controls.state.manualEnabled && focusAvailable
        viewBinding.focusFineModeButton.alpha = when {
            !enabled -> 0.5f
            viewBinding.focusWheel.isFineMode() -> 1.0f
            else -> 0.6f
        }
    }

    private fun updateManualControlsUI(characteristics: CameraCharacteristics) {
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val shutterRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val computedShutterSpeeds = CameraUtils.calculateShutterSpeeds(
            shutterRange?.lower ?: 100_000L,
            shutterRange?.upper ?: 1_000_000_000L
        )
        val computedIsoStops = CameraUtils.calculateIsoStops(
            isoRange?.lower ?: 100,
            isoRange?.upper ?: 3200
        )
        val computedMinFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        // Explicit colour gains need AWB_MODE_OFF. Devices without it can still hold
        // colour steady through CONTROL_AWB_LOCK, just without a Kelvin dial.
        manualWhiteBalanceSupported = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?.contains(CameraMetadata.CONTROL_AWB_MODE_OFF) ?: false
        whiteBalanceLockSupported = characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false
        val canLockWhiteBalance = manualWhiteBalanceSupported || whiteBalanceLockSupported

        // Publish the domains before touching the UI: the capture session can come up
        // on the camera thread and resolves them through the getters.
        shutterSpeeds = computedShutterSpeeds
        isoStops = computedIsoStops
        minFocusDistance = computedMinFocus
        // A fixed-focus lens reports 0 diopters; there is nothing to drive, so the
        // focus controls are disabled rather than left looking functional.
        focusAvailable = computedMinFocus > 0f

        val defaultShutterIndex = CameraUtils.indexOfNearest(computedShutterSpeeds, 16_666_666L)
        val defaultIsoIndex = CameraUtils.indexOfNearest(computedIsoStops, CameraUtils.DEFAULT_ISO)

        runOnUiThread {
            // A camera switch can change every domain, so re-point both presentations and
            // re-seed the controller indices into the new ranges. Manual mode starts
            // disabled; this only stages the controls for when it is switched on.
            viewBinding.shutterWheel.setDomain(computedShutterSpeeds.size) { index ->
                CameraUtils.formatShutterSpeed(
                    computedShutterSpeeds.getOrElse(index) { computedShutterSpeeds.last() }
                )
            }
            viewBinding.isoWheel.setDomain(computedIsoStops.size) { index ->
                computedIsoStops.getOrElse(index) { computedIsoStops.last() }.toString()
            }
            viewBinding.focusWheel.setMinFocusDistance(computedMinFocus)

            configureSlider(
                viewBinding.shutterSlider,
                computedShutterSpeeds.size,
                defaultShutterIndex
            )
            configureSlider(
                viewBinding.isoSlider,
                computedIsoStops.size,
                defaultIsoIndex
            )
            configureSlider(
                viewBinding.focusSlider,
                CameraUtils.FOCUS_PROGRESS_MAX + 1,
                CameraUtils.FOCUS_PROGRESS_MAX
            )

            // White balance is not reconfigured here: its range is a constant, and
            // re-seeding it on every camera open would discard the user's choice.
            viewBinding.whiteBalanceLockSwitch.isEnabled = canLockWhiteBalance
            viewBinding.whiteBalanceLockSwitch.alpha = if (canLockWhiteBalance) 1.0f else 0.5f
            if (!canLockWhiteBalance) viewBinding.whiteBalanceLockSwitch.isChecked = false
            val wbEnabled =
                viewBinding.whiteBalanceLockSwitch.isChecked && manualWhiteBalanceSupported
            viewBinding.whiteBalanceWheel.isEnabled = wbEnabled
            viewBinding.whiteBalanceSlider.isEnabled = wbEnabled

            controls.scrub {
                it.copy(
                    shutterIndex = defaultShutterIndex,
                    isoIndex = defaultIsoIndex,
                    focusProgress = CameraUtils.FOCUS_PROGRESS_MAX
                )
            }

            updateManualControlEnabledState()
            updateSettingsTexts()
        }
    }

    /**
     * Re-points a discrete slider at a new index domain.
     *
     * The value is parked at the minimum before the range changes so a smaller [count]
     * can never leave the current value above the new maximum, which Material Slider
     * rejects. A single-entry domain has no meaningful track, so it is disabled.
     */
    private fun configureSlider(slider: Slider, count: Int, valueIndex: Int, enabled: Boolean = true) {
        if (count <= 1) {
            slider.isEnabled = false
            return
        }
        if (slider.value != slider.valueFrom) {
            slider.value = slider.valueFrom
        }
        slider.stepSize = 1f
        slider.valueFrom = 0f
        slider.valueTo = (count - 1).toFloat()
        slider.value = valueIndex.coerceIn(0, count - 1).toFloat()
        slider.isEnabled = enabled
    }

    private fun updateSettingsTexts() {
        val state = controls.state
        if (state.manualEnabled) {
            viewBinding.isoValueText.text = getIso().toString()
            if (shutterSpeeds.isNotEmpty()) {
                viewBinding.shutterSpeedValueText.text =
                    CameraUtils.formatShutterSpeed(getShutterSpeed())
            }
            viewBinding.focusDistanceValueText.text =
                CameraUtils.formatFocusDistance(getFocusDistance())
        } else {
            viewBinding.isoValueText.text = getString(R.string.auto)
            viewBinding.shutterSpeedValueText.text = getString(R.string.auto)
            viewBinding.focusDistanceValueText.text = getString(R.string.auto)
        }

        viewBinding.whiteBalanceValueText.text = when {
            !state.wbLocked -> getString(R.string.auto)
            manualWhiteBalanceSupported ->
                CameraUtils.formatColorTemperature(state.whiteBalanceTemperature())
            else -> getString(R.string.locked)
        }
    }

    private fun setupRetractableControls() {
        viewBinding.toggleControlsButton.setOnClickListener {
            viewBinding.controlsScroll.isVisible = !viewBinding.controlsScroll.isVisible
        }
    }

    private fun setupCameraSwitch() {
        viewBinding.switchCameraButton.setOnClickListener {
            if (cameraIds.size > 1) {
                cameraIndex = (cameraIndex + 1) % cameraIds.size
                // Colour gains are sensor-specific, so let the new camera re-seed.
                controls.scrub { it.copy(wbSetByUser = false) }
                closeCamera()
                openCamera(viewBinding.viewFinder.width, viewBinding.viewFinder.height)
            }
        }
    }

    private fun setupTimerControl() {
        viewBinding.timerDecreaseButton.setOnClickListener { adjustTimer(-1) }
        viewBinding.timerIncreaseButton.setOnClickListener { adjustTimer(1) }
        applyTimerValue(timerSeconds)
    }

    private fun adjustTimer(delta: Int) {
        applyTimerValue(timerSeconds + delta)
        viewBinding.timerDecreaseButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun applyTimerValue(seconds: Int) {
        timerSeconds = seconds.coerceIn(0, TIMER_MAX_SECONDS)
        viewBinding.timerValueText.text = getString(R.string.timer_seconds, timerSeconds)
        val canDecrease = timerSeconds > 0
        val canIncrease = timerSeconds < TIMER_MAX_SECONDS
        viewBinding.timerDecreaseButton.isEnabled = canDecrease
        viewBinding.timerDecreaseButton.alpha = if (canDecrease) 1.0f else 0.5f
        viewBinding.timerIncreaseButton.isEnabled = canIncrease
        viewBinding.timerIncreaseButton.alpha = if (canIncrease) 1.0f else 0.5f
    }

    private fun setupBurstControl() {
        viewBinding.burstDecreaseButton.setOnClickListener { adjustBurst(-1) }
        viewBinding.burstIncreaseButton.setOnClickListener { adjustBurst(1) }
        applyBurstValue(burstCount)
    }

    private fun adjustBurst(delta: Int) {
        applyBurstValue(burstCount + delta)
        viewBinding.burstDecreaseButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun applyBurstValue(count: Int) {
        burstCount = count.coerceIn(1, BURST_MAX_COUNT)
        viewBinding.burstValueText.text = burstCount.toString()
        val canDecrease = burstCount > 1
        val canIncrease = burstCount < BURST_MAX_COUNT
        viewBinding.burstDecreaseButton.isEnabled = canDecrease
        viewBinding.burstDecreaseButton.alpha = if (canDecrease) 1.0f else 0.5f
        viewBinding.burstIncreaseButton.isEnabled = canIncrease
        viewBinding.burstIncreaseButton.alpha = if (canIncrease) 1.0f else 0.5f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        viewBinding.viewFinder.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                focusOnTouch(event)
                true
            } else {
                false
            }
        }
    }

    private fun focusOnTouch(event: MotionEvent) {
        // Manual focus owns the lens; a stray tap must not silently hand it back to AF.
        if (controls.state.manualEnabled) return
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        
        if (cameraId == null) return
        val characteristics = manager.getCameraCharacteristics(cameraId!!)
        
        val focusArea = TapToFocusHelper.getFocusArea(
            event, 
            viewBinding.viewFinder.width, 
            viewBinding.viewFinder.height, 
            characteristics
        ) ?: return

        try {
            // Cancel any existing AF trigger
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(builder.build(), null, backgroundHandler)

            // Add new focus area and start focus
            if (characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0 > 0) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusArea))
            }
            if (characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0 > 0) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusArea))
            }
            
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)

            session.capture(builder.build(), null, backgroundHandler)

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), previewCaptureCallback, backgroundHandler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to set focus area", e)
        }
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
        private const val MAX_PREVIEW_WIDTH = 1920
        private const val MAX_PREVIEW_HEIGHT = 1080
        private const val TIMER_MAX_SECONDS = 10
        private const val BURST_MAX_COUNT = 10
        private const val PREFS_NAME = "astrocam_controls"
        private const val KEY_CLASSIC_SLIDERS = "classic_sliders"
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
}
