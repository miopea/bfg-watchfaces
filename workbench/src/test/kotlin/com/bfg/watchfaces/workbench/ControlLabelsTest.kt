package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.ComplicationSource
import com.bfg.watchfaces.generator.ControlInventory
import com.bfg.watchfaces.generator.Engine
import com.bfg.watchfaces.generator.SlotPosition
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What replaced DemoIsTheSpecTest.
 *
 * That test asserted the app's hardcoded control lists matched :generator's.
 * They cannot differ any more — the app builds itself from /api/controls — so
 * checking it would be asserting that two generated things match, which is
 * noise.
 *
 * One copy genuinely remains, and it is the one that SHOULD: the labels. Words
 * are presentation and belong to whichever front end is drawing, as DialParams
 * says. But a missing label is still a real defect, so this checks every id the
 * inventory can produce has one.
 *
 * A missing label is not fatal by design — the app falls back to showing the id,
 * so a new engine appears as "GUILLOCHE" rather than vanishing. That is the
 * right failure, and this test is what stops it shipping.
 */
class ControlLabelsTest {

    private val html: String =
        javaClass.getResourceAsStream("/workbench/index.html")!!.bufferedReader().readText()

    private fun labelKeys(mapName: String): Set<String> {
        val start = html.indexOf("const $mapName = {")
        assertTrue(start >= 0) { "the app has no $mapName; every label would fall back to a raw id" }
        val body = html.substring(start, html.indexOf("};", start))
        return Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*:""").findAll(body)
            .map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `every engine has a name a person can read`() {
        val labelled = labelKeys("ENGINE_LABELS")
        val missing = Engine.entries.map { it.name } - labelled
        assertTrue(missing.isEmpty()) { "$missing would show as raw ids. Add them to ENGINE_LABELS." }
    }

    @Test
    fun `every complication has a name a person can read`() {
        val labelled = labelKeys("COMP_LABELS")
        val missing = ComplicationSource.entries.map { it.name } - labelled
        assertTrue(missing.isEmpty()) { "$missing would show as raw ids. Add them to COMP_LABELS." }
    }

    @Test
    fun `every slot has a name a person can read`() {
        val labelled = labelKeys("SLOT_LABELS")
        val missing = SlotPosition.entries.map { it.name } - labelled
        assertTrue(missing.isEmpty()) { "$missing would show as raw ids. Add them to SLOT_LABELS." }
    }

    @Test
    fun `every slider has a name a person can read`() {
        // The ids are jargon on purpose -- "vignette", "relief" -- so a missing
        // label here is worse than elsewhere: the control is unusable, not just
        // ugly.
        val labelled = labelKeys("TUNE_LABELS")
        val missing = ControlInventory.CONTROLS.map { it.id } - labelled
        assertTrue(missing.isEmpty()) { "$missing would show as raw parameter names. Add them to TUNE_LABELS." }
    }

    @Test
    fun `the app no longer hardcodes what the controls are`() {
        // The whole point. If these come back, there are two copies again.
        for (gone in listOf("const TUNE = [", "const ENGINES = [", "const COMP_SOURCES = [")) {
            assertTrue(!html.contains(gone)) {
                "index.html hardcodes its controls again ('$gone'). They come from " +
                    "/api/controls so the two UIs cannot disagree."
            }
        }
    }
}
