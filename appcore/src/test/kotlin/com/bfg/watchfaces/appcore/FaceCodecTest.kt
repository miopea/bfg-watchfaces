package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.ComplicationSource
import com.bfg.watchfaces.generator.DateStyle
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.Engine
import com.bfg.watchfaces.generator.Layout
import org.junit.jupiter.api.Assertions.assertEquals
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
        showComplicationIcons = false,
        dateStyle = DateStyle.WEEKDAY_MONTH_DAY,
        lens = true, lensAmount = 33.0,
        complications = listOf(
            ComplicationSource.STEP_COUNT, ComplicationSource.HEART_RATE,
            ComplicationSource.WATCH_BATTERY
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
        val missing = fieldNames().filter { name ->
            // `layout` is flattened into its own keys rather than carried whole.
            name != "layout" && !query.contains("$name=")
        }
        assertTrue(missing.isEmpty()) {
            "FaceCodec.toQuery drops ${missing.joinToString(", ")} -- every face " +
                "saved with those set loses them. Add them to fromQuery, toQuery and toJson."
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
}
