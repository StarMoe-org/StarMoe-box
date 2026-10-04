package moe.starmoe.box

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import moe.starmoe.box.shizuku.ShizukuShell
import moe.starmoe.box.ui.MainScreen
import moe.starmoe.box.ui.MainViewModel
import moe.starmoe.box.ui.theme.StarMoeTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == ShizukuShell.REQUEST_CODE) vm.refreshShizuku()
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { vm.refreshShizuku() }
    private val binderDead = Shizuku.OnBinderDeadListener { vm.refreshShizuku() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        setContent {
            StarMoeTheme { MainScreen(vm) }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshShizuku()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        super.onDestroy()
    }

    @Suppress("unused")
    private fun granted(result: Int) = result == PackageManager.PERMISSION_GRANTED
}
