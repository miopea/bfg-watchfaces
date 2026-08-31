package com.bfg.watchfaces.appcore

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * THE SEAM.
 *
 * Everything the app knows about the catalog service goes through here, so the
 * whole thing can be pointed somewhere else — or pointed back — by changing one
 * object. That was the condition the migration sequencing was designed around
 * from the start: moving off GitHub is only safe if it can be undone.
 *
 * The interface exists so tests can answer without a network. There is exactly
 * ONE real implementation, [HttpTransport], and it uses `HttpURLConnection`
 * because that works unchanged on both the JVM and Android. `java.net.http`
 * would have been nicer and is API 34+, which would have meant two
 * implementations of the same three methods — the shape this repo keeps paying
 * for.
 */
interface CatalogTransport {

    /** What came back. [body] is empty rather than null when there is none. */
    data class Reply(val status: Int, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    fun get(url: String): Reply

    /** [bearer] is only ever the moderator token, and only from tooling. */
    fun post(url: String, body: String, bearer: String? = null): Reply

    /** Thrown for a failure to REACH the service, as opposed to a reply from it. */
    class Unreachable(message: String, cause: Throwable? = null) : IOException(message, cause)
}

/**
 * The real one.
 *
 * Deliberately small: no retries, no backoff, no connection pooling. A gallery
 * read that fails falls back to the cached index, and a submission that fails is
 * something the person retries by pressing the button again. Machinery that
 * hides a failure is worse than a failure here — the app has already been
 * through one round of reporting success for a transfer it could not see the
 * outcome of.
 */
class HttpTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000
) : CatalogTransport {

    override fun get(url: String): CatalogTransport.Reply = send(url, "GET", null, null)

    override fun post(url: String, body: String, bearer: String?): CatalogTransport.Reply =
        send(url, "POST", body, bearer)

    private fun send(
        url: String,
        method: String,
        body: String?,
        bearer: String?
    ): CatalogTransport.Reply {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection)
        } catch (e: Exception) {
            throw CatalogTransport.Unreachable("could not open $url", e)
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("accept", "application/json")
            if (bearer != null) connection.setRequestProperty("authorization", "Bearer $bearer")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("content-type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            // An error status still carries a body, and that body is where the
            // service says WHY -- which is the whole point of the problems list
            // it returns for an invalid face.
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return CatalogTransport.Reply(status, text)
        } catch (e: CatalogTransport.Unreachable) {
            throw e
        } catch (e: Exception) {
            throw CatalogTransport.Unreachable("could not reach the catalog: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }
}
