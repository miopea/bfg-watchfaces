package com.bfg.watchfaces.workbench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Device discovery is adb today and the Data Layer on a real phone, so the
 * mechanism is going to be replaced. The JUDGEMENT is not: which devices can
 * take a pushed face, and what to tell someone about the ones that cannot.
 * That is what these tests hold, so the rules survive the swap.
 */
class WatchDevicesTest {

    private fun watch(sdk: Int = 36, state: String = "device", model: String = "Pixel Watch 4") =
        WatchDevices.describe(
            "adb-XYZ", state, mapOf(
                "ro.product.model" to model,
                "ro.build.version.release" to "16",
                "ro.build.version.sdk" to sdk.toString(),
                "ro.build.characteristics" to "nosdcard,watch"
            )
        )

    @Test
    fun `adb device list parses, ignoring the banner and daemon chatter`() {
        val out = """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            emulator-5554	device
            29181FDH200ABC	unauthorized
            192.168.1.9:5555	offline

        """.trimIndent()
        val parsed = WatchDevices.parseDeviceList(out)
        // The banner line is dropped, the two daemon lines are not devices, and
        // blank lines are not entries.
        assertEquals(
            listOf(
                "emulator-5554" to "device",
                "29181FDH200ABC" to "unauthorized",
                "192.168.1.9:5555" to "offline"
            ),
            parsed
        )
    }

    @Test
    fun `a Wear OS 6 watch can receive a face`() {
        val d = watch(sdk = 36)
        assertTrue(d.isWatch)
        assertTrue(d.supportsPush)
        assertNull(d.blockedReason)
        assertEquals("Pixel Watch 4", d.label)
    }

    @Test
    fun `an older watch is refused with a reason someone can act on`() {
        // docs/SPEC.md: Watch Face Push is Wear OS 6+ only. An older watch is
        // not degraded, it simply cannot receive a face, and saying so early
        // beats failing at install time.
        val d = watch(sdk = 34)
        assertFalse(d.supportsPush)
        val why = d.blockedReason
        assertNotNull(why)
        assertTrue(why!!.contains("Wear OS 5")) { "should name the version it has: $why" }
        assertTrue(why.contains("Wear OS 6")) { "should name the version it needs: $why" }
    }

    @Test
    fun `a phone is not offered as a target`() {
        val phone = WatchDevices.describe(
            "PHONE1", "device", mapOf(
                "ro.product.model" to "Pixel 9",
                "ro.build.version.sdk" to "36",
                "ro.build.characteristics" to "nosdcard"
            )
        )
        assertFalse(phone.isWatch)
        assertFalse(phone.supportsPush)
        assertTrue(phone.blockedReason!!.contains("not a watch"))
    }

    @Test
    fun `an unauthorised watch says what the person has to do`() {
        val d = watch(state = "unauthorized")
        assertFalse(d.supportsPush)
        // The fix is on the watch, so the message points there rather than
        // reporting a state name.
        assertTrue(d.blockedReason!!.contains("allow this computer")) { d.blockedReason!! }
    }

    @Test
    fun `an offline device is distinguished from an absent one`() {
        assertTrue(watch(state = "offline").blockedReason!!.contains("not responding"))
        assertFalse(watch(state = "offline").online)
    }

    @Test
    fun `a device with unreadable properties is refused rather than assumed good`() {
        // Missing props used to be indistinguishable from a fine device. Better
        // to refuse and say so than to attempt a push that cannot work.
        val d = WatchDevices.describe("X", "device", emptyMap())
        assertFalse(d.supportsPush)
        assertNotNull(d.blockedReason)
        assertEquals("X", d.label) { "with no model, the serial is the only honest label" }
    }

    @Test
    fun `wear os names map to the api levels that matter here`() {
        assertEquals("6", WatchDevices.wearOsName(36))
        assertEquals("5", WatchDevices.wearOsName(34))
        assertEquals("4", WatchDevices.wearOsName(33))
        // The floor is the thing this file exists to enforce.
        assertEquals(36, WatchDevices.MIN_PUSH_SDK)
    }

    @Test
    fun `an emulator without a model still reads as something`() {
        val d = WatchDevices.describe(
            "emulator-5554", "device",
            mapOf("ro.build.version.sdk" to "36", "ro.build.characteristics" to "watch")
        )
        assertTrue(d.supportsPush)
        assertTrue(d.label.contains("emulator-5554"))
    }
}
