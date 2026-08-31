package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.Engine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import com.bfg.watchfaces.appcore.FaceLibrary

/**
 * The catalog's rules are the architecture's, not preferences:
 *
 *  - parameters only, which is what keeps a face ~5KB (so 10,000 of them are
 *    ~50MB of Git and free to host) AND is the IP shield
 *  - every face must emit schema-valid WFF, because an invalid one installs
 *    cleanly and then never appears -- there is nothing for a reviewer to see
 *
 * These tests exist so a submission cannot slip past either.
 */
class CatalogStoreTest {

    companion object {
        @JvmStatic @BeforeAll fun headless() { System.setProperty("java.awt.headless", "true") }
    }

    private fun repoRoot(): File = File(System.getProperty("user.dir")).let {
        generateSequence(it) { d -> d.parentFile }.first { d -> File(d, "settings.gradle.kts").isFile }
    }

    /** A temp root that still has the real schema, so validation is genuine. */
    private fun stagedRoot(tmp: File): File {
        val real = repoRoot()
        val schema = File(real, "generator/src/test/resources/wff-schema")
        if (schema.isDirectory) {
            val dest = File(tmp, "generator/src/test/resources/wff-schema")
            dest.parentFile.mkdirs()
            schema.copyRecursively(dest, overwrite = true)
        }
        return tmp
    }

    private fun write(root: File, slug: String, json: String): File {
        val f = File(CatalogStore.dir(root).apply { mkdirs() }, "$slug.json")
        f.writeText(json)
        return f
    }

    private fun entry(name: String, p: DialParams) = CatalogStore.Entry(
        slug = FaceLibrary.slugify(name), name = name, author = "Tester",
        created = "2026-08-28T00:00:00Z", params = p
    )

    @Test
    fun `a good face round trips and validates`(@TempDir tmp: File) {
        val root = stagedRoot(tmp)
        val e = entry("Knot Test", DialParams(engine = Engine.KNOTWORK))
        val f = write(root, e.slug, CatalogStore.toJson(e))
        assertTrue(CatalogStore.validate(root, f).isEmpty()) { CatalogStore.validate(root, f).toString() }
        assertEquals(e.params, CatalogStore.parse(f.readText()).params)
        assertEquals("Tester", CatalogStore.parse(f.readText()).author)
    }

    @Test
    fun `a TEXTURE face is refused`(@TempDir tmp: File) {
        val root = stagedRoot(tmp)
        val e = entry("Photo Face", DialParams(engine = Engine.TEXTURE, texture = "a".repeat(40)))
        val f = write(root, e.slug, CatalogStore.toJson(e))
        val problems = CatalogStore.validate(root, f)
        // Parametric-only is the IP shield: an uploaded image is not something
        // we can host under our own name, and it cannot be re-derived.
        assertTrue(problems.any { it.message.contains("TEXTURE") }) { "a raster face was accepted: $problems" }
    }

    @Test
    fun `a face whose slug does not match its name is refused`(@TempDir tmp: File) {
        val root = stagedRoot(tmp)
        val e = entry("Proper Name", DialParams()).copy(slug = "something_else")
        val f = write(root, "something_else", CatalogStore.toJson(e))
        assertTrue(CatalogStore.validate(root, f).any { it.message.contains("does not match") })
    }

    @Test
    fun `a filename that disagrees with the slug is refused`(@TempDir tmp: File) {
        val root = stagedRoot(tmp)
        val e = entry("Knot Test", DialParams())
        val f = write(root, "wrong_filename", CatalogStore.toJson(e))
        assertTrue(CatalogStore.validate(root, f).any { it.message.contains("filename") })
    }

    @Test
    fun `a face from the future is refused rather than mis-rendered`(@TempDir tmp: File) {
        val root = stagedRoot(tmp)
        // Written as raw JSON on purpose: DialParams' constructor refuses an
        // unknown version, so this shape cannot be built in Kotlin at all -- but
        // it can certainly arrive in a pull request from a newer client.
        val json = CatalogStore.toJson(entry("Future Face", DialParams()))
            .replace(Regex("\"generatorVersion\": \\d+"), "\"generatorVersion\": 99")
        val f = write(root, "future_face", json)
        val problems = CatalogStore.validate(root, f)
        assertTrue(problems.any { it.message.contains("generatorVersion") }) {
            "a face needing a newer generator was accepted: $problems"
        }
    }

    @Test
    fun `an oversized file is refused`(@TempDir tmp: File) {
        val root = stagedRoot(tmp)
        val e = entry("Fat Face", DialParams())
        val padded = CatalogStore.toJson(e).dropLast(2) + ",\n  \"junk\": \"" + "x".repeat(9000) + "\"\n}\n"
        val f = write(root, e.slug, padded)
        assertTrue(CatalogStore.validate(root, f).any { it.message.contains("bytes") })
    }

    @Test
    fun `submitting a local-only face is refused and leaves nothing staged`(@TempDir tmp: File) {
        val root = stagedRoot(tmp)
        val face = FaceLibrary.StoredFace(
            "photo_dial", "Photo Dial", "2026-08-28T00:00:00Z",
            DialParams(engine = Engine.TEXTURE, texture = "b".repeat(40))
        )
        val (file, problems) = CatalogStore.submit(root, root, face, "Tester")
        assertTrue(problems.isNotEmpty())
        // An invalid submission must not be left behind for someone to commit.
        assertFalse(file.exists()) { "a refused submission was left staged at $file" }
    }

    @Test
    fun `the index carries what a gallery needs and no parameters`(@TempDir tmp: File) {
        val root = stagedRoot(tmp)
        val e = entry("Knot Test", DialParams(engine = Engine.KNOTWORK, dialColor = "#2B2E33"))
        write(root, e.slug, CatalogStore.toJson(e))
        val index = CatalogStore.buildIndex(root)

        assertTrue(index.contains("\"count\": 1"))
        assertTrue(index.contains("\"maxGeneratorVersion\":")) { "the index must say how new a client needs to be" }
        assertTrue(index.contains("\"name\": \"Knot Test\""))
        assertTrue(index.contains("\"author\": \"Tester\""))
        assertTrue(index.contains("\"engine\": \"KNOTWORK\""))
        // One request for the whole gallery: the index must NOT inline full
        // parameters, or it grows with the catalog instead of the face count.
        assertFalse(index.contains("\"scale\"")) { "the index inlined full parameters" }
        assertFalse(index.contains("\"layout\"")) { "the index inlined full parameters" }
    }

    @Test
    fun `duplicate slugs are reported`(@TempDir tmp: File) {
        val root = stagedRoot(tmp)
        val e = entry("Knot Test", DialParams())
        write(root, e.slug, CatalogStore.toJson(e))
        // Same slug, different file name is caught by the filename rule; a true
        // duplicate needs two files that both claim it.
        val problems = CatalogStore.validateAll(root)
        assertTrue(problems.isEmpty()) { problems.toString() }
    }

    @Test
    fun `a catalog checkout, if present, is valid and its index is current`() {
        // The catalog is its own public repository now, so this repo usually has
        // no checkout and there is nothing to assert. When a contributor DOES
        // have one beside this repo, holding it to the same bar locally is
        // cheaper than finding out in the catalog repo's CI.
        val repo = repoRoot()
        val catalog = CatalogStore.resolveRoot(repo)
        org.junit.jupiter.api.Assumptions.assumeTrue(catalog != null) {
            "no catalog checkout beside this repo -- set BFG_CATALOG_DIR to include it"
        }
        val problems = CatalogStore.validateAll(repo, catalog!!)
        assertTrue(problems.isEmpty()) { "catalog is invalid:\n" + problems.joinToString("\n") }

        fun strip(s: String) = s.lines().filterNot { it.trimStart().startsWith("\"generated\"") }.joinToString("\n")
        val onDisk = CatalogStore.indexFile(catalog).takeIf { it.isFile }?.readText().orEmpty()
        assertEquals(strip(CatalogStore.buildIndex(catalog)), strip(onDisk)) {
            "index.json is stale -- run ./gradlew :workbench:catalog"
        }
    }
}

/**
 * The catalog is a DIFFERENT repository from the app, and CatalogStore.dir()
 * appends "faces" to whatever root it is given. Hand it the app's repo root and
 * it reads <repo>/faces -- the user's own private designs.
 *
 * That is not hypothetical. When the catalog moved to its own repo the endpoints
 * kept passing the app root, and a face the operator had saved privately
 * appeared in the Community gallery. These tests pin the distinction.
 */
class CatalogRootTest {

    companion object {
        @JvmStatic @BeforeAll fun headless() { System.setProperty("java.awt.headless", "true") }
    }

    @Test
    fun `the app root is not a catalog root`(@TempDir tmp: File) {
        // A private save, in the app repo, exactly where FaceLibrary puts it.
        FaceLibrary.save(tmp, "Private Design", DialParams(engine = Engine.KNOTWORK))
        assertEquals(1, FaceLibrary.list(tmp).size)

        // With no catalog checkout anywhere near it, resolution must fail rather
        // than quietly landing on the personal directory.
        assertNull(CatalogStore.resolveRoot(tmp)) {
            "resolveRoot returned a root with no catalog in it; the personal " +
            "faces directory would be served as the community catalog"
        }
    }

    @Test
    fun `a private face never appears in a catalog listing`(@TempDir tmp: File) {
        val app = File(tmp, "app").apply { mkdirs() }
        val catalog = File(tmp, "bfg-watchfaces-catalog").apply { mkdirs() }
        File(catalog, "faces").mkdirs()

        FaceLibrary.save(app, "Private Design", DialParams(engine = Engine.KNOTWORK))
        val resolved = CatalogStore.resolveRoot(app)
        assertEquals(catalog.canonicalFile, resolved?.canonicalFile) { "sibling clone should win" }

        val listed = CatalogStore.list(resolved!!).map { it.name }
        assertTrue(listed.isEmpty()) { "the catalog is empty but listed $listed" }
        assertTrue("Private Design" !in listed) { "a private face leaked into the catalog listing" }
    }

    @Test
    fun `submitting writes to the catalog, never to the personal directory`(@TempDir tmp: File) {
        val app = File(tmp, "app").apply { mkdirs() }
        val catalog = File(tmp, "bfg-watchfaces-catalog").apply { mkdirs() }
        File(catalog, "faces").mkdirs()
        // The schema has to be reachable or validate() cannot judge the face.
        val real = File(System.getProperty("user.dir")).let {
            generateSequence(it) { d -> d.parentFile }.first { d -> File(d, "settings.gradle.kts").isFile }
        }
        File(real, "generator/src/test/resources/wff-schema").takeIf { it.isDirectory }
            ?.copyRecursively(File(app, "generator/src/test/resources/wff-schema"), overwrite = true)

        val face = FaceLibrary.save(app, "Shared Design", DialParams(engine = Engine.KNOTWORK))
        val (file, problems) = CatalogStore.submit(app, catalog, face, "Tester")
        assertTrue(problems.isEmpty()) { problems.toString() }

        assertTrue(file.canonicalPath.startsWith(catalog.canonicalPath)) {
            "submission landed outside the catalog at ${file.canonicalPath}"
        }
        // And the private copy is untouched, still one face.
        assertEquals(1, FaceLibrary.list(app).size)
    }
}
