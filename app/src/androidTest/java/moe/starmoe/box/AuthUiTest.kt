package moe.starmoe.box

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import androidx.lifecycle.ViewModelProvider
import io.logto.sdk.android.exception.LogtoException
import moe.starmoe.box.ui.MainViewModel
import java.io.File
import android.graphics.Bitmap

@Suppress("DEPRECATION")
class AuthUiTest {
    @Test fun testExpiredLoginUi() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName.endsWith(".preview")) { "Run this fixture only with -PpreviewBuild=true" }
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        instrumentation.runOnMainSync {
            val vm = ViewModelProvider(activity)[MainViewModel::class.java]
            val method = MainViewModel::class.java.getDeclaredMethod("handleAuthFailure", Exception::class.java)
            method.isAccessible = true
            method.invoke(vm, LogtoException(LogtoException.Type.UNABLE_TO_FETCH_TOKEN_BY_REFRESH_TOKEN))
            assertTrue(vm.state.value.loginExpired)
            assertEquals(moe.starmoe.box.net.EXPIRED_LOGIN_MESSAGE, vm.state.value.message)
        }
        instrumentation.waitForIdleSync()
        Thread.sleep(1500)
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "expired-login-test.png")
        file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
        activity.finish()
    }
}
