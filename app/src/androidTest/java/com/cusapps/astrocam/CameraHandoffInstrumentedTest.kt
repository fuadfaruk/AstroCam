package com.cusapps.astrocam

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.widget.CompoundButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a camera-capable device, including API 31 and API 34+ foreground-service checks. */
@RunWith(AndroidJUnit4::class)
class CameraHandoffInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    @Before
    fun grantPermissionsAndWake() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= 28) permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        permissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
        wakeAndUnlock()
    }

    @After
    fun releaseCameraAndService() {
        wakeAndUnlock()
        context.stopService(Intent(context, CameraService::class.java))
        await("camera release after finishing") { main { CameraOwner.ownership.currentOwner == null } }
        await("foreground notification removal") { notifications.activeNotifications.isEmpty() }
    }

    @Test
    fun preparingAndDisablingBackgroundModeDoesNotStealPreview() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val activity = activityFrom(scenario)
            awaitPreview(activity)
            scenario.onActivity { it.findViewById<CompoundButton>(R.id.manualModeSwitch).isChecked = true }
            awaitPreparedService(activity)
            assertTrue(main { previewReady(activity) })
            assertEquals(CameraOwnership.Owner.ACTIVITY, main { CameraOwner.ownership.currentOwner })

            scenario.onActivity { it.findViewById<CompoundButton>(R.id.manualModeSwitch).isChecked = false }
            await("disabled background service") { notifications.activeNotifications.isEmpty() }
            assertTrue(main { previewReady(activity) })
        }
    }

    @Test
    fun homeAndForegroundRoundTripsPreserveManualSettings() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val activity = activityFrom(scenario)
            enableBackgroundMode(scenario, activity)
            val settings = main { settingsText(activity) }
            repeat(3) {
                shell("input keyevent KEYCODE_HOME")
                awaitBackgroundCamera()
                assertNull(main { field(activity, "cameraDevice") })

                foreground()
                awaitPreview(activity)
                assertEquals(settings, main { settingsText(activity) })
            }
        }
    }

    @Test
    fun screenOffCaptureAndForegroundReturnUsePreparedCameraService() {
        // The test cannot dismiss a user's secure lock screen.
        assumeFalse(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val activity = activityFrom(scenario)
            enableBackgroundMode(scenario, activity)
            val service = main { field(activity, "cameraService") as CameraService }
            val token = main {
                (field(service, "mediaSession") as MediaSessionCompat).sessionToken
            }
            val existingPhotos = publishedPhotoIds()
            try {
                shell("input keyevent KEYCODE_SLEEP")
                await("screen off") { !power.isInteractive }
                awaitBackgroundCamera()
                assertFalse(power.isInteractive)

                MediaControllerCompat(context, token).transportControls.play()
                await("photo saved with screen off", timeoutMillis = 60_000) {
                    publishedPhotoIds().any { it !in existingPhotos } && main {
                        !(field(service, "captureSequence") as CaptureSequence).isCaptureInProgress
                    }
                }
                assertFalse(power.isInteractive)

                wakeAndUnlock()
                foreground()
                awaitPreview(activity)
                assertEquals(CameraOwnership.Owner.ACTIVITY, main { CameraOwner.ownership.currentOwner })
            } finally {
                wakeAndUnlock()
            }
        }
    }

    @Test
    fun rapidHomeAndForegroundTransitionsDoNotReopenStaleBackgroundRequests() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val activity = activityFrom(scenario)
            enableBackgroundMode(scenario, activity)
            repeat(5) {
                shell("input keyevent KEYCODE_HOME")
                await("activity stop during rapid transition") {
                    main { field(activity, "activityStarted") == false }
                }
                foreground()
                awaitPreview(activity)
                assertEquals(CameraOwnership.Owner.ACTIVITY, main { CameraOwner.ownership.currentOwner })
            }
        }
    }

    @Test
    fun autoModeReleasesCameraWithoutStartingServiceFromBackground() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val activity = activityFrom(scenario)
            awaitPreview(activity)
            shell("input keyevent KEYCODE_HOME")
            await("auto-mode camera release") { main { CameraOwner.ownership.currentOwner == null } }
            assertTrue(notifications.activeNotifications.isEmpty())

            foreground()
            awaitPreview(activity)
        }
    }

    private fun enableBackgroundMode(scenario: ActivityScenario<MainActivity>, activity: MainActivity) {
        awaitPreview(activity)
        scenario.onActivity { it.findViewById<CompoundButton>(R.id.manualModeSwitch).isChecked = true }
        awaitPreparedService(activity)
    }

    private fun awaitPreparedService(activity: MainActivity) {
        await("visible camera foreground-service preparation") {
            main { (field(activity, "cameraService") as? CameraService)?.isForegroundReady == true } &&
                notifications.activeNotifications.isNotEmpty()
        }
    }

    private fun awaitBackgroundCamera() {
        await("exclusive background camera session") {
            main { CameraOwner.ownership.currentOwner == CameraOwnership.Owner.SERVICE } &&
                notificationText() == "Ready to capture even when screen is off"
        }
    }

    private fun awaitPreview(activity: MainActivity) {
        await("foreground preview session") { main { previewReady(activity) } }
    }

    private fun previewReady(activity: MainActivity): Boolean =
        CameraOwner.ownership.currentOwner == CameraOwnership.Owner.ACTIVITY &&
            field(activity, "cameraDevice") != null && field(activity, "captureSession") != null

    private fun settingsText(activity: MainActivity): List<String> = listOf(
        R.id.isoValueText, R.id.shutterSpeedValueText, R.id.focusDistanceValueText, R.id.whiteBalanceValueText
    ).map { activity.findViewById<TextView>(it).text.toString() }

    private fun activityFrom(scenario: ActivityScenario<MainActivity>): MainActivity {
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it }
        return activity
    }

    private fun notificationText(): String? = notifications.activeNotifications.firstOrNull()
        ?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun publishedPhotoIds(): Set<Long> {
        val selection = if (Build.VERSION.SDK_INT >= 29) {
            "${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
        } else null
        val args = if (Build.VERSION.SDK_INT >= 29) arrayOf(context.packageName) else null
        return context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), selection, args, null
        )?.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        } ?: emptySet()
    }

    private fun foreground() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        ))
    }

    private fun wakeAndUnlock() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun await(description: String, timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Timed out waiting for $description; notification=${notificationText()}")
    }
}
