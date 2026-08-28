package com.bfg.watchfaces.workbench

import java.io.File

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
        val root = findRoot()
        val dir = CatalogStore.dir(root)

        println("catalog: ${dir.relativeTo(root)}")
        if (!dir.isDirectory) {
            println("  no catalog directory yet -- nothing to validate")
            if (!checkOnly) {
                CatalogStore.writeIndex(root)
                println("  wrote an empty ${CatalogStore.indexFile(root).relativeTo(root)}")
            }
            return
        }

        val faces = (dir.listFiles { f -> f.extension == "json" } ?: emptyArray()).size
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

        val problems = CatalogStore.validateAll(root)
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
            val fresh = CatalogStore.buildIndex(root)
            val existing = CatalogStore.indexFile(root).takeIf { it.isFile }?.readText() ?: ""
            if (stripGenerated(fresh) != stripGenerated(existing)) {
                System.err.println("  index.json is out of date. Run: ./gradlew :workbench:catalog")
                kotlin.system.exitProcess(1)
            }
            println("  index.json is up to date")
            return
        }

        val n = CatalogStore.writeIndex(root)
        println("  wrote ${CatalogStore.indexFile(root).relativeTo(root)} ($n face(s))")
        println("  served in production from ${CatalogStore.CDN_URL}")
    }

    /** The timestamp changes on every run; it is not drift. */
    private fun stripGenerated(s: String) =
        s.lines().filterNot { it.trimStart().startsWith("\"generated\"") }.joinToString("\n")

    private fun findRoot(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            if (File(d, "settings.gradle.kts").isFile) return d
            d = d.parentFile
        }
        return File(System.getProperty("user.dir")).absoluteFile
    }
}
