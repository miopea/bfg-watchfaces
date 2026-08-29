package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.DIAL_SIZE
import com.bfg.watchfaces.generator.ComplicationSource
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.Engine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import com.bfg.watchfaces.appcore.FaceCodec
import com.bfg.watchfaces.appcore.Presets

/**
 * The bake path is now load-bearing: it produces the artwork that ships inside
 * the APK. These tests cover the properties that would otherwise only be
 * discovered on a wrist.
 */
class RenderPipelineTest {

    companion object {
        @JvmStatic @BeforeAll fun headless() { System.setProperty("java.awt.headless", "true") }
    }

    private fun pixels(img: BufferedImage): IntArray =
        img.getRGB(0, 0, img.width, img.height, null, 0, img.width)

    @ParameterizedTest
    @EnumSource(Engine::class)
    fun `every engine rasterizes to a full dial`(engine: Engine) {
        val img = DialRenderer.render(DialParams(engine = engine))
        assertEquals(DIAL_SIZE, img.width)
        assertEquals(DIAL_SIZE, img.height)
        // Centre is inside the dial and must be opaque; corners are outside the
        // circle and must be transparent, or we are shipping wasted bytes.
        assertTrue((img.getRGB(DIAL_SIZE / 2, DIAL_SIZE / 2) ushr 24) == 255) { "$engine centre not opaque" }
        assertTrue((img.getRGB(2, 2) ushr 24) == 0) { "$engine corner not transparent" }
    }

    @ParameterizedTest
    @EnumSource(Engine::class)
    fun `rendering is deterministic`(engine: Engine) {
        // Community faces are parameters. If the same params rasterize to
        // different bytes, an author's face is not reproducible.
        val p = DialParams(engine = engine)
        assertArrayEqualsInt(pixels(DialRenderer.render(p)), pixels(DialRenderer.render(p)))
    }

    private fun assertArrayEqualsInt(a: IntArray, b: IntArray) {
        assertEquals(a.size, b.size)
        for (i in a.indices) if (a[i] != b[i]) {
            throw AssertionError("pixel $i differs: ${a[i].toUInt().toString(16)} vs ${b[i].toUInt().toString(16)}")
        }
    }

    @Test
    fun `render scales without changing the design`() {
        // Geometry is authored in 456 space. A 2x render must be the same image
        // at higher resolution, not a differently-composed one.
        val small = DialRenderer.render(DialParams(), DIAL_SIZE)
        val big = DialRenderer.render(DialParams(), DIAL_SIZE * 2)
        assertEquals(DIAL_SIZE * 2, big.width)
        // Centre pixel colour should agree closely across scales.
        val a = small.getRGB(DIAL_SIZE / 2, DIAL_SIZE / 2)
        val b = big.getRGB(DIAL_SIZE, DIAL_SIZE)
        for (shift in listOf(16, 8, 0)) {
            val d = Math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
            assertTrue(d <= 24) { "channel at shift $shift differs by $d across scales" }
        }
    }

    @Test
    fun `quantization stays inside the budget the spec measured`() {
        val src = DialRenderer.render(DialParams())
        val q = Quantizer.quantize(src, 64)
        assertTrue(q.colors <= 64) { "palette grew to ${q.colors}" }
        // docs/SPEC.md measured mean error 0.66/255 on the reference dial and
        // calls it visually identical. Guard the property, with headroom.
        assertTrue(q.meanError < 2.0) { "quantization error ${q.meanError}/255 is too high" }
    }

    @Test
    fun `quantization actually shrinks the transferred file`() {
        // The APK crosses to the watch over Bluetooth; this is the whole reason
        // quantization is mandatory rather than optional.
        val src = DialRenderer.render(DialParams())
        fun bytes(img: BufferedImage): Int {
            val bos = java.io.ByteArrayOutputStream(); ImageIO.write(img, "png", bos); return bos.size()
        }
        val raw = bytes(src)
        val quant = bytes(Quantizer.quantize(src, 64).image)
        assertTrue(quant < raw) { "quantized ($quant) is not smaller than raw ($raw)" }
    }

    @Test
    fun `ambient stays far under the lit-pixel ceiling`() {
        // DECISIONS.md: the dial fades to alpha 0 in ambient because a lit
        // mid-tone dial is the most expensive thing on an OLED panel. Wear OS
        // budgets roughly 15% lit pixels. This test is what keeps that true if
        // someone "improves" ambient by leaving the texture visible.
        fun litFraction(img: BufferedImage): Double {
            val px = pixels(img)
            var lit = 0
            for (p in px) {
                if ((p ushr 24) < 128) continue
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                if ((r * 299 + g * 587 + b * 114) / 1000 > 24) lit++
            }
            return lit.toDouble() / px.size
        }
        val ambient = litFraction(FacePreview.render(DialParams(), ambient = true))
        val interactive = litFraction(FacePreview.render(DialParams(), ambient = false))
        assertTrue(ambient < 0.15) { "ambient lights ${"%.1f".format(ambient * 100)}% of pixels, over the ~15% ceiling" }
        assertTrue(interactive > ambient * 4) { "ambient is not meaningfully darker than interactive" }
    }

    @Test
    fun `ambient and interactive are genuinely different renders`() {
        assertNotEquals(
            pixels(FacePreview.render(DialParams(), ambient = true)).toList(),
            pixels(FacePreview.render(DialParams(), ambient = false)).toList()
        )
    }

    @Test
    fun `export writes exactly what build_sh needs`(@TempDir tmp: File) {
        // build.sh fails on a missing dial_bg.png, and aapt2 link fails on the
        // unresolved @drawable/preview that watch_face_info.xml requires.
        File(tmp, "watchface-template/res/raw").mkdirs()
        Workbench.exportTo(tmp, DialParams(), 64)

        val dial = File(tmp, "watchface-template/res/drawable-nodpi/dial_bg.png")
        val preview = File(tmp, "watchface-template/res/drawable-nodpi/preview.png")
        val xml = File(tmp, "watchface-template/res/raw/watchface.xml")
        assertTrue(dial.isFile && dial.length() > 0) { "dial_bg.png not written" }
        assertTrue(preview.isFile && preview.length() > 0) { "preview.png not written" }
        assertTrue(xml.isFile && xml.readText().contains("<WatchFace")) { "watchface.xml not written" }
    }

    @Test
    fun `params survive a query string round trip`() {
        val p = DialParams(
            engine = Engine.ROSETTE, scale = 17.5, depth = 6.25, freq = 11,
            dialColor = "#23262B", inkColor = "#E8E6E1", lens = false, lensAmount = 12.0
        )
        val back = FaceCodec.fromQuery(
            FaceCodec.toQuery(p).split("&").associate {
                val i = it.indexOf('=')
                java.net.URLDecoder.decode(it.substring(0, i), Charsets.UTF_8) to
                    java.net.URLDecoder.decode(it.substring(i + 1), Charsets.UTF_8)
            }
        )
        assertEquals(p, back)
    }

    private fun repoRoot(): File = File(System.getProperty("user.dir")).let {
        generateSequence(it) { d -> d.parentFile }.first { d -> File(d, "settings.gradle.kts").isFile }
    }

    /**
     * Guards the no-op: if the schema is not installed, [WffValidator.validate]
     * returns null and EVERY schema assertion in this suite passes without
     * validating anything. Passing-because-unmeasured is indistinguishable from
     * passing-because-correct, which is the whole defect class.
     *
     * Locally that is a skip with a message telling you to run bootstrap.sh.
     * In CI, where bootstrap.sh is a build step, its absence is a hard failure.
     */
    @Test
    fun `schema validation is wired up, not silently skipped`() {
        val issues = WffValidator.validate(repoRoot(), com.bfg.watchfaces.generator.WffEmitter.emit(DialParams()))
        if (issues == null && System.getenv("CI") != null) {
            org.junit.jupiter.api.Assertions.fail<Unit>(
                "WFF schema is not installed but CI=true. scripts/bootstrap.sh did not deliver it, " +
                "and every schema assertion in this suite would have passed without validating anything."
            )
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(issues != null) {
            "WFF schema not installed -- run scripts/bootstrap.sh. Schema assertions are NOT being checked."
        }
        assertTrue(issues!!.isEmpty()) { "default params emit invalid WFF: $issues" }
    }

    @Test
    fun `every preset is renderable and schema valid`() {
        val root = repoRoot()
        for ((name, p) in Presets.ALL) {
            val img = DialRenderer.render(p)
            assertTrue(img.width == DIAL_SIZE) { "$name did not render" }
            val issues = WffValidator.validate(root, com.bfg.watchfaces.generator.WffEmitter.emit(p))
            if (issues != null) assertTrue(issues.isEmpty()) { "$name emits invalid WFF: $issues" }
        }
    }
    /**
     * The composite preview, pinned.
     *
     * `preview.png` SHIPS — it is the thumbnail the carousel shows — so a change
     * to how the complication icons or the clock are drawn changes an asset
     * inside every APK built afterwards. This is the only test that would notice.
     *
     * A failure here is not automatically a bug. It means the preview's
     * appearance changed, and whoever changed it has to say so on purpose.
     */
    @Test
    fun `the composite preview is byte-for-byte what it has always been`() {
        val faces = listOf(
            DialParams(),
            DialParams(engine = Engine.KNOTWORK, dialColor = "#2B2E33", inkColor = "#C9A227"),
            DialParams(complications = ComplicationSource.entries.take(5)),
            // A generated surface, so the procedural shading is pinned too. The
            // stroked engines do not exercise it at all.
            DialParams(engine = Engine.BRUSHED, contrast = 62.0, relief = 3.4),
            DialParams(engine = Engine.GRAIN, dialColor = "#3E4A3F")
        )
        val actual = faces.map { p ->
            val img = FacePreview.render(p)
            val md = java.security.MessageDigest.getInstance("SHA-256")
            for (v in pixels(img)) {
                md.update((v ushr 24).toByte()); md.update((v ushr 16).toByte())
                md.update((v ushr 8).toByte()); md.update(v.toByte())
            }
            md.digest().joinToString("") { "%02x".format(it) }.take(16)
        }
        assertEquals(listOf("f161fc20d0acadfc", "00d9ca560ad79da1", "d8df01efa40bfedc", "0f4b86e7c410afaf", "eaa62fd3f6ee8016"), actual) {
            "the composite preview changed; every preview.png built after this differs"
        }
    }

}

/**
 * Generated surfaces cross to the watch as a quantized PNG like every other
 * dial. The task that asked for them warned that noise quantizes badly and said
 * to MEASURE rather than assume, so this measures.
 */
class ProceduralSurfaceTest {

    companion object {
        @JvmStatic @BeforeAll fun headless() { System.setProperty("java.awt.headless", "true") }
    }

    private fun params(e: Engine) = DialParams(
        generatorVersion = 4, engine = e, scale = 18.0, contrast = 40.0, relief = 2.0
    )

    @ParameterizedTest
    @EnumSource(value = Engine::class, names = ["GRAIN", "BRUSHED", "CARBON", "LINEN"])
    fun `a generated surface survives the 64-colour budget`(e: Engine) {
        val q = Quantizer.quantize(DialRenderer.render(params(e)), 64)
        assertTrue(q.colors <= 64) { "$e used ${q.colors} colours" }
        // The warning was reasonable and turned out not to bite: these are
        // low-contrast variations around ONE dial colour, so the palette only
        // has to cover a narrow band rather than a full gamut. Measured well
        // under 1/255 for all four; 2.0 leaves headroom without hiding a
        // regression that would show as visible banding.
        assertTrue(q.meanError < 2.0) { "$e quantized at ${"%.2f".format(q.meanError)}/255" }
    }

    @ParameterizedTest
    @EnumSource(value = Engine::class, names = ["GRAIN", "BRUSHED", "CARBON", "LINEN"])
    fun `rendering a surface is deterministic`(e: Engine) {
        fun px(img: java.awt.image.BufferedImage) =
            img.getRGB(0, 0, img.width, img.height, null, 0, img.width).toList()
        assertEquals(px(DialRenderer.render(params(e))), px(DialRenderer.render(params(e)))) {
            "$e did not reproduce byte for byte"
        }
    }

    @ParameterizedTest
    @EnumSource(value = Engine::class, names = ["GRAIN", "BRUSHED", "CARBON", "LINEN"])
    fun `a surface fills the dial rather than leaving it flat`(e: Engine) {
        // Guards the case where the field is computed but never reaches the
        // pixels: the dial would render as a plain colour and look exactly like
        // the engine silently doing nothing.
        val img = DialRenderer.render(params(e))
        val mid = img.getRGB(DIAL_SIZE / 2, DIAL_SIZE / 2)
        var different = 0
        for (y in 100 until 356 step 7) for (x in 100 until 356 step 7) {
            if (img.getRGB(x, y) != mid) different++
        }
        assertTrue(different > 100) { "$e looks flat: only $different sampled pixels differ from the centre" }
    }

    @ParameterizedTest
    @EnumSource(value = Engine::class, names = ["GRAIN", "BRUSHED", "CARBON", "LINEN"])
    fun `the transfer cost of a surface is pinned`(e: Engine) {
        // Surfaces are heavier than the stroked engines -- more distinct tones
        // per pixel. Worth pinning: this crosses to the watch over Bluetooth.
        val bos = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(Quantizer.quantize(DialRenderer.render(params(e)), 64).image, "png", bos)
        val kb = bos.size() / 1024
        assertTrue(kb < 200) { "$e quantized to ${kb}KB, too heavy for a Bluetooth transfer" }
    }


}
