package com.bfg.watchfaces.workbench

import java.io.File
import com.bfg.watchfaces.appcore.Json

/**
 * Validate the community catalog and regenerate its index.
 *
 * This is what CI runs on a submission PR. docs/SPEC.md says invalid faces are
 * rejected before human review, and that is only true if a bad face FAILS THE
 * BUILD -- a reviewer cannot see that a face is schema-invalid by reading the
 * diff, and the failure mode on a watch is silence, not an error.
 *
 *   ./gradlew :workbench:catalog          # validate + rewrite index.json
 *   ./gradlew :workbench:catalog --args="--check"   # validate only, no writes
 */
object Catalog {

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val checkOnly = args.contains("--check")
        val repoRoot = RepoRoot.find()

        // --dir lets the catalog repo's own CI validate its checkout using this
        // validator, so there is one implementation rather than a copy that
        // drifts. Without it, resolve the usual places.
        val explicit = args.firstOrNull { it.startsWith("--dir=") }?.removePrefix("--dir=")
        val catalogRoot = explicit?.let { File(it) } ?: CatalogStore.resolveRoot(repoRoot)
        if (catalogRoot == null) {
            println("no catalog checkout found.")
            println("  set BFG_CATALOG_DIR, or put one beside this repo as bfg-watchfaces-catalog")
            return
        }
        val root = repoRoot          // schema lives here; the catalog may not
        val dir = CatalogStore.dir(catalogRoot)

        println("catalog: ${dir.absolutePath}")

        val faces = (dir.listFiles { f -> f.extension == "json" } ?: emptyArray()).size
        val indexed = indexedCount(catalogRoot)

        // An index that disagrees with the faces beside it is worse than no
        // index at all. This is not hypothetical: an unanchored `faces/` line in
        // .gitignore matched catalog/faces/ as well as the personal directory,
        // so index.json was committed describing seven faces that were not.
        // CI happily validated a directory that was not there and passed.
        if (indexed > faces) {
            System.err.println("  index.json describes $indexed face(s) but only $faces are present.")
            System.err.println("  The faces are missing -- check .gitignore, and see DECISIONS.md 2026-08-28.")
            kotlin.system.exitProcess(1)
        }

        if (!dir.isDirectory || faces == 0) {
            println("  no faces yet -- nothing to validate")
            if (!checkOnly) {
                CatalogStore.writeIndex(catalogRoot)
                println("  wrote an empty ${CatalogStore.indexFile(catalogRoot).absolutePath}")
            }
            return
        }

        println("  $faces face(s)")

        // A missing schema makes every validation vacuous while still exiting 0.
        // Under CI, where bootstrap.sh is a build step, that is a failure.
        if (WffValidator.validate(root, "<x/>") == null) {
            val msg = "  WFF schema not installed -- submissions cannot be checked"
            if (System.getenv("CI") != null) {
                System.err.println("$msg, and CI=true. scripts/bootstrap.sh did not deliver it.")
                System.err.println("  Refusing to pass: this step would validate nothing.")
                kotlin.system.exitProcess(1)
            }
            println("$msg. Run scripts/bootstrap.sh.")
        }

        val problems = CatalogStore.validateAll(root, catalogRoot)
        if (problems.isNotEmpty()) {
            System.err.println()
            System.err.println("  ${problems.size} problem(s):")
            problems.forEach { System.err.println("    ${it.file}: ${it.message}") }
            System.err.println()
            System.err.println("  The catalog is parameters only, and every face must emit")
            System.err.println("  schema-valid WFF. See CONTRIBUTING.md.")
            kotlin.system.exitProcess(1)
        }
        println("  all faces valid")

        if (checkOnly) {
            // Regenerate in memory and compare, so a stale committed index is a
            // failure rather than something that silently drifts from the faces.
            val fresh = CatalogStore.buildIndex(catalogRoot)
            val existing = CatalogStore.indexFile(catalogRoot).takeIf { it.isFile }?.readText() ?: ""
            if (stripGenerated(fresh) != stripGenerated(existing)) {
                System.err.println("  index.json is out of date. Run: ./gradlew :workbench:catalog")
                kotlin.system.exitProcess(1)
            }
            println("  index.json is up to date")
            return
        }

        val n = CatalogStore.writeIndex(catalogRoot)
        println("  wrote ${CatalogStore.indexFile(catalogRoot).absolutePath} ($n face(s))")
        // No production URL to print any more: the live catalog is the
        // service, and this task validates a LOCAL checkout. Saying where
        // production served from was true when the checkout WAS production.
        println("  this is a local checkout; the live catalog is the service")
    }

    /** How many faces the committed index claims, or 0 if there is no index. */
    private fun indexedCount(root: File): Int {
        val f = CatalogStore.indexFile(root)
        if (!f.isFile) return 0
        return runCatching {
            Json.num(Json.obj(Json.parse(f.readText())), "count", 0.0).toInt()
        }.getOrDefault(0)
    }

    /** The timestamp changes on every run; it is not drift. */
    private fun stripGenerated(s: String) =
        s.lines().filterNot { it.trimStart().startsWith("\"generated\"") }.joinToString("\n")

}
