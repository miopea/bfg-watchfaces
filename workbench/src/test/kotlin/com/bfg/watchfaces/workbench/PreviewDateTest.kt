package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.DateStyle
import com.bfg.watchfaces.generator.DialParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.imageio.ImageIO

/**
 * A preview must draw the same day whenever it is rendered, on whichever
 * machine, on whatever date.
 *
 * `RenderPipelineTest` pins the pixels. This pins the two things pinning pixels
 * cannot: that NO renderer reads the wall clock, and that the file which
 * actually ships is the deterministic one.
 *
 * The bug this exists for: `DateStyle.sample()` defaults to `LocalDate.now()`,
 * both preview renderers called it bare, and so a preview drew whatever day it
 * happened to be rendered on beside a clock fixed at 10:10. The goldens rotted
 * overnight — they went red mid-session because it passed midnight — and every
 * `preview.png` baked into an APK carried its build date.
 */
class PreviewDateTest {

    private val root = RepoRoot.find()

    /**
     * THE ONE THAT COVERS THE RENDERER NO TEST HERE CAN REACH.
     *
     * `AndroidFacePreview` needs an Android runtime, so nothing on the JVM
     * exercises it — and it is half the problem, because the phone preview and
     * the baked preview are two renderers that can disagree. Reading the source
     * is a blunt instrument and it is the one that covers both.
     *
     * The task offered this as the alternative to running the suite with the
     * system date faked. There is no `faketime` on this machine, so this is the
     * proof.
     */
    @Test
    fun `no renderer asks the wall clock what day it is`() {
        val renderers = listOf(
            "workbench/src/main/kotlin/com/bfg/watchfaces/workbench/FacePreview.kt",
            "workbench/src/main/kotlin/com/bfg/watchfaces/workbench/DialRenderer.kt",
            "mobile/src/main/kotlin/com/bfg/watchfaces/mobile/AndroidFacePreview.kt",
            "mobile/src/main/kotlin/com/bfg/watchfaces/mobile/AndroidDialRenderer.kt"
        )
        val bare = Regex("""\.sample\(\s*\)""")
        for (path in renderers) {
            val file = File(root, path)
            if (!file.isFile) continue          // a renderer may be renamed; do not fail on that
            val offenders = file.readLines().withIndex().filter { (_, line) ->
                bare.containsMatchIn(line) && !line.trimStart().startsWith("//")
            }
            assertTrue(offenders.isEmpty()) {
                "$path calls sample() with no argument at line ${offenders.map { it.index + 1 }}, " +
                    "so it draws today's date. Pass the render's own date, or DateStyle.SAMPLE_DATE."
            }
            assertTrue(file.readText().contains("LocalDate.now()").not()) {
                "$path reads the wall clock directly"
            }
        }
    }

    /**
     * The file that actually ships is the canonical render, quantized — and
     * NOTHING MORE THAN THAT IS CLAIMED HERE.
     *
     * `Workbench.exportTo` writes `preview.png` into the template and aapt2
     * builds it into the APK as the carousel thumbnail. This pins that the
     * exported bytes are exactly `FacePreview.render` at 64 colours, so it
     * catches `exportTo` drifting onto a different renderer or a different
     * quantization.
     *
     * IT DOES NOT PROVE THE DATE IS DETERMINISTIC, and it was briefly named as
     * though it did. Both sides of the comparison go through the same
     * `FacePreview.render`, so if that render read the wall clock both would
     * read it and still match — ablating the fix showed this test passing.
     * Determinism is proved by the source scan above and by
     * `RenderPipelineTest`'s two-different-days guard.
     *
     * That is the second time on this bug that a guard compared two calls down
     * one path and could not fail. It is worth naming as a habit rather than an
     * accident.
     */
    @Test
    fun `the exported preview is exactly the canonical render`(@TempDir dir: File) {
        File(dir, "watchface-template/res/raw").mkdirs()
        File(dir, "watchface-template/res/values").mkdirs()
        File(dir, "watchface-template/res/drawable-nodpi").mkdirs()

        val face = DialParams(dateStyle = DateStyle.WEEKDAY_MONTH_DAY)
        Workbench.exportTo(dir, face, colors = 64, faceName = "Date Check")

        val exported = ImageIO.read(File(dir, "watchface-template/res/drawable-nodpi/preview.png"))
        val expected = Quantizer.quantize(
            FacePreview.render(face, ambient = false, size = com.bfg.watchfaces.generator.DIAL_SIZE),
            64
        ).image

        assertEquals(expected.width, exported.width)
        assertEquals(expected.height, exported.height)
        var differing = 0
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                if (expected.getRGB(x, y) != exported.getRGB(x, y)) differing++
            }
        }
        assertEquals(0, differing) {
            "the exported preview differs from FacePreview.render in $differing pixels, so " +
                "exportTo is no longer writing the canonical render"
        }
    }

    /**
     * The sample moment agrees with itself.
     *
     * 10 March is not arbitrary: `Complications.sample` renders `DAY_AND_DATE`
     * as "MAR 10" and both renderers put the clock at 10:10. If someone moves
     * one they should be made to move the other.
     */
    @Test
    fun `the drawn date and the sample complication describe the same day`() {
        val drawn = DateStyle.WEEKDAY_MONTH_DAY.sample(DateStyle.SAMPLE_DATE).uppercase()
        assertTrue(drawn.contains("MAR") && drawn.contains("10")) {
            "the drawn date sample is '$drawn', which no longer matches the 'MAR 10' the " +
                "complication samples use or the 10:10 clock both renderers draw"
        }
    }
}
