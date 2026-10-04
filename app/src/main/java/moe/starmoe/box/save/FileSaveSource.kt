package moe.starmoe.box.save

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Bytes the user handed over: a save file, a file of the account directory, or a zip of them (the PC tool's
 * 账号包.zip). Zip entries are offered one by one; everything else as is.
 */
object FileSaveSource {
    fun offer(collector: SaveCollector, bytes: ByteArray, name: String, modifiedAt: Long) {
        if (isZip(bytes)) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val data = readLimited(zip, SaveCollector.MAX_FILE) ?: continue
                    collector.offer(data, "$name · ${entry.name.substringAfterLast('/').take(12)}", entry.time.coerceAtLeast(0))
                }
            }
        } else {
            collector.offer(bytes, name, modifiedAt)
        }
    }

    /** Convenience for one file: the player data in it, by account. */
    fun fromFile(bytes: ByteArray, name: String): List<PlayerData> {
        val collector = SaveCollector(null)
        offer(collector, bytes, name, 0)
        return collector.players()
    }

    private fun isZip(bytes: ByteArray) =
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    /** Reads to EOF; null when more than [limit] bytes come. */
    fun readLimited(input: InputStream, limit: Long): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) return buffer.toByteArray()
            total += n
            if (total > limit) return null
            buffer.write(chunk, 0, n)
        }
    }
}
