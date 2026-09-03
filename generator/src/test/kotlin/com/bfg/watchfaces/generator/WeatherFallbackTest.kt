package com.bfg.watchfaces.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * A drawn weather slot shows something when the watch has no weather.
 *
 * The behaviour recorded in `backlog.md` #3 — fall back to the slot's system
 * provider — is NOT what this does, because it is not expressible: see
 * [WeatherFallback].
 */
class WeatherFallbackTest {

    private fun face(version: Int, source: ComplicationSource) =
        DialParams(generatorVersion = version, complications = List(5) { source })

    /**
     * Every face saved before v14 emits exactly what it always did.
     *
     * This is the gate, and it is the whole reason for the version branch. A
     * byte comparison rather than a spot check: anything that changed would be
     * a face rendering differently than its author saw it.
     */
    @ParameterizedTest
    @EnumSource(ComplicationSource::class)
    fun `v13 output is untouched`(source: ComplicationSource) {
        val before = WffEmitter.emit(face(13, source))
        assertTrue(!before.contains("WEATHER.IS_AVAILABLE")) {
            "$source at v13 gained a fallback; existing faces must not change"
        }
        assertTrue(!before.contains(WeatherFallback.PLACEHOLDER)) {
            "$source at v13 gained a placeholder"
        }
    }

    /** Only weather sources get one. Steps do not go missing. */
    @ParameterizedTest
    @EnumSource(ComplicationSource::class)
    fun `only weather-reading sources get a fallback`(source: ComplicationSource) {
        val xml = WffEmitter.emit(face(14, source))
        val wrapped = xml.contains("WEATHER.IS_AVAILABLE")
        assertEquals(source.isDrawn && source.readsWeather, wrapped) {
            "$source: drawn=${source.isDrawn} readsWeather=${source.readsWeather} but wrapped=$wrapped"
        }
    }

    /**
     * The available branch is the OLD element, unchanged.
     *
     * A fallback that also restyled the normal case would be a redesign wearing
     * a fallback's name. Every expression the v13 face asked for is still asked
     * for, in the same slot boxes.
     */
    @ParameterizedTest
    @EnumSource(ComplicationSource::class)
    fun `the available branch still asks for exactly what it used to`(source: ComplicationSource) {
        if (!source.isDrawn || !source.readsWeather) return
        val before = WffEmitter.emit(face(13, source))
        val after = WffEmitter.emit(face(14, source))
        for (expression in source.drawn) {
            assertEquals(
                before.split(expression).size, after.split(expression).size
            ) { "$source asks for $expression a different number of times at v14" }
        }
    }

    /** Weather sources that are NOT drawn are complications and keep a provider. */
    @Test
    fun `a weather source with a provider is left alone`() {
        val provider = ComplicationSource.entries.firstOrNull {
            it.name.startsWith("WEATHER") && !it.isDrawn
        } ?: return
        val xml = WffEmitter.emit(face(14, provider))
        assertTrue(!xml.contains("WEATHER.IS_AVAILABLE")) {
            "$provider has a real ComplicationSlot; the provider decides what an empty slot shows"
        }
    }

    /**
     * The placeholder is a value, not a sentence, and not blank.
     *
     * Blank reads as a broken face. "No data" is a system talking about itself.
     */
    @Test
    fun `the placeholder is something you can see and not a sentence`() {
        assertTrue(WeatherFallback.PLACEHOLDER.isNotBlank())
        assertTrue(WeatherFallback.PLACEHOLDER.length <= 3)
    }

    /**
     * The fallback text is NOT wrapped in a Template.
     *
     * Every other string this emitter writes is, so a Template is the obvious
     * thing to reach for — and it is schema-invalid for a fixed string, because
     * a Template requires at least one Parameter. Caught by Xerces the first
     * time; pinned here so it is not reintroduced.
     */
    @Test
    fun `the placeholder is literal text rather than an empty Template`() {
        val xml = WffEmitter.emit(face(14, ComplicationSource.WEATHER_TEMPERATURE))
        assertTrue(!xml.contains("<Template><![CDATA[${WeatherFallback.PLACEHOLDER}]]></Template>")) {
            "a Template with no Parameter is invalid: Xerces rejects the whole face"
        }
        assertTrue(xml.contains(WeatherFallback.PLACEHOLDER)) { "the placeholder is not emitted at all" }
    }

    /** One Condition per drawn weather slot, not one for the whole face. */
    @Test
    fun `each drawn weather slot gets its own condition`() {
        val p = face(14, ComplicationSource.WEATHER_TEMPERATURE)
        val xml = WffEmitter.emit(p)
        val slots = SlotGeometry.boxes(p).size
        assertEquals(slots, Regex("\\[WEATHER\\.IS_AVAILABLE]").findAll(xml).count()) {
            "expected one availability test per drawn slot"
        }
    }
}
