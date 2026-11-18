package com.cusapps.astrocam

import com.cusapps.astrocam.CameraOwnership.Owner.ACTIVITY
import com.cusapps.astrocam.CameraOwnership.Owner.SERVICE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOwnershipTest {
    private val ownership = CameraOwnership()
    private val granted = mutableListOf<CameraOwnership.Request>()

    private fun request(owner: CameraOwnership.Owner) = CameraOwnership.Request(owner) { granted.add(it) }

    @Test
    fun `screen off handoff waits for activity onClosed rather than close returning`() {
        val activity = request(ACTIVITY)
        val service = request(SERVICE)
        var closes = 0
        ownership.acquire(activity)
        assertTrue(ownership.opened(activity) { closes++ })

        ownership.close(activity)
        ownership.acquire(service)

        assertEquals(1, closes)
        assertEquals(listOf(activity), granted)
        assertEquals(ACTIVITY, ownership.currentOwner)

        ownership.release(activity)
        assertEquals(listOf(activity, service), granted)
        assertEquals(SERVICE, ownership.currentOwner)
    }

    @Test
    fun `foreground return waits for service onClosed`() {
        val service = request(SERVICE)
        val activity = request(ACTIVITY)
        ownership.acquire(service)
        assertTrue(ownership.opened(service) {})

        ownership.close(service)
        ownership.acquire(activity)
        assertEquals(listOf(service), granted)

        ownership.release(service)
        assertEquals(listOf(service, activity), granted)
        assertEquals(ACTIVITY, ownership.currentOwner)
    }

    @Test
    fun `screen off during activity open closes late camera without publishing it`() {
        val activity = request(ACTIVITY)
        val service = request(SERVICE)
        var closes = 0
        ownership.acquire(activity)
        ownership.close(activity)
        ownership.acquire(service)

        assertFalse(ownership.opened(activity) { closes++ })
        assertEquals(1, closes)
        assertEquals(listOf(activity), granted)

        ownership.release(activity)
        assertEquals(listOf(activity, service), granted)
    }

    @Test
    fun `rapid foreground return cancels queued background ownership`() {
        val oldActivity = request(ACTIVITY)
        val service = request(SERVICE)
        val newActivity = request(ACTIVITY)
        ownership.acquire(oldActivity)
        ownership.close(oldActivity)
        ownership.acquire(service)

        ownership.close(service)
        ownership.acquire(newActivity)
        ownership.release(oldActivity)

        assertEquals(listOf(oldActivity, newActivity), granted)
        assertEquals(ACTIVITY, ownership.currentOwner)
    }

    @Test
    fun `foreground return during service open closes late device before activity opens`() {
        val service = request(SERVICE)
        val activity = request(ACTIVITY)
        var closes = 0
        ownership.acquire(service)
        ownership.close(service)
        ownership.acquire(activity)

        assertFalse(ownership.opened(service) { closes++ })
        assertEquals(1, closes)
        assertEquals(listOf(service), granted)

        ownership.release(service)
        assertEquals(listOf(service, activity), granted)
    }

    @Test
    fun `duplicate close and stale onClosed callbacks never admit a second owner`() {
        val first = request(ACTIVITY)
        val second = request(SERVICE)
        val third = request(ACTIVITY)
        var closes = 0
        ownership.acquire(first)
        ownership.opened(first) { closes++ }
        ownership.acquire(second)
        ownership.acquire(third)

        ownership.close(first)
        ownership.close(first)
        ownership.release(first)
        ownership.release(first)

        assertEquals(1, closes)
        assertEquals(listOf(first, second), granted)
        assertEquals(SERVICE, ownership.currentOwner)
    }

    @Test
    fun `synchronous open failure releases only its lease`() {
        val failed = request(ACTIVITY)
        val service = request(SERVICE)
        val activity = request(ACTIVITY)
        ownership.acquire(failed)
        ownership.acquire(service)
        ownership.acquire(activity)

        ownership.release(failed)
        ownership.release(failed)

        assertEquals(listOf(failed, service), granted)
        assertEquals(SERVICE, ownership.currentOwner)
    }

    @Test
    fun `camera error after open retains ownership until closed`() {
        val failed = request(SERVICE)
        val activity = request(ACTIVITY)
        ownership.acquire(failed)
        ownership.opened(failed) {}
        ownership.acquire(activity)
        ownership.close(failed)

        assertEquals(listOf(failed), granted)
        ownership.release(failed)
        assertEquals(listOf(failed, activity), granted)
    }

    @Test
    fun `repeated screen off and foreground cycles preserve exclusive ownership`() {
        repeat(25) {
            val activity = request(ACTIVITY)
            val service = request(SERVICE)
            ownership.acquire(activity)
            ownership.opened(activity) {}
            assertEquals(ACTIVITY, ownership.currentOwner)
            ownership.close(activity)
            ownership.acquire(service)
            assertEquals(ACTIVITY, ownership.currentOwner)
            ownership.release(activity)
            ownership.opened(service) {}
            assertEquals(SERVICE, ownership.currentOwner)
            ownership.close(service)
            ownership.release(service)
            assertNull(ownership.currentOwner)
        }
        assertEquals(50, granted.size)
    }
}
