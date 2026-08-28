package com.bfg.watchfaces.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The only part of the watch side that can be tested without a watch.
 *
 * It earns a test because the failure it prevents is expensive and misleading:
 * an absent or malformed token is not rejected until inside addWatchFace, at the
 * very end of a Bluetooth transfer, as ERROR_INVALID_VALIDATION_TOKEN. That
 * reads like a transfer problem and is not.
 */
class ValidationTokenTest {

    @Test
    fun `a face channel carries its token`() {
        assertEquals("abc123", ValidationToken.fromChannelPath("/bfg-watchfaces/face/abc123"))
    }

    @Test
    fun `tokens with base64 padding survive intact`() {
        val token = "eyJhbGciOi.J9-_=="
        assertEquals(token, ValidationToken.fromChannelPath(ValidationToken.PREFIX + token))
    }

    @Test
    fun `a channel that is not ours is ignored`() {
        assertNull(ValidationToken.fromChannelPath("/something-else/face/abc"))
        assertNull(ValidationToken.fromChannelPath(""))
    }

    @Test
    fun `a face channel with no token is refused, not defaulted`() {
        // Returning a placeholder here would push the failure all the way to
        // addWatchFace, after the whole APK has crossed over Bluetooth.
        assertNull(ValidationToken.fromChannelPath("/bfg-watchfaces/face/"))
        assertNull(ValidationToken.fromChannelPath("/bfg-watchfaces/face/   "))
    }

    @Test
    fun `the old token-less path is not accepted`() {
        // The path gained the token segment deliberately. A device still sending
        // the old shape must be refused rather than silently installing nothing.
        assertNull(ValidationToken.fromChannelPath("/bfg-watchfaces/face"))
    }
}
