package com.syncedwallpapers.app.data.repo

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.syncedwallpapers.app.core.AppConfig
import com.syncedwallpapers.app.core.AppError
import com.syncedwallpapers.app.core.AppException
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.functions.functions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String) : AuthState
}

sealed class SignInResult {
    data object Success : SignInResult()
    data object Cancelled : SignInResult()
    data class Failed(val error: AppError) : SignInResult()
}

@Singleton
class AuthRepository @Inject constructor(private val client: SupabaseClient) {

    val state: Flow<AuthState> = client.auth.sessionStatus.map { s ->
        when (s) {
            is SessionStatus.Authenticated -> AuthState.SignedIn(s.session.user?.id ?: "")
            is SessionStatus.Initializing -> AuthState.Loading
            // A failed refresh keeps the stored session; the SDK retries when online again.
            is SessionStatus.RefreshFailure -> client.auth.currentUserOrNull()?.let { AuthState.SignedIn(it.id) } ?: AuthState.SignedOut
            is SessionStatus.NotAuthenticated -> AuthState.SignedOut
        }
    }.distinctUntilChanged()

    fun currentUserId(): String? = client.auth.currentUserOrNull()?.id
    fun currentEmail(): String? = client.auth.currentUserOrNull()?.email

    /**
     * Sign in with Google through Credential Manager and hand the ID token to Supabase.
     * The raw nonce goes to Supabase; Google gets its SHA-256 (Supabase hashes the raw
     * nonce and compares it with the token's nonce claim).
     * [activityContext] must be an Activity so the account picker can be shown.
     */
    suspend fun signInWithGoogle(activityContext: Context): SignInResult {
        if (!AppConfig.isSupabaseConfigured || !AppConfig.isGoogleConfigured) return SignInResult.Failed(AppError.NotConfigured)
        val rawNonce = randomNonce()
        val option = GetSignInWithGoogleOption.Builder(AppConfig.googleWebClientId)
            .setNonce(sha256(rawNonce))
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val idToken = try {
            val credential = CredentialManager.create(activityContext).getCredential(activityContext, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleIdTokenCredential.createFrom(credential.data).idToken
            } else {
                return SignInResult.Failed(AppError.Unknown("Google returned an unexpected credential."))
            }
        } catch (e: GetCredentialCancellationException) {
            return SignInResult.Cancelled
        } catch (e: NoCredentialException) {
            return SignInResult.Failed(AppError.Unknown("No Google account on this phone. Add one in Settings → Accounts, then try again."))
        } catch (e: GetCredentialException) {
            return SignInResult.Failed(AppError.Unknown(e.message ?: "Google sign-in failed."))
        }
        return try {
            client.auth.signInWith(IDToken) {
                this.idToken = idToken
                provider = Google
                nonce = rawNonce
            }
            SignInResult.Success
        } catch (t: Throwable) {
            SignInResult.Failed(AppError.from(t))
        }
    }

    suspend fun signOut() {
        runCatching { client.auth.signOut() }
        // signOut can fail offline; clearing the local session is what matters here.
        runCatching { client.auth.clearSession() }
    }

    /** Server-side deletion (leave all groups, delete data, delete auth user). */
    suspend fun deleteAccount() {
        if (client.auth.currentSessionOrNull() == null) throw AppException(AppError.SessionExpired)
        client.functions.invoke("delete-account")
        runCatching { client.auth.clearSession() }
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun sha256(input: String): String =
            MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
