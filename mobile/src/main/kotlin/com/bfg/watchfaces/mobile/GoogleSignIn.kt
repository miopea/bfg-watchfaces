package com.bfg.watchfaces.mobile

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
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
        data class Failed(val message: String) : Outcome
    }

    /**
     * Ask for an ID token, using the BUTTON flow and only the button flow.
     *
     * ## Why not the bottom sheet
     *
     * `GetGoogleIdOption` is the sheet, and it exists for a different job:
     * signing somebody in QUIETLY, on app launch, when they already have an
     * authorised account. Google's own description says what it cannot do — it
     * "excludes accounts that require re-authentication", and "if no Google
     * Accounts exist on the device, the bottom sheet UI does not appear".
     *
     * The first version of this file used the sheet alone and told a phone with
     * an account on it that it had none. The second tried the sheet and fell
     * through to the button on `NoCredentialException`, and produced a loop
     * from a real device: pick an account, land back on the share sheet
     * unchanged, tap Share, get asked again.
     *
     * That second design deserved to fail. Two sign-in UIs behind one button is
     * two chances to end in a state nobody asked for, and the sheet was buying
     * nothing here: this is an EXPLICIT action. Somebody has already read what
     * sharing does and pressed Share. Nobody needs one tap saved at that point;
     * they need the picker to appear and work, which is exactly and only what
     * `GetSignInWithGoogleOption` promises.
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
        val button = GetSignInWithGoogleOption.Builder(serverClientId).build()
        return attempt(manager, context, button)
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
            // The button flow can ADD an account, so reaching here really does
            // mean there was nothing to sign in with -- unlike the sheet, where
            // this exception meant far less than it appeared to.
            Outcome.Failed("no Google account on this phone could be used to sign in")
        } catch (e: GetCredentialException) {
            Outcome.Failed(e.message ?: "that sign-in did not complete")
        }
    }
}
