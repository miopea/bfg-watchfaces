package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.ComplicationSource
import com.bfg.watchfaces.generator.DateScale
import com.bfg.watchfaces.generator.HourFormat
import com.bfg.watchfaces.generator.RingSource
import com.bfg.watchfaces.generator.DateStyle
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.Engine
import com.bfg.watchfaces.generator.Layout
import com.bfg.watchfaces.generator.SlotPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The codec is what a saved face IS.
 *
 * A face is stored as parameters, so anything [FaceCodec] drops is gone from
 * every design someone saved -- silently, and only noticed the next time they
 * open it and their date or their seconds are missing.
 *
 * It had no test at all until `dateStyle` was added to [DialParams], written
 * through the emitter, and never wired into the codec. Nothing failed. The
 * completeness test below is the one that has to fail for the NEXT field,
 * because it is the only one that does not need to be updated to notice.
 */
class FaceCodecTest {

    /**
     * A face with nothing left at its default.
     *
     * Every value here differs from [DialParams]()'s, which is what makes a
     * round trip meaningful: a codec that silently returned the defaults would
     * pass a test built on defaults.
     */
    private fun loaded() = DialParams(
        engine = Engine.ROSETTE,
        scale = 42.5, depth = 31.0, freq = 7, stroke = 2.5, relief = 3.5,
        contrast = 61.0, rotate = 22.0, vignette = 44.0, sheen = 55.0,
        dialColor = "#123456", inkColor = "#FEDCBA",
        showSeconds = true,
        iconSlots = setOf(SlotPosition.TOP, SlotPosition.RIGHT),
        providers = mapOf(
            SlotPosition.RIGHT to "com.example.weather/.WeatherProvider",
            SlotPosition.LEFT to "com.example.health/.Steps"
        ),
        launchers = mapOf(SlotPosition.MIDDLE to "com.example.player/.Main"),
        dateStyle = DateStyle.WEEKDAY_MONTH_DAY,
        dateScale = DateScale.LARGE,
        ring = RingSource.BATTERY,
        hourFormat = HourFormat.TWENTY_FOUR,
        lens = true, lensAmount = 33.0,
        complications = listOf(
            ComplicationSource.STEP_COUNT, ComplicationSource.HEART_RATE,
            ComplicationSource.SHORTCUT_APP, ComplicationSource.SUNRISE_SUNSET,
            ComplicationSource.WEATHER_TEMPERATURE
        ),
        layout = Layout(
            dateY = 141, dateSize = 23, timeY = 211, timeSize = 97, tracking = 3.0,
            complicationY = 301, complicationSpread = 121, complicationSize = 41,
            batteryY = 361,
            fontFamily = "ROBOTO", fontWeight = "BOLD"
        )
    )

    /**
     * Every top-level field of [DialParams] survives a query round trip.
     *
     * The field list comes from the data class's own `toString`, not a list
     * maintained here -- so adding a field to [DialParams] and forgetting the
     * codec fails this test without anyone remembering to update it. That is
     * the entire point: the two tests below would both have passed while
     * `dateStyle` was being dropped, because neither mentioned it.
     */
    @Test
    fun `every field of DialParams is carried by the query form`() {
        val query = FaceCodec.toQuery(loaded())
        // Fields that are NOT their own key, and why.
        //
        // `layout` is flattened into its own keys. `launchers` rides inside the
        // per-slot token ("SHORTCUT_APP+open:pkg/cls"), which is the whole
        // point of one namespaced string per slot. Both are covered by the
        // round-trip tests below, which is the stronger check -- this one only
        // catches a field nobody wired anywhere at all.
        val folded = setOf("layout", "launchers")

        // DELIBERATELY not in the stored format, which is a different thing
        // from being forgotten.
        //
        // `debugRanged` swaps a complication's reading for its raw min/value/max
        // so the bar can be diagnosed on a wrist. It is set from a device
        // setting at build time. If it round-tripped it could be SHARED, and a
        // catalog face rendering "4210/0-10000" on a stranger's watch would look
        // like a broken app with nothing to explain it. See the test at the
        // bottom of this file, which pins the exclusion from the other side.
        val deviceOnly = setOf("debugRanged")

        val missing = fieldNames().filter { name ->
            name !in folded && name !in deviceOnly && !query.contains("$name=")
        }
        assertTrue(missing.isEmpty()) {
            "FaceCodec.toQuery drops ${missing.joinToString(", ")} -- every face " +
                "saved with those set loses them. Add them to fromQuery, toQuery and toJson."
        }
        // And the exclusions must still BE fields, or this list silently rots
        // into a set of names that no longer mean anything.
        assertTrue(fieldNames().containsAll(folded + deviceOnly)) {
            "the folded/device-only lists name a field DialParams no longer has"
        }
    }

    @Test
    fun `a fully loaded face survives a query round trip`() {
        val original = loaded()
        val back = FaceCodec.fromQuery(parse(FaceCodec.toQuery(original)))
        assertEquals(original, back)
    }

    @Test
    fun `a fully loaded face survives a JSON round trip`() {
        val original = loaded()
        val back = FaceCodec.fromJson(Json.obj(Json.parse(FaceCodec.toJson(original))))
        assertEquals(original, back)
    }

    @Test
    fun `an unknown date style falls back rather than throwing`() {
        // A face saved by a newer build has to still open, minus what this
        // build does not understand. Throwing would make it unopenable.
        val back = FaceCodec.fromQuery(mapOf("dateStyle" to "SOMETHING_LATER"))
        assertEquals(DialParams().dateStyle, back.dateStyle)
    }

    @Test
    fun `an empty query is the default face`() {
        assertEquals(DialParams(), FaceCodec.fromQuery(emptyMap()))
    }


    @Test
    fun `the old single icon switch still opens a face saved with it`() {
        // showComplicationIcons preceded iconSlots. Faces were saved with it,
        // and dropping it would silently turn every glyph back on.
        val off = FaceCodec.fromQuery(mapOf("showComplicationIcons" to "false"))
        assertTrue(off.iconSlots.isEmpty()) { "a face saved with glyphs off got them back" }

        val on = FaceCodec.fromQuery(mapOf("showComplicationIcons" to "true"))
        assertEquals(SlotPosition.entries.toSet(), on.iconSlots)
    }

    @Test
    fun `iconSlots wins over the switch it replaced`() {
        val p = FaceCodec.fromQuery(
            mapOf("showComplicationIcons" to "false", "iconSlots" to "TOP,BOTTOM")
        )
        assertEquals(setOf(SlotPosition.TOP, SlotPosition.BOTTOM), p.iconSlots)
    }


    @Test
    fun `a provider naming an unknown slot is skipped, not fatal`() {
        val p = FaceCodec.fromQuery(mapOf("providers" to "ELBOW:com.example/.X,RIGHT:com.example/.Y"))
        assertEquals(mapOf(SlotPosition.RIGHT to "com.example/.Y"), p.providers)
    }

    @Test
    fun `a face with no chosen providers round trips as empty`() {
        val back = FaceCodec.fromQuery(parse(FaceCodec.toQuery(DialParams())))
        assertTrue(back.providers.isEmpty())
    }


    @Test
    fun `a shortcut's app and a provider's app do not collide in one token`() {
        // Filling a slot with a reading and opening something when it is
        // pressed are different jobs, and one string per slot has to say which
        // it meant. "+app:" and "+open:" are how.
        val token = Complications.token(
            ComplicationSource.STEP_COUNT,
            component = "com.example.fit/.Provider",
            launcher = "com.example.player/.Main"
        )
        assertEquals("STEP_COUNT", Complications.sourceIn(token))
        assertEquals("com.example.fit/.Provider", Complications.componentIn(token))
        assertEquals("com.example.player/.Main", Complications.launcherIn(token))
    }


    @Test
    fun `a face saved with the old stepRing flag still opens`() {
        // The ring was a boolean for one release. Dropping the key would turn
        // every ring saved in that window off with no way to tell.
        assertEquals(RingSource.STEPS, FaceCodec.fromQuery(mapOf("stepRing" to "true")).ring)
        assertEquals(RingSource.NONE, FaceCodec.fromQuery(mapOf("stepRing" to "false")).ring)
        // And the new key wins when both are present.
        assertEquals(
            RingSource.BATTERY,
            FaceCodec.fromQuery(mapOf("stepRing" to "true", "ring" to "BATTERY")).ring
        )
    }

    /** Top-level property names, read off the data class rather than listed. */
    private fun fieldNames(): List<String> {
        val s = DialParams().toString()
        val body = s.substringAfter("(").substringBeforeLast(")")
        return Regex("(?:^|, )([a-zA-Z][a-zA-Z0-9]*)=").findAll(body)
            .map { it.groupValues[1] }.toList()
    }

    private fun parse(query: String): Map<String, String> =
        query.split("&").filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore("=")
            val v = java.net.URLDecoder.decode(it.substringAfter("=", ""), "UTF-8")
            k to v
        }

    /**
     * A face saved before the tilt glow had a control reads back at full glow.
     *
     * This is what lets the control ship with no `generatorVersion` branch.
     * Every stored face in the catalog and in anybody's library predates the
     * `glare` key; if the missing key read as anything but 100, adding a slider
     * would have silently dimmed every face already saved.
     */
    @Test
    fun `a face saved before the glare control reads back at full glow`() {
        val stored = parse(FaceCodec.toQuery(DialParams())).toMutableMap()
        stored.remove("glare")
        assertTrue(!stored.containsKey("glare")) { "the key was not actually removed" }
        assertEquals(100.0, FaceCodec.fromQuery(stored).glare, 1e-9) {
            "an older face dimmed itself just by being loaded"
        }
    }

    /** And a face that DID choose a value keeps it. */
    @Test
    fun `a chosen glare survives the round trip`() {
        for (v in listOf(0.0, 40.0, 100.0)) {
            val q = parse(FaceCodec.toQuery(DialParams(glare = v)))
            assertEquals(v, FaceCodec.fromQuery(q).glare, 1e-9) { "glare=$v did not survive" }
        }
    }

    /**
     * `debugRanged` must never survive a round trip.
     *
     * It is a device-side diagnostic that swaps a complication's reading for
     * its raw minimum, value and maximum. If it could be stored it could be
     * SHARED -- and a catalog face that renders "4210/0-10000" on somebody
     * else's wrist would look like a broken app, with nothing in the design to
     * explain it and no way for them to turn it off.
     *
     * The protection is that the codec simply does not know the field. This
     * pins that, because the natural instinct when adding a parameter is to add
     * it everywhere.
     */
    @Test
    fun `the ranged diagnostic is never written to a face`() {
        val on = DialParams(rangedBars = true, debugRanged = true)
        val json = FaceCodec.toJson(on)
        assertFalse(json.contains("debugRanged")) { "a diagnostic flag reached the stored format" }
        assertFalse(FaceCodec.toQuery(on).contains("debugRanged")) { "it reached the query form" }
        // And it comes back OFF, however it was stored.
        assertFalse(FaceCodec.fromJson(Json.obj(Json.parse(json))).debugRanged) {
            "a face round-tripped with diagnostics still on"
        }
        assertFalse(FaceCodec.fromQuery(mapOf("debugRanged" to "true")).debugRanged) {
            "a hand-written query turned diagnostics on"
        }
        // Everything else about the face must survive, or this test would pass
        // by the codec being broken.
        assertTrue(FaceCodec.fromJson(Json.obj(Json.parse(json))).rangedBars) {
            "rangedBars was lost, so this proves nothing"
        }
    }
}
