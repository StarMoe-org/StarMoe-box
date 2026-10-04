package moe.starmoe.box.save

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Checks against real saves, which are personal data and never committed. Point the system properties at local
 * copies to run them, e.g. `./gradlew testDebugUnitTest -PrealJpSave=… -PrealIntlPlayer=…`; without them the tests
 * are skipped.
 *
 * - `realJpSave`: an encrypted save file straight from `Android/data/com.bushiroad.sirius/files/<hash>/`.
 * - `realIntlPlayer`: a stored upload (`save.json.gz`, the `_player` object) of the international build.
 * - `realJpId` / `realIntlId`: the expected in-game player IDs.
 */
class RealSaveTest {
    private fun file(property: String): File? =
        System.getProperty(property)?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }

    @Test
    fun jpSaveDecryptsAndNamesTheAccount() {
        val save = file("realJpSave")
        assumeTrue("set realJpSave to run", save != null)
        val bytes = save!!.readBytes()
        assertTrue(SaveCodec.looksLikeSave(bytes))
        val plain = SaveCodec.decrypt(bytes)!!
        val player = SaveCodec.extractPlayer(plain)!!
        System.getProperty("realJpId")?.let { assertEquals(it, player.info.accountId) }
        // The upload is the _player value, byte for byte: it sits in the plaintext right after the key.
        val key = "\"_player\":".toByteArray()
        val at = (0..plain.size - key.size).first { i -> key.indices.all { plain[i + it] == key[it] } } + key.size
        assertArrayEquals(plain.copyOfRange(at, at + player.bytes.size), player.bytes)
        println("jp: id=${player.info.accountId} name=${player.info.name} player=${player.bytes.size} B of ${plain.size} B")
    }

    @Test
    fun intlStoredPlayerIsReadTheSameWay() {
        val stored = file("realIntlPlayer")
        assumeTrue("set realIntlPlayer to run", stored != null)
        val player = GZIPInputStream(stored!!.inputStream()).use { it.readBytes() }
        // Wrap it back into a save, as the phone has it, and run the real path.
        val save = SaveCodec.encrypt("{\"_player\":".toByteArray() + player + "}".toByteArray(), ByteArray(32))
        val data = SaveCodec.extractPlayer(SaveCodec.decrypt(save)!!)!!
        assertArrayEquals(player, data.bytes)
        System.getProperty("realIntlId")?.let { assertEquals(it, data.info.accountId) }
        println("intl: id=${data.info.accountId} name=${data.info.name}")
    }
}
