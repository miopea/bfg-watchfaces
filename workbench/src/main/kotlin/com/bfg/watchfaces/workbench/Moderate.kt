package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.appcore.FaceCodec
import com.bfg.watchfaces.appcore.Json
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.imageio.ImageIO
import java.util.Base64

/**
 * Work the moderation queue.
 *
 * ```bash
 * export BFG_CATALOG_URL=https://bfg-catalog.<subdomain>.workers.dev
 * eval "$(op-login)" && export BFG_MODERATOR_TOKEN="$(op read op://…/moderator-token)"
 *
 * ./gradlew :workbench:moderate                          # review the queue, decide nothing
 * ./gradlew :workbench:moderate --args="--publish=<id>"
 * ./gradlew :workbench:moderate --args="--reject=<id> --reason=..."
 * ./gradlew :workbench:moderate --args="--reports"
 * ```
 *
 * ## Two things this is careful about
 *
 * **It renders every face to a PNG.** `MODERATION.md` promises slurs and
 * harassment are removed on sight, and "on sight" is not a figure of speech —
 * a queue of parameter blobs is an inbox, not something one person can work
 * through. The previews land in `build/moderation/` and are the point of the
 * whole exercise: the automated half cannot tell you a dial is somebody's logo.
 *
 * **It refuses to run without the schema.** Every validation would still return
 * "no problems", which is indistinguishable from success and would publish
 * exactly the faces this pass exists to catch.
 *
 * The token is read from the environment and never printed, logged, or included
 * in an error. It is the only credential in the system.
 */
object Moderate {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val root = RepoRoot.find()

        // Refusing beats passing everything. A missing schema makes every
        // verdict vacuous while looking exactly like a clean queue.
        if (!Moderation.schemaInstalled(root)) {
            System.err.println("The WFF schema is not installed, so nothing can be validated.")
            System.err.println("Every face would come back clean, which is the failure this pass exists to prevent.")
            System.err.println("Run scripts/bootstrap.sh.")
            kotlin.system.exitProcess(1)
        }

        val base = System.getenv("BFG_CATALOG_URL")?.trimEnd('/')
        val token = System.getenv("BFG_MODERATOR_TOKEN")
        if (base.isNullOrBlank() || token.isNullOrBlank()) {
            System.err.println("Set BFG_CATALOG_URL and BFG_MODERATOR_TOKEN.")
            System.err.println("The token is the only credential in the system; read it from 1Password,")
            System.err.println("export it, and do not paste it into a command line that gets logged.")
            kotlin.system.exitProcess(1)
        }

        val flag = { name: String -> args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=') }

        when {
            args.contains("--auto-reject") -> error("Automatic rejection is disabled; failed validation requires manual review.")
            args.contains("--reports") -> reports(base, token)
            flag("publish") != null -> decide(base, token, flag("publish")!!, "publish", null)
            flag("reject") != null -> {
                val reason = flag("reason")
                if (reason.isNullOrBlank()) {
                    // MODERATION.md promises appeals are answered. An appeal
                    // against a decision with no recorded reason cannot be.
                    System.err.println("--reject needs --reason=...")
                    kotlin.system.exitProcess(1)
                }
                decide(base, token, flag("reject")!!, "reject", reason)
            }
            flag("remove") != null -> {
                val reason = flag("reason")
                if (reason.isNullOrBlank()) {
                    System.err.println("--remove needs --reason=...")
                    kotlin.system.exitProcess(1)
                }
                decide(base, token, flag("remove")!!, "remove", reason)
            }
            else -> queue(root, base, token)
        }
    }

    private fun queue(root: File, base: String, token: String) {
        val body = get(base, token, "/admin/queue?state=pending&due=1")
        val rows = Json.arr(Json.obj(Json.parse(body))["faces"]).map { Json.obj(it) }
        println("due for processing: ${rows.size}")
        val previews = File(root, "build/moderation").apply { mkdirs() }
        var failures = 0
        for (row in rows) {
            val id = Json.str(row, "id", "")
            var lease: String? = null
            var stage = "preview"
            try {
                val claim = Json.obj(Json.parse(post(base, token, "/admin/faces/$id/processing/claim", "{}")))
                if (claim["claimed"] != true) continue
                lease = Json.str(claim, "lease", "")
                if (Json.str(row, "validation", "pending") != "passed") {
                    val review = Moderation.review(root, row)
                    if (review.verdict == Moderation.Verdict.REFUSE) {
                        recordReview(base, token, row, review, previewBase64 = null, lease = lease)
                        report(base, token, id, lease, "attention", stage, "Technical validation requires manual review.")
                        continue
                    }
                    val preview = writePreview(previews, row, review)
                        ?: error("trusted preview generation failed")
                    recordReview(base, token, row, review, preview.base64, lease = lease)
                }
                if (claim["aiEnabled"] == true) {
                    stage = "ai"
                    report(base, token, id, lease, "running", stage)
                    val advice = Json.obj(Json.parse(post(base, token, "/admin/faces/$id/ai-review", "{}", lease)))
                    println("$id: AI recommendation ${Json.str(advice, "recommendation", "unavailable")}")
                }
                report(base, token, id, lease, "complete", stage)
            } catch (failure: Exception) {
                failures++
                val detail = "$stage processing failed: ${failure.message?.take(180) ?: "unknown error"}"
                System.err.println("$id: $detail")
                if (lease != null) runCatching {
                    report(base, token, id, lease, "retry", stage, detail)
                }.onFailure { System.err.println("$id: failure could not be recorded; the processing lease will expire.") }
            }
        }
        check(failures == 0) { "$failures review attempts failed; other submissions were still processed." }
    }

    private fun report(base: String, token: String, id: String, lease: String, status: String, stage: String, error: String? = null) {
        val body = "{\"lease\":${Json.quote(lease)},\"status\":${Json.quote(status)},\"stage\":${Json.quote(stage)},\"error\":${error?.let { Json.quote(it) } ?: "null"}}"
        post(base, token, "/admin/faces/$id/processing/report", body)
    }
    /**
     * Rasterize the face so a person can look at it.
     *
     * Failure here is reported and does not stop the queue: a preview that
     * cannot be drawn is a nuisance, and stopping would leave the rest of the
     * queue unworked, which is the outcome the response promises are about.
     */
    private data class GeneratedPreview(val path: String, val base64: String)

    private fun writePreview(
        dir: File,
        row: Map<String, Any?>,
        review: Moderation.Review
    ): GeneratedPreview? =
        runCatching {
            val params = FaceCodec.fromJson(Json.obj(Json.parse(Json.str(row, "params", "{}"))))
            val image = FacePreview.render(params, size = 320)
            val file = File(dir, "${review.slug}.png")
            ImageIO.write(image, "png", file)
            val bytes = ByteArrayOutputStream().use { out ->
                check(ImageIO.write(image, "png", out)) { "no PNG writer installed" }
                out.toByteArray()
            }
            GeneratedPreview(file.path, Base64.getEncoder().encodeToString(bytes))
        }.getOrElse {
            println("        (preview failed: ${it.message})")
            null
        }

    /**
     * Put the machine verdict beside the exact params it reviewed.
     *
     * The service checks both the hash and generator version again. Echoing
     * those values from the queue makes an old render fail closed if the stored
     * face changed between GET and POST.
     */
    private fun recordReview(
        base: String,
        token: String,
        row: Map<String, Any?>,
        review: Moderation.Review,
        previewBase64: String?,
        overrideProblems: List<String>? = null,
        lease: String? = null
    ) {
        val problems = overrideProblems ?: review.problems.map { it.message }
        val passed = review.verdict == Moderation.Verdict.LOOKS_FINE && previewBase64 != null
        val payload = buildString {
            append("{\"paramsHash\":")
            append(Json.quote(Json.str(row, "params_hash", "")))
            append(",\"generatorVersion\":")
            append((row["generator_version"] as? Number)?.toInt() ?: -1)
            append(",\"verdict\":")
            append(Json.quote(if (passed) "passed" else "failed"))
            append(",\"problems\":[")
            append(problems.take(20).joinToString(",") { Json.quote(it) })
            append("]")
            if (previewBase64 != null) {
                append(",\"previewBase64\":")
                append(Json.quote(previewBase64))
            }
            append("}")
        }
        post(base, token, "/admin/faces/${review.id}/review", payload, lease)
        println("        technical review: ${if (passed) "passed" else "failed"} (synced)")
    }

    private fun reports(base: String, token: String) {
        val body = get(base, token, "/admin/reports")
        val rows = Json.arr(Json.obj(Json.parse(body))["reports"]).map { Json.obj(it) }
        println("open reports: ${rows.size}")
        for (row in rows) {
            println()
            println("  ${Json.str(row, "reason")}  on ${Json.str(row, "face_slug")} (${Json.str(row, "face_state", "gone")})")
            println("  ${Json.str(row, "id")}   ${Json.str(row, "created")}")
            Json.str(row, "detail").takeIf { it.isNotBlank() }?.let { println("  \"$it\"") }
        }
        if (rows.isNotEmpty()) {
            println()
            // The promise is 72 hours for IP claims and on sight for slurs.
            // Restating it here is cheaper than remembering it.
            println("MODERATION.md promises: IP claims within 72 hours, slurs on sight,")
            println("impersonation within seven days, and every appeal answered.")
        }
    }

    private fun decide(base: String, token: String, id: String, action: String, reason: String?) {
        val payload = if (reason == null) "{}" else """{"reason": ${Json.quote(reason)}}"""
        val body = post(base, token, "/admin/faces/$id/$action", payload)
        val result = Json.obj(Json.parse(body))
        println("  $action ${Json.str(result, "slug", id)} -> ${Json.str(result, "state", "?")}")
    }

    // ---- HTTP ---------------------------------------------------------------

    private fun get(base: String, token: String, path: String): String =
        send(HttpRequest.newBuilder(URI.create("$base$path"))
            .header("User-Agent", "BFG-Moderation-Runner/1.0")
            .header("authorization", "Bearer $token")
            .timeout(Duration.ofSeconds(30))
            .GET())

    private fun post(base: String, token: String, path: String, body: String, lease: String? = null): String =
        send(HttpRequest.newBuilder(URI.create("$base$path"))
            .header("User-Agent", "BFG-Moderation-Runner/1.0")
            .header("authorization", "Bearer $token")
            .header("content-type", "application/json")
            .apply { if (lease != null) header("X-Moderation-Lease", lease) }
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body)))

    private fun send(builder: HttpRequest.Builder): String {
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            // The request carried the moderator token in a header. Report the
            // status and the SERVICE's message, never the request -- a stack
            // trace with the headers in it is how a token ends up in a log.
            throw IllegalStateException("Moderation service answered HTTP ${response.statusCode()}")
        }
        return response.body()
    }
}
