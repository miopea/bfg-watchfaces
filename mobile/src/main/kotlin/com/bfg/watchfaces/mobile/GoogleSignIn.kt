package com.bfg.watchfaces.mobile

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * A Google ID token, for the one thing that needs one.
 *
 * ## What this is allowed to gate
 *
 * Publishing a face, and nothing else. Browsing the gallery, installing a face
 * and reporting one all stay anonymous, and that asymmetry is the whole shape
 * of the catalog's design — "anyone could publish and only developers could
 * complain" is what moved it off GitHub. If this ever appears on a path other
 * than submit or withdraw, that has gone backwards.
 *
 * ## Why Credential Manager
 *
 * `GoogleSignInClient` is deprecated and Google is not accepting new
 * integrations on it. Credential Manager is the replacement and it hands back
 * exactly what the catalog needs: an ID token, signed by Google, which the
 * Worker verifies against Google's JWKS. The app never sees a password and
 * never holds a credential of its own.
 *
 * ## Why the client id is fetched rather than compiled in
 *
 * It comes from the catalog service's `/config`, the same value the Worker
 * checks the token's `aud` against. A copy in this app could drift from the
 * one the service accepts, and the failure would be a sign-in that succeeds
 * and a submission that is refused — the shape of bug that looks like the
 * network. One source, and it is the side that decides.
 */
object GoogleSignIn {

    /**
     * What happened, in terms the UI can act on.
     *
     * Cancelled is separate from failed on purpose: dismissing the sheet is a
     * decision, not an error, and showing somebody a red message because they
     * changed their mind is the app arguing with them.
     */
    sealed interface Outcome {
        data class Ok(val idToken: String, val displayName: String?) : Outcome
        data object Cancelled : Outcome
        /**
         * [retryWithButton] marks the one failure that is not final: the sheet
         * had nothing to show, which is the documented signal to try the button
         * flow instead. It never reaches a person — the caller either retries or
         * replaces the message.
         */
        data class Failed(val message: String, val retryWithButton: Boolean = false) : Outcome
    }

    /**
     * Ask for an ID token, trying both of Google's flows.
     *
     * ## Why there are two, and why the first one is not enough
     *
     * `GetGoogleIdOption` is the BOTTOM SHEET. It is the nicer of the two and
     * it is also, by Google's own description, incomplete:
     *
     * > The bottom sheet excludes accounts that require re-authentication…
     * > If no Google Accounts exist on the device, the bottom sheet UI does not
     * > appear.
     *
     * In both of those cases it throws `NoCredentialException`, and the first
     * version of this file reported that as "there is no Google account on this
     * device". Reported from a real phone that had one: the account simply
     * needed re-authentication, and the app told its owner something they could
     * see was untrue and offered no way forward.
     *
     * `GetSignInWithGoogleOption` is the BUTTON flow, and it is the one that can
     * reach a re-auth account and add a new one. So the sheet is an
     * optimisation and the button is the actual answer: try the sheet, and on
     * `NoCredentialException` — the documented signal that it had nothing to
     * show — fall through to the button rather than giving up.
     *
     * Cancelling is NOT retried. Dismissing the sheet is a decision, and
     * answering it by immediately opening a second sign-in UI is the app
     * arguing with somebody who just said no.
     *
     * Must be called from a coroutine on an Activity context — Credential
     * Manager needs a context that can show UI, the same constraint that bit
     * `WatchFacePushManager` from a `BroadcastReceiver`.
     */
    suspend fun idToken(context: Context, serverClientId: String): Outcome {
        if (serverClientId.isBlank()) {
            // Fail closed and say which side is not configured. The app cannot
            // fix this; the service can.
            return Outcome.Failed("sharing is switched off on the catalog service")
        }
        val manager = CredentialManager.create(context)

        // The sheet. Filter off, so somebody who has never used this app still
        // sees their accounts; on, it would show an empty sheet on a first run.
        val sheet = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        when (val first = attempt(manager, context, sheet)) {
            is Outcome.Ok, Outcome.Cancelled -> return first
            is Outcome.Failed -> if (!first.retryWithButton) return first
        }

        // The button. Reaches accounts the sheet excluded, and can add one.
        val button = GetSignInWithGoogleOption.Builder(serverClientId).build()
        return when (val second = attempt(manager, context, button)) {
            is Outcome.Failed ->
                // Both flows are out of ideas. Now the honest reading really is
                // that there is no account this phone can sign in with.
                if (second.retryWithButton)
                    Outcome.Failed("no Google account on this phone could be used to sign in")
                else second
            else -> second
        }
    }

    private suspend fun attempt(
        manager: CredentialManager,
        context: Context,
        option: androidx.credentials.CredentialOption
    ): Outcome {
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = manager.getCredential(context, request).credential
            if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return Outcome.Failed("that sign-in was not a Google account")
            }
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            Outcome.Ok(google.idToken, google.displayName)
        } catch (_: GetCredentialCancellationException) {
            Outcome.Cancelled
        } catch (_: NoCredentialException) {
            Outcome.Failed("nothing to show", retryWithButton = true)
        } catch (e: GetCredentialException) {
            Outcome.Failed(e.message ?: "that sign-in did not complete")
        }
    }
}
