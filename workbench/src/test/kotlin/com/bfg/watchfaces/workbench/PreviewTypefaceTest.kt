package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.DIAL_SIZE
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.FaceFont
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

/**
 * The typeface control has to change the picture.
 *
 * ## The bug this exists for
 *
 * `FacePreview.font()` took `face: FaceFont = FaceFont.DEFAULT`, and its one
 * caller never passed it. So the mapping from [FaceFont] to an AWT family was
 * written, correct, and unreachable: every text on every workbench preview drew
 * in the default sans, whatever the face asked for. Choosing Serif or Mono
 * changed the emitted `watchface.xml` and changed nothing you could see, in the
 * one view the whole design loop exists to look at.
 *
 * It is the quieter half of the pair. The phone preview (`AndroidFacePreview`)
 * had the same defaulted-parameter shape and one call site out of six forgot
 * it, which GHOSTED — relief passes in the chosen family under glyphs in the
 * default one — and a person reported that within a day. This one hid, because
 * a control that does nothing looks exactly like a control you have not
 * noticed working.
 *
 * ## Why it is written this way
 *
 * `PreviewDateTest` records that this bug's guards twice "compared two calls
 * down one path and could not fail". So this test renders through the SAME
 * public entry point the browser and the bake use, changes ONLY `fontFamily`,
 * and asserts the pixels move. Ablate the fix and it goes red; that is the
 * whole point of it.
 *
 * SERIF and MONO are the two that must differ, because AWT genuinely has them
 * ([Font.SERIF], [Font.MONOSPACED]). The remaining families all map to the same
 * local sans on a desktop JVM and are deliberately NOT asserted here — that is
 * the documented approximation, not a defect, and asserting it would pin this
 * test to a machine's installed fonts.
 */
class PreviewTypefaceTest {

    private fun render(family: FaceFont): BufferedImage {
        val p = DialParams()
        return FacePreview.render(
            p.copy(layout = p.layout.copy(fontFamily = family.wff)),
            ambient = false,
            size = DIAL_SIZE
        )
    }

    private fun differingPixels(a: BufferedImage, b: BufferedImage): Int {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a.getRGB(x, y) != b.getRGB(x, y)) n++
        }
        return n
    }

    @Test
    fun `a serif face does not render as the default sans`() {
        val sans = render(FaceFont.SANS)
        val serif = render(FaceFont.SERIF)
        val n = differingPixels(sans, serif)
        assertTrue(n > 0) {
            "asking for ${FaceFont.SERIF.wff} produced pixel-identical output to " +
                "${FaceFont.SANS.wff}, so the typeface control does nothing in the preview. " +
                "Check that FacePreview.font() is actually told which FaceFont to use."
        }
    }

    @Test
    fun `a monospaced face does not render as the default sans`() {
        val sans = render(FaceFont.SANS)
        val mono = render(FaceFont.MONO)
        val n = differingPixels(sans, mono)
        assertTrue(n > 0) {
            "asking for ${FaceFont.MONO.wff} produced pixel-identical output to " +
                "${FaceFont.SANS.wff}, so the typeface control does nothing in the preview. " +
                "Check that FacePreview.font() is actually told which FaceFont to use."
        }
    }

    /**
     * The two previews must AGREE about typeface, which is the rule CLAUDE.md
     * states and the reason both halves of this bug matter.
     *
     * `AndroidFacePreview` needs an Android runtime, so nothing on the JVM can
     * render it — the same wall `PreviewDateTest` hit. Reading the source is
     * the blunt instrument that covers both, and it is the only thing here that
     * can see the phone renderer at all.
     *
     * What it pins: neither renderer may give its font-family parameter a
     * DEFAULT. A default is precisely what let a call site forget on each side,
     * in one case invisibly for the life of the control.
     */
    @Test
    fun `neither preview lets a font family parameter default`() {
        val root = RepoRoot.find()
        val renderers = listOf(
            "workbench/src/main/kotlin/com/bfg/watchfaces/workbench/FacePreview.kt",
            "mobile/src/main/kotlin/com/bfg/watchfaces/mobile/AndroidFacePreview.kt"
        )
        // `face: FaceFont = ...` or `family: String = ...` in a parameter list.
        val defaulted = Regex("""\b(face|family)\s*:\s*(FaceFont|String)\s*=""")
        for (path in renderers) {
            val file = java.io.File(root, path)
            if (!file.isFile) continue          // a renderer may be renamed; do not fail on that
            val offenders = file.readLines().withIndex().filter { (_, line) ->
                defaulted.containsMatchIn(line) && !line.trimStart().startsWith("*") &&
                    !line.trimStart().startsWith("//")
            }
            assertTrue(offenders.isEmpty()) {
                "$path gives a font family parameter a default at line " +
                    "${offenders.map { it.index + 1 }}. That is how this bug happened twice: " +
                    "a call site omits it and silently draws in the wrong typeface. " +
                    "Make it required and let the compiler ask."
            }
        }
    }
}
