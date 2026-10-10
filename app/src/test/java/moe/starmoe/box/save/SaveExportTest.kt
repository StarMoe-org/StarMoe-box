package moe.starmoe.box.save

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaveExportTest {
    private val player = """{ "_profileId":7445432298994985508, "_name":"夏目", "unknown": [1.2300, {"text":"你好\\世界"}], "stickers": [34,54] }""".toByteArray()
    private fun save(server: GameServer = GameServer.INTL, time: Long = 1234) =
        FoundSave(server, SaveInfo("7445432298994985508", "夏目"), player, "synthetic", time)

    @Test fun roundTripPreservesEveryPlayerByteAndMetadata() {
        for (server in GameServer.entries) {
            val result = requireNotNull(SaveExport.decode(SaveExport.encode(save(server))))
            assertArrayEquals(player, result.player)
            assertEquals(server, result.server)
            assertEquals(1234L, result.modifiedAt)
            assertEquals("7445432298994985508", result.info.accountId)
            assertEquals("夏目", result.info.name)
            assertEquals("starmoe-${server.id}-7445432298994985508.json", SaveExport.fileName(result))
        }
    }

    @Test fun fileImportUsesOriginalTimeAndRejectsWrongServer() {
        val bytes = SaveExport.encode(save())
        val c = SaveCollector(GameServer.INTL)
        FileSaveSource.offer(c, bytes, "renamed.json", 9999)
        assertArrayEquals(player, c.saves().single().player)
        assertEquals(1234L, c.saves().single().modifiedAt)
        assertTrue(c.offer(SaveExport.encode(save(time = 1000)), "older.json", 10000))
        assertEquals(1234L, c.saves().single().modifiedAt)
        val jp = SaveCollector(GameServer.JP)
        assertFalse(jp.offer(bytes, "intl.json", 9999))
        assertTrue(jp.isEmpty)
    }

    @Test fun unsupportedAndMalformedBackupsAreNotAccepted() {
        val bytes = String(SaveExport.encode(save()))
        assertNull(SaveExport.decode(bytes.replace("\"version\":1", "\"version\":2").toByteArray()))
        assertNull(SaveExport.decode(bytes.replace("\"intl\"", "\"unknown\"").toByteArray()))
        assertNull(SaveExport.decode(bytes.replace("7445432298994985508", "0").toByteArray()))
        assertNull(SaveExport.decode("{}".toByteArray()))
        assertNull(SaveExport.decode(bytes.dropLast(10).toByteArray()))
    }

    @Test fun zipImportAcceptsBackupAndKeepsNewerEncryptedSave() {
        val c = SaveCollector(GameServer.INTL)
        val encrypted = SaveCodec.encrypt("""{"_player":{"_profileId":7445432298994985508,"_name":"newer"}}""".toByteArray(), ByteArray(32))
        assertTrue(c.offer(encrypted, "game", 2000))
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use {
            it.putNextEntry(ZipEntry("backup/renamed.json"))
            it.write(SaveExport.encode(save()))
            it.closeEntry()
        }
        FileSaveSource.offer(c, output.toByteArray(), "backup.zip", 10000)
        assertEquals("newer", c.saves().single().info.name)
        assertEquals(2000L, c.saves().single().modifiedAt)
    }
}
