package com.cusapps.astrocam.controls

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.cusapps.astrocam.CameraUtils
import com.cusapps.astrocam.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Continuous ruler wheel for manual focus.
 *
 * Focus has 1001 selectable positions ([CameraUtils.FOCUS_PROGRESS_MAX] + 1). Mapped
 * onto a slider track that is ~340dp wide, one step occupies 0.34dp, i.e. less than a
 * quarter of a millimetre and far below the resolution of a fingertip. That is why the
 * old layout shipped nudge buttons and a separate infinity shortcut.
 *
 * This control decouples gain from the domain instead:
 *  - a coarse gain traverses the full range in roughly one swipe,
 *  - a fine gain is used for small, deliberate drags and when [setFineMode] is on,
 *  - the top of the range is a hard detent with a distinct haptic, so reaching and
 *    holding infinity is a real gesture rather than the last 1% of a track.
 *
 * Fling is deliberately omitted: momentum on a focus ring would overshoot the subject
 * with no visual confirmation. Gain, not inertia, is the speed mechanism here.
 */
class FocusWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val accentColor = ContextCompat.getColor(context, R.color.night_accent)
    private val surfaceColor = ContextCompat.getColor(context, R.color.control_surface)
    private val variantColor = ContextCompat.getColor(context, R.color.control_on_surface_variant)

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = variantColor
        strokeWidth = dp(1f)
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = variantColor
        strokeWidth = dp(1.5f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = variantColor
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        textAlign = Paint.Align.CENTER
        textSize = sp(18f)
        isFakeBoldText = true
    }
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Int id for ACTION_SET_PROGRESS, which is only exposed via [AccessibilityAction]. */
    private val actionSetProgressId =
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id

    private var minFocusDistance = 0f
    private var fineMode = false

    /** Continuous position in progress units, 0..[maxProgress]. */
    private var floatProgress = CameraUtils.FOCUS_PROGRESS_MAX.toFloat()
    private var progress = CameraUtils.FOCUS_PROGRESS_MAX
    private var lastTickProgress = progress

    private var isDragging = false
    private var downX = 0f
    private var downY = 0f
    private var disallowRequested = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var lastTouchX = 0f
    private var suppressCallback = false

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isEnabled) return false
                setProgressInternal(CameraUtils.FOCUS_PROGRESS_MAX)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onSelectionChanged?.invoke(progress)
                announce()
                onSettle?.invoke()
                return true
            }
        }
    )

    var onSelectionChanged: ((Int) -> Unit)? = null
    var onSettle: (() -> Unit)? = null

    private val maxProgress: Int get() = CameraUtils.FOCUS_PROGRESS_MAX

    init {
        isFocusable = true
        isClickable = true
    }

    /** The lens's closest focus in diopters, needed to render metric labels. */
    fun setMinFocusDistance(diopters: Float) {
        minFocusDistance = diopters
        contentDescription = "Focus distance, ${labelAt(progress)}"
        invalidate()
    }

    /** Forces the fine gain on for the whole gesture, and for small drags regardless. */
    fun setFineMode(enabled: Boolean) {
        fineMode = enabled
        invalidate()
    }

    fun isFineMode(): Boolean = fineMode

    fun setProgress(value: Int) {
        if (isDragging) return
        val clamped = value.coerceIn(0, maxProgress)
        if (clamped == progress) return
        suppressCallback = true
        setProgressInternal(clamped)
        suppressCallback = false
    }

    private fun setProgressInternal(value: Int) {
        progress = value.coerceIn(0, maxProgress)
        floatProgress = progress.toFloat()
        lastTickProgress = progress
        contentDescription = "Focus distance, ${labelAt(progress)}"
        invalidate()
    }

    private fun labelAt(value: Int): String = CameraUtils.formatFocusDistance(
        CameraUtils.calculateFocusDistance(value, minFocusDistance)
    )

    private fun gainFor(travelDp: Float): Float = when {
        fineMode -> FINE_GAIN
        travelDp < FINE_TRAVEL_DP -> FINE_GAIN
        else -> COARSE_GAIN
    }

    // ------------------------------------------------------------ touch input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                disallowRequested = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                val dx = event.x - lastTouchX
                lastTouchX = event.x
                maybeClaimHorizontalScroll(event)
                applyDrag(dx, abs(event.x - downX))
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) return false
                isDragging = false
                releaseParent()
                if (!suppressCallback) onSettle?.invoke()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                releaseParent()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Claims the gesture for the ruler only once it is clearly horizontal, leaving a
     * vertical drag to the parent ScrollView so the control panel still scrolls.
     */
    private fun maybeClaimHorizontalScroll(event: MotionEvent) {
        if (disallowRequested) return
        val totalDx = abs(event.x - downX)
        val totalDy = abs(event.y - downY)
        if (totalDx > touchSlop && totalDx > totalDy) {
            parent?.requestDisallowInterceptTouchEvent(true)
            disallowRequested = true
        }
    }

    private fun releaseParent() {
        if (disallowRequested) {
            parent?.requestDisallowInterceptTouchEvent(false)
            disallowRequested = false
        }
    }

    private fun applyDrag(dxPx: Float, travelPx: Float) {
        val gain = gainFor(travelPx / resources.displayMetrics.density)
        val deltaSteps = (dxPx / resources.displayMetrics.density) * gain
        // Drag right reveals closer focus (lower progress), matching the wheels.
        val next = (floatProgress - deltaSteps).coerceIn(0f, maxProgress.toFloat())
        floatProgress = next
        val rounded = next.roundToInt().coerceIn(0, maxProgress)
        if (rounded != progress) {
            progress = rounded
            val tickEvery = if (gain <= FINE_GAIN * 1.5f) 1 else COARSE_TICK_INTERVAL
            val atBoundary = rounded == 0 || rounded == maxProgress
            if (atBoundary || abs(rounded - lastTickProgress) >= tickEvery) {
                performHapticFeedback(
                    if (atBoundary) HapticFeedbackConstants.LONG_PRESS
                    else HapticFeedbackConstants.CLOCK_TICK
                )
                lastTickProgress = rounded
            }
            onSelectionChanged?.invoke(rounded)
            announce()
        }
        invalidate()
    }

    private fun announce() {
        contentDescription = "Focus distance, ${labelAt(progress)}"
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isEnabled) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MINUS -> {
                nudge(-FINE_KEY_STEP); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PLUS -> {
                nudge(FINE_KEY_STEP); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /** One deliberate fine nudge that commits immediately. */
    fun nudge(delta: Int) {
        val target = (progress + delta).coerceIn(0, maxProgress)
        if (target == progress) return
        setProgressInternal(target)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onSelectionChanged?.invoke(target)
        announce()
        onSettle?.invoke()
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val alpha = if (isEnabled) 1f else 0.4f
        val centerX = width / 2f
        val gain = if (fineMode) FINE_GAIN else COARSE_GAIN
        val dpPerStep = 1f / gain
        val pxPerStep = dp(dpPerStep)
        val halfSpanSteps = (width / 2f) / pxPerStep

        val tickInterval = maxOf(1, ceil(3f * gain).toInt())
        val labelInterval = niceInterval(ceil(64f * gain).toInt())

        val low = (floatProgress - halfSpanSteps).toInt()
        val high = (floatProgress + halfSpanSteps).toInt() + 1

        val tickTop = height * 0.34f
        val minorBottom = height * 0.55f
        val majorBottom = height * 0.66f
        val labelBaseline = height * 0.86f

        tickPaint.alpha = (alpha * 255f).roundToInt()
        majorTickPaint.alpha = (alpha * 255f).roundToInt()
        labelPaint.alpha = (alpha * 255f).roundToInt()

        for (step in low..high) {
            if (step < 0 || step > maxProgress) continue
            val x = centerX + (floatProgress - step) * pxPerStep
            if (x < -dp(40f) || x > width + dp(40f)) continue

            val isMajor = step % labelInterval == 0
            if (step % tickInterval == 0) {
                canvas.drawLine(
                    x, tickTop, x, if (isMajor) majorBottom else minorBottom,
                    if (isMajor) majorTickPaint else tickPaint
                )
            }
            if (isMajor) {
                canvas.drawText(labelAt(step), x, labelBaseline, labelPaint)
            }
        }

        // Selected value sits on the centre line and is never occluded by the finger.
        valuePaint.alpha = (alpha * 255f).roundToInt()
        val valueBaseline = height * 0.20f
        canvas.drawText(labelAt(progress), centerX, valueBaseline, valuePaint)

        caretPaint.alpha = (alpha * 255f).roundToInt()
        canvas.drawLine(centerX, tickTop - dp(3f), centerX, majorBottom + dp(3f), caretPaint)

        drawEdgeScrims(canvas, alpha)
    }

    private fun drawEdgeScrims(canvas: Canvas, alpha: Float) {
        val fade = dp(28f)
        scrimPaint.shader = LinearGradient(
            0f, 0f, fade, 0f,
            intArrayOf(withAlpha(surfaceColor, alpha), withAlpha(surfaceColor, 0f)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, fade, height.toFloat(), scrimPaint)
        scrimPaint.shader = LinearGradient(
            width - fade, 0f, width.toFloat(), 0f,
            intArrayOf(withAlpha(surfaceColor, 0f), withAlpha(surfaceColor, alpha)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(width - fade, 0f, width.toFloat(), height.toFloat(), scrimPaint)
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255f).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /** Rounds a raw spacing up to a human-friendly 1/2/5 x 10^n interval. */
    private fun niceInterval(raw: Int): Int {
        if (raw <= 1) return 1
        var pow = 1
        while (pow * 10 < raw) pow *= 10
        return listOf(pow, pow * 2, pow * 5, pow * 10).firstOrNull { it >= raw } ?: (pow * 10)
    }

    // ------------------------------------------------------------ accessibility

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.contentDescription = "Focus distance, ${labelAt(progress)}"
        info.isScrollable = true
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
            0f, maxProgress.toFloat(), progress.toFloat()
        )
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        return when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
                nudge(FINE_KEY_STEP); true
            }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                nudge(-FINE_KEY_STEP); true
            }
            actionSetProgressId -> {
                val target = arguments
                    ?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)
                    ?.roundToInt() ?: return false
                val clamped = target.coerceIn(0, maxProgress)
                if (clamped == progress) return false
                setProgressInternal(clamped)
                onSelectionChanged?.invoke(clamped)
                announce()
                onSettle?.invoke()
                true
            }
            else -> super.performAccessibilityAction(action, arguments)
        }
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.fromIndex = progress
        event.toIndex = progress
        event.itemCount = maxProgress + 1
    }

    // -------------------------------------------------------------- lifecycle

    override fun onSaveInstanceState(): Parcelable {
        return Bundle().apply {
            putParcelable(KEY_SUPER, super.onSaveInstanceState())
            putInt(KEY_PROGRESS, progress)
            putBoolean(KEY_FINE, fineMode)
        }
    }

    @Suppress("DEPRECATION")
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            fineMode = state.getBoolean(KEY_FINE, fineMode)
            setProgressInternal(state.getInt(KEY_PROGRESS, progress))
            super.onRestoreInstanceState(state.getParcelable(KEY_SUPER))
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    )

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics
    )

    private companion object {
        /** Full range in roughly one comfortable swipe. */
        const val COARSE_GAIN = 3.0f
        /** Deliberate trim: about 2.9dp of travel per focus step. */
        const val FINE_GAIN = 0.35f
        /** A drag shorter than this is treated as a precision adjustment. */
        const val FINE_TRAVEL_DP = 24f
        const val COARSE_TICK_INTERVAL = 25
        const val FINE_KEY_STEP = 10
        const val KEY_SUPER = "focus.super"
        const val KEY_PROGRESS = "focus.progress"
        const val KEY_FINE = "focus.fine"
    }
}
