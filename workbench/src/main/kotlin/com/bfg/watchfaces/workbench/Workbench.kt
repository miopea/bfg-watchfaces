package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.appcore.ActivationConsent
import com.bfg.watchfaces.generator.DIAL_SIZE
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.PatternEngines
import com.bfg.watchfaces.generator.WffEmitter
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import com.bfg.watchfaces.appcore.FaceCodec
import com.bfg.watchfaces.appcore.FaceLibrary
import com.bfg.watchfaces.appcore.Presets
import com.bfg.watchfaces.appcore.Complications

/**
 * The workbench: a localhost design loop for watch faces.
 *
 * The problem it solves is the round trip. Judging a dial used to mean bake a
 * PNG by hand, run aapt2, sign, sideload, long-press the watch, squint. That is
 * minutes per iteration, and it needs a watch. This is milliseconds, and it
 * needs a browser.
 *
 * Design rule that matters: the BROWSER NEVER DRAWS THE PATTERN. It asks this
 * server for a PNG produced by [DialRenderer] -- the same code, the same call,
 * that bakes the shipped dial_bg.png. A JS canvas reimplementation would have
 * been faster to write and would have quietly become a second renderer that
 * drifts from the shipped one. docs/SPEC.md is explicit that there is one
 * geometry implementation and what you see is what ships; this preserves that.
 *
 * Binds to loopback only. It writes into your working tree and shells out to
 * build.sh -- it is a dev tool, not a service, and must not be reachable off
 * the machine.
 */
object Workbench {

    private val root: File by lazy { findRoot() }

    private fun findRoot(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            if (File(d, "settings.gradle.kts").isFile) return d
            d = d.parentFile
        }
        return File(System.getProperty("user.dir")).absoluteFile
    }

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val port = args.firstOrNull { it.startsWith("--port=") }?.removePrefix("--port=")?.toIntOrNull() ?: 7777

        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        server.executor = Executors.newFixedThreadPool(4)

        server.createContext("/") { ex -> safe(ex) { serveIndex(ex) } }
        server.createContext("/api/dial.png") { ex -> safe(ex) { serveDial(ex) } }
        server.createContext("/api/face.png") { ex -> safe(ex) { serveFace(ex) } }
        server.createContext("/api/wff.xml") { ex -> safe(ex) { serveWff(ex) } }
        server.createContext("/api/validate") { ex -> safe(ex) { serveValidate(ex) } }
        server.createContext("/api/stats") { ex -> safe(ex) { serveStats(ex) } }
        server.createContext("/api/presets") { ex -> safe(ex) { servePresets(ex) } }
        server.createContext("/api/export") { ex -> safe(ex) { serveExport(ex) } }
        server.createContext("/api/build") { ex -> safe(ex) { serveBuild(ex) } }
        server.createContext("/api/faces") { ex -> safe(ex) { serveFaces(ex) } }
        server.createContext("/api/textures") { ex -> safe(ex) { serveTextures(ex) } }
        server.createContext("/api/layout") { ex -> safe(ex) { serveLayout(ex) } }
        server.createContext("/api/catalog") { ex -> safe(ex) { serveCatalog(ex) } }
        server.createContext("/logos/") { ex -> safe(ex) { serveLogo(ex) } }
        server.createContext("/api/devices") { ex -> safe(ex) { serveDevices(ex) } }
        server.createContext("/api/activation") { ex -> safe(ex) { serveActivation(ex) } }
        server.createContext("/api/controls") { ex -> safe(ex) { serveControls(ex) } }
        server.start()

        println()
        println("  BFG Watch Faces -- workbench")
        println("  http://localhost:$port")
        println()
        println("  repo root : ${root.absolutePath}")
        println("  schema    : " + if (WffValidator.validate(root, "<x/>") != null) "loaded (live validation on)"
                                    else "MISSING -- run scripts/bootstrap.sh")
        println("  ANDROID_HOME : " + (System.getenv("ANDROID_HOME") ?: "unset (export/build APK disabled)"))
        println()
        println("  Ctrl-C to stop.")
    }

    // ---- plumbing -----------------------------------------------------------

    private fun safe(ex: HttpExchange, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            val msg = (t.message ?: t.toString())
            send(ex, 500, "application/json", """{"error":${jsonStr(msg)}}""".toByteArray())
        } finally {
            ex.close()
        }
    }

    private fun query(ex: HttpExchange): Map<String, String> {
        val q = ex.requestURI.rawQuery ?: return emptyMap()
        return q.split("&").mapNotNull {
            val i = it.indexOf('='); if (i <= 0) return@mapNotNull null
            URLDecoder.decode(it.substring(0, i), StandardCharsets.UTF_8) to
                URLDecoder.decode(it.substring(i + 1), StandardCharsets.UTF_8)
        }.toMap()
    }

    private fun params(ex: HttpExchange): DialParams = FaceCodec.fromQuery(query(ex))

    /**
     * The catalog checkout, which is a DIFFERENT repository from this one.
     *
     * Resolving this correctly matters more than it looks: CatalogStore.dir()
     * appends "faces", so passing the app's repo root here reads
     * <repo>/faces -- the user's OWN saved designs. That is exactly what
     * happened when the catalog moved out and the endpoints kept passing
     * `root`: private faces were listed as community content, and a submission
     * would have been written into the personal directory.
     */
    private fun catalogRoot(): File? = CatalogStore.resolveRoot(root)

    /** The imported image a TEXTURE face refers to, or null for every other engine. */
    private fun textureFor(p: DialParams): java.awt.image.BufferedImage? =
        if (p.engine == com.bfg.watchfaces.generator.Engine.TEXTURE && p.texture.isNotBlank())
            TextureStore.load(root, p.texture) else null

    private fun send(ex: HttpExchange, code: Int, type: String, body: ByteArray) {
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(code, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun json(ex: HttpExchange, body: String) =
        send(ex, 200, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    private fun png(img: java.awt.image.BufferedImage): ByteArray {
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "png", bos)
        return bos.toByteArray()
    }

    // ---- endpoints ----------------------------------------------------------

    private fun serveIndex(ex: HttpExchange) {
        val path = ex.requestURI.path
        if (path != "/" && path != "/index.html") { send(ex, 404, "text/plain", "not found".toByteArray()); return }
        val html = javaClass.getResourceAsStream("/workbench/index.html")!!.readBytes()
        send(ex, 200, "text/html; charset=utf-8", html)
    }

    /**
     * Product logos for the About screen, served from the jar.
     *
     * Bundled rather than fetched from bfgsolutions.net: the app works with no
     * network except the community catalog, and an About screen with broken
     * images on a train is worse than no logos at all.
     */
    private fun serveLogo(ex: HttpExchange) {
        // Only a bare filename from the bundled set -- the name arrives in a
        // URL, so anything path-like is refused rather than resolved.
        val name = ex.requestURI.path.removePrefix("/logos/")
        if (!name.matches(Regex("^[a-z0-9_-]+\\.(svg|png)$"))) {
            send(ex, 404, "text/plain", "not found".toByteArray()); return
        }
        val bytes = javaClass.getResourceAsStream("/workbench/logos/$name")?.readBytes()
        if (bytes == null) { send(ex, 404, "text/plain", "not found".toByteArray()); return }
        val type = if (name.endsWith(".svg")) "image/svg+xml" else "image/png"
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Cache-Control", "public, max-age=3600")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** The dial texture alone -- literally the bytes that become dial_bg.png. */
    private fun serveDial(ex: HttpExchange) {
        val q = query(ex)
        val p = params(ex)
        val size = q["size"]?.toIntOrNull() ?: DIAL_SIZE
        var img = DialRenderer.render(p, size, textureFor(p))
        if (q["quantize"] == "true") img = Quantizer.quantize(img, q["colors"]?.toIntOrNull() ?: 64).image
        send(ex, 200, "image/png", png(img))
    }

    /** The whole face, dial plus text layers, interactive or ambient. */
    private fun serveFace(ex: HttpExchange) {
        val q = query(ex)
        val p = params(ex)
        val size = q["size"]?.toIntOrNull() ?: DIAL_SIZE
        send(ex, 200, "image/png",
            png(FacePreview.render(p, ambient = q["ambient"] == "true", size = size, texture = textureFor(p))))
    }

    private fun serveWff(ex: HttpExchange) =
        send(ex, 200, "text/plain; charset=utf-8", WffEmitter.emit(params(ex)).toByteArray(Charsets.UTF_8))

    /**
     * Live XSD 1.1 validation. The single highest-value endpoint here: this is
     * the failure that is otherwise completely silent all the way to the wrist.
     */
    private fun serveValidate(ex: HttpExchange) {
        val xml = WffEmitter.emit(params(ex))
        val issues = WffValidator.validate(root, xml)
        if (issues == null) {
            json(ex, """{"available":false,"reason":"WFF schema not installed -- run scripts/bootstrap.sh"}""")
            return
        }
        val arr = issues.joinToString(",") {
            """{"line":${it.line},"fatal":${it.fatal},"message":${jsonStr(it.message)}}"""
        }
        json(ex, """{"available":true,"valid":${issues.isEmpty()},"issues":[$arr]}""")
    }

    /** Numbers that predict behaviour on the watch, shown live while you drag. */
    private fun serveStats(ex: HttpExchange) {
        val p = params(ex)
        val paths = PatternEngines.paths(p)
        val points = paths.sumOf { it.size }
        val coverage = PatternEngines.coverage(paths)

        val full = DialRenderer.render(p, DIAL_SIZE, textureFor(p))
        val rawBytes = png(full).size
        val q = Quantizer.quantize(full, 64)
        val quantBytes = png(q.image).size

        // 4 bytes/pixel/frame on the watch, independent of PNG size. Quantizing
        // buys transfer time over Bluetooth, never memory budget.
        val framebuffer = DIAL_SIZE * DIAL_SIZE * 4
        json(ex, """{
  "points": $points, "polylines": ${paths.size}, "coverage": ${"%.4f".format(coverage)},
  "pngBytes": $rawBytes, "quantizedBytes": $quantBytes,
  "quantizedColors": ${q.colors}, "meanError": ${"%.3f".format(q.meanError)},
  "framebufferBytes": $framebuffer
}""")
    }

    private fun servePresets(ex: HttpExchange) {
        val arr = Presets.ALL.entries.joinToString(",") { (name, p) ->
            """{"name":${jsonStr(name)},"query":${jsonStr(FaceCodec.toQuery(p))}}"""
        }
        json(ex, """{"presets":[$arr]}""")
    }

    /**
     * Write the generated artefacts into watchface-template so build.sh can run.
     *
     * This is the seam the repo was missing: dial_bg.png and preview.png are
     * generated output, correctly gitignored, and until now nothing in the repo
     * could produce them -- build.sh referred to a "workbench" that did not exist.
     */
    private fun serveExport(ex: HttpExchange) {
        val p = params(ex)
        val q = query(ex)
        val colors = q["colors"]?.toIntOrNull() ?: 64
        val name = q["name"]?.takeIf { it.isNotBlank() } ?: "Untitled"
        val written = exportTo(root, p, colors, name)
        json(ex, """{"ok":true,"written":[${written.joinToString(",") { jsonStr(it) }}]}""")
    }

    /**
     * Saved faces. GET lists, POST saves, DELETE removes.
     *
     * Storage is `faces/<slug>.json` -- the catalog format from docs/SPEC.md, so
     * saving in the app and submitting to the catalog are the same artefact.
     */
    private fun serveFaces(ex: HttpExchange) {
        val q = query(ex)
        when (ex.requestMethod.uppercase()) {
            "POST" -> {
                val name = q["name"]?.trim().orEmpty()
                if (name.isBlank()) { json(ex, """{"ok":false,"error":"a face needs a name"}"""); return }
                val f = FaceLibrary.save(root, name, params(ex))
                json(ex, """{"ok":true,"face":${faceJson(f)}}""")
            }
            "DELETE" -> {
                val slug = q["slug"].orEmpty()
                json(ex, """{"ok":${FaceLibrary.delete(root, slug)}}""")
            }
            else -> {
                val all = FaceLibrary.list(root).joinToString(",") { faceJson(it) }
                json(ex, """{"faces":[$all]}""")
            }
        }
    }

    /**
     * Imported images for TEXTURE faces. POST the raw file bytes as the body.
     *
     * These never leave the machine and never enter a stored face -- the face
     * keeps only the content hash. A TEXTURE face is local-only by construction.
     */
    /**
     * The community catalog.
     *
     * GET lists it. POST stages a saved face as a submission -- it writes the
     * file and validates it, and stops there. Opening the pull request is the
     * author's action: a design tool that pushes to a public repo on a button
     * press is a mistake waiting to happen.
     */
    private fun serveCatalog(ex: HttpExchange) {
        val q = query(ex)
        when (ex.requestMethod.uppercase()) {
            "POST" -> {
                val catalog = catalogRoot()
                if (catalog == null) { json(ex, noCatalogJson()); return }
                val slug = q["slug"].orEmpty()
                val face = FaceLibrary.load(root, slug)
                if (face == null) {
                    json(ex, """{"ok":false,"error":${jsonStr(
                        "That face is no longer saved on this device."
                    )}}"""); return
                }
                if (face.params.isLocalOnly) {
                    json(ex, """{"ok":false,"error":${jsonStr(
                        "This face uses a picture you added. Community faces are built from " +
                        "patterns rather than photos, so this one stays on your device."
                    )}}"""); return
                }
                val (file, problems) = CatalogStore.submit(root, catalog, face, q["author"].orEmpty())
                if (problems.isNotEmpty()) {
                    json(ex, """{"ok":false,"error":${jsonStr(problems.joinToString("; ") { it.message })}}""")
                    return
                }
                CatalogStore.writeIndex(catalog)
                json(ex, """{"ok":true,"path":${jsonStr(file.absolutePath)},""" +
                         """"slug":${jsonStr(face.slug)},"name":${jsonStr(face.name)}}""")
            }
            "DELETE" -> {
                val catalog = catalogRoot()
                if (catalog == null) { json(ex, noCatalogJson()); return }
                val slug = q["slug"].orEmpty()
                val f = File(CatalogStore.dir(catalog), "${FaceLibrary.slugify(slug)}.json")
                val gone = f.isFile && f.delete()
                if (gone) CatalogStore.writeIndex(catalog)
                json(ex, """{"ok":$gone}""")
            }
            else -> {
                val catalog = catalogRoot()
                if (catalog == null) { json(ex, noCatalogJson()); return }
                val entries = CatalogStore.list(catalog).joinToString(",") { e ->
                    """{"slug":${jsonStr(e.slug)},"name":${jsonStr(e.name)},"author":${jsonStr(e.author)},""" +
                    """"engine":${jsonStr(e.params.engine.name)},"created":${jsonStr(e.created)},""" +
                    """"query":${jsonStr(FaceCodec.toQuery(e.params))},""" +
                    """"reportUrl":${jsonStr(CatalogStore.reportUrl(e.slug, e.name))}}"""
                }
                json(ex, """{"cdn":${jsonStr(CatalogStore.CDN_URL)},"faces":[$entries]}""")
            }
        }
    }

    /**
     * Watches this machine can see.
     *
     * Exposed as its own endpoint so the app can show the connection state
     * BEFORE someone commits to a build -- previously "Save and Update Watch"
     * was the first place anyone learned nothing was attached, which is a slow
     * way to find out.
     */
    private fun serveDevices(ex: HttpExchange) {
        val devices = WatchDevices.list()
        if (devices == null) {
            json(ex, """{"available":false,"reason":${jsonStr(
                "This computer cannot see any watches. Set ANDROID_HOME so the app can talk to a connected watch."
            )},"devices":[]}""")
            return
        }
        val rows = devices.joinToString(",") { d ->
            """{"serial":${jsonStr(d.serial)},"label":${jsonStr(d.label)},""" +
            """"state":${jsonStr(d.state)},"wearOs":${jsonStr(if (d.isWatch) WatchDevices.wearOsName(d.sdk) else "")},""" +
            """"isWatch":${d.isWatch},"ready":${d.supportsPush},""" +
            """"blocked":${d.blockedReason?.let { jsonStr(it) } ?: "null"}}"""
        }
        json(ex, """{"available":true,"devices":[$rows]}""")
    }

    /**
     * Everything a UI needs to build its own controls.
     *
     * The app used to hardcode these lists and a test checked they matched
     * :generator. Now it builds itself from this, so the two cannot disagree
     * rather than being checked for disagreement — see ControlInventory.
     *
     * Labels are NOT here, deliberately. They are presentation and belong to
     * whichever front end is drawing.
     */
    private fun serveControls(ex: HttpExchange) {
        val controls = com.bfg.watchfaces.generator.ControlInventory.CONTROLS.joinToString(",") { c ->
            """{"id":${jsonStr(c.id)},"min":${c.min},"max":${c.max},"step":${c.step},""" +
            """"target":${jsonStr(c.target.name)},"integral":${c.integral}}"""
        }
        val engines = com.bfg.watchfaces.generator.Engine.entries.joinToString(",") { jsonStr(it.name) }
        val sources = com.bfg.watchfaces.generator.ComplicationSource.entries.joinToString(",") { jsonStr(it.name) }
        val slots = com.bfg.watchfaces.generator.SlotPosition.entries.joinToString(",") { jsonStr(it.name) }
        json(ex, """{"controls":[$controls],"engines":[$engines],""" +
                 """"complicationSources":[$sources],"slots":[$slots]}""")
    }

    /**
     * The device's half of the activation flow.
     *
     * Operator decision 01a049a1-390b-7b50-a5d3-cc082037bb55 splits it: the
     * device explains what is coming, the WATCH puts the actual dialog up the
     * first time a face lands. So this serves the explanation and the remembered
     * state, and never pretends to request anything -- the permission does not
     * exist on this side and a demo that mimed it would be teaching the wrong
     * thing about the app.
     *
     * POST records what the watch came back with, which is what the Data Layer
     * will report once :wear exists.
     */
    private fun serveActivation(ex: HttpExchange) {
        if (ex.requestMethod.equals("POST", ignoreCase = true)) {
            val answer = query(ex)["granted"]
            val state = ActivationConsent.load(root)
            if (!ActivationConsent.canAsk(state)) {
                // Not an error to report loudly -- it is the rule working.
                json(ex, """{"ok":false,"state":"$state","error":${jsonStr(
                    "This was already answered. It can only be asked once."
                )}}"""); return
            }
            val next = ActivationConsent.record(state, granted = answer == "true")
            ActivationConsent.save(root, next)
            json(ex, """{"ok":true,"state":"$next"}"""); return
        }

        val state = ActivationConsent.load(root)
        val steps = ActivationConsent.HANDOFF.joinToString(",") { st ->
            """{"title":${jsonStr(st.title)},"detail":${jsonStr(st.detail)}}"""
        }
        json(ex, """{"state":"$state","needsHandoff":${ActivationConsent.needsHandoff(state)},""" +
                 """"steps":[$steps],"note":${ActivationConsent.persistentNote(state)?.let { jsonStr(it) } ?: "null"}}""")
    }

    /** What SlotGeometry actually used, so a clamped control can say so. */
    private fun serveLayout(ex: HttpExchange) {
        val e = com.bfg.watchfaces.generator.SlotGeometry.effective(params(ex))
        json(ex, """{"size":${e.size},"spread":${e.spread},"verticalAir":${e.verticalAir},""" +
                 """"sizeClamped":${e.sizeClamped},"spreadClamped":${e.spreadClamped},""" +
                 """"verticalClamped":${e.verticalClamped}}""")
    }

    private fun serveTextures(ex: HttpExchange) {
        when (ex.requestMethod.uppercase()) {
            "POST" -> {
                val bytes = ex.requestBody.readBytes()
                val t = try {
                    TextureStore.save(root, bytes)
                } catch (e: IllegalArgumentException) {
                    json(ex, """{"ok":false,"error":${jsonStr(e.message ?: "could not read that image")}}"""); return
                }
                json(ex, """{"ok":true,"texture":${textureJson(t)}}""")
            }
            "DELETE" -> json(ex, """{"ok":${TextureStore.delete(root, query(ex)["id"].orEmpty())}}""")
            else -> json(ex, """{"textures":[${TextureStore.list(root).joinToString(",") { textureJson(it) }}]}""")
        }
    }

    /**
     * No catalog checkout. Returns an EMPTY catalog rather than falling back to
     * any local directory -- showing the user's private designs under
     * "Community" would be a privacy bug, not a graceful degradation.
     */
    private fun noCatalogJson(): String =
        """{"cdn":${jsonStr(CatalogStore.CDN_URL)},"repo":${jsonStr(CatalogStore.REPO_URL)},""" +
        """"available":false,"faces":[]}"""

    private fun textureJson(t: TextureStore.Texture): String =
        """{"id":${jsonStr(t.id)},"width":${t.width},"height":${t.height},""" +
        """"bytes":${t.bytes},"note":${jsonStr(TextureStore.qualityNote(t))}}"""

    private fun faceJson(f: FaceLibrary.StoredFace): String =
        """{"slug":${jsonStr(f.slug)},"name":${jsonStr(f.name)},"created":${jsonStr(f.created)},""" +
        """"query":${jsonStr(FaceCodec.toQuery(f.params))}}"""

    /**
     * Writes everything the APK build needs for ONE named design.
     *
     * The face name is not decoration: it becomes the carousel label, the
     * Watch Face Push package suffix, and the APK filename. Nothing here is
     * hardcoded to a particular face any more.
     */
    fun exportTo(root: File, p: DialParams, colors: Int = 64, faceName: String = "Untitled"): List<String> {
        val tpl = File(root, "watchface-template")
        val drawable = File(tpl, "res/drawable-nodpi").apply { mkdirs() }
        val slug = FaceLibrary.slugify(faceName)

        // Dial: quantized, because it crosses to the watch over Bluetooth.
        val tex = if (p.engine == com.bfg.watchfaces.generator.Engine.TEXTURE && p.texture.isNotBlank())
            TextureStore.load(root, p.texture) else null
        val dial = Quantizer.quantize(DialRenderer.render(p, DIAL_SIZE, tex), colors)
        val dialFile = File(drawable, "dial_bg.png")
        ImageIO.write(dial.image, "png", dialFile)

        // Preview: what the carousel shows. res/xml/watch_face_info.xml requires
        // it, and without it aapt2 link fails on an unresolved @drawable/preview.
        val preview = Quantizer.quantize(FacePreview.render(p, ambient = false, size = DIAL_SIZE, texture = tex), colors)
        val previewFile = File(drawable, "preview.png")
        ImageIO.write(preview.image, "png", previewFile)

        // The WFF definition itself, so the template tracks the params exactly.
        val xmlFile = File(tpl, "res/raw/watchface.xml")
        xmlFile.writeText(WffEmitter.emit(p, faceName))

        // Carousel label.
        val strings = File(tpl, "res/values/strings.xml").apply { parentFile.mkdirs() }
        // A slot_<source> string for every ACTIVE complication. The emitter
        // references these by name, so a missing one is not a cosmetic gap --
        // aapt2 link fails outright on the unresolved @string.
        val slotStrings = p.complications.filter { it.enabled }.distinct().joinToString("\n") {
            """  <string name="slot_${it.name.lowercase()}">${Complications.label(it)}</string>"""
        }
        strings.writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by the workbench. The face name is chosen by whoever designs it. -->
<resources>
  <string name="watch_face_name">${faceName.trim().replace("&", "&amp;").replace("<", "&lt;")}</string>
$slotStrings
</resources>
"""
        )

        // Package name. Watch Face Push requires <app>.watchfacepush.<slug> and
        // it lives in the binary manifest, so it has to be set before aapt2 runs
        // -- this is the one thing reskin.sh cannot swap after the fact.
        val manifest = File(tpl, "AndroidManifest.xml")
        if (manifest.isFile) {
            val pkg = WffEmitter.pushPackageName("com.bfg.watchfaces", slug)
            manifest.writeText(
                manifest.readText().replace(
                    Regex("""package="com\.bfg\.watchfaces\.watchfacepush\.[a-z0-9_]+""""),
                    """package="$pkg""""
                )
            )
        }

        return listOf(
            "${dialFile.relativeTo(root)} (${dialFile.length()} bytes, ${dial.colors} colours, mean error ${"%.2f".format(dial.meanError)}/255)",
            "${previewFile.relativeTo(root)} (${previewFile.length()} bytes)",
            "${xmlFile.relativeTo(root)}",
            "${strings.relativeTo(root)} (watch_face_name = \"${faceName.trim()}\")",
            "package com.bfg.watchfaces.watchfacepush.$slug"
        )
    }

    /** Runs the real build.sh. Same script CI and a human would run -- no shortcut path. */
    private fun serveBuild(ex: HttpExchange) {
        if (System.getenv("ANDROID_HOME") == null) {
            json(ex, """{"ok":false,"output":"ANDROID_HOME is not set, so aapt2/apksigner are unavailable."}""")
            return
        }
        val p = params(ex)
        val q = query(ex)
        val name = q["name"]?.takeIf { it.isNotBlank() } ?: "Untitled"
        val slug = FaceLibrary.slugify(name)
        val written = exportTo(root, p, q["colors"]?.toIntOrNull() ?: 64, name)
        val pb = ProcessBuilder("./build.sh")
            .directory(File(root, "watchface-template"))
            .redirectErrorStream(true)
        pb.environment()["FACE_SLUG"] = slug
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        val apk = File(root, "watchface-template/build/$slug.apk")

        // "Save and Update Watch" also installs. Reported separately from the
        // build so a missing watch never reads as a broken face -- and an
        // `adb install` Success is NOT reported as "it's on your watch",
        // because a schema-invalid face installs cleanly and never appears.
        // Sending to a CHOSEN watch, rather than to whatever adb happened to
        // see first. Reported separately from the build so a missing watch
        // never reads as a broken face.
        var installed = "not attempted"
        if (code == 0 && q["install"] == "true" && apk.isFile) {
            installed = runCatching {
                val devices = WatchDevices.list()
                val eligible = devices?.filter { it.supportsPush }.orEmpty()
                val chosen = q["serial"]?.let { s -> eligible.firstOrNull { it.serial == s } }
                    ?: eligible.singleOrNull()
                when {
                    devices == null -> "adb is not available, so nothing could be sent"
                    eligible.isEmpty() -> "no watch connected"
                    chosen == null -> "more than one watch is connected -- choose which one"
                    else -> {
                        val (ok, out2) = WatchDevices.install(chosen.serial, apk)
                        if (ok) "sent to ${chosen.label}" else "could not send to ${chosen.label}: $out2"
                    }
                }
            }.getOrElse { "could not send it: ${it.message}" }
        }

        json(ex, """{"ok":${code == 0},"exit":$code,"apkBytes":${if (apk.isFile) apk.length() else 0},
"installed":${jsonStr(installed)},
"written":[${written.joinToString(",") { jsonStr(it) }}],"apk":${jsonStr("watchface-template/build/$slug.apk")},"output":${jsonStr(out)}}""")
    }
}
