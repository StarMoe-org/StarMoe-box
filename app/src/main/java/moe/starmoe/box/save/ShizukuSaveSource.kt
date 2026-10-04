package moe.starmoe.box.save

import moe.starmoe.box.shizuku.ShizukuShell

/** One account's `_player` object, cut out of its decrypted save. [player] is exactly the game's text. */
class FoundSave(
    val server: GameServer,
    val info: SaveInfo,
    val player: ByteArray,
    /** Where it was read from, for display. */
    val source: String,
    /** File modification time, Unix milliseconds (0 when unknown). */
    val modifiedAt: Long,
) {
    val key: String get() = "${server.id}/${info.accountId}"
}

sealed interface ScanResult {
    data object NotInstalled : ScanResult
    /** The game is installed but has no account directory yet: it was never signed in on this phone. */
    data object NoSave : ScanResult
    /** Saves decrypted, but none had an account id this app knows how to read (the game changed its format). */
    data object Unreadable : ScanResult
    class Found(val saves: List<FoundSave>) : ScanResult
}

/**
 * Reads the saves of one game build through Shizuku, as the shell user. The game keeps them in
 * `files/<hex hash>/`; every small file in those directories is offered to [SaveCollector].
 */
object ShizukuSaveSource {
    fun scan(server: GameServer, progress: (String) -> Unit): ScanResult {
        var installed = false
        val collector = SaveCollector(server)
        for (pkg in server.packageNames) {
            val root = GameServer.filesDir(pkg)
            val top = ShizukuShell.run("ls", "-1p", root)
            if (!top.ok) continue
            installed = true
            val dirs = lines(top.text).filter { it.endsWith("/") }.map { it.dropLast(1) }.filter(SaveCollector::isAccountDir)
            for (dir in dirs) {
                val path = "$root/$dir"
                val listing = ShizukuShell.run("ls", "-1p", path)
                if (!listing.ok) continue
                for (name in lines(listing.text).filterNot { it.endsWith("/") }) {
                    val file = "$path/$name"
                    val (size, mtime) = stat(file)
                    if (size == 0L || size > SaveCollector.MAX_FILE) continue
                    progress("${server.label}：读取 ${dir.take(8)}…/${name.take(12)}")
                    val bytes = try {
                        ShizukuShell.run("cat", file, limit = SaveCollector.MAX_FILE).takeIf { it.ok }?.stdout
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    collector.offer(bytes, "$pkg · ${name.take(12)}", mtime * 1000)
                }
            }
        }
        return when {
            !installed -> ScanResult.NotInstalled
            collector.isEmpty && collector.unreadable > 0 -> ScanResult.Unreadable
            collector.isEmpty -> ScanResult.NoSave
            else -> ScanResult.Found(collector.saves())
        }
    }

    /** Size in bytes and mtime in seconds; -1 / 0 when stat is unavailable. */
    private fun stat(file: String): Pair<Long, Long> {
        val result = runCatching { ShizukuShell.run("stat", "-c", "%s %Y", file) }.getOrNull()
        if (result == null || !result.ok) return -1L to 0L
        val parts = result.text.trim().split(Regex("\\s+"))
        return (parts.getOrNull(0)?.toLongOrNull() ?: -1L) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    private fun lines(text: String) = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
}
