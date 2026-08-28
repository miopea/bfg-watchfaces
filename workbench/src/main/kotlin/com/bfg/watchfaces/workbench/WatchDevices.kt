package com.bfg.watchfaces.workbench

import java.io.File

/**
 * Which watches are attached, and whether they can take a pushed face.
 *
 * ## This is a stand-in, and the shape matters more than the mechanism
 *
 * On a real device this is the Wear Data Layer — `CapabilityClient` discovery
 * over Bluetooth. The workbench has no Bluetooth and no paired phone, so it asks
 * `adb`. That is a DEV STAND-IN, not the shipping mechanism.
 *
 * Because of that, everything except the two `ProcessBuilder` calls is pure and
 * tested: parsing a device list, reading properties into a [Device], and judging
 * whether it can take a push. When this is rebuilt on the Data Layer the
 * discovery changes and the judgement does not, so the part with the rules in it
 * survives the swap.
 *
 * ## Wear OS 6 is a hard floor
 *
 * docs/SPEC.md: Watch Face Push is Wear OS 6+ only — Pixel Watch 4 and 5, recent
 * Galaxy Watch. Wear OS 6 is API 36, confirmed against the emulator image
 * (`system-images;android-36;android-wear-signed`, which avdmanager reports as
 * "Wear OS 6.0"). An older watch is not a degraded experience, it simply cannot
 * receive a face, and saying so early is kinder than failing at install.
 */
object WatchDevices {

    /** Wear OS 6 = API 36. Below this, Watch Face Push does not exist. */
    const val MIN_PUSH_SDK = 36

    data class Device(
        val serial: String,
        val state: String,
        val model: String = "",
        val release: String = "",
        val sdk: Int = 0,
        val isWatch: Boolean = false
    ) {
        val online: Boolean get() = state == "device"

        /** Can this actually receive a pushed watch face? */
        val supportsPush: Boolean get() = online && isWatch && sdk >= MIN_PUSH_SDK

        /** What to call it in the UI, in a user's words rather than a serial. */
        val label: String get() = when {
            model.isNotBlank() -> model
            serial.startsWith("emulator-") -> "Emulator $serial"
            else -> serial
        }

        /**
         * Why it cannot take a face, or null when it can. Phrased for someone
         * holding a watch, not reading a log.
         */
        val blockedReason: String? get() = when {
            state == "unauthorized" -> "Waiting for you to allow this computer on the watch"
            state == "offline" -> "Connected but not responding"
            !online -> "Not connected"
            !isWatch -> "This is a phone or tablet, not a watch"
            sdk in 1 until MIN_PUSH_SDK ->
                "Runs Wear OS ${wearOsName(sdk)}. Sending faces needs Wear OS 6 or newer"
            sdk == 0 -> "Could not read its Android version"
            else -> null
        }
    }

    /** Rough Wear OS name for an API level, for a message a person can act on. */
    fun wearOsName(sdk: Int): String = when {
        sdk >= 37 -> "7"
        sdk >= 36 -> "6"
        sdk >= 34 -> "5"
        sdk >= 33 -> "4"
        sdk >= 30 -> "3"
        else -> "older than 3"
    }

    /** The states adb reports in the second column. Anything else is not a device row. */
    private val ADB_STATES = setOf(
        "device", "offline", "unauthorized", "bootloader",
        "recovery", "sideload", "host", "authorizing", "connecting"
    )

    /**
     * Parse `adb devices`.
     *
     * Rows are recognised by their STATE, not by position. Dropping the first
     * line looks equivalent and is not: adb prints "* daemon not running" and
     * "* daemon started successfully" BEFORE the banner when it cold-starts, so
     * positional dropping leaves "List of devices attached" to be parsed as a
     * device called "List" in state "of". Pure, so it can be tested without adb.
     */
    fun parseDeviceList(output: String): List<Pair<String, String>> =
        output.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && parts[0].isNotBlank() && parts[1] in ADB_STATES) {
                parts[0] to parts[1]
            } else null
        }

    /** Build a [Device] from adb properties. Pure; the lookup is injected. */
    fun describe(serial: String, state: String, props: Map<String, String>): Device = Device(
        serial = serial,
        state = state,
        model = props["ro.product.model"].orEmpty().trim(),
        release = props["ro.build.version.release"].orEmpty().trim(),
        sdk = props["ro.build.version.sdk"]?.trim()?.toIntOrNull() ?: 0,
        // Wear OS sets this; a phone does not.
        isWatch = props["ro.build.characteristics"].orEmpty().contains("watch")
    )

    // ---- the thin, untestable part: actually calling adb ----------------------

    private fun adb(): File? =
        File(System.getenv("ANDROID_HOME") ?: "", "platform-tools/adb").takeIf { it.isFile }

    private fun run(vararg args: String, timeoutMs: Long = 8000): String? = runCatching {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly(); return null
        }
        p.inputStream.bufferedReader().readText()
    }.getOrNull()

    /**
     * Everything adb can see, with the properties needed to judge each one.
     *
     * Returns null when adb is unavailable at all, which is a different state
     * from "no watches attached" and the UI says so differently.
     */
    fun list(): List<Device>? {
        val adb = adb() ?: return null
        val out = run(adb.path, "devices") ?: return null
        return parseDeviceList(out).map { (serial, state) ->
            if (state != "device") {
                Device(serial, state)
            } else {
                val props = listOf(
                    "ro.product.model", "ro.build.version.release",
                    "ro.build.version.sdk", "ro.build.characteristics"
                ).associateWith { key ->
                    run(adb.path, "-s", serial, "shell", "getprop", key)?.trim().orEmpty()
                }
                describe(serial, state, props)
            }
        }
    }

    /** Install an APK on one specific device. */
    fun install(serial: String, apk: File): Pair<Boolean, String> {
        val adb = adb() ?: return false to "adb is not available"
        val out = run(adb.path, "-s", serial, "install", "-r", apk.absolutePath, timeoutMs = 600_000)
            ?: return false to "the install timed out"
        val ok = out.contains("Success")
        return ok to out.trim().takeLast(400)
    }
}
