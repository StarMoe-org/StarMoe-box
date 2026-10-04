package moe.starmoe.box.crypto

/**
 * Rijndael with a 256-bit block (Nb = 8), as Our Notes uses for its save files (`Fwk.UnityCipher`,
 * C# RijndaelManaged with BlockSize = 256). This is not AES: AES fixes the block at 128 bits, and
 * javax.crypto has no other Rijndael block size, so the cipher is implemented here.
 *
 * Keys may be 128, 192 or 256 bits; the game uses 256.
 */
class Rijndael(key: ByteArray) {
    private val nk = key.size / 4
    private val nr: Int
    private val roundKeys: ByteArray

    init {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "key must be 16, 24 or 32 bytes" }
        nr = maxOf(nk, NB) + 6
        roundKeys = expandKey(key)
    }

    private fun expandKey(key: ByteArray): ByteArray {
        val words = NB * (nr + 1)
        val w = ByteArray(words * 4)
        key.copyInto(w)
        var rcon = 1
        val t = IntArray(4)
        for (i in nk until words) {
            for (k in 0 until 4) t[k] = w[4 * (i - 1) + k].toInt() and 0xff
            if (i % nk == 0) {
                val first = t[0]
                t[0] = SBOX[t[1]] xor rcon
                t[1] = SBOX[t[2]]
                t[2] = SBOX[t[3]]
                t[3] = SBOX[first]
                rcon = xtime(rcon)
            } else if (nk > 6 && i % nk == 4) {
                for (k in 0 until 4) t[k] = SBOX[t[k]]
            }
            for (k in 0 until 4) {
                w[4 * i + k] = ((w[4 * (i - nk) + k].toInt() and 0xff) xor t[k]).toByte()
            }
        }
        return w
    }

    fun encryptBlock(input: ByteArray, inOff: Int, output: ByteArray, outOff: Int) {
        val s = IntArray(BLOCK) { input[inOff + it].toInt() and 0xff }
        addRoundKey(s, 0)
        for (round in 1 until nr) {
            subBytes(s, SBOX)
            shiftRows(s)
            mixColumns(s)
            addRoundKey(s, round)
        }
        subBytes(s, SBOX)
        shiftRows(s)
        addRoundKey(s, nr)
        for (i in 0 until BLOCK) output[outOff + i] = s[i].toByte()
    }

    fun decryptBlock(input: ByteArray, inOff: Int, output: ByteArray, outOff: Int) {
        val s = IntArray(BLOCK) { input[inOff + it].toInt() and 0xff }
        addRoundKey(s, nr)
        for (round in nr - 1 downTo 1) {
            invShiftRows(s)
            subBytes(s, INV_SBOX)
            addRoundKey(s, round)
            invMixColumns(s)
        }
        invShiftRows(s)
        subBytes(s, INV_SBOX)
        addRoundKey(s, 0)
        for (i in 0 until BLOCK) output[outOff + i] = s[i].toByte()
    }

    /** CBC without padding; data must be whole blocks. */
    fun decryptCbc(data: ByteArray, iv: ByteArray): ByteArray {
        require(iv.size == BLOCK) { "IV must be $BLOCK bytes" }
        require(data.size % BLOCK == 0) { "data must be whole $BLOCK-byte blocks" }
        val out = ByteArray(data.size)
        val block = ByteArray(BLOCK)
        var off = 0
        while (off < data.size) {
            decryptBlock(data, off, block, 0)
            for (i in 0 until BLOCK) {
                val prev = if (off == 0) iv[i] else data[off - BLOCK + i]
                out[off + i] = (block[i].toInt() xor prev.toInt()).toByte()
            }
            off += BLOCK
        }
        return out
    }

    /** CBC without padding; data must be whole blocks. */
    fun encryptCbc(data: ByteArray, iv: ByteArray): ByteArray {
        require(iv.size == BLOCK) { "IV must be $BLOCK bytes" }
        require(data.size % BLOCK == 0) { "data must be whole $BLOCK-byte blocks" }
        val out = ByteArray(data.size)
        val block = ByteArray(BLOCK)
        var off = 0
        while (off < data.size) {
            for (i in 0 until BLOCK) {
                val prev = if (off == 0) iv[i] else out[off - BLOCK + i]
                block[i] = (data[off + i].toInt() xor prev.toInt()).toByte()
            }
            encryptBlock(block, 0, out, off)
            off += BLOCK
        }
        return out
    }

    private fun addRoundKey(s: IntArray, round: Int) {
        val base = round * BLOCK
        for (i in 0 until BLOCK) s[i] = s[i] xor (roundKeys[base + i].toInt() and 0xff)
    }

    private fun subBytes(s: IntArray, box: IntArray) {
        for (i in 0 until BLOCK) s[i] = box[s[i]]
    }

    // The state is column-major: byte (row r, column c) is s[r + 4c].
    private fun shiftRows(s: IntArray) {
        val tmp = IntArray(NB)
        for (r in 1 until 4) {
            val shift = SHIFTS[r]
            for (c in 0 until NB) tmp[c] = s[r + 4 * ((c + shift) % NB)]
            for (c in 0 until NB) s[r + 4 * c] = tmp[c]
        }
    }

    private fun invShiftRows(s: IntArray) {
        val tmp = IntArray(NB)
        for (r in 1 until 4) {
            val shift = SHIFTS[r]
            for (c in 0 until NB) tmp[(c + shift) % NB] = s[r + 4 * c]
            for (c in 0 until NB) s[r + 4 * c] = tmp[c]
        }
    }

    private fun mixColumns(s: IntArray) {
        for (c in 0 until NB) {
            val i = 4 * c
            val a0 = s[i]; val a1 = s[i + 1]; val a2 = s[i + 2]; val a3 = s[i + 3]
            s[i] = MUL2[a0] xor MUL3[a1] xor a2 xor a3
            s[i + 1] = a0 xor MUL2[a1] xor MUL3[a2] xor a3
            s[i + 2] = a0 xor a1 xor MUL2[a2] xor MUL3[a3]
            s[i + 3] = MUL3[a0] xor a1 xor a2 xor MUL2[a3]
        }
    }

    private fun invMixColumns(s: IntArray) {
        for (c in 0 until NB) {
            val i = 4 * c
            val a0 = s[i]; val a1 = s[i + 1]; val a2 = s[i + 2]; val a3 = s[i + 3]
            s[i] = MUL14[a0] xor MUL11[a1] xor MUL13[a2] xor MUL9[a3]
            s[i + 1] = MUL9[a0] xor MUL14[a1] xor MUL11[a2] xor MUL13[a3]
            s[i + 2] = MUL13[a0] xor MUL9[a1] xor MUL14[a2] xor MUL11[a3]
            s[i + 3] = MUL11[a0] xor MUL13[a1] xor MUL9[a2] xor MUL14[a3]
        }
    }

    companion object {
        /** Columns per block (256-bit block). */
        private const val NB = 8
        const val BLOCK = 4 * NB

        /** Row shift offsets for Nb = 8. */
        private val SHIFTS = intArrayOf(0, 1, 3, 4)

        internal val SBOX = IntArray(256)
        internal val INV_SBOX = IntArray(256)

        init {
            // The S-box from the multiplicative inverse in GF(2^8) and the affine transform:
            // p walks the field by multiplying with 3, q by dividing by 3, so q = p^-1.
            var p = 1
            var q = 1
            do {
                p = (p xor (p shl 1) xor (if (p and 0x80 != 0) 0x1b else 0)) and 0xff
                q = q xor (q shl 1)
                q = q xor (q shl 2)
                q = q xor (q shl 4)
                q = q and 0xff
                if (q and 0x80 != 0) q = q xor 0x09
                val x = q xor rotl8(q, 1) xor rotl8(q, 2) xor rotl8(q, 3) xor rotl8(q, 4)
                SBOX[p] = (x xor 0x63) and 0xff
            } while (p != 1)
            SBOX[0] = 0x63
            for (i in 0 until 256) INV_SBOX[SBOX[i]] = i
        }

        private val MUL2 = table(2)
        private val MUL3 = table(3)
        private val MUL9 = table(9)
        private val MUL11 = table(11)
        private val MUL13 = table(13)
        private val MUL14 = table(14)

        private fun rotl8(x: Int, shift: Int): Int = ((x shl shift) or (x ushr (8 - shift))) and 0xff

        private fun xtime(x: Int): Int = ((x shl 1) xor (if (x and 0x80 != 0) 0x1b else 0)) and 0xff

        private fun gmul(a: Int, b: Int): Int {
            var x = a
            var y = b
            var product = 0
            while (y != 0) {
                if (y and 1 != 0) product = product xor x
                x = xtime(x)
                y = y ushr 1
            }
            return product
        }

        private fun table(factor: Int) = IntArray(256) { gmul(it, factor) }
    }
}
