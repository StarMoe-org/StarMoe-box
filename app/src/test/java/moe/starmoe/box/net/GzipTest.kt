package moe.starmoe.box.net

import org.junit.Assert.assertArrayEquals

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

class GzipTest {
    // Something shaped like a _player: repetitive JSON with numbers.
    private val player = buildString {
        append("{\"_profileId\":7445432298994985508,\"_memberCards\":[")
        val r = Random(3)
        repeat(3000) { if (it > 0) append(','); append("{\"_masterId\":${r.nextInt(1, 400)},\"_exp\":${r.nextInt(0, 999999)},\"_rank\":${r.nextInt(1, 5)}}") }
        append("]}")
    }.toByteArray()

    @Test
    fun roundTripsByteForByte() {
        val inflated = GZIPInputStream(gzipBest(player).inputStream()).use { it.readBytes() }
        assertArrayEquals(player, inflated)
    }

    @Test
    fun usesBestCompression() {
        val default = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(player) } }.toByteArray()
        val best = gzipBest(player)
        assertTrue("level 9 (${best.size}) should be smaller than level 6 (${default.size})", best.size < default.size)
        // Java writes XFL = 0 whatever the level, so compare the deflate body with a level-9 raw deflate instead.
        val level9 = Deflater(Deflater.BEST_COMPRESSION, true).run {
            setInput(player); finish()
            val buf = ByteArray(player.size + 1024)
            val n = deflate(buf); end()
            buf.copyOf(n)
        }
        assertArrayEquals(level9, best.copyOfRange(10, best.size - 8)) // 10-byte header, 8-byte CRC32+ISIZE trailer
    }
}
