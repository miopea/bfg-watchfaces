package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.ComplicationSource
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.SlotPosition
import com.bfg.watchfaces.generator.WffEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every `@string/...` the emitted WFF references must exist in the APK.
 *
 * The phone shipped faces where it did not. `WffEmitter` wrote a
 * `displayName="@string/slot_..."` for every complication slot and
 * `FaceBuilder.strings()` emitted only `watch_face_name`, so every face built on
 * the device carried dangling resource references for its slot names.
 *
 * Nothing caught it. The workbench builds with aapt2, which FAILS on an
 * unresolved `@string`, so that path was safe by accident; the phone builds with
 * `pack`, which does not, so the face compiled, signed, installed and ran with
 * no name for any slot. It surfaced only as a wearer saying the right
 * complication always matched the bottom one.
 */
class SlotStringsTest {

    /** Every reference the emitter can produce, across the configurations. */
    private fun referenced(p: DialParams): Set<String> =
        Regex("""@string/([a-z0-9_]+)""").findAll(WffEmitter.emit(p, "Probe"))
            .map { it.groupValues[1] }.toSet()

    @Test
    fun `every string the emitter references is one the builders emit`() {
        val supplied = Complications.slotStrings().map { it.first }.toSet() + "watch_face_name"

        val configurations = buildList {
            add(DialParams())
            add(DialParams(complications = emptyList()))
            for (off in SlotPosition.entries) {
                add(DialParams(complications = SlotPosition.entries.map {
                    if (it == off) ComplicationSource.NONE else ComplicationSource.STEP_COUNT
                }))
            }
            // Every source in every slot, since the reference used to be keyed
            // by the SOURCE and that is exactly what varied.
            for (source in ComplicationSource.entries) {
                add(DialParams(complications = List(5) { source }))
            }
        }

        for (p in configurations) {
            val missing = referenced(p) - supplied
            assertTrue(missing.isEmpty()) {
                "the WFF references $missing, which no builder emits -- the APK " +
                    "ships a dangling resource and the watch has no name for the slot"
            }
        }
    }

    @Test
    fun `a slot is named for where it is, not for what is in it`() {
        // Two slots holding the SAME source must still be distinguishable in
        // the watch's editor, which is what keying the name by source broke.
        val p = DialParams(complications = List(5) { ComplicationSource.WATCH_BATTERY })
        val names = Regex("""displayName="@string/([a-z0-9_]+)"""")
            .findAll(WffEmitter.emit(p)).map { it.groupValues[1] }.toList()

        assertEquals(names.size, names.toSet().size) {
            "five slots share ${names.toSet().size} name(s): $names"
        }
        assertEquals(SlotPosition.entries.map { it.resource }, names)
    }

    @Test
    fun `slot names read as positions a person understands`() {
        assertEquals("Right", Complications.slotLabel(SlotPosition.RIGHT))
        assertEquals(5, Complications.slotStrings().size)
    }
}
