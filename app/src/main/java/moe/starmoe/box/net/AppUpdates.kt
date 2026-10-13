package moe.starmoe.box.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppRelease(val version: String, val notes: String, val url: String)

fun newerVersion(candidate: String, current: String): Boolean {
    fun parts(value: String): List<Int>? = value.removePrefix("v").takeIf {
        it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))
    }?.split('.')?.map { it.toIntOrNull() ?: return null }
    val a = parts(candidate) ?: return false
    val b = parts(current) ?: return false
    for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
    return false
}

class AppUpdates {
    suspend fun latest(): AppRelease = withContext(Dispatchers.IO) {
        val connection = URL("https://api.github.com/repos/StarMoe-org/StarMoe-box/releases/latest")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "StarMoeBox")
        try {
            check(connection.responseCode == 200) { "更新服务暂时不可用，请稍后重试。" }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            check(!json.optBoolean("draft") && !json.optBoolean("prerelease"))
            val url = json.getString("html_url")
            check(url.startsWith("https://github.com/StarMoe-org/StarMoe-box/releases/"))
            AppRelease(json.getString("tag_name"), json.optString("body").take(2000), url)
        } finally { connection.disconnect() }
    }
}
