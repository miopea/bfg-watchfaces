package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.DialParams
import java.io.File
import com.bfg.watchfaces.appcore.FaceCodec
import com.bfg.watchfaces.appcore.Presets

/**
 * Headless bake: params in, dial_bg.png + preview.png + watchface.xml out.
 *
 * The workbench server is for humans; this is the same pipeline for build.sh,
 * CI, and anything else without a browser. Both call [Workbench.exportTo], so
 * there is no "quick path" that can produce different bytes than the UI shows.
 *
 *   ./gradlew :workbench:bake
 *   ./gradlew :workbench:bake --args="--preset=Rosette Noir"
 *   ./gradlew :workbench:bake --args="--engine=CLOUS --scale=22 --dialColor=#6E6A66"
 */
object Bake {

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")

        // Gradle splits --args on whitespace, so `--preset=Rosette Noir` arrives
        // as TWO argv entries and the second one used to be silently dropped --
        // which made every multi-word preset name in the README a command that
        // did not work. A bare word continues the value of the flag before it.
        val kv = LinkedHashMap<String, String>()
        var last: String? = null
        for (a in args) {
            if (a.startsWith("--")) {
                val body = a.removePrefix("--")
                val i = body.indexOf('=')
                if (i > 0) { kv[body.substring(0, i)] = body.substring(i + 1); last = body.substring(0, i) }
                else { kv[body] = "true"; last = null }
            } else if (last != null) {
                kv[last] = (kv[last] + " " + a).trim()
            }
        }

        val preset = kv.remove("preset")
        val colors = kv.remove("colors")?.toIntOrNull() ?: 64
        // The baked face carries a name: it becomes the carousel label and the
        // Push package. Falls back to the preset it came from rather than
        // stamping every bake "Untitled".
        val faceName = kv.remove("name") ?: preset ?: "Untitled"
        val base: DialParams = if (preset != null) {
            Presets.ALL.entries.firstOrNull { it.key.equals(preset, ignoreCase = true) }?.value
                ?: run {
                    System.err.println("unknown preset '$preset'. known: ${Presets.ALL.keys.joinToString(", ")}")
                    kotlin.system.exitProcess(2)
                }
        } else DialParams()

        // Explicit flags win over the preset, so you can nudge one value.
        val merged = FaceCodec.fromQuery(FaceCodec.toQuery(base).toQueryMap() + kv)

        val root = findRoot()
        println("baking into ${root.absolutePath}")
        println("  \"$faceName\" -- engine=${merged.engine} scale=${merged.scale} dial=${merged.dialColor} ink=${merged.inkColor}")

        // Refuse to write an artefact that would install and then never appear.
        // Validate the exact XML that gets written, name included.
        val xml = com.bfg.watchfaces.generator.WffEmitter.emit(merged, faceName)
        val issues = WffValidator.validate(root, xml)
        when {
            // A missing schema makes this step measure nothing while still
            // exiting 0 -- identical to success from the outside. Locally that
            // is a warning; in CI, where bootstrap.sh is a build step, it is a
            // failure, or the bake smoke test silently stops validating.
            issues == null && System.getenv("CI") != null -> {
                System.err.println("  WFF schema not installed but CI=true -- scripts/bootstrap.sh did not deliver it.")
                System.err.println("  Refusing to bake: this step would otherwise pass without validating anything.")
                kotlin.system.exitProcess(1)
            }
            issues == null -> println("  WARNING: WFF schema not installed -- run scripts/bootstrap.sh. Skipping validation.")
            issues.isEmpty() -> println("  WFF: valid against Google's XSD")
            else -> {
                System.err.println("  WFF INVALID -- refusing to bake. A schema-invalid face installs silently and never appears:")
                issues.forEach { System.err.println("    line ${it.line}: ${it.message}") }
                kotlin.system.exitProcess(1)
            }
        }

        Workbench.exportTo(root, merged, colors, faceName).forEach { println("  wrote $it") }
        println("done. next: cd watchface-template && ./build.sh")
    }

    private fun String.toQueryMap(): Map<String, String> =
        split("&").mapNotNull {
            val i = it.indexOf('='); if (i <= 0) return@mapNotNull null
            java.net.URLDecoder.decode(it.substring(0, i), Charsets.UTF_8) to
                java.net.URLDecoder.decode(it.substring(i + 1), Charsets.UTF_8)
        }.toMap()

    private fun findRoot(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            if (File(d, "settings.gradle.kts").isFile) return d
            d = d.parentFile
        }
        return File(System.getProperty("user.dir")).absoluteFile
    }
}
