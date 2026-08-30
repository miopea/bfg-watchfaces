package com.bfg.watchfaces.workbench

import java.io.File

/**
 * Where the repository starts, from wherever a task happened to be launched.
 *
 * Every headless task here writes into the tree — `bake` into
 * `watchface-template`, `brand` into two `res/` directories, `contract` into
 * `catalog-service` — so each one needs the root, and each one had its own
 * identical copy of this walk. The fourth copy is what made it worth naming.
 *
 * `settings.gradle.kts` is the marker rather than `.git`, so it still resolves
 * in a checkout used as a submodule or exported without history.
 */
internal object RepoRoot {

    fun find(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            if (File(d, "settings.gradle.kts").isFile) return d
            d = d.parentFile
        }
        // Fall back to the working directory rather than throwing: every Gradle
        // task here sets workingDir to the root, so this only matters when one
        // is run by hand from somewhere unexpected.
        return File(System.getProperty("user.dir")).absoluteFile
    }
}
