package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.appcore.FaceCodec
import com.bfg.watchfaces.appcore.Json
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.imageio.ImageIO

/**
 * Work the moderation queue.
 *
 * ```bash
 * export BFG_CATALOG_URL=https://bfg-catalog.<subdomain>.workers.dev
 * eval "$(op-login)" && export BFG_MODERATOR_TOKEN="$(op read op://…/moderator-token)"
 *
 * ./gradlew :workbench:moderate                          # review the queue, decide nothing
 * ./gradlew :workbench:moderate --args="--auto-reject"    # reject what provably fails
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
            else -> queue(root, base, token, autoReject = args.contains("--auto-reject"))
        }
    }

    private fun queue(root: File, base: String, token: String, autoReject: Boolean) {
        val body = get(base, token, "/admin/queue?state=pending")
        val rows = Json.arr(Json.obj(Json.parse(body))["faces"]).map { Json.obj(it) }

        println("pending: ${rows.size}")
        if (rows.isEmpty()) return

        val previews = File(root, "build/moderation").apply { mkdirs() }
        var refused = 0

        // Oldest first, which is how the queue is served. Submissions are
        // always accepted and worked through in order -- nothing is lost and
        // nobody is turned away because strangers arrived first.
        for (row in rows) {
            val review = Moderation.review(root, row)
            val mark = if (review.verdict == Moderation.Verdict.REFUSE) "REFUSE " else "look at"
            println()
            println("$mark ${review.slug}  \"${review.name}\"${if (review.author.isBlank()) "" else " by ${review.author}"}")
            println("        ${review.id}")
            review.problems.forEach { println("        - ${it.message}") }

            if (review.verdict == Moderation.Verdict.REFUSE) {
                refused++
                if (autoReject) {
                    decide(base, token, review.id, "reject", review.reason())
                }
                // No preview for a face that cannot render. Trying would fail
                // in a second place for the same reason.
                continue
            }

            writePreview(previews, row, review)?.let { println("        preview: $it") }
        }

        println()
        println("$refused of ${rows.size} fail automated validation${if (autoReject) " and were rejected" else ""}.")
        if (refused > 0 && !autoReject) println("Re-run with --auto-reject to record those rejections.")
        println()
        // Said every time, because it is the thing that gets forgotten: the
        // automated half says nothing about whether a face is somebody's logo,
        // somebody's name, or a slur rendered in knotwork.
        println("The rest need a PERSON. Nothing above says a face is not somebody's")
        println("trademark, impersonation, or harassment -- look at ${previews.path}/ and decide.")
    }

    /**
     * Rasterize the face so a person can look at it.
     *
     * Failure here is reported and does not stop the queue: a preview that
     * cannot be drawn is a nuisance, and stopping would leave the rest of the
     * queue unworked, which is the outcome the response promises are about.
     */
    private fun writePreview(dir: File, row: Map<String, Any?>, review: Moderation.Review): String? =
        runCatching {
            val params = FaceCodec.fromJson(Json.obj(Json.parse(Json.str(row, "params", "{}"))))
            val image = FacePreview.render(params)
            val file = File(dir, "${review.slug}.png")
            ImageIO.write(image, "png", file)
            file.path
        }.getOrElse {
            println("        (preview failed: ${it.message})")
            null
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
            .header("authorization", "Bearer $token")
            .timeout(Duration.ofSeconds(30))
            .GET())

    private fun post(base: String, token: String, path: String, body: String): String =
        send(HttpRequest.newBuilder(URI.create("$base$path"))
            .header("authorization", "Bearer $token")
            .header("content-type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body)))

    private fun send(builder: HttpRequest.Builder): String {
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            // The request carried the moderator token in a header. Report the
            // status and the SERVICE's message, never the request -- a stack
            // trace with the headers in it is how a token ends up in a log.
            System.err.println("the service answered ${response.statusCode()}: ${response.body().take(300)}")
            kotlin.system.exitProcess(1)
        }
        return response.body()
    }
}
