package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.ComplicationSource
import com.bfg.watchfaces.generator.Engine
import com.bfg.watchfaces.generator.SlotPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The app is the specification, so it has to offer everything the format can do.
 *
 * DECISIONS.md 2026-08-28 commits to the localhost app being the exact spec for
 * the shipped one, rather than a design toy an Android app later approximates.
 * That commitment is worthless if it is only prose: the app's control lists are
 * JavaScript literals, and `:generator`'s are Kotlin enums, and today they agree
 * only because someone kept them in step by hand.
 *
 * This repo has already been bitten twice by exactly that shape. `SlotGeometry`
 * exists because the slot arithmetic was written twice with a test asserting the
 * two copies agreed -- and they agreed while both were wrong. `DECISIONS.md`
 * 2026-08-27 rejected a JavaScript re-implementation of the dial for the same
 * reason: a second copy that starts identical and drifts.
 *
 * So this asserts the interesting direction as well as the easy one. An engine
 * the app does not list is not a small omission -- it is a piece of the file
 * format no user can reach, which means the app has stopped being the spec.
 *
 * It is a stopgap, deliberately. The real fix is for both UIs to build their
 * controls from one inventory in `:generator`, so the lists cannot disagree
 * rather than being checked for disagreement. Until `:mobile` exists there is
 * only one UI to unify, and guessing the shape of that inventory before the
 * second consumer is real is how the wrong abstraction gets frozen.
 */
class DemoIsTheSpecTest {

    private val html: String =
        javaClass.getResourceAsStream("/workbench/index.html")!!.bufferedReader().readText()

    /** Pull the `["VALUE","Label"]` pairs out of a named JS array literal. */
    private fun jsPairs(name: String): List<Pair<String, String>> {
        val start = html.indexOf("const $name = [")
        assertTrue(start >= 0) { "the app has no $name list any more; this test needs updating with it" }
        val body = html.substring(start, html.indexOf("];", start))
        return Regex("""\["([A-Z_]+)"\s*,\s*"([^"]*)"]""").findAll(body)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
    }

    /** Pull a `["A","B"]` array of bare strings. */
    private fun jsStrings(name: String): List<String> {
        val start = html.indexOf("const $name = [")
        assertTrue(start >= 0) { "the app has no $name list any more" }
        val body = html.substring(start, html.indexOf("];", start))
        return Regex(""""([A-Za-z_]+)"""").findAll(body).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `the app offers every pattern the format supports`() {
        val offered = jsPairs("ENGINES").map { it.first }
        val supported = Engine.entries.map { it.name }
        val missing = supported - offered.toSet()
        assertTrue(missing.isEmpty()) {
            "$missing exists in the file format but no one can pick it in the app, " +
                "so the app is no longer the specification. Add it to ENGINES in index.html."
        }
    }

    @Test
    fun `the app offers no pattern the format does not have`() {
        val offered = jsPairs("ENGINES").map { it.first }
        val supported = Engine.entries.map { it.name }.toSet()
        val invented = offered.filterNot { it in supported }
        assertTrue(invented.isEmpty()) { "the app offers $invented, which :generator cannot render" }
        assertEquals(offered.size, offered.toSet().size) { "ENGINES lists something twice: $offered" }
    }

    @Test
    fun `the app offers every complication the format supports`() {
        val offered = jsPairs("COMP_SOURCES").map { it.first }
        val supported = ComplicationSource.entries.map { it.name }
        val missing = supported - offered.toSet()
        assertTrue(missing.isEmpty()) {
            "$missing can be stored in a face but not chosen in the app. " +
                "Add it to COMP_SOURCES in index.html."
        }
    }

    @Test
    fun `the app offers no complication the schema would reject`() {
        // ComplicationSource is exactly the schema's defaultProviderListType.
        // Anything outside it installs and then never appears in the carousel.
        val offered = jsPairs("COMP_SOURCES").map { it.first }
        val supported = ComplicationSource.entries.map { it.name }.toSet()
        val invented = offered.filterNot { it in supported }
        assertTrue(invented.isEmpty()) {
            "the app offers $invented, which is not in the schema's provider list — " +
                "a face using it installs and then silently never appears"
        }
    }

    @Test
    fun `the slot labels are in the order the generator lays them out`() {
        // index.html says "order matches SlotPosition" in a comment. This is the
        // part that makes the comment true: the labels are positional, so a
        // reordered enum would silently relabel every slot in the UI.
        val labels = jsStrings("COMP_SLOTS")
        assertEquals(SlotPosition.entries.size, labels.size) {
            "the app shows ${labels.size} slots and the generator has ${SlotPosition.entries.size}"
        }
        SlotPosition.entries.forEachIndexed { i, pos ->
            assertEquals(pos.name.lowercase(), labels[i].lowercase()) {
                "slot $i is ${pos.name} in the generator but labelled '${labels[i]}' in the app"
            }
        }
    }
}
