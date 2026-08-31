package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.DialParams
import java.io.File

/**
 * The app's view of the community catalog.
 *
 * One object, one base URL, one place to point somewhere else. Everything the
 * shipped apps know about the service is here; nothing above this layer builds
 * a URL or parses a response.
 *
 * ## What browsing costs the person, which is nothing
 *
 * Reads carry no identity: no install id, no account, no header that says who
 * is asking. The install id exists and is sent ONLY on submit and report, which
 * is what makes withdrawing your own face possible without making browsing
 * traceable.
 *
 * The one exception is deliberate and stated in About rather than buried: an
 * install of a community face posts a bare increment to that face's counter, so
 * the gallery can order by popularity. Empty body, no id, nothing correlatable
 * — one number per face.
 */
class CatalogService(
    private val transport: CatalogTransport = HttpTransport(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * Where the index is cached so the gallery still opens on a train.
     *
     * Null disables caching, which is what the tests that are about the network
     * use so a previous test's cache cannot answer for the service.
     */
    private val cacheDir: File? = null
) {

    companion object {
        /**
         * THE ONE PLACE THE SERVICE IS NAMED.
         *
         * Deployed 2026-08-30. If the catalog ever has to move, or move back to
         * a git repository behind a CDN, this line and the parsing below are
         * the whole of what changes.
         */
        const val DEFAULT_BASE_URL = "https://bfg-catalog.bfg-solutions.workers.dev"

        /** How long a cached index is served before it is called stale. */
        const val FRESH_FOR_MS = 6 * 60 * 60 * 1000L

        /**
         * What a person is told when the catalog cannot be reached.
         *
         * The exception's own message is DROPPED, not passed through. It says
         * things like `Unable to resolve host "bfg-catalog.bfg-solutions.workers.dev":
         * No address associated with hostname` — which appeared on a real
         * screen during testing, and is developer speech in an app the operator
         * has said more than once should talk to people instead.
         *
         * Nothing a person could act on is lost: there is exactly one thing to
         * do about it, and this says it.
         */
        const val UNREACHABLE = "This phone could not reach the community catalog. " +
            "Check your connection and try again."
    }

    /**
     * A result that can be a success, a success from cache, or a failure.
     *
     * [stale] is not a detail. The interview settled that offline shows the
     * cached index MARKED as possibly out of date, because a face can be
     * removed after it was cached and the app cannot know — `MODERATION.md`
     * already admits it cannot reach a face on a wrist, and this is the same
     * honesty one layer up.
     */
    sealed interface Result<out T> {
        data class Ok<T>(val value: T, val stale: Boolean = false) : Result<T>
        data class Failed(val message: String, val problems: List<String> = emptyList()) : Result<Nothing>
    }

    data class Face(
        val slug: String,
        val name: String,
        val author: String,
        val engine: String,
        val dialColor: String,
        val inkColor: String,
        val generatorVersion: Int,
        val created: String,
        val installs: Int
    )

    data class Index(
        val generated: String,
        val maxGeneratorVersion: Int,
        val count: Int,
        val faces: List<Face>
    )

    /** What the service says about itself: the sign-in client id, and limits. */
    data class Config(
        val googleClientId: String,
        val contractVersion: Int,
        val currentGeneratorVersion: Int,
        val maxFaceBytes: Int
    ) {
        /**
         * Whether the service can accept a submission at all right now.
         *
         * An empty client id means sign-in is not configured, and the write
         * endpoints fail closed. The app should say so up front rather than
         * letting someone design, name and submit a face into a refusal.
         */
        val acceptsSubmissions: Boolean get() = googleClientId.isNotBlank()
    }

    // ---- reads --------------------------------------------------------------

    /**
     * The gallery, popular first.
     *
     * Falls back to the cached copy when the service cannot be reached, marked
     * stale. A gallery that shows nothing because a train went into a tunnel is
     * worse than one that shows yesterday's and says so.
     */
    fun index(): Result<Index> {
        val reply = try {
            transport.get("$baseUrl/index.json")
        } catch (e: CatalogTransport.Unreachable) {
            return cached()?.let { Result.Ok(it, stale = true) } ?: Result.Failed(UNREACHABLE)
        }
        if (!reply.ok) {
            return cached()?.let { Result.Ok(it, stale = true) }
                ?: Result.Failed("the catalog answered ${reply.status}")
        }
        val parsed = runCatching { parseIndex(reply.body) }.getOrElse {
            return cached()?.let { Result.Ok(it, stale = true) }
                ?: Result.Failed("the catalog sent something this app could not read")
        }
        writeCache(reply.body)
        return Result.Ok(parsed, stale = false)
    }

    /** One face's parameters, for previewing or sending to a watch. */
    fun face(slug: String): Result<DialParams> {
        val reply = try {
            transport.get("$baseUrl/faces/$slug")
        } catch (_: CatalogTransport.Unreachable) {
            return Result.Failed(UNREACHABLE)
        }
        if (reply.status == 404) return Result.Failed("that face is no longer in the catalog")
        if (!reply.ok) return Result.Failed("the catalog answered ${reply.status}")
        return runCatching {
            Result.Ok(FaceCodec.fromJson(Json.obj(Json.obj(Json.parse(reply.body))["params"])))
        }.getOrElse { Result.Failed("that face could not be read: ${it.message}") }
    }

    fun config(): Result<Config> {
        val reply = try {
            transport.get("$baseUrl/config")
        } catch (_: CatalogTransport.Unreachable) {
            return Result.Failed(UNREACHABLE)
        }
        if (!reply.ok) return Result.Failed("the catalog answered ${reply.status}")
        val o = Json.obj(Json.parse(reply.body))
        return Result.Ok(
            Config(
                googleClientId = Json.str(o, "googleClientId", ""),
                contractVersion = Json.num(o, "contractVersion", 0.0).toInt(),
                currentGeneratorVersion = Json.num(o, "currentGeneratorVersion", 0.0).toInt(),
                maxFaceBytes = Json.num(o, "maxFaceBytes", 0.0).toInt()
            )
        )
    }

    // ---- the counter --------------------------------------------------------

    /**
     * One install, counted. Empty body, nothing about the person.
     *
     * Returns nothing and reports nothing, because there is nothing a person
     * could do about a failure and nothing they need to know. The count is a
     * hint for ordering a gallery, not a truth — it is inflatable by anyone
     * posting in a loop, which is acceptable for this and would not be for
     * anything else.
     */
    fun reportInstall(slug: String) {
        runCatching { transport.post("$baseUrl/faces/$slug/installed", "") }
    }

    // ---- writes -------------------------------------------------------------

    /**
     * Share a face.
     *
     * [idToken] is a Google ID token, obtained by the UI when somebody signs
     * in. There is no way to submit without one and no way for this layer to
     * get one.
     *
     * Signing in is required to PUBLISH and for nothing else — browsing,
     * fetching a face and reporting all stay anonymous. Publishing is a
     * privilege; complaining is not.
     */
    fun submit(
        name: String,
        author: String,
        params: DialParams,
        idToken: String
    ): Result<Submission> {
        val body = """{
  "name": ${Json.quote(name.trim())},
  "author": ${Json.quote(author.trim())},
  "slug": ${Json.quote(PublishedSlug.stemFor(name))},
  "params": ${FaceCodec.toJson(params)}
}"""
        val reply = try {
            transport.post("$baseUrl/faces", body, bearer = idToken)
        } catch (_: CatalogTransport.Unreachable) {
            return Result.Failed(UNREACHABLE)
        }
        if (!reply.ok) return Result.Failed(errorIn(reply.body, reply.status), problemsIn(reply.body))
        val o = Json.obj(Json.parse(reply.body))
        return Result.Ok(
            Submission(
                id = Json.str(o, "id", ""),
                slug = Json.str(o, "slug", ""),
                state = Json.str(o, "state", "pending")
            )
        )
    }

    data class Submission(val id: String, val slug: String, val state: String)

    /**
     * Every reason a face can be reported.
     *
     * A fixed list rather than free text alone, so the queue can be worked
     * through by category. The free-text detail is optional and is where
     * somebody explains.
     */
    enum class ReportReason(val wire: String, val label: String) {
        INTELLECTUAL_PROPERTY("intellectual_property", "It copies something I own"),
        IMPERSONATION("impersonation", "It pretends to be someone or some brand"),
        HATE_OR_HARASSMENT("hate_or_harassment", "It is hateful or harassing"),
        SEXUAL_CONTENT("sexual_content", "It is sexual content"),
        SPAM("spam", "It is spam"),
        OTHER("other", "Something else")
    }

    /**
     * Report a face. NO SIGN-IN, deliberately.
     *
     * Requiring an account to complain was intolerable the moment submitting
     * did not require one — "anyone could publish and only developers could
     * complain" is what moved this catalog off GitHub. It is safe to leave open
     * because a report is a MESSAGE, not an action: nothing auto-hides.
     */
    fun report(
        slug: String,
        reason: ReportReason,
        detail: String
    ): Result<String> {
        val body = """{
  "slug": ${Json.quote(slug)},
  "reason": ${Json.quote(reason.wire)},
  "detail": ${Json.quote(detail.trim().take(2000))}
}"""
        val reply = try {
            transport.post("$baseUrl/reports", body)
        } catch (_: CatalogTransport.Unreachable) {
            return Result.Failed(UNREACHABLE)
        }
        if (!reply.ok) return Result.Failed(errorIn(reply.body, reply.status))
        return Result.Ok(Json.str(Json.obj(Json.parse(reply.body)), "id", ""))
    }

    /** What happened to something this device submitted. Sends no install id. */
    fun submissionState(id: String): Result<Submission> {
        val reply = try {
            transport.get("$baseUrl/submissions/$id")
        } catch (_: CatalogTransport.Unreachable) {
            return Result.Failed(UNREACHABLE)
        }
        if (!reply.ok) return Result.Failed("the catalog answered ${reply.status}")
        val o = Json.obj(Json.parse(reply.body))
        return Result.Ok(
            Submission(
                id = Json.str(o, "id", ""),
                slug = Json.str(o, "slug", ""),
                state = Json.str(o, "state", "")
            )
        )
    }

    /** Take back one of your own faces. Needs the account that submitted it. */
    fun withdraw(id: String, idToken: String): Result<String> {
        val reply = try {
            transport.post("$baseUrl/submissions/$id/withdraw", "{}", bearer = idToken)
        } catch (_: CatalogTransport.Unreachable) {
            return Result.Failed(UNREACHABLE)
        }
        if (!reply.ok) return Result.Failed(errorIn(reply.body, reply.status))
        return Result.Ok("withdrawn")
    }

    // ---- parsing and cache --------------------------------------------------

    private fun parseIndex(text: String): Index {
        val o = Json.obj(Json.parse(text))
        val faces = Json.arr(o["faces"]).map { Json.obj(it) }.map { f ->
            Face(
                slug = Json.str(f, "slug", ""),
                name = Json.str(f, "name", ""),
                author = Json.str(f, "author", ""),
                engine = Json.str(f, "engine", ""),
                dialColor = Json.str(f, "dialColor", "#000000"),
                inkColor = Json.str(f, "inkColor", "#FFFFFF"),
                generatorVersion = Json.num(f, "generatorVersion", 0.0).toInt(),
                created = Json.str(f, "created", ""),
                installs = Json.num(f, "installs", 0.0).toInt()
            )
        }
        // A face with no slug cannot be fetched or reported, so it is dropped
        // rather than shown as something that does nothing when tapped.
        return Index(
            generated = Json.str(o, "generated", ""),
            maxGeneratorVersion = Json.num(o, "maxGeneratorVersion", 0.0).toInt(),
            count = Json.num(o, "count", 0.0).toInt(),
            faces = faces.filter { it.slug.isNotBlank() }
        )
    }

    private fun cacheFile(): File? = cacheDir?.let { File(it, "catalog-index.json") }

    private fun cached(): Index? {
        val f = cacheFile()?.takeIf { it.isFile } ?: return null
        return runCatching { parseIndex(f.readText()) }.getOrNull()
    }

    private fun writeCache(text: String) {
        val f = cacheFile() ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(text)
        }
    }

    /**
     * Whether the cached index is old enough to be worth saying so about, even
     * when the service IS reachable. Used by a caller that wants to show a
     * "last updated" line rather than only a failure state.
     */
    fun cacheAgeMs(now: Long = System.currentTimeMillis()): Long? =
        cacheFile()?.takeIf { it.isFile }?.let { now - it.lastModified() }

    private fun errorIn(body: String, status: Int): String =
        runCatching { Json.str(Json.obj(Json.parse(body)), "error", "") }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: "the catalog answered $status"

    private fun problemsIn(body: String): List<String> =
        runCatching {
            Json.arr(Json.obj(Json.parse(body))["problems"]).map { Json.obj(it) }
                .map { "${Json.str(it, "field", "")}: ${Json.str(it, "message", "")}".trim(':', ' ') }
        }.getOrElse { emptyList() }
}
