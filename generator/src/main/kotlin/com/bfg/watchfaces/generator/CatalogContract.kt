package com.bfg.watchfaces.generator

/**
 * Everything a non-JVM validator needs to know to refuse a bad face.
 *
 * ## Why this exists
 *
 * The catalog service runs on Cloudflare Workers, which is JavaScript. It has
 * to reject a malformed submission before that submission occupies a slot in a
 * human moderation queue — and to do that it needs the ranges, the enum
 * members, and the field list that define the file format.
 *
 * All of that already exists, in Kotlin: [ControlInventory] holds the ranges,
 * [DialParams] holds the fields, [SlotGeometry] holds the two layout bounds
 * that are not sliders. Writing them out again in JavaScript is precisely the
 * shape this repo keeps getting hurt by, and `ControlInventory`'s own header
 * says so: "A test that two copies match cannot tell you they are both
 * correct."
 *
 * So this emits them. `./gradlew :workbench:contract` writes
 * `catalog-service/params-contract.json`, the Worker imports that file, and
 * `CatalogContractTest` fails if the checked-in copy has gone stale. The
 * contract is generated output that happens to be committed, the same
 * arrangement `BrandMark` has with the launcher icons and for the same reason:
 * the deploy must not depend on a JVM task having been run.
 *
 * ## What this is NOT
 *
 * **It is not the validator.** A face can satisfy every bound here and still
 * emit schema-invalid WFF, which installs cleanly and then never appears in the
 * carousel. Only Xerces against Google's XSD catches that, and that cannot run
 * in a Worker. See `docs/specs/catalog-service.md` — the full check moves to
 * the moderation pass, before a human sees the face and before anything is
 * published, but after the POST returns.
 *
 * What this catches is the cheap half: a value outside its slider's range, an
 * enum member that does not exist, a colour that is not a colour, a field
 * nobody has ever heard of, and a font family with a quote in it — that last
 * one being an XML injection into [WffEmitter]'s output rather than a mistake.
 */
object CatalogContract {

    /**
     * The contract's own version, bumped when its SHAPE changes — a new
     * section, a renamed key — so a deployed Worker can refuse a file it is too
     * old to read rather than silently ignoring half of it.
     *
     * Not [CURRENT_GENERATOR_VERSION]. That one describes what a face MEANS and
     * moves when geometry changes; this one describes how this file is laid out
     * and moves when the file is laid out differently. They were briefly the
     * same number and it read as though bumping one should bump the other.
     */
    const val CONTRACT_VERSION = 1

    /**
     * A face is parameters. Anything much larger than this is not a face.
     *
     * Lives here rather than beside the catalog reader because the size limit
     * is a fact about the file format, and there are now two things enforcing
     * it — the JVM validator and a Worker that has never seen this source.
     */
    const val MAX_FACE_BYTES = 8 * 1024

    /** Long enough for a real name, short enough not to break a carousel label. */
    const val MAX_NAME_CHARS = 40

    /** Optional, and a display string rather than a login. Same reasoning. */
    const val MAX_AUTHOR_CHARS = 40

    /**
     * `#RRGGBB`, exactly as [DialParams] enforces it — the pattern itself, not
     * a description of it.
     *
     * The emitter converts to WFF's 8-digit `#AARRGGBB` on the way out, so an
     * 8-digit colour arriving here is a stored-format error, not a preference.
     * Either case is accepted, because `DialParams` accepts either and a
     * stricter rule on the public endpoint would reject faces the app itself
     * considers valid.
     */
    val COLOR_PATTERN: String = DialParams.HEX.pattern

    /**
     * `package/class`, for a complication provider or a launch target.
     *
     * [DialParams]' own pattern, anchored. It is unanchored in Kotlin because
     * `Regex.matches` requires the whole string anyway; JavaScript's
     * `RegExp.test` does not, so an unanchored copy would accept a
     * ComponentName with anything at all around it — including the quote that
     * closes the XML attribute this string is written into verbatim.
     */
    val COMPONENT_PATTERN: String = "^(?:${COMPONENT.pattern})$"

    /**
     * What a font family may contain.
     *
     * This one is a security bound rather than a taste one. [WffEmitter]
     * interpolates `fontFamily` straight into an XML attribute with no
     * escaping, which is harmless while every face is made on the machine that
     * renders it and stops being harmless the moment strangers can submit one.
     * A family containing a quote closes the attribute.
     *
     * The schema itself is no help: `family` is `xs:string`, because it names a
     * font the device resolves rather than choosing from a list.
     */
    const val FONT_FAMILY_PATTERN = "^[A-Za-z0-9 _-]{1,32}$"

    /**
     * What a slug may look like: Watch Face Push's package-segment rule.
     *
     * The slug IS the `watchfacepush.<slug>` package suffix, so this is Push's
     * constraint rather than a preference — `FaceLibrary.slugify` exists to
     * produce it and explains why ASCII-only, why not starting with a digit,
     * and what happens at install time when it is wrong.
     *
     * The service does NOT slugify. The app sends a base slug computed by the
     * one implementation both apps share, and the Worker checks its SHAPE and
     * appends the published id. A second slugifier in JavaScript would be a
     * second answer to "what is this face's package name", and two answers
     * means faces installing under different packages and silently failing to
     * replace each other — which is the exact failure the shared rule exists to
     * prevent.
     */
    const val SLUG_PATTERN = "^[a-z][a-z0-9_]*$"

    /** Slug length ceiling, matching `FaceLibrary.slugify`'s own `take(40)`. */
    const val MAX_SLUG_CHARS = 40

    /**
     * Hex characters of the id appended to a PUBLISHED slug.
     *
     * Two strangers may both call a face "Midnight", and
     * `watchfacepush.midnight` is one package: installing the second would
     * silently replace the first on the watch. `midnight_7f3a` and
     * `midnight_c214` cannot collide, and nobody has to be told their name is
     * taken.
     *
     * Four is a compromise. It is not collision-proof by itself — the service
     * enforces uniqueness on the column and retries — it is short enough that
     * the package name stays readable in Settings on the watch, where it is
     * visible.
     */
    const val PUBLISHED_ID_CHARS = 4

    /**
     * Legal `weight` values, from Watch Face Format's own schema.
     *
     * Copied from `group/part/text/fontElement.xsd` rather than derived,
     * because the schema is a fetched test resource and `:generator`'s main
     * source set must not read one. `CatalogContractTest` reads the XSD and
     * fails if this list has drifted from it — so it is a copy that cannot go
     * stale quietly, which is the most this arrangement allows.
     */
    val FONT_WEIGHTS: List<String> = listOf(
        "THIN", "ULTRA_LIGHT", "EXTRA_LIGHT", "LIGHT", "NORMAL", "MEDIUM",
        "SEMI_BOLD", "BOLD", "ULTRA_BOLD", "EXTRA_BOLD", "BLACK", "EXTRA_BLACK"
    )

    /**
     * Letter spacing on the clock, in dial pixels.
     *
     * The only bound here that is invented rather than derived: `tracking` has
     * never had a slider, so [ControlInventory] does not describe it and there
     * is no measured limit to quote. +/-20px at a 456px dial is far past
     * anything legible in either direction, which makes it a guard against
     * absurd input rather than a claim about where it stops looking good.
     */
    const val TRACKING_MIN = -20.0
    const val TRACKING_MAX = 20.0

    /**
     * The preview lens, 0-100.
     *
     * Bounded here even though the lens never reaches the shipped WFF —
     * `DECISIONS.md` 2026-08-27 records it as a preview-only effect. An
     * unbounded number in a stored face is still a number some renderer will
     * one day multiply by something.
     */
    const val LENS_MIN = 0.0
    const val LENS_MAX = 100.0

    /**
     * The stored `dateSize`, which is no longer what sizes the date.
     *
     * [SlotGeometry.fittedDateSize] derives the drawn size from the clock's
     * width and says in as many words that "`dateSize` is therefore no longer
     * read when drawing. It stays in [Layout] and in the file so faces written
     * by any build still parse."
     *
     * So this is a sanity bound on a vestigial number, and it is NOT
     * `MIN_DATE_SIZE`..`MAX_DATE_SIZE` — those bound the DERIVED size. Using
     * them here was the first thing the generated fixture caught: the stored
     * default is 64, `MAX_DATE_SIZE` is 56, and the service would have rejected
     * every real face while every test using a hand-written fixture passed.
     */
    const val DATE_SIZE_MIN = 1.0
    const val DATE_SIZE_MAX = 200.0

    /**
     * The contract, as JSON.
     *
     * [fields] is every key a stored face's flattened `params` may carry, and
     * the caller supplies it rather than this object listing them: the field
     * list belongs to `FaceCodec`, which lives in `:appcore` because both
     * shipped apps need it, and `:generator` must not depend on that direction.
     * The contract task derives it from `FaceCodec.toQuery(DialParams())` — the
     * keys the codec actually writes — so it is read off the codec rather than
     * transcribed from it.
     *
     * The keys `fromQuery` still READS but no longer writes — `stepRing`,
     * `showComplicationIcons`, a separate `providers` — are deliberately
     * absent. They exist so an old saved face still opens; a submission is
     * re-serialized by the app on its way out and therefore always carries the
     * current spelling. Accepting them here would widen the public surface for
     * no one's benefit.
     *
     * Deterministic: same input, same bytes, so the checked-in file only
     * changes when the format does and a diff means something.
     */
    fun json(fields: List<String>): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("""  "contractVersion": $CONTRACT_VERSION,${'\n'}""")
        sb.append("""  "currentGeneratorVersion": $CURRENT_GENERATOR_VERSION,${'\n'}""")
        sb.append("""  "maxFaceBytes": $MAX_FACE_BYTES,${'\n'}""")
        sb.append("""  "maxNameChars": $MAX_NAME_CHARS,${'\n'}""")
        sb.append("""  "maxAuthorChars": $MAX_AUTHOR_CHARS,${'\n'}""")
        sb.append("""  "colorPattern": ${q(COLOR_PATTERN)},${'\n'}""")
        sb.append("""  "fontFamilyPattern": ${q(FONT_FAMILY_PATTERN)},${'\n'}""")
        sb.append("""  "componentPattern": ${q(COMPONENT_PATTERN)},${'\n'}""")
        sb.append("""  "slugPattern": ${q(SLUG_PATTERN)},${'\n'}""")
        sb.append("""  "maxSlugChars": $MAX_SLUG_CHARS,${'\n'}""")
        sb.append("""  "publishedIdChars": $PUBLISHED_ID_CHARS,${'\n'}""")

        sb.append("""  "controls": [${'\n'}""")
        sb.append(ControlInventory.CONTROLS.joinToString(",\n") { c ->
            """    {"id": ${q(c.id)}, "min": ${n(c.min)}, "max": ${n(c.max)}, """ +
                """"step": ${n(c.step)}, "integral": ${c.integral}, "target": ${q(c.target.name)}}"""
        })
        sb.append("\n  ],\n")

        // The layout fields with no slider. Their bounds are SlotGeometry's own
        // -- the same numbers that clamp them when a face is rendered -- so a
        // submission cannot be accepted carrying a value the renderer would
        // quietly move.
        sb.append("""  "bounds": {${'\n'}""")
        sb.append(
            listOf(
                """    "dateSize": {"min": ${n(DATE_SIZE_MIN)}, "max": ${n(DATE_SIZE_MAX)}, "integral": true}""",
                """    "complicationSize": {"min": ${SlotGeometry.MIN_SIZE}, "max": ${SlotGeometry.MAX_SIZE}, "integral": true}""",
                """    "tracking": {"min": ${n(TRACKING_MIN)}, "max": ${n(TRACKING_MAX)}, "integral": false}""",
                """    "lensAmount": {"min": ${n(LENS_MIN)}, "max": ${n(LENS_MAX)}, "integral": false}"""
            ).joinToString(",\n")
        )
        sb.append("\n  },\n")

        sb.append("""  "enums": {${'\n'}""")
        sb.append(
            listOf(
                "engine" to Engine.entries.map { it.name },
                "dateStyle" to DateStyle.entries.map { it.name },
                "dateScale" to DateScale.entries.map { it.name },
                "ring" to RingSource.entries.map { it.name },
                "hourFormat" to HourFormat.entries.map { it.name },
                "slotPosition" to SlotPosition.entries.map { it.name },
                "complicationSource" to ComplicationSource.entries.map { it.name },
                "fontWeight" to FONT_WEIGHTS
            ).joinToString(",\n") { (name, values) ->
                """    ${q(name)}: [${values.joinToString(", ") { q(it) }}]"""
            }
        )
        sb.append("\n  },\n")

        // Engines that emit no shareable geometry. TEXTURE is the whole reason
        // the catalog is parameters-only: an imported image cannot be
        // re-derived from parameters and cannot be licensed by us.
        sb.append("""  "unpublishableEngines": [${q(Engine.TEXTURE.name)}],${'\n'}""")

        sb.append("""  "fields": [${'\n'}""")
        sb.append(fields.sorted().joinToString(",\n") { "    ${q(it)}" })
        sb.append("\n  ]\n}\n")
        return sb.toString()
    }

    private fun q(s: String): String {
        val out = StringBuilder("\"")
        for (ch in s) when (ch) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            else -> out.append(ch)
        }
        return out.append('"').toString()
    }

    /** Whole doubles print as integers, so `4.0` is not gratuitously `4.0` in JSON. */
    private fun n(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
}
