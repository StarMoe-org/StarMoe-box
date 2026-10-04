package moe.starmoe.box.crypto

import org.bouncycastle.crypto.engines.RijndaelEngine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.random.Random

class RijndaelTest {
    private fun bouncyCbc(encrypt: Boolean, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = CBCBlockCipher.newInstance(RijndaelEngine(256))
        cipher.init(encrypt, ParametersWithIV(KeyParameter(key), iv))
        val out = ByteArray(data.size)
        var off = 0
        while (off < data.size) {
            cipher.processBlock(data, off, out, off)
            off += 32
        }
        return out
    }

    @Test
    fun matchesBouncyCastleForEveryKeySize() {
        val random = Random(20261003)
        for (keySize in intArrayOf(16, 24, 32)) {
            repeat(20) {
                val key = random.nextBytes(keySize)
                val iv = random.nextBytes(32)
                val data = random.nextBytes(32 * (1 + random.nextInt(8)))
                val ours = Rijndael(key)
                val expected = bouncyCbc(true, key, iv, data)
                assertArrayEquals("encrypt, key $keySize", expected, ours.encryptCbc(data, iv))
                assertArrayEquals("decrypt, key $keySize", data, ours.decryptCbc(expected, iv))
                assertArrayEquals("bc decrypts ours", data, bouncyCbc(false, key, iv, ours.encryptCbc(data, iv)))
            }
        }
    }

    @Test
    fun sboxIsTheAesSbox() {
        // Spot values from FIPS-197: S(0x00)=0x63, S(0x01)=0x7c, S(0x53)=0xed, S(0xff)=0x16.
        assert(Rijndael.SBOX[0x00] == 0x63)
        assert(Rijndael.SBOX[0x01] == 0x7c)
        assert(Rijndael.SBOX[0x53] == 0xed)
        assert(Rijndael.SBOX[0xff] == 0x16)
        for (i in 0 until 256) assert(Rijndael.INV_SBOX[Rijndael.SBOX[i]] == i)
    }
}
