package moe.starmoe.box.shizuku

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Runs commands as the shell user through Shizuku. Since Android 11 `Android/data` is closed to apps (even with
 * all-files access); only the shell user (uid 2000, what adb runs as) can read it. Shizuku lends that identity
 * to apps over wireless debugging, so no computer and no root are needed.
 */
object ShizukuShell {
    const val REQUEST_CODE = 7301
    const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"

    enum class Status { NOT_RUNNING, NEEDS_PERMISSION, UNSUPPORTED, READY }

    fun status(): Status = try {
        when {
            !Shizuku.pingBinder() -> Status.NOT_RUNNING
            Shizuku.isPreV11() -> Status.UNSUPPORTED
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Status.READY
            else -> Status.NEEDS_PERMISSION
        }
    } catch (_: Throwable) {
        Status.NOT_RUNNING
    }

    fun requestPermission() {
        if (status() == Status.NEEDS_PERMISSION) {
            runCatching { Shizuku.requestPermission(REQUEST_CODE) }
        }
    }

    class Result(val exitCode: Int, val stdout: ByteArray, val stderr: String) {
        val ok get() = exitCode == 0
        val text get() = String(stdout, Charsets.UTF_8)
    }

    /**
     * Runs argv directly (no shell, so paths need no quoting) and returns its output. Output past [limit] bytes
     * stops the process and throws, so a huge file cannot fill memory.
     */
    fun run(vararg argv: String, limit: Long = 1L shl 20): Result {
        val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            ?: throw IOException("Shizuku 没有在运行")
        val process = service.newProcess(arrayOf(*argv), null, null)
        try {
            val err = ByteArrayOutputStream()
            val errReader = thread(name = "shizuku-stderr") {
                runCatching { ParcelFileDescriptor.AutoCloseInputStream(process.errorStream).use { copy(it, err, 64L shl 10) } }
            }
            val out = ByteArrayOutputStream()
            val complete = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream).use { copy(it, out, limit) }
            if (!complete) {
                process.destroy()
                throw IOException("输出超过 ${limit / 1024} KB：${argv.joinToString(" ")}")
            }
            val code = process.waitFor()
            errReader.join(2000)
            return Result(code, out.toByteArray(), err.toString(Charsets.UTF_8.name()).trim())
        } finally {
            runCatching { process.destroy() }
        }
    }

    /** Copies until EOF; false when more than limit bytes came (the rest is left unread). */
    private fun copy(input: InputStream, output: ByteArrayOutputStream, limit: Long): Boolean {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return true
            total += n
            if (total > limit) return false
            output.write(buffer, 0, n)
        }
    }
}
