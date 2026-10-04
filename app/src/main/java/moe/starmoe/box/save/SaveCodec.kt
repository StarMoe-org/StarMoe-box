package moe.starmoe.box.save

import moe.starmoe.box.BuildConfig
import moe.starmoe.box.crypto.Rijndael
import org.json.JSONObject

/** What a save's `_player` says about itself; everything else in it is left alone. */
class SaveInfo(val accountId: String, val name: String?)

/** A save's `_player` object, cut out of the decrypted text byte for byte, and what it says about itself. */
class PlayerData(val info: SaveInfo, val bytes: ByteArray)

/**
 * The Our Notes save file format, the same on every build (intl and jp):
 *
 *     magic[32] ‖ iv[32] ‖ Rijndael-256-CBC(key, iv, PKCS#7(json))
 *
 * The key and magic are constants of the game client. Decrypting yields the save's JSON exactly as the game
 * wrote it. This app uploads only its `_player` value, cut out of that text unchanged (see [JsonSlice]); the
 * rest of the save stays on the phone.
 */
object SaveCodec {
    // Build-time only: GitHub Actions supplies OURNOTES_SAVE_KEY / OURNOTES_SAVE_MAGIC. Local builds use a
    // deterministic test pair and deliberately report crypto as unavailable to the UI; the official build has the
    // real values. The encoded strings are light obfuscation, not a security boundary.
    private val MAGIC by lazy { decodeBuildHex(BuildConfig.SAVE_MAGIC_ENCODED) }
    private val KEY by lazy { decodeBuildHex(BuildConfig.SAVE_KEY_ENCODED) }

    private fun decodeBuildHex(encoded: String): ByteArray = ByteArray(32) { i ->
        (encoded.substring(i * 2, i * 2 + 2).toInt(16) xor ((i * 17 + 93) and 255)).toByte()
    }

    private const val BLOCK = Rijndael.BLOCK
    private const val HEADER = 64

    private val cipher by lazy { Rijndael(KEY) }

    private val ACCOUNT_ID = Regex("^[1-9][0-9]{0,18}$")
    private const val INT64_MAX = "9223372036854775807"

    /** Cheap check on the first bytes, before reading or decrypting anything. */
    fun looksLikeSave(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER + BLOCK || (bytes.size - HEADER) % BLOCK != 0) return false
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return false
        return true
    }

    /** The decrypted save, or null when the file is not one (so callers can try every candidate). */
    fun decrypt(bytes: ByteArray): ByteArray? {
        if (!looksLikeSave(bytes)) return null
        val iv = bytes.copyOfRange(32, HEADER)
        val plain = cipher.decryptCbc(bytes.copyOfRange(HEADER, bytes.size), iv)
        return stripPkcs7(plain)
    }

    /** The inverse of [decrypt]; the app never writes saves, this exists for tests. */
    fun encrypt(plain: ByteArray, iv: ByteArray): ByteArray {
        require(iv.size == BLOCK)
        val pad = BLOCK - plain.size % BLOCK
        val padded = plain.copyOf(plain.size + pad)
        for (i in plain.size until padded.size) padded[i] = pad.toByte()
        return MAGIC + iv + cipher.encryptCbc(padded, iv)
    }

    /**
     * Cuts `_player` out of a decrypted save and reads its account id and `_name`. Null when the JSON is not a
     * player save. The account id is `_profileId`, the in-game player ID, which every build writes (the
     * international build also has `_accountid`, the same number; JP has only `_profileId`). It is taken as
     * decimal text and must be a positive int64, the rule the server applies too.
     */
    fun extractPlayer(plain: ByteArray): PlayerData? {
        val bytes = JsonSlice.member(plain, "_player") ?: return null
        val player = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        val accountId = player.opt(ACCOUNT_ID_KEY)?.let(::accountIdOf) ?: return null
        val name = player.optString("_name").takeIf { it.isNotBlank() }
        return PlayerData(SaveInfo(accountId, name), bytes)
    }

    /** Whether the JSON has a `_player` object at all, so a save whose account id is unreadable can be told apart. */
    fun hasPlayer(plain: ByteArray): Boolean = JsonSlice.member(plain, "_player")?.firstOrNull() == '{'.code.toByte()

    /** The member of `_player` that names the account. */
    const val ACCOUNT_ID_KEY = "_profileId"

    private fun accountIdOf(raw: Any): String? {
        val id = when (raw) {
            is String -> raw.trim()
            is Int, is Long -> raw.toString()
            is Number -> raw.toString() // a double or BigDecimal never passes the pattern below
            else -> return null
        }
        if (!ACCOUNT_ID.matches(id)) return null
        if (id.length == 19 && id > INT64_MAX) return null
        return id
    }

    private fun stripPkcs7(buf: ByteArray): ByteArray? {
        if (buf.isEmpty()) return null
        val n = buf[buf.size - 1].toInt() and 0xff
        if (n == 0 || n > BLOCK || n > buf.size) return null
        for (i in buf.size - n until buf.size) if ((buf[i].toInt() and 0xff) != n) return null
        return buf.copyOfRange(0, buf.size - n)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
