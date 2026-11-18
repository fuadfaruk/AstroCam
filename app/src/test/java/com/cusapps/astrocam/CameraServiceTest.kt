package com.cusapps.astrocam

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34, 35])
class CameraServiceTest {
    @Test
    fun `visible preparation promotes camera service without taking the camera`() {
        val controller = Robolectric.buildService(CameraService::class.java).create()
        val service = controller.get()
        try {
            assertTrue(service.isForegroundReady)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA, service.foregroundServiceType)
            assertEquals(Service.START_NOT_STICKY,
                service.onStartCommand(Intent(CameraService.ACTION_PREPARE), 0, 1))
            assertNull(CameraOwner.ownership.currentOwner)

            service.pauseCamera()
            assertNull(CameraOwner.ownership.currentOwner)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `stale cancel action cannot resurrect an unprepared background camera`() {
        val controller = Robolectric.buildService(CameraService::class.java).create()
        val service = controller.get()
        try {
            assertEquals(Service.START_NOT_STICKY,
                service.onStartCommand(Intent(CameraService.ACTION_CANCEL_CAPTURE), 0, 1))
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertNull(CameraOwner.ownership.currentOwner)
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Config(sdk = [34, 35], shadows = [DeniedCameraService::class])
    fun `modern while in use denial stops service without crashing or taking camera`() {
        val controller = Robolectric.buildService(CameraService::class.java).create()
        val service = controller.get()
        try {
            assertTrue(Build.VERSION.SDK_INT >= 34)
            assertFalse(service.isForegroundReady)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(Service.START_NOT_STICKY,
                service.onStartCommand(Intent(CameraService.ACTION_PREPARE), 0, 1))
            service.activateCamera(Intent().putExtra(CameraService.EXTRA_CAMERA_ID, "0"))
            assertNull(CameraOwner.ownership.currentOwner)
        } finally {
            controller.destroy()
        }
    }

    @Implements(Service::class)
    class DeniedCameraService : ShadowService() {
        @Implementation(minSdk = 34)
        protected override fun startForeground(id: Int, notification: Notification, type: Int) {
            throw SecurityException("Camera foreground service requires a visible activity.")
        }
    }
}
