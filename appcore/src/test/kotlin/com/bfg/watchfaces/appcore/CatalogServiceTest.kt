package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.CURRENT_GENERATOR_VERSION
import com.bfg.watchfaces.generator.DialParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The seam, against a transport that answers without a network.
 *
 * What these are FOR: the parsing, the offline behaviour, and the shape of what
 * gets sent. What they are NOT: evidence that the live service accepts it —
 * that is [CatalogLiveTest], which only runs when pointed at a real one.
 */
class CatalogServiceTest {

    /** Answers from a script. Records what it was asked, so sends can be checked. */
    private class Fake(
        private val replies: Map<String, CatalogTransport.Reply> = emptyMap(),
        private val unreachable: Boolean = false
    ) : CatalogTransport {
        val gets = mutableListOf<String>()
        val posts = mutableListOf<Pair<String, String>>()
        val bearers = mutableListOf<String?>()

        override fun get(url: String): CatalogTransport.Reply {
            gets += url
            if (unreachable) throw CatalogTransport.Unreachable("no network")
            return replies[url] ?: CatalogTransport.Reply(404, """{"error":"no such thing"}""")
        }

        override fun post(url: String, body: String, bearer: String?): CatalogTransport.Reply {
            posts += url to body
            bearers += bearer
            if (unreachable) throw CatalogTransport.Unreachable("no network")
            return replies[url] ?: CatalogTransport.Reply(201, """{"id":"an-id","slug":"a_slug","state":"pending"}""")
        }
    }

    private val base = CatalogService.DEFAULT_BASE_URL

    private val oneFace = """{
      "generated":"2026-08-30T00:00:00Z","maxGeneratorVersion":8,"count":1,
      "faces":[{"slug":"midnight_7f3a","name":"Midnight","author":"Ann","engine":"KNOTWORK",
                "dialColor":"#7D7369","inkColor":"#FCF9F1","generatorVersion":8,
                "created":"2026-08-30T00:00:00Z","installs":12}]
    }"""

    // ---- reads --------------------------------------------------------------

    @Test
    fun `the gallery parses, and carries the count that orders it`() {
        val service = CatalogService(
            Fake(mapOf("$base/index.json" to CatalogTransport.Reply(200, oneFace))), base
        )
        val result = service.index()
        assertTrue(result is CatalogService.Result.Ok)
        val ok = result as CatalogService.Result.Ok
        assertFalse(ok.stale)
        assertEquals(1, ok.value.faces.size)
        assertEquals("midnight_7f3a", ok.value.faces[0].slug)
        assertEquals(12, ok.value.faces[0].installs)
    }

    @Test
    fun `a face with no slug is dropped rather than shown as something that does nothing`() {
        val broken = """{"count":2,"faces":[{"slug":"","name":"Ghost"},{"slug":"real_7f3a","name":"Real"}]}"""
        val service = CatalogService(
            Fake(mapOf("$base/index.json" to CatalogTransport.Reply(200, broken))), base
        )
        val ok = service.index() as CatalogService.Result.Ok
        assertEquals(listOf("real_7f3a"), ok.value.faces.map { it.slug })
    }

    /**
     * The interview settled this: offline shows the cached index MARKED as
     * possibly out of date. A gallery that shows nothing because a train went
     * into a tunnel is worse than one showing yesterday's and saying so.
     */
    @Test
    fun `offline falls back to the cached index, marked stale`(@TempDir dir: File) {
        val online = CatalogService(
            Fake(mapOf("$base/index.json" to CatalogTransport.Reply(200, oneFace))), base, dir
        )
        assertTrue((online.index() as CatalogService.Result.Ok).value.faces.isNotEmpty())

        val offline = CatalogService(Fake(unreachable = true), base, dir)
        val result = offline.index()
        assertTrue(result is CatalogService.Result.Ok) { "the cache did not answer" }
        val ok = result as CatalogService.Result.Ok
        assertTrue(ok.stale) { "cached content was served without being marked stale" }
        assertEquals("midnight_7f3a", ok.value.faces[0].slug)
    }

    @Test
    fun `offline with no cache fails rather than pretending the catalog is empty`() {
        // An empty gallery and an unreachable one look identical to a person,
        // and only one of them is worth a retry.
        val service = CatalogService(Fake(unreachable = true), base, null)
        assertTrue(service.index() is CatalogService.Result.Failed)
    }

    @Test
    fun `a garbled index falls back to cache rather than throwing`(@TempDir dir: File) {
        val online = CatalogService(
            Fake(mapOf("$base/index.json" to CatalogTransport.Reply(200, oneFace))), base, dir
        )
        online.index()
        val garbled = CatalogService(
            Fake(mapOf("$base/index.json" to CatalogTransport.Reply(200, "{ not json"))), base, dir
        )
        val ok = garbled.index() as CatalogService.Result.Ok
        assertTrue(ok.stale)
    }

    @Test
    fun `a removed face reads as removed, not as a network problem`() {
        val service = CatalogService(Fake(), base)
        val failed = service.face("gone_7f3a") as CatalogService.Result.Failed
        assertTrue(failed.message.contains("no longer in the catalog")) { failed.message }
    }

    // ---- what the app is allowed to send ------------------------------------

    /**
     * The promise in About is that browsing sends nothing about the person.
     * This is the test that would fail if someone attached the install id to a
     * read for convenience.
     */
    @Test
    fun `reads carry no install id anywhere`(@TempDir dir: File) {
        val fake = Fake(mapOf("$base/index.json" to CatalogTransport.Reply(200, oneFace)))
        val service = CatalogService(fake, base, dir)
        service.index()
        service.face("midnight_7f3a")
        service.config()
        service.submissionState("an-id")
        for (url in fake.gets) {
            assertFalse(url.contains("install", ignoreCase = true)) { "a read carried an install id: $url" }
        }
        assertTrue(fake.posts.isEmpty()) { "a read posted something" }
    }

    /**
     * The counter is a bare increment. No install id, no device details, no
     * body at all — one number per face. A per-person history is exactly what
     * this must not become.
     */
    @Test
    fun `an install is reported as an empty body and nothing else`() {
        val fake = Fake()
        CatalogService(fake, base).reportInstall("midnight_7f3a")
        assertEquals(1, fake.posts.size)
        assertEquals("$base/faces/midnight_7f3a/installed", fake.posts[0].first)
        assertEquals("", fake.posts[0].second) { "the install report carried a body" }
    }

    @Test
    fun `a failed install report is swallowed, because nobody could act on it`() {
        // Nothing is returned and nothing throws: the count is a hint for
        // ordering a gallery, and a person installing a face has no use for the
        // news that a counter did not move.
        CatalogService(Fake(unreachable = true), base).reportInstall("midnight_7f3a")
    }

    @Test
    fun `a submission sends the published stem and signs in with a bearer token`() {
        val fake = Fake()
        val result = CatalogService(fake, base)
            .submit("Midnight Blue", "Ann", DialParams(), "google-id-token")
        assertTrue(result is CatalogService.Result.Ok)

        val (url, body) = fake.posts.single()
        assertEquals("$base/faces", url)
        // The stem, not a slug this layer invented: PublishedSlug is the one
        // implementation, and the service appends the short id.
        assertTrue(body.contains(""""slug": "${PublishedSlug.stemFor("Midnight Blue")}""""))
        // Not a literal 8: this asserts the params round-trip, not what the
        // format version happens to be, and hardcoding it makes every version
        // bump look like a client bug.
        assertTrue(body.contains(""""generatorVersion": $CURRENT_GENERATOR_VERSION""")) {
            "the params did not round-trip"
        }
        // The identity travels in the Authorization header, never in the body.
        assertEquals("google-id-token", fake.bearers.single())
        assertFalse(body.contains("google-id-token")) { "the token was put in the body as well" }
    }

    /**
     * Publishing needs an account; complaining never does.
     *
     * Requiring one to report was intolerable the moment submitting did not —
     * "anyone could publish and only developers could complain" is what moved
     * this catalog off GitHub. This is the test that fails if somebody
     * "tidies up" by making report take a token too.
     */
    @Test
    fun `reporting sends no identity of any kind`() {
        val fake = Fake(mapOf("$base/reports" to CatalogTransport.Reply(201, """{"id":"r1"}""")))
        CatalogService(fake, base)
            .report("midnight_7f3a", CatalogService.ReportReason.SPAM, "junk")
        assertEquals(listOf(null), fake.bearers) { "a report carried a bearer token" }
    }

    @Test
    fun `a refused submission surfaces the service's own reasons`() {
        // The service returns a problems list so somebody fixing a face sees
        // every reason at once. Losing it here would turn that into "failed".
        val refusal = """{"error":"that face cannot be published",
            "problems":[{"field":"engine","message":"TEXTURE cannot be published"},
                        {"field":"scale","message":"must be between 4 and 80"}]}"""
        val service = CatalogService(
            Fake(mapOf("$base/faces" to CatalogTransport.Reply(422, refusal))), base
        )
        val failed = service.submit("X", "", DialParams(), "t") as CatalogService.Result.Failed
        assertEquals("that face cannot be published", failed.message)
        assertEquals(2, failed.problems.size)
        assertTrue(failed.problems[0].contains("TEXTURE"))
    }

    @Test
    fun `a report sends one of the listed reasons, not free text alone`() {
        val fake = Fake(mapOf("$base/reports" to CatalogTransport.Reply(201, """{"id":"r1","state":"open"}""")))
        val result = CatalogService(fake, base)
            .report("midnight_7f3a", CatalogService.ReportReason.IMPERSONATION, "that is my logo")
        assertTrue(result is CatalogService.Result.Ok)
        val (_, body) = fake.posts.single()
        assertTrue(body.contains(""""reason": "impersonation""""))
        assertTrue(body.contains("that is my logo"))
        assertFalse(body.contains("turnstile")) { "the bot check is gone; nothing should still send one" }
    }

    @Test
    fun `every report reason has words a person would recognise`() {
        // The queue is worked by category, but the person choosing one is not a
        // moderator and should not have to read a wire value.
        for (reason in CatalogService.ReportReason.entries) {
            assertTrue(reason.label.isNotBlank())
            assertFalse(reason.label.contains('_')) { "${reason.name} shows a wire value: ${reason.label}" }
            assertTrue(reason.wire.none { it.isUpperCase() }) { "${reason.name} wire value is not lowercase" }
        }
    }

    /**
     * An empty OAuth client id means the service cannot accept anything, and
     * the app should say so BEFORE someone designs, names and submits a face
     * into a 403.
     */
    @Test
    fun `the app can tell that submissions are switched off`() {
        val off = """{"googleClientId":"","contractVersion":1,"currentGeneratorVersion":8,"maxFaceBytes":8192}"""
        val on = """{"googleClientId":"x.apps.googleusercontent.com","contractVersion":1,"currentGeneratorVersion":8,"maxFaceBytes":8192}"""

        val closed = CatalogService(Fake(mapOf("$base/config" to CatalogTransport.Reply(200, off))), base)
        assertFalse((closed.config() as CatalogService.Result.Ok).value.acceptsSubmissions)

        val open = CatalogService(Fake(mapOf("$base/config" to CatalogTransport.Reply(200, on))), base)
        assertTrue((open.config() as CatalogService.Result.Ok).value.acceptsSubmissions)
    }

    /**
     * Taking a face back is the promise the sign-in was ASKED FOR.
     *
     * The share sheet tells people the account exists so they can take the
     * face back later. If withdraw stopped carrying the token it would 401 and
     * the button would fail for everyone, which turns that sentence into a lie
     * about the one thing the account was justified by.
     */
    @Test
    fun `taking a face back carries the account that submitted it`() {
        val fake = Fake(
            mapOf("$base/submissions/sub-1/withdraw" to CatalogTransport.Reply(200, """{"ok":true}"""))
        )
        val result = CatalogService(fake, base).withdraw("sub-1", "google-id-token")
        assertTrue(result is CatalogService.Result.Ok)

        val (url, body) = fake.posts.single()
        assertEquals("$base/submissions/sub-1/withdraw", url)
        assertEquals("google-id-token", fake.bearers.single())
        assertFalse(body.contains("google-id-token")) { "the token was put in the body as well" }
    }

    /**
     * Asking what happened to a face needs no account at all.
     *
     * The state is not private -- a published face is public by definition --
     * and requiring a sign-in to read it would mean the list could not show
     * "waiting to be checked" without an account prompt on every open.
     */
    @Test
    fun `asking what happened to a submission sends no identity`() {
        val fake = Fake(
            mapOf("$base/submissions/sub-1" to
                CatalogTransport.Reply(200, """{"id":"sub-1","slug":"x_1","state":"pending"}"""))
        )
        val result = CatalogService(fake, base).submissionState("sub-1")
        assertEquals("pending", (result as CatalogService.Result.Ok).value.state)
        assertTrue(fake.bearers.isEmpty()) { "reading a state asked for an account" }
        assertTrue(fake.posts.isEmpty()) { "reading a state was not a read" }
    }

    /**
     * Every state the SERVICE can answer with is one the app has words for.
     *
     * `SubmissionLog.State.of` maps an unknown word to PENDING on purpose, so a
     * drift here is silent: the author would be told "waiting" forever about a
     * face that was actually refused. This is the test that notices.
     */
    @Test
    fun `every state the service can report has words in the app`() {
        for (wire in listOf("pending", "published", "rejected", "removed")) {
            assertEquals(wire, SubmissionLog.State.of(wire).wire) {
                "the app would silently read '$wire' as something else"
            }
        }
    }

    @Test
    fun `the service is named in exactly one place`() {
        // The seam the sequencing was designed around: moving off GitHub is
        // only safe if it can be pointed back.
        assertTrue(CatalogService.DEFAULT_BASE_URL.startsWith("https://"))
        val sources = File("src/main/kotlin/com/bfg/watchfaces/appcore").listFiles()
            ?.filter { it.extension == "kt" } ?: emptyList()
        val mentions = sources.filter { it.readText().contains("bfg-catalog.bfg-solutions") }
        assertEquals(listOf("CatalogService.kt"), mentions.map { it.name }) {
            "the service URL appears outside the seam: ${mentions.map { it.name }}"
        }
    }
}
