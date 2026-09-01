package com.bfg.watchfaces.mobile

import android.content.Context
import android.util.Log
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

    private const val TAG = "GoogleSignIn"

    /**
     * What happened, in terms the UI can act on.
     *
     * Cancelled is separate from failed on purpose: dismissing the sheet is a
     * decision, not an error, and showing somebody a red message because they
     * changed their mind is the app arguing with them.
     */
    sealed interface Outcome {
        data class Ok(val idToken: String, val displayName: String?) : Outcome
        /**
         * The flow ended without a token and without an error.
         *
         * [why] exists because this branch was SILENT and that hid a bug
         * twice. The first two attempts at the loop reported from a phone —
         * pick an account, land back on the Share button — were both diagnosed
         * from reasoning rather than evidence, and both were wrong. A branch
         * that does nothing and says nothing cannot be told apart from a branch
         * that never ran.
         */
        data class Cancelled(val why: String) : Outcome
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

    /**
     * Credential Manager's own words, turned into somebody's.
     *
     * The instrument added to diagnose a three-times-reported loop worked: it
     * returned `[16] Account reauth failed`. Verified afterwards that the
     * config was NOT at fault — the Play App Signing certificate registered
     * for this app matches Play's own Classical signing key exactly, the
     * package matches, and the consent screen is External and in production.
     *
     * So the message means what it says: the Google account on the device
     * needs re-authentication, and the attempt to do it inside the picker
     * failed. That is a real and common state, it is the PERSON's to clear,
     * and nothing in this app can clear it for them.
     *
     * Which makes the wording the whole of what this app can contribute.
     * "[16] Account reauth failed" tells somebody they did something wrong and
     * gives them nowhere to go. Naming the actual remedy is the difference
     * between a dead end and a thirty-second fix.
     *
     * The raw text is kept on the end, because the next person debugging this
     * needs it and burying it would be repeating the mistake that made this
     * take three attempts.
     */
    private fun explain(raw: String): String = when {
        raw.contains("reauth", ignoreCase = true) ->
            "Your Google account needs to be re-verified on this phone. Open the " +
                "Google or Gmail app and finish any \"verify it's you\" prompt, then " +
                "try again. ($raw)"
        raw.contains("no credential", ignoreCase = true) ->
            "No Google account on this phone could be used. Add one in Settings, " +
                "then try again. ($raw)"
        else -> raw
    }

    private suspend fun attempt(
        manager: CredentialManager,
        context: Context,
        option: androidx.credentials.CredentialOption
    ): Outcome {
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = manager.getCredential(context, request).credential
            Log.i(TAG, "credential returned, type=${credential.type}")
            if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                // NAMES the type. "That sign-in was not a Google account" told
                // somebody nothing they could act on and nothing anyone could
                // debug from.
                return Outcome.Failed("sign-in returned a ${credential.type}, not a Google account")
            }
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            if (google.idToken.isBlank()) {
                return Outcome.Failed("Google returned a sign-in with no token in it")
            }
            Log.i(TAG, "got an ID token for ${google.displayName ?: "an account"}")
            Outcome.Ok(google.idToken, google.displayName)
        } catch (e: GetCredentialCancellationException) {
            Log.i(TAG, "sign-in cancelled: ${e.type} ${e.message}", e)
            Outcome.Cancelled(explain(e.message ?: e.type))
        } catch (e: NoCredentialException) {
            // The button flow can ADD an account, so reaching here really does
            // mean there was nothing to sign in with -- unlike the sheet, where
            // this exception meant far less than it appeared to.
            Log.w(TAG, "no credential: ${e.type} ${e.message}", e)
            Outcome.Failed("no Google account on this phone could be used to sign in")
        } catch (e: GetCredentialException) {
            // The TYPE, not just the message. Credential Manager's messages are
            // often null, and the type is the part that names the cause -- an
            // unregistered signing certificate and a misconfigured client id
            // produce different types and identical (empty) messages.
            Log.e(TAG, "sign-in failed: ${e.type} ${e.message}", e)
            Outcome.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Sign-in failed: ${e.type}")
        }
    }
}
