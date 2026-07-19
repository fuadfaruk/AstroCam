package com.cusapps.astrocam

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

object TapToFocusHelper {
    fun getFocusArea(
        event: MotionEvent,
        viewWidth: Int,
        viewHeight: Int,
        characteristics: CameraCharacteristics
    ): MeteringRectangle? {
        val rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null

        // Map touch coordinates to sensor coordinates
        val touchX = (event.x / viewWidth) * rect.width()
        val touchY = (event.y / viewHeight) * rect.height()

        // Create a focus area of 150x150 pixels (or adjust as needed)
        val halfTouchWidth = 150
        val halfTouchHeight = 150

        // Ensure the area stays within the sensor bounds
        val left = max(0, (touchX - halfTouchWidth).toInt())
        val top = max(0, (touchY - halfTouchHeight).toInt())
        val right = min(rect.width(), (touchX + halfTouchWidth).toInt())
        val bottom = min(rect.height(), (touchY + halfTouchHeight).toInt())

        return MeteringRectangle(left, top, right - left, bottom - top, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }
}
