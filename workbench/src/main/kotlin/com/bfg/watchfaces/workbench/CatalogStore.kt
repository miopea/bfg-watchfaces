package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.CatalogContract
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.Engine
import com.bfg.watchfaces.generator.WffEmitter
import java.io.File
import java.time.Instant
import com.bfg.watchfaces.appcore.Json
import com.bfg.watchfaces.appcore.FaceCodec
import com.bfg.watchfaces.appcore.FaceLibrary
import com.bfg.watchfaces.appcore.PublishedSlug

/**
 * The community catalog: `catalog/faces/<slug>.json` plus a generated
 * `catalog/index.json`.
 *
 * Shape comes straight from docs/SPEC.md. Two things about it are load-bearing
 * and worth restating where the code is:
 *
 * **Parameters only, never rasters.** That is what keeps a face ~5KB, which is
 * what makes a 10,000-face catalog ~50MB of Git and therefore free to host. It
 * is also the IP shield: you cannot encode someone's logo as "knotwork, scale
 * 26, pewter", but you can certainly upload one -- so [Engine.TEXTURE] faces are
 * rejected here rather than politely accepted and dealt with later.
 *
 * **Submissions validate without a human.** A PR that adds a face is checked by
 * CI: it must parse, render, and emit WFF that passes Google's XSD. An invalid
 * face is not a review problem, it is a build failure. This matters more than
 * usual here because a schema-invalid face installs cleanly and then never
 * appears in the carousel -- there is nothing for a reviewer to notice.
 *
 *
 * ## The GitHub parts are gone
 *
 * `CDN_URL`, `REPO_URL` and `reportUrl` were removed on 2026-08-31, once the
 * catalog service was deployed and the app's own report path was observed
 * reaching it. They were kept until then because deleting a complaint path
 * before its replacement works is worse than one that needs an account.
 *
 * What is left is the FORMAT and the VALIDATOR, which outlived the transport:
 * `Entry` is still the face record, `toJson` still writes the shape `/export`
 * emits, and `validateDocument` is still the only place a face meets Google's
 * XSD. `docs/specs/catalog-service.md` is the contract.
 *
 * Served in production by the service now, not by jsDelivr.
 */
object CatalogStore {

    /**
     * A face is parameters. Anything much larger than this is not a face.
     *
     * The number lives in [CatalogContract] because the catalog service
     * enforces the same limit from JavaScript, having read it out of the
     * generated contract. Two constants would be two limits that can disagree,
     * and the one that disagrees is on the public endpoint.
     */
    const val MAX_FACE_BYTES = CatalogContract.MAX_FACE_BYTES

    data class Entry(
        val slug: String,
        val name: String,
        val author: String,
        val created: String,
        val params: DialParams
    )

    data class Problem(val file: String, val message: String)

    /**
     * Resolve the catalog root.
     *
     * The catalog is its own public repository now -- strangers opening pull
     * requests against a folder of JSON is a very different risk profile from
     * strangers opening them against the app's source. Order of preference:
     *
     *   1. BFG_CATALOG_DIR, for anyone working on a checkout somewhere else
     *   2. a sibling clone, which is what a contributor working on both has
     *   3. catalog/ inside this repo, which is the legacy in-tree location
     *
     * Returns null when none exists; the caller falls back to the CDN.
     */
    fun resolveRoot(repoRoot: File): File? {
        System.getenv("BFG_CATALOG_DIR")?.takeIf { it.isNotBlank() }?.let {
            val f = File(it)
            if (File(f, "faces").isDirectory) return f
        }
        val sibling = File(repoRoot.parentFile, "bfg-watchfaces-catalog")
        if (File(sibling, "faces").isDirectory) return sibling
        val inTree = File(repoRoot, "catalog")
        if (File(inTree, "faces").isDirectory) return inTree
        return null
    }

    fun dir(root: File): File = File(root, "faces")
    fun indexFile(root: File): File = File(root, "index.json")

    // ---- reading ------------------------------------------------------------

    fun list(root: File): List<Entry> =
        (dir(root).listFiles { f -> f.extension == "json" } ?: emptyArray())
            .sortedBy { it.name }
            .mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }

    fun parse(text: String): Entry {
        val o = Json.obj(Json.parse(text))
        val name = Json.str(o, "name", "")
        return Entry(
            slug = Json.str(o, "slug", FaceLibrary.slugify(name)),
            name = name,
            author = Json.str(o, "author", ""),
            created = Json.str(o, "created", ""),
            params = FaceCodec.fromJson(Json.obj(o["params"]))
        )
    }

    fun toJson(e: Entry): String = """{
  "name": ${Json.quote(e.name)},
  "slug": ${Json.quote(e.slug)},
  "author": ${Json.quote(e.author)},
  "created": ${Json.quote(e.created)},
  "params": ${FaceCodec.toJson(e.params).prependIndent("  ").trimStart()}
}
"""

    // ---- validation ---------------------------------------------------------

    /**
     * How a face's slug is expected to relate to its name.
     *
     * The two differ and it is not cosmetic. In the git catalog a file is
     * `<slug>.json` and the slug IS `slugify(name)` — anything else is a
     * mismatched pair. A face published by the SERVICE carries a random short
     * id (`midnight_7f3a`) because the slug is the Watch Face Push package
     * suffix and two strangers may pick the same name.
     *
     * Applying the git rule to a service submission would reject every single
     * one of them. Applying the service rule to a git file would let a
     * mismatched pair through. So the caller says which world it is in rather
     * than one rule guessing.
     */
    enum class SlugRule {
        /** `<slug>.json` in a git checkout: the slug is exactly `slugify(name)`. */
        GIT_CATALOG,

        /** Published by the service: `slugify(name)` truncated, plus a short id. */
        PUBLISHED
    }

    /**
     * Every reason a submission can be refused, checked in one pass so an
     * author sees all of them at once rather than one per attempt.
     *
     * Takes the document as TEXT rather than a file, because the moderation
     * pass validates what came back over HTTP and has nothing on disk to point
     * at. [label] is only used to name the thing in a [Problem].
     *
     * This is the ONLY place a face is checked against Google's XSD. The
     * catalog service cannot do it — a Worker is JavaScript, the emitter is
     * Kotlin and the validator is Xerces — so if this does not run before
     * publication, nothing does. A schema-invalid face installs cleanly,
     * reports success, and then never appears in the carousel.
     */
    fun validateDocument(
        root: File,
        label: String,
        text: String,
        slugRule: SlugRule = SlugRule.GIT_CATALOG
    ): List<Problem> {
        val problems = mutableListOf<Problem>()
        fun bad(msg: String) = problems.add(Problem(label, msg))

        if (text.toByteArray().size > MAX_FACE_BYTES) {
            bad("is ${text.toByteArray().size} bytes; a face is parameters and must stay under $MAX_FACE_BYTES")
        }

        val entry = runCatching { parse(text) }.getOrElse {
            bad("is not valid catalog JSON: ${it.message}"); return problems
        }

        if (entry.name.isBlank()) bad("has no name")
        when (slugRule) {
            SlugRule.GIT_CATALOG ->
                if (entry.slug != FaceLibrary.slugify(entry.name)) {
                    bad("slug '${entry.slug}' does not match its name '${entry.name}' (expected '${FaceLibrary.slugify(entry.name)}')")
                }
            SlugRule.PUBLISHED ->
                if (!PublishedSlug.matches(entry.slug, entry.name)) {
                    bad("slug '${entry.slug}' is not a published slug for '${entry.name}' " +
                        "(expected '${PublishedSlug.stemFor(entry.name)}_' plus a short id). " +
                        "The slug is the package name, so the watch would file it under something the gallery does not call it")
                }
        }
        runCatching { WffEmitter.pushPackageName("com.bfg.watchfaces", entry.slug) }
            .onFailure { bad("slug is not a legal Watch Face Push package segment: ${it.message}") }

        // Parametric only. This is the IP shield and the size guarantee, not a
        // style preference -- an imported image cannot be re-derived from
        // parameters and cannot be licensed by us.
        if (entry.params.engine == Engine.TEXTURE) {
            bad("uses the TEXTURE engine. The catalog is parameters only: a face built on an imported image stays on the machine that made it")
        }
        if (entry.params.texture.isNotBlank()) bad("references an imported image, which cannot be published")

        // No generatorVersion check here on purpose: DialParams' own constructor
        // already refuses a version this build does not implement, so parse()
        // above throws first and reports it. A second check would be
        // unreachable code that looks like protection.

        // The one that actually bites: a schema-invalid face installs, reports
        // success, and never appears.
        val xml = runCatching { WffEmitter.emit(entry.params, entry.name) }.getOrElse {
            bad("does not render: ${it.message}"); return problems
        }
        when (val issues = WffValidator.validate(root, xml)) {
            null -> bad("could not be schema-checked: the WFF schema is not installed (run scripts/bootstrap.sh)")
            else -> issues.take(3).forEach { bad("emits schema-invalid WFF at line ${it.line}: ${it.message}") }
        }
        return problems
    }

    /** A face on disk, in a git catalog checkout. */
    fun validate(root: File, file: File): List<Problem> {
        val text = runCatching { file.readText() }.getOrElse {
            return listOf(Problem(file.name, "cannot be read: ${it.message}"))
        }
        val problems = validateDocument(root, file.name, text, SlugRule.GIT_CATALOG).toMutableList()
        // Only meaningful for a file: the service has no filenames.
        runCatching { parse(text) }.getOrNull()?.let { entry ->
            if (file.nameWithoutExtension != entry.slug) {
                problems.add(Problem(file.name, "filename does not match slug '${entry.slug}'"))
            }
        }
        return problems
    }

    /**
     * Validates every face and reports duplicates.
     *
     * [schemaRoot] is where the WFF schema lives (the app repo); [catalogRoot]
     * is the catalog checkout. They are different repositories now.
     */
    fun validateAll(schemaRoot: File, catalogRoot: File = schemaRoot): List<Problem> {
        val files = (dir(catalogRoot).listFiles { f -> f.extension == "json" } ?: emptyArray()).sortedBy { it.name }
        val problems = files.flatMap { validate(schemaRoot, it) }.toMutableList()

        val bySlug = files.groupBy { it.nameWithoutExtension }
        bySlug.filterValues { it.size > 1 }.forEach { (slug, dupes) ->
            problems += Problem(dupes.first().name, "duplicate slug '$slug' (${dupes.size} files)")
        }
        return problems
    }

    // ---- index --------------------------------------------------------------

    /**
     * The generated index the gallery reads.
     *
     * It carries enough to render a browsable list -- name, author, engine and
     * the two colours -- so a gallery of a thousand faces is ONE request, not a
     * thousand. Full parameters still live in the per-face files, fetched only
     * when someone opens one.
     */
    fun buildIndex(root: File): String {
        val entries = list(root)
        val rows = entries.joinToString(",\n") { e ->
            """    {"slug": ${Json.quote(e.slug)}, "name": ${Json.quote(e.name)}, """ +
            """"author": ${Json.quote(e.author)}, "engine": ${Json.quote(e.params.engine.name)}, """ +
            """"dialColor": ${Json.quote(e.params.dialColor)}, "inkColor": ${Json.quote(e.params.inkColor)}, """ +
            """"generatorVersion": ${e.params.generatorVersion}, "created": ${Json.quote(e.created)}}"""
        }
        // The HIGHEST version among the faces, not the version of whatever built
        // the index. Recording the builder's version made every committed index
        // go stale the moment the generator was bumped, which is churn that says
        // nothing. What a client actually needs to know is whether it is new
        // enough to render everything in here.
        val maxVersion = entries.maxOfOrNull { it.params.generatorVersion } ?: 0
        return """{
  "generated": ${Json.quote(Instant.now().toString())},
  "maxGeneratorVersion": $maxVersion,
  "count": ${entries.size},
  "faces": [
$rows
  ]
}
"""
    }

    fun writeIndex(root: File): Int {
        val f = indexFile(root)
        f.parentFile.mkdirs()
        f.writeText(buildIndex(root))
        return list(root).size
    }

    // ---- submitting ---------------------------------------------------------

    /**
     * Stage a saved face as a catalog submission.
     *
     * It does NOT open a pull request. Publishing is the author's action, not
     * the tool's: this writes the file and validates it, and the human commits
     * and opens the PR. A design tool that pushes to a public repo on a button
     * press is a mistake waiting to happen.
     */
    fun submit(
        schemaRoot: File,
        catalogRoot: File,
        face: FaceLibrary.StoredFace,
        author: String
    ): Pair<File, List<Problem>> {
        val entry = Entry(
            slug = face.slug,
            name = face.name,
            author = author.trim(),
            created = face.created.ifBlank { Instant.now().toString() },
            params = face.params
        )
        // Two different roots, deliberately named: the face is written to the
        // CATALOG, but the WFF schema that judges it lives in the app repo.
        // Conflating them is what listed private faces as community content.
        val f = File(dir(catalogRoot).apply { mkdirs() }, "${entry.slug}.json")
        f.writeText(toJson(entry))
        val problems = validate(schemaRoot, f)
        if (problems.isNotEmpty()) f.delete()   // never leave an invalid submission staged
        return f to problems
    }
}
