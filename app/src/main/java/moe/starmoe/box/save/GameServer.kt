package moe.starmoe.box.save

/**
 * Our Notes builds a save can come from. The ids are the server's `{server}` path segment (starmoe-api,
 * "Game saves"). The international build ships under two package names: the official-site APK
 * (`com.bilibili.sirius.official`) and the Google Play one (`com.bilibili.sirius`); both are the same game and
 * the same accounts. There is no mainland China build.
 */
enum class GameServer(val id: String, val label: String, val regions: String, val packageNames: List<String>) {
    INTL("intl", "国际服", "繁中 · EN · KR", listOf("com.bilibili.sirius.official", "com.bilibili.sirius")),
    JP("jp", "日服", "日本", listOf("com.bushiroad.sirius"));

    companion object {
        fun byId(id: String): GameServer? = entries.firstOrNull { it.id == id }

        /** Where a package keeps its files; only the shell user (adb / Shizuku) can read it on Android 11+. */
        fun filesDir(packageName: String) = "/sdcard/Android/data/$packageName/files"
    }
}
