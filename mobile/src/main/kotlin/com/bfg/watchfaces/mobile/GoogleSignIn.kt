package com.bfg.watchfaces.mobile

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
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
        data class Failed(val message: String) : Outcome
    }

    /**
     * Ask for an ID token. Shows Google's own account picker.
     *
     * [filterByAuthorizedAccounts] false so somebody who has never used this
     * app still sees their accounts; true would show an empty sheet on a first
     * run, which reads as broken.
     *
     * Must be called from a coroutine on an Activity context — Credential
     * Manager needs a context that can show UI, the same constraint that bit
     * `WatchFacePushManager` from a BroadcastReceiver.
     */
    suspend fun idToken(context: Context, serverClientId: String): Outcome {
        if (serverClientId.isBlank()) {
            // Fail closed and say which side is not configured. The app cannot
            // fix this; the service can.
            return Outcome.Failed("sharing is switched off on the catalog service")
        }
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        return try {
            val response = CredentialManager.create(context).getCredential(context, request)
            val credential = response.credential
            if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return Outcome.Failed("that sign-in was not a Google account")
            }
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            Outcome.Ok(google.idToken, google.displayName)
        } catch (_: GetCredentialCancellationException) {
            Outcome.Cancelled
        } catch (_: NoCredentialException) {
            // The specific, common, and otherwise baffling case: a device with
            // no Google account on it at all. "Something went wrong" would send
            // somebody looking for a bug in the app.
            Outcome.Failed("there is no Google account on this device to sign in with")
        } catch (e: GetCredentialException) {
            Outcome.Failed(e.message ?: "that sign-in did not complete")
        }
    }
}
