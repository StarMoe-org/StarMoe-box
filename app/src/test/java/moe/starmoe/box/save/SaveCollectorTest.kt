package moe.starmoe.box.save

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class SaveCollectorTest {
    private val iv = Random(11).nextBytes(32)

    private fun save(accountId: String, name: String) =
        SaveCodec.encrypt("""{"_player":{"_profileId":$accountId,"_name":"$name"},"_other":1}""".toByteArray(), iv)

    @Test
    fun newestCopyOfAnAccountWins() {
        val c = SaveCollector(GameServer.INTL)
        assertTrue(c.offer(save("100", "old"), "official · a", 1_000))
        assertTrue(c.offer(save("100", "new"), "play · b", 2_000))
        assertTrue(c.offer(save("100", "older"), "official · c", 500))
        assertTrue(c.offer(save("200", "other"), "play · d", 0))
        val saves = c.saves().associateBy { it.info.accountId }
        assertEquals(2, saves.size)
        assertEquals("new", saves.getValue("100").info.name)
        assertEquals("play · b", saves.getValue("100").source)
        assertEquals(GameServer.INTL, saves.getValue("200").server)
        assertEquals("intl/100", saves.getValue("100").key)
    }

    @Test
    fun nonSavesAreIgnored() {
        val c = SaveCollector(GameServer.JP)
        assertFalse(c.offer("hello".toByteArray(), "readme", 0))
        assertFalse(c.offer(Random(1).nextBytes(4096), "bundle", 0))
        // Right format, but the settings file of the account directory: no _player.
        assertFalse(c.offer(SaveCodec.encrypt("""{"volume":1}""".toByteArray(), iv), "settings", 0))
        assertTrue(c.isEmpty)
        assertEquals(0, c.unreadable)
    }

    @Test
    fun playerWithoutAnIdIsCountedNotDropped() {
        val c = SaveCollector(GameServer.JP)
        assertFalse(c.offer(SaveCodec.encrypt("""{"_player":{"_accountid":5,"_name":"x"}}""".toByteArray(), iv), "f", 0))
        assertTrue(c.isEmpty)
        assertEquals(1, c.unreadable)
    }

    @Test
    fun zipOfACopiedAccountFolder() {
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
                z.putNextEntry(ZipEntry("0a1b2c3d4e5f60718293a4b5c6d7e8f9/")); z.closeEntry()
                put("0a1b2c3d4e5f60718293a4b5c6d7e8f9/9f8e7d6c", SaveCodec.encrypt("""{"volume":1}""".toByteArray(), iv))
                put("0a1b2c3d4e5f60718293a4b5c6d7e8f9/1a2b3c4d", save("7445432298994985508", "かすみ"))
            }
        }.toByteArray()
        val c = SaveCollector(GameServer.JP)
        FileSaveSource.offer(c, zip, "账号包.zip", 0)
        val saves = c.saves()
        assertEquals(1, saves.size)
        assertEquals("7445432298994985508", saves[0].info.accountId)
        assertEquals("账号包.zip · 1a2b3c4d", saves[0].source)
    }

    @Test
    fun accountDirectoryNames() {
        assertTrue(SaveCollector.isAccountDir("0a1b2c3d4e5f6071"))
        assertTrue(SaveCollector.isAccountDir("B50B23A5FD628C3DC386F7488F81D6B0450B8C89671574F55A3AD815F10B8E30"))
        assertFalse(SaveCollector.isAccountDir("EncryptedBundles"))
        assertFalse(SaveCollector.isAccountDir("0a1b2c3d"))
        assertFalse(SaveCollector.isAccountDir("files"))
    }
}
