package com.bfg.watchfaces.appcore

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

        override fun get(url: String): CatalogTransport.Reply {
            gets += url
            if (unreachable) throw CatalogTransport.Unreachable("no network")
            return replies[url] ?: CatalogTransport.Reply(404, """{"error":"no such thing"}""")
        }

        override fun post(url: String, body: String, bearer: String?): CatalogTransport.Reply {
            posts += url to body
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
    fun `a submission sends the published stem, the token and the install id`() {
        val fake = Fake()
        val result = CatalogService(fake, base)
            .submit("Midnight Blue", "Ann", DialParams(), "turnstile-token", "install-aaaa")
        assertTrue(result is CatalogService.Result.Ok)

        val (url, body) = fake.posts.single()
        assertEquals("$base/faces", url)
        // The stem, not a slug this layer invented: PublishedSlug is the one
        // implementation, and the service appends the short id.
        assertTrue(body.contains(""""slug": "${PublishedSlug.stemFor("Midnight Blue")}""""))
        assertTrue(body.contains(""""turnstile": "turnstile-token""""))
        assertTrue(body.contains(""""installId": "install-aaaa""""))
        assertTrue(body.contains(""""generatorVersion": 8""")) { "the params did not round-trip" }
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
        val failed = service.submit("X", "", DialParams(), "t", "i") as CatalogService.Result.Failed
        assertEquals("that face cannot be published", failed.message)
        assertEquals(2, failed.problems.size)
        assertTrue(failed.problems[0].contains("TEXTURE"))
    }

    @Test
    fun `a report sends one of the listed reasons, not free text alone`() {
        val fake = Fake(mapOf("$base/reports" to CatalogTransport.Reply(201, """{"id":"r1","state":"open"}""")))
        val result = CatalogService(fake, base)
            .report("midnight_7f3a", CatalogService.ReportReason.IMPERSONATION, "that is my logo", "t")
        assertTrue(result is CatalogService.Result.Ok)
        val (_, body) = fake.posts.single()
        assertTrue(body.contains(""""reason": "impersonation""""))
        assertTrue(body.contains("that is my logo"))
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
     * An empty Turnstile site key means the service cannot accept anything, and
     * the app should say so BEFORE someone designs, names and submits a face
     * into a 403.
     */
    @Test
    fun `the app can tell that submissions are switched off`() {
        val off = """{"turnstileSiteKey":"","contractVersion":1,"currentGeneratorVersion":8,"maxFaceBytes":8192}"""
        val on = """{"turnstileSiteKey":"0x4AAA","contractVersion":1,"currentGeneratorVersion":8,"maxFaceBytes":8192}"""

        val closed = CatalogService(Fake(mapOf("$base/config" to CatalogTransport.Reply(200, off))), base)
        assertFalse((closed.config() as CatalogService.Result.Ok).value.acceptsSubmissions)

        val open = CatalogService(Fake(mapOf("$base/config" to CatalogTransport.Reply(200, on))), base)
        assertTrue((open.config() as CatalogService.Result.Ok).value.acceptsSubmissions)
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
