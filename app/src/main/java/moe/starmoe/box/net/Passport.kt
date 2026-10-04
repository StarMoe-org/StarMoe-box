package moe.starmoe.box.net

import android.app.Activity
import android.app.Application
import io.logto.sdk.android.LogtoClient
import io.logto.sdk.android.exception.LogtoException
import io.logto.sdk.android.type.LogtoConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.starmoe.box.BuildConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Who is signed in, from the ID token. `sub` is the same passport user as on the StarMoe sites. */
data class PassportUser(val sub: String, val name: String?, val username: String?, val picture: String?) {
    val displayName get() = name ?: username ?: "StarMoe 用户"
}

/**
 * StarMoe Passport (self-hosted Logto) through the Logto Android SDK v3: sign-in runs in a Custom Tab, tokens are
 * kept by the SDK, and access tokens for the StarMoe API resource are refreshed on demand.
 */
class Passport(application: Application) {
    private val client = LogtoClient(
        LogtoConfig(
            endpoint = BuildConfig.PASSPORT_ENDPOINT,
            appId = BuildConfig.PASSPORT_APP_ID,
            scopes = listOf(SCOPE_READ, SCOPE_WRITE),
            resources = listOf(BuildConfig.API_RESOURCE),
            usingPersistStorage = true,
        ),
        application,
    )

    val configured get() = BuildConfig.PASSPORT_APP_ID.isNotBlank()

    val signedIn get() = client.isAuthenticated

    suspend fun signIn(activity: Activity) = suspendCancellableCoroutine { cont ->
        client.signIn(activity, REDIRECT_URI) { error ->
            if (error == null) cont.resume(Unit) else cont.resumeWithException(error)
        }
    }

    suspend fun signOut(activity: Activity) = suspendCancellableCoroutine { cont ->
        client.signOut(activity, REDIRECT_URI) { cont.resume(Unit) }
    }

    /** Drops the local tokens without the browser round trip, for an expired session. */
    fun forget() = client.clearCredentials()

    suspend fun user(): PassportUser? = suspendCancellableCoroutine { cont ->
        client.getIdTokenClaims { error, claims ->
            cont.resume(if (error != null || claims == null) null else PassportUser(claims.sub, claims.name, claims.username, claims.picture))
        }
    }

    /** An unexpired access token for the API resource, refreshed when needed. */
    suspend fun accessToken(): String = suspendCancellableCoroutine { cont ->
        client.getAccessToken(BuildConfig.API_RESOURCE) { error, token ->
            when {
                token != null -> cont.resume(token.token)
                else -> cont.resumeWithException(error ?: LogtoException(LogtoException.Type.NOT_AUTHENTICATED))
            }
        }
    }

    companion object {
        const val SCOPE_READ = "saves:read"
        const val SCOPE_WRITE = "saves:write"

        /** Registered in the Logto console as both redirect and post sign-out redirect URI. */
        const val REDIRECT_URI = "moe.starmoe.box://moe.starmoe.box/callback"
    }
}
