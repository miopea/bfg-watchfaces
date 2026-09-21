package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A slot that DRAWS a bar must also ASK for the data a bar needs.
 *
 * ## The bug
 *
 * `supportedTypes` listed `RANGED_VALUE` from v15 and the emitter wrote a
 * `<Complication type="RANGED_VALUE">` block containing the bar — but
 * `defaultSystemProviderType` stayed hardcoded to `SHORT_TEXT`. The watch bound
 * the provider as SHORT_TEXT, that block never activated, and the bar never
 * drew. Both previews drew it anyway, because they render from `showsBars` with
 * a sample fill and never consult a provider.
 *
 * So the phone showed a bar and the wrist did not, for the whole life of the
 * feature. Bars shipped in 1.87 on 2026-09-19 and were first seen missing on
 * 2026-09-20.
 *
 * ## Why no existing test caught it
 *
 * The XML is schema-valid both ways, so `WffSchemaTest` passes on either, and
 * nothing in a JVM test can know which type a watch will bind. This asserts the
 * one thing that IS knowable here: the attribute agrees with the block.
 */
class RangedProviderTypeTest {

    private fun barsOn(source: ComplicationSource) = DialParams(
        rangedBars = true,
        complications = listOf(source, ComplicationSource.DATE,
                               ComplicationSource.DATE, ComplicationSource.NONE)
    )

    private fun policyFor(xml: String, provider: String): String =
        xml.lines().first { it.contains("defaultSystemProvider=\"$provider\"") }

    /** Every source that draws a bar asks for the data the bar reads. */
    @Test
    fun `a slot drawing a bar asks for RANGED_VALUE`() {
        for (source in ComplicationSource.entries.filter { it.ranged }) {
            val p = barsOn(source)
            assertTrue(p.showsBars) { "$source should draw a bar with rangedBars on" }
            val line = policyFor(WffEmitter.emit(p), source.wff!!)
            assertTrue(line.contains("""defaultSystemProviderType="RANGED_VALUE"""")) {
                "$source draws a bar but asks for the wrong type:\n$line"
            }
        }
    }

    /**
     * And a source with no range keeps asking for SHORT_TEXT.
     *
     * Asking for RANGED_VALUE where there is no range trades a missing bar for
     * an EMPTY SLOT, which is strictly worse than the bug being fixed.
     */
    @Test
    fun `a source with no range still asks for SHORT_TEXT`() {
        val p = barsOn(ComplicationSource.HEART_RATE)
        val line = policyFor(WffEmitter.emit(p), "HEART_RATE")
        assertTrue(line.contains("""defaultSystemProviderType="SHORT_TEXT"""")) {
            "HEART_RATE has no range and must not be asked for one:\n$line"
        }
    }

    /** Bars OFF means the old request, so a face that draws no bar is unchanged. */
    @Test
    fun `with bars off even a ranged source asks for SHORT_TEXT`() {
        val p = DialParams(
            rangedBars = false,
            complications = listOf(ComplicationSource.STEP_COUNT, ComplicationSource.DATE,
                                   ComplicationSource.DATE, ComplicationSource.NONE)
        )
        val line = policyFor(WffEmitter.emit(p), "STEP_COUNT")
        assertTrue(line.contains("""defaultSystemProviderType="SHORT_TEXT"""")) {
            "a face with no bar must request exactly what it did before:\n$line"
        }
    }

    /**
     * The attribute and the block cannot disagree.
     *
     * Stated as its own assertion because the pair IS the bug: either alone
     * looks correct, and the failure only exists in the gap between them.
     */
    @Test
    fun `asking for RANGED_VALUE implies a RANGED_VALUE block exists`() {
        val xml = WffEmitter.emit(barsOn(ComplicationSource.STEP_COUNT))
        if (xml.contains("""defaultSystemProviderType="RANGED_VALUE"""")) {
            assertTrue(xml.contains("""<Complication type="RANGED_VALUE">""")) {
                "the slot asks for RANGED_VALUE and there is nothing to render it"
            }
        }
    }
}
