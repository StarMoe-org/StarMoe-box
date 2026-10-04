package moe.starmoe.box.save

/**
 * Turns candidate files into account saves, the same way whatever the files came from (Shizuku, a granted game
 * directory, a picked folder or files). Every candidate is tried: names are content hashes that change between
 * versions, so a file is a save exactly when it decrypts to JSON with a valid `_player`. When an account turns up
 * more than once (both international packages, old copies), the newest file wins; ties keep the first seen.
 */
class SaveCollector(private val server: GameServer?) {
    private val newest = LinkedHashMap<String, Entry>()

    private class Entry(val player: PlayerData, val source: String, val modifiedAt: Long)

    /** Files that decrypted to a `_player` without a usable account id: a format change, not "no save". */
    var unreadable = 0
        private set

    /** Offers one file's bytes. Returns true when it was a save. */
    fun offer(bytes: ByteArray, source: String, modifiedAt: Long): Boolean {
        if (!SaveCodec.looksLikeSave(bytes)) return false
        val plain = SaveCodec.decrypt(bytes) ?: return false
        val player = SaveCodec.extractPlayer(plain)
        if (player == null) {
            if (SaveCodec.hasPlayer(plain)) unreadable++
            return false
        }
        val previous = newest[player.info.accountId]
        if (previous == null || modifiedAt > previous.modifiedAt) {
            newest[player.info.accountId] = Entry(player, source, modifiedAt)
        }
        return true
    }

    val isEmpty get() = newest.isEmpty()

    /** The saves found, for a known server. */
    fun saves(server: GameServer = requireNotNull(this.server)): List<FoundSave> =
        newest.values.map { FoundSave(server, it.player.info, it.player.bytes, it.source, it.modifiedAt) }

    /** The player data found, when the server is not known yet (the user names it). */
    fun players(): List<PlayerData> = newest.values.map { it.player }

    companion object {
        /** Save files are a few hundred KB; anything far bigger is an asset bundle and never read. */
        const val MAX_FILE = 16L shl 20

        /** Account directories are named by a hex hash. */
        private val HEX_DIR = Regex("^[0-9a-fA-F]{16,128}$")

        fun isAccountDir(name: String) = HEX_DIR.matches(name)

        /**
         * Directories a folder walk never enters: the game's asset caches next to the account directories hold
         * gigabytes of bundles and no saves.
         */
        val SKIP_DIRS = setOf(
            "EncryptedBundles", "Addressables", "Master", "RemoteCatalog", "il2cpp", "Unity", "UnityCache",
            "cache", "tmp", "com.unity3d.player",
        )
    }
}
