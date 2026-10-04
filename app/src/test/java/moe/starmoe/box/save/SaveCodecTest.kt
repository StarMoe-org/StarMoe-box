package moe.starmoe.box.save

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class SaveCodecTest {
    private val iv = Random(7).nextBytes(32)

    // The game's _player text: int64 past 2^53 as a bare number, 1.50, odd spacing, an escaped quote and a brace
    // inside a string. Uploading must keep every byte of it.
    private val player = """{"_profileId":7445432298994985508, "_name":"か\"す}み","_memberCards":[{"_masterId":1,"_exp":12}],"_ratio":1.50}"""
    private val save = """{"_settings":{"volume":0.80,"_player":"decoy"},"_player": $player ,"_tail":[1,{"a":"]"}]}"""
        .toByteArray(Charsets.UTF_8)

    @Test
    fun roundTripKeepsEveryByte() {
        for (length in listOf(0, 1, 31, 32, 33, 1000)) {
            val plain = Random(length).nextBytes(length)
            val file = SaveCodec.encrypt(plain, iv)
            assertTrue(SaveCodec.looksLikeSave(file))
            assertArrayEquals(plain, SaveCodec.decrypt(file))
        }
    }

    @Test
    fun extractsPlayerByteForByte() {
        val plain = SaveCodec.decrypt(SaveCodec.encrypt(save, iv))!!
        val data = SaveCodec.extractPlayer(plain)!!
        assertEquals(player, String(data.bytes, Charsets.UTF_8))
        assertEquals("7445432298994985508", data.info.accountId)
        assertEquals("か\"す}み", data.info.name)
    }

    @Test
    fun extractRefusesWhatTheServerRefuses() {
        for (json in listOf(
            """{"_player":{"_profileId":0}}""",
            """{"_player":{"_profileId":-1}}""",
            """{"_player":{"_profileId":1.5}}""",
            """{"_player":{"_profileId":9223372036854775808}}""",
            """{"_player":{"_profileId":"../x"}}""",
            """{"_player":{}}""",
            """{"_player":"text"}""",
            """{"other":{"_player":{"_profileId":5}}}""",
            """{"_profileId":5}""",
            """[{"_player":{"_profileId":5}}]""",
            """not json""",
            """{"_player":{"_profileId":5}""",
            // _accountid alone is not enough: only _profileId names the account.
            """{"_player":{"_accountid":21123456789}}""",
        )) {
            assertNull(json, SaveCodec.extractPlayer(json.toByteArray()))
        }
        // The international build writes both (same number); _profileId is what is read.
        val intl = """{"_player":{"_accountid":7445432298994985508,"_name":"玩家","_profileId":21123456789}}"""
        assertEquals("21123456789", SaveCodec.extractPlayer(intl.toByteArray())!!.info.accountId)
        // A _player without a usable id is still recognised as a player save, so the scan can say so.
        assertTrue(SaveCodec.hasPlayer("""{"_player":{"_accountid":5}}""".toByteArray()))
        assertFalse(SaveCodec.hasPlayer("""{"volume":1}""".toByteArray()))
        val bom = "\uFEFF{\"_player\":{\"_profileId\":9223372036854775807}}".toByteArray()
        assertEquals("9223372036854775807", SaveCodec.extractPlayer(bom)!!.info.accountId)
        assertEquals("42", SaveCodec.extractPlayer("""{"_player":{"_profileId":"42"}}""".toByteArray())!!.info.accountId)
    }

    @Test
    fun rejectsOtherFiles() {
        val file = SaveCodec.encrypt(save, iv)
        val wrongMagic = file.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(SaveCodec.looksLikeSave(wrongMagic))
        assertNull(SaveCodec.decrypt(wrongMagic))
        assertNull(SaveCodec.decrypt(file.copyOf(file.size - 1)))
        assertNull(SaveCodec.decrypt(ByteArray(10)))
        // A corrupted last block breaks the padding instead of yielding garbage.
        val corrupt = file.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x55).toByte() }
        val result = SaveCodec.decrypt(corrupt)
        assertTrue(result == null || SaveCodec.extractPlayer(result) == null)
    }

    @Test
    fun fromFileReadsSingleFilesAndZips() {
        val file = SaveCodec.encrypt(save, iv)
        val other = SaveCodec.encrypt("""{"settings":true}""".toByteArray(), iv)

        val single = FileSaveSource.fromFile(file, "abc")
        assertEquals(1, single.size)
        assertEquals(player, String(single[0].bytes, Charsets.UTF_8))

        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                for ((name, data) in listOf("dir/a" to other, "dir/b" to file, "dir/c" to file, "readme.txt" to "hi".toByteArray())) {
                    z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
                }
            }
        }.toByteArray()
        val fromZip = FileSaveSource.fromFile(zip, "账号包.zip")
        assertEquals("one account, found once", 1, fromZip.size)
        assertEquals("7445432298994985508", fromZip[0].info.accountId)
    }
}
