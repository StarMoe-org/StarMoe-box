package moe.starmoe.box.net

import io.logto.sdk.android.exception.LogtoException
import java.io.IOException

const val EXPIRED_LOGIN_MESSAGE = "登录已过期，请重新登录。"

fun isExpiredLogin(error: Throwable): Boolean {
    if (error is ApiException && error.status == 401) return true
    val chain = generateSequence(error) { it.cause }.take(16).toList()
    // A network failure during refresh does not invalidate stored credentials.
    if (chain.any { it is IOException }) return false
    return chain.any { it is LogtoException && it.message in setOf(
        "NOT_AUTHENTICATED", "UNABLE_TO_FETCH_TOKEN_BY_REFRESH_TOKEN",
    ) }
}
