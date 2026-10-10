package moe.starmoe.box.save

import org.json.JSONObject

/** Portable player-data backup, not a restorable encrypted game save. */
object SaveExport {
    const val MIME_TYPE = "application/json"

    fun fileName(save: FoundSave) = "starmoe-${save.server.id}-${save.info.accountId}.json"

    /** Never parse/re-serialize the player: large numeric IDs and every unknown field must survive. */
    fun encode(save: FoundSave): ByteArray =
        ("{\"_starmoeBox\":{\"version\":1,\"server\":\"${save.server.id}\",\"modifiedAt\":${save.modifiedAt}},\"_player\":".toByteArray() +
            save.player + "}".toByteArray())

    fun decode(bytes: ByteArray): FoundSave? {
        if (bytes.firstOrNull() != '{'.code.toByte()) return null
        return try {
            val metadata = JsonSlice.member(bytes, "_starmoeBox") ?: return null
            val json = JSONObject(String(metadata, Charsets.UTF_8))
            if (json.optInt("version") != 1) return null
            val server = GameServer.byId(json.optString("server")) ?: return null
            val player = SaveCodec.extractPlayer(bytes) ?: return null
            FoundSave(server, player.info, player.bytes, "Box 导出", json.optLong("modifiedAt").coerceAtLeast(0))
        } catch (_: Exception) {
            null
        }
    }
}
