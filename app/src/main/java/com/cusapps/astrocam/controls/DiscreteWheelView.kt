package com.cusapps.astrocam.controls

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import com.cusapps.astrocam.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Horizontal detented wheel for a discrete [ControlDomain].
 *
 * This is the View-based variant of the interaction recommended for the manual
 * controls: a relative, snap-to-detent wheel with momentum traverse, per-detent haptic
 * feedback and alpha-falloff neighbours. Unlike a Slider it keeps a constant
 * control-to-display gain regardless of domain size, never jumps on a stray tap, and
 * leaves the selected value unoccluded on the centre line.
 *
 * Scrub and commit are reported separately so the owner can keep the expensive
 * `setRepeatingRequest` call to one per completed gesture.
 */
class DiscreteWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private enum class Phase { NONE, FLING, SNAP }

    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null

    private val accentColor = ContextCompat.getColor(context, R.color.night_accent)
    private val surfaceColor = ContextCompat.getColor(context, R.color.control_surface)
    private val trackColor = ContextCompat.getColor(context, R.color.slider_track_inactive)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = surfaceColor // replaced per draw
    }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val rangeBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rangeBarRect = RectF()

    /** Int id for ACTION_SET_PROGRESS, which is only exposed via [AccessibilityAction]. */
    private val actionSetProgressId =
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id

    private val itemWidthPx = dp(64f)
    private val centreIndicatorHalfSpan = itemWidthPx * 0.46f

    private var itemCount = 1
    private var labelProvider: ((Int) -> String)? = null
    private var controlLabel = ""

    /** Fractional index parked under the centre line. */
    private var positionPx = 0f
    private var phase = Phase.NONE
    private var isDragging = false
    private var suppressCallback = false

    private var lastTouchX = 0f
    private var downX = 0f
    private var downY = 0f
    private var disallowRequested = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var lastTickIndex = 0

    /** Index currently centred. */
    var selection: Int = 0
        private set

    /** Fired on every detent crossed while scrubbing. Cheap: labels only. */
    var onSelectionChanged: ((Int) -> Unit)? = null

    /** Fired once when a gesture fully settles. Safe to commit to the camera. */
    var onSettle: (() -> Unit)? = null

    private val maxIndex: Int get() = (itemCount - 1).coerceAtLeast(0)
    private val maxPx: Float get() = maxIndex * itemWidthPx

    init {
        isFocusable = true
        isClickable = true
    }

    /** Points the wheel at a new index domain. [selection] is clamped into the new range. */
    fun setDomain(itemCount: Int, labelProvider: (Int) -> String) {
        this.itemCount = itemCount.coerceAtLeast(1)
        this.labelProvider = labelProvider
        val clamped = selection.coerceIn(0, maxIndex)
        forceSelection(clamped)
        contentDescription = "$controlLabel, ${labelAt(clamped)}"
        invalidate()
    }

    /** Human-readable control name used for accessibility and announcements. */
    fun setControlLabel(label: String) {
        controlLabel = label
        invalidate()
    }

    /**
     * Programmatic selection (camera reopen, white-balance seed, sibling mirroring).
     *
     * Ignored while the user is dragging or the wheel is animating so a state-change
     * round-trip can never fight the in-progress gesture.
     */
    fun setSelection(index: Int) {
        if (isDragging || phase != Phase.NONE) return
        val clamped = index.coerceIn(0, maxIndex)
        if (clamped == selection && positionPx == clamped * itemWidthPx) return
        forceSelection(clamped)
    }

    private fun forceSelection(index: Int) {
        scroller.abortAnimation()
        phase = Phase.NONE
        suppressCallback = true
        selection = index
        positionPx = index * itemWidthPx
        lastTickIndex = index
        suppressCallback = false
        invalidate()
    }

    private fun labelAt(index: Int): String =
        labelProvider?.invoke(index.coerceIn(0, maxIndex)) ?: ""

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (itemCount <= 0) return

        val alpha = if (isEnabled) 1f else 0.4f
        val centerX = width / 2f
        val centerY = height * 0.46f
        val fraction = if (maxIndex == 0) 0f else positionPx / maxPx

        drawRangeBar(canvas, fraction, alpha)
        drawItems(canvas, centerX, centerY, alpha)
        drawCentreIndicator(canvas, centerX, alpha)
        drawEdgeScrims(canvas, alpha)
    }

    private fun drawItems(canvas: Canvas, centerX: Float, centerY: Float, alpha: Float) {
        val first = (positionPx / itemWidthPx).toInt() - 4
        val last = (positionPx / itemWidthPx).toInt() + 4

        for (i in first..last) {
            if (i < 0 || i > maxIndex) continue
            val distance = abs(i - positionPx / itemWidthPx)
            if (distance > 2.6f) continue

            val x = centerX + (i * itemWidthPx - positionPx)
            textPaint.color = if (distance < 0.5f) accentColor else android.graphics.Color.WHITE
            textPaint.textSize = if (distance < 0.5f) sp(18f) else sp(14f)
            textPaint.isFakeBoldText = distance < 0.5f
            textPaint.alpha = (itemAlpha(distance) * alpha * 255f).roundToInt().coerceIn(0, 255)

            canvas.drawText(
                labelAt(i),
                x,
                centerY - (textPaint.descent() + textPaint.ascent()) / 2f,
                textPaint
            )
        }
        textPaint.alpha = 255
    }

    /** Peeking neighbours are the primary "this scrolls" affordance a wheel needs. */
    private fun itemAlpha(distance: Float): Float = when {
        distance < 0.5f -> 1f
        distance < 1.5f -> 0.55f
        distance < 2.5f -> 0.30f
        else -> 0.16f
    }

    private fun drawCentreIndicator(canvas: Canvas, centerX: Float, alpha: Float) {
        indicatorPaint.alpha = (alpha * 255f).roundToInt()
        val top = height * 0.12f
        val bottom = height * 0.80f
        canvas.drawLine(centerX - centreIndicatorHalfSpan, top, centerX - centreIndicatorHalfSpan, bottom, indicatorPaint)
        canvas.drawLine(centerX + centreIndicatorHalfSpan, top, centerX + centreIndicatorHalfSpan, bottom, indicatorPaint)
    }

    /** Compensates for the loss of `tickVisible` range legibility versus a slider. */
    private fun drawRangeBar(canvas: Canvas, fraction: Float, alpha: Float) {
        if (maxIndex == 0) return
        val inset = dp(24f)
        val barHeight = dp(3f)
        val top = height - dp(8f) - barHeight
        rangeBarPaint.color = trackColor
        rangeBarPaint.alpha = (alpha * 255f).roundToInt()
        rangeBarRect.set(inset, top, width - inset, top + barHeight)
        canvas.drawRoundRect(rangeBarRect, barHeight / 2f, barHeight / 2f, rangeBarPaint)

        rangeBarPaint.color = accentColor
        val filledRight = inset + (width - 2 * inset) * fraction.coerceIn(0f, 1f)
        rangeBarRect.set(inset, top, filledRight.coerceAtLeast(inset), top + barHeight)
        canvas.drawRoundRect(rangeBarRect, barHeight / 2f, barHeight / 2f, rangeBarPaint)
    }

    private fun drawEdgeScrims(canvas: Canvas, alpha: Float) {
        val fade = itemWidthPx * 1.2f
        scrimPaint.shader = LinearGradient(
            0f, 0f, fade, 0f,
            intArrayOf(withAlpha(surfaceColor, alpha), withAlpha(surfaceColor, 0f)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, fade, height.toFloat(), scrimPaint)

        scrimPaint.shader = LinearGradient(
            width - fade, 0f, width.toFloat(), 0f,
            intArrayOf(withAlpha(surfaceColor, 0f), withAlpha(surfaceColor, alpha)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(width - fade, 0f, width.toFloat(), height.toFloat(), scrimPaint)
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255f).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    // ------------------------------------------------------------ touch input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || maxIndex == 0) return super.onTouchEvent(event)

        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.abortAnimation()
                phase = Phase.NONE
                isDragging = true
                lastTouchX = event.x
                downX = event.x
                downY = event.y
                disallowRequested = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                val dx = event.x - lastTouchX
                lastTouchX = event.x
                maybeClaimHorizontalScroll(event)
                // Drag right reveals earlier (lower) values, like scrolling a list.
                setPositionPx(positionPx - dx)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) return false
                isDragging = false
                releaseParent()

                val tracker = velocityTracker
                tracker?.addMovement(event)
                tracker?.computeCurrentVelocity(1000)
                val velocityX = tracker?.xVelocity ?: 0f
                tracker?.recycle()
                velocityTracker = null

                if (abs(velocityX) > dp(40f)) {
                    phase = Phase.FLING
                    // Gesture velocity is positive to the right; position moves the other way.
                    scroller.fling(
                        positionPx.roundToInt(), 0, -velocityX.roundToInt(), 0,
                        0, maxPx.roundToInt(), 0, 0
                    )
                    postInvalidateOnAnimation()
                } else {
                    beginSnap()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                releaseParent()
                velocityTracker?.recycle()
                velocityTracker = null
                setPositionPx(selection * itemWidthPx)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Claims the gesture for this wheel only once it is clearly horizontal. A vertical
     * drag is left to the parent ScrollView, so the tall control panel still scrolls.
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

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            setPositionPx(scroller.currX.toFloat())
            postInvalidateOnAnimation()
            return
        }
        when (phase) {
            Phase.FLING -> beginSnap()
            Phase.SNAP -> {
                phase = Phase.NONE
                setPositionPx(selection * itemWidthPx)
                if (!suppressCallback) onSettle?.invoke()
            }
            Phase.NONE -> Unit
        }
    }

    private fun beginSnap() {
        val targetPx = (positionPx / itemWidthPx).roundToInt().coerceIn(0, maxIndex) * itemWidthPx
        val delta = targetPx - positionPx
        if (abs(delta) < 0.5f) {
            phase = Phase.NONE
            setPositionPx(targetPx)
            if (!suppressCallback) onSettle?.invoke()
            return
        }
        phase = Phase.SNAP
        scroller.startScroll(positionPx.roundToInt(), 0, delta.roundToInt(), 0, SNAP_DURATION_MS)
        postInvalidateOnAnimation()
    }

    private fun setPositionPx(px: Float) {
        positionPx = px.coerceIn(0f, maxPx)
        val index = (positionPx / itemWidthPx).roundToInt().coerceIn(0, maxIndex)
        if (index != selection) {
            selection = index
            if (!suppressCallback) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onSelectionChanged?.invoke(index)
                announceSelection()
            }
        }
        invalidate()
    }

    private fun announceSelection() {
        contentDescription = "$controlLabel, ${labelAt(selection)}"
        // Always dispatch; the framework coalesces and no-ops when no service is active.
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isEnabled) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MINUS -> {
                moveSelection(-1); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PLUS -> {
                moveSelection(1); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /** One-detent move that commits immediately, since there is no gesture to release. */
    fun moveSelection(delta: Int) {
        val target = (selection + delta).coerceIn(0, maxIndex)
        if (target == selection) return
        forceSelection(target)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onSelectionChanged?.invoke(target)
        announceSelection()
        onSettle?.invoke()
    }

    // ------------------------------------------------------------ accessibility

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // Without this a bare custom view is opaque to TalkBack. Restores the range
        // semantics Material Slider provided for free.
        info.className = "android.widget.SeekBar"
        info.contentDescription = "$controlLabel, ${labelAt(selection)}"
        info.isScrollable = true
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
            0f,
            maxIndex.toFloat(),
            selection.toFloat()
        )
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        return when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
                moveSelection(1); true
            }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                moveSelection(-1); true
            }
            actionSetProgressId -> {
                val target = arguments
                    ?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)
                    ?.roundToInt()
                if (target == null) return false
                val clamped = target.coerceIn(0, maxIndex)
                if (clamped == selection) return false
                forceSelection(clamped)
                onSelectionChanged?.invoke(clamped)
                announceSelection()
                onSettle?.invoke()
                true
            }
            else -> super.performAccessibilityAction(action, arguments)
        }
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.fromIndex = selection
        event.toIndex = selection
        event.itemCount = itemCount
    }

    // -------------------------------------------------------------- lifecycle

    override fun onSaveInstanceState(): Parcelable {
        return Bundle().apply {
            putParcelable(KEY_SUPER, super.onSaveInstanceState())
            putInt(KEY_SELECTION, selection)
        }
    }

    @Suppress("DEPRECATION")
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            forceSelection(state.getInt(KEY_SELECTION, selection))
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
        const val SNAP_DURATION_MS = 180
        const val KEY_SUPER = "wheel.super"
        const val KEY_SELECTION = "wheel.selection"
    }
}
