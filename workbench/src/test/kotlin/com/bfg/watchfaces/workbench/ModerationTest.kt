package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.appcore.Json
import com.bfg.watchfaces.appcore.PublishedSlug
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The moderation pass is the ONLY place a face meets Google's XSD.
 *
 * The catalog service cannot do it — a Worker is JavaScript, the emitter is
 * Kotlin and Xerces is neither — so `docs/specs/catalog-service.md` moves the
 * real check here. If it is wrong, schema-invalid faces get published, install
 * cleanly, and never appear in anyone's carousel with no error on either side.
 *
 * The service is not deployed, so none of this can be proven through HTTP.
 * That is exactly why the verdict logic takes a document and returns a verdict:
 * the load-bearing half is provable now, and [Moderate] is the thin part that
 * is not.
 */
class ModerationTest {

    private val root = RepoRoot.find()

    @BeforeEach
    fun requireSchema() {
        // Without the XSD every verdict is vacuous. Skipping is honest;
        // passing would be a lie of exactly the kind this pass exists to stop.
        assumeTrue(Moderation.schemaInstalled(root)) {
            "the WFF schema is not installed -- run scripts/bootstrap.sh"
        }
    }

    /** The real fixture, generated from FaceCodec by `:workbench:contract`. */
    private fun fixtureParams(): String =
        File(root, Contract.FIXTURE_PATH).readText()
            .let { Json.obj(Json.parse(it))["params"] }
            .let { serialize(it) }

    /** Re-emit a parsed JSON value as text, the way the service stores it. */
    private fun serialize(v: Any?): String = when (v) {
        null -> "null"
        is String -> Json.quote(v)
        is Boolean -> v.toString()
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        is List<*> -> v.joinToString(", ", "[", "]") { serialize(it) }
        is Map<*, *> -> v.entries.joinToString(", ", "{", "}") { "${Json.quote(it.key.toString())}: ${serialize(it.value)}" }
        else -> Json.quote(v.toString())
    }

    /** A queue row as `/admin/queue` returns one: `params` is JSON TEXT. */
    private fun row(
        name: String = "Fixture Face",
        slug: String = PublishedSlug.stemFor("Fixture Face") + "_7f3a",
        params: String = fixtureParams()
    ): Map<String, Any?> = mapOf(
        "id" to "11111111-2222-3333-4444-555555555555",
        "slug" to slug,
        "name" to name,
        "author" to "The Test Suite",
        "created" to "2026-08-30T00:00:00Z",
        "params" to params
    )

    @Test
    fun `a real face passes, and the fixture is a real face`() {
        val review = Moderation.review(root, row())
        assertEquals(Moderation.Verdict.LOOKS_FINE, review.verdict) {
            "a face the app actually produces was refused: ${review.problems.map { it.message }}"
        }
    }

    /**
     * The published slug rule, which the git-catalog rule would get wrong.
     *
     * `CatalogStore.validate` requires slug == slugify(name) for a file in a
     * git checkout. A published slug is `slugify(name)_7f3a`, so reusing that
     * rule here would refuse EVERY submission the service ever accepted.
     */
    @Test
    fun `a published slug with its short id is accepted, not mistaken for a mismatch`() {
        val review = Moderation.review(root, row(slug = "fixture_face_c214"))
        assertEquals(Moderation.Verdict.LOOKS_FINE, review.verdict) {
            "the published slug rule refused a correct slug: ${review.problems.map { it.message }}"
        }
    }

    @Test
    fun `a slug that does not describe its name is refused`() {
        // The slug is the package name. A face filed on the watch under
        // something the gallery does not call it is worth a person seeing.
        val review = Moderation.review(root, row(slug = "something_else_7f3a"))
        assertEquals(Moderation.Verdict.REFUSE, review.verdict)
        assertTrue(review.problems.any { it.message.contains("published slug") })
    }

    @Test
    fun `a slug with no short id is refused`() {
        val review = Moderation.review(root, row(slug = "fixture_face"))
        assertEquals(Moderation.Verdict.REFUSE, review.verdict)
    }

    /**
     * The one that actually bites.
     *
     * A face whose emitted WFF fails the XSD installs, signs, links and reports
     * success, and then never appears in the carousel. There is no runtime
     * error anywhere. This check is the only signal that exists.
     */
    @Test
    fun `a face that emits schema-invalid WFF is refused`() {
        // A font weight the schema does not enumerate. It survives the Worker's
        // structural check only if that check is wrong, and it reaches the XSD
        // either way -- which is the point of having both.
        val params = fixtureParams().replace(""""fontWeight": "MEDIUM"""", """"fontWeight": "MEDIUMISH"""")
        assertTrue(params.contains("MEDIUMISH")) { "the substitution did not apply; this test proves nothing" }

        val review = Moderation.review(root, row(params = params))
        assertEquals(Moderation.Verdict.REFUSE, review.verdict) {
            "a schema-invalid face was going to be published"
        }
        // Refused BY THE SCHEMA, not by parsing failing earlier for some other
        // reason. A test that passes because the document broke on the way in
        // proves nothing about whether the XSD is being consulted at all.
        assertTrue(review.problems.any { it.message.contains("schema-invalid WFF") }) {
            "refused, but not by the XSD: ${review.problems.map { it.message }}"
        }
    }

    @Test
    fun `a TEXTURE face is refused, because the catalog is parameters only`() {
        val params = fixtureParams().replace(""""engine": "KNOTWORK"""", """"engine": "TEXTURE"""")
        val review = Moderation.review(root, row(params = params))
        assertEquals(Moderation.Verdict.REFUSE, review.verdict)
        assertTrue(review.problems.any { it.message.contains("parameters only") })
    }

    @Test
    fun `an unparseable document is refused rather than thrown`() {
        // The queue has to keep moving. One malformed row must not stop a
        // maintainer working through the rest -- the response promises are
        // about the queue being workable.
        val review = Moderation.review(root, row(params = "{ this is not json"))
        assertEquals(Moderation.Verdict.REFUSE, review.verdict)
    }

    /**
     * `MODERATION.md` promises appeals are answered, and an appeal against
     * "rejected" with nothing after it cannot be.
     */
    @Test
    fun `a refusal always carries a reason in the validator's own words`() {
        val params = fixtureParams().replace(""""engine": "KNOTWORK"""", """"engine": "TEXTURE"""")
        val review = Moderation.review(root, row(params = params))
        assertTrue(review.reason().isNotBlank())
        assertTrue(review.reason().contains("parameters only")) {
            "the reason does not say what was wrong: '${review.reason()}'"
        }
    }

    /**
     * LOOKS_FINE is not approval, and the naming is deliberate.
     *
     * No automated check can tell you a dial is somebody's logo, somebody's
     * name, or a slur rendered in knotwork. Pre-moderation is the abuse control
     * precisely because a person has to look.
     */
    @Test
    fun `the passing verdict does not claim the face is acceptable`() {
        assertFalse(Moderation.Verdict.entries.any { it.name.contains("APPROVE") }) {
            "a verdict called APPROVE would be read as a decision this pass cannot make"
        }
        assertEquals("LOOKS_FINE", Moderation.Verdict.LOOKS_FINE.name)
    }
}
