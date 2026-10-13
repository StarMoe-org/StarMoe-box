package moe.starmoe.box.net

import org.junit.Assert.*
import org.junit.Test
import io.logto.sdk.android.exception.LogtoException
import java.net.SocketTimeoutException

class AppUpdatesTest {
    @Test fun versionsCompareNumerically() {
        assertTrue(newerVersion("v0.1.10", "0.1.3"))
        assertFalse(newerVersion("v0.1.3", "0.1.3"))
        assertFalse(newerVersion("v0.1.2", "0.1.3"))
        assertFalse(newerVersion("v0.2.0-beta", "0.1.3"))
        assertFalse(newerVersion("broken", "0.1.3"))
    }
    @Test fun refreshFailureGetsExpiredMessageButNetworkDoesNot() {
        assertTrue(isExpiredLogin(LogtoException(LogtoException.Type.UNABLE_TO_FETCH_TOKEN_BY_REFRESH_TOKEN)))
        assertTrue(isExpiredLogin(ApiException(401, "invalid_token", null)))
        assertFalse(isExpiredLogin(LogtoException(LogtoException.Type.UNABLE_TO_FETCH_TOKEN_BY_REFRESH_TOKEN, SocketTimeoutException())))
        assertFalse(isExpiredLogin(LogtoException(LogtoException.Type.UNABLE_TO_FETCH_TOKEN_BY_AUTHORIZATION_CODE)))
    }
}
