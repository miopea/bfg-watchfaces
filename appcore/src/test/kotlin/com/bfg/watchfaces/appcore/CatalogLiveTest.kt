package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.DialParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The client against the REAL service.
 *
 * `CatalogServiceTest` proves the parsing and the offline behaviour against a
 * scripted transport, which is evidence about this code and none at all about
 * the deployed thing. Curling the service proves the deployment and says
 * nothing about the client. This is the only test that puts the two together.
 *
 * Skipped unless `BFG_CATALOG_URL` is set, so it never fails a build on a
 * machine with no network:
 *
 * ```bash
 * BFG_CATALOG_URL=https://bfg-catalog.bfg-solutions.workers.dev \
 *   ./gradlew :appcore:test --tests '*CatalogLive*'
 * ```
 *
 * ## What it deliberately does NOT do
 *
 * It never submits anything. That needs a real Google sign-in, which
 * this cannot obtain and should not fake — and even with one, a test that
 * writes into the real moderation queue makes work for a person. The write path
 * is exercised only as far as the refusal, which is itself the thing worth
 * checking right now.
 */
class CatalogLiveTest {

    private val url: String? = System.getenv("BFG_CATALOG_URL")?.takeIf { it.isNotBlank() }

    private fun service() = CatalogService(baseUrl = url!!, cacheDir = null)

    @Test
    fun `the gallery loads from the live service, not from a cache`() {
        assumeTrue(url != null) { "set BFG_CATALOG_URL to run this" }
        val result = service().index()
        assertTrue(result is CatalogService.Result.Ok) {
            "the live catalog did not answer: ${(result as? CatalogService.Result.Failed)?.message}"
        }
        val ok = result as CatalogService.Result.Ok
        // cacheDir is null, so a stale answer here would mean the code found a
        // cache it should not have.
        assertFalse(ok.stale) { "a fresh read came back marked stale" }
        assertTrue(ok.value.count >= 0)
        assertEquals(ok.value.count, ok.value.faces.size) {
            "the count disagrees with the faces beside it"
        }
    }

    @Test
    fun `a face that does not exist reads as removed`() {
        assumeTrue(url != null)
        val failed = service().face("definitely_not_a_real_face") as CatalogService.Result.Failed
        assertTrue(failed.message.contains("no longer in the catalog")) { failed.message }
    }

    /**
     * The install counter, against the live service.
     *
     * Posted for a slug that does not exist ON PURPOSE: the endpoint answers
     * 204 either way, deliberately disclosing nothing about whether a face is
     * published, so this exercises the whole path without touching a real
     * face's count.
     */
    @Test
    fun `an install can be reported without anything blowing up`() {
        assumeTrue(url != null)
        service().reportInstall("definitely_not_a_real_face")
    }

    /**
     * THE STATE OF PLAY, ASSERTED RATHER THAN ASSUMED.
     *
     * Sign-in is not configured — no OAuth client id — so the service cannot
     * accept submissions and says so through `/config`. When the client id
     * lands this test FAILS, which is exactly what should happen: it is the
     * signal that the app may now offer sharing, and a reminder that this
     * file's claim about the write path has gone out of date.
     */
    @Test
    fun `submissions are switched off, and the app can tell`() {
        assumeTrue(url != null)
        val config = (service().config() as CatalogService.Result.Ok).value
        assertFalse(config.acceptsSubmissions) {
            "sign-in is now configured -- submissions are live. Update this test, and the " +
                "handoff that says submit and report have never run end to end."
        }
        assertEquals(1, config.contractVersion)
    }

    /**
     * The write path, as far as it can go without a real sign-in: the client
     * reaches the service, the service refuses, and the refusal arrives as a
     * readable message rather than an exception or a silent success.
     */
    @Test
    fun `a submission without a real sign-in is refused, and the reason survives`() {
        assumeTrue(url != null)
        val result = service().submit(
            name = "Live Test Probe",
            author = "",
            params = DialParams(),
            idToken = "not-a-real-token"
        )
        assertTrue(result is CatalogService.Result.Failed) { "an unverified submission was ACCEPTED" }
        val failed = result as CatalogService.Result.Failed
        assertTrue(failed.message.isNotBlank()) {
            "the service's own reason did not survive: ${failed.message}"
        }
    }
}
