package moe.starmoe.box.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.starmoe.box.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/** The stored copy as starmoe-api describes it. */
class StoredSave(
    val server: String,
    val accountId: String,
    val sha256: String,
    val size: Long,
    val storedSize: Long,
    val uploadedAt: Long,
    val checkedAt: Long,
) {
    companion object {
        fun from(json: JSONObject) = StoredSave(
            server = json.getString("server"),
            accountId = json.getString("accountId"),
            sha256 = json.getString("sha256"),
            size = json.optLong("size"),
            storedSize = json.optLong("storedSize"),
            uploadedAt = json.optLong("uploadedAt"),
            checkedAt = json.optLong("checkedAt"),
        )
    }
}

class UploadResult(val save: StoredSave, val changed: Boolean)

/** An answer from the API that is not a success; [code] is its `{"error": ...}`. */
class ApiException(val status: Int, val code: String?, val retryAfter: Int?) :
    IOException("HTTP $status${code?.let { " $it" } ?: ""}")

/** starmoe-api's save endpoints (README.md, "Game saves"). */
class SavesApi(private val base: String = BuildConfig.API_BASE) {
    private val userAgent = "StarMoeBox/${BuildConfig.VERSION_NAME} (Android)"

    /** Uploads a save's `_player` object unchanged, gzip on the wire. */
    suspend fun upload(token: String, server: String, player: ByteArray): UploadResult = withContext(Dispatchers.IO) {
        val body = gzipBest(player)
        val connection = open("/api/me/saves/$server", "PUT", token)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Content-Encoding", "gzip")
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
        val json = read(connection)
        UploadResult(StoredSave.from(json.getJSONObject("save")), json.optBoolean("changed"))
    }

    suspend fun list(token: String): List<StoredSave> = withContext(Dispatchers.IO) {
        val json = read(open("/api/me/saves", "GET", token))
        val saves = json.getJSONArray("saves")
        List(saves.length()) { StoredSave.from(saves.getJSONObject(it)) }
    }

    suspend fun delete(token: String, server: String, accountId: String) = withContext(Dispatchers.IO) {
        val connection = open("/api/me/saves/$server/$accountId", "DELETE", token)
        val status = connection.responseCode
        if (status != HttpURLConnection.HTTP_NO_CONTENT) throw failure(connection, status)
        connection.disconnect()
    }

    private fun open(path: String, method: String, token: String): HttpURLConnection {
        val connection = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", userAgent)
        return connection
    }

    private fun read(connection: HttpURLConnection): JSONObject {
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw failure(connection, status)
            return JSONObject(connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
        } finally {
            connection.disconnect()
        }
    }

    private fun failure(connection: HttpURLConnection, status: Int): ApiException {
        val text = runCatching { connection.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
        val code = text?.let { runCatching { JSONObject(it).optString("error").takeIf(String::isNotEmpty) }.getOrNull() }
        val retryAfter = connection.getHeaderField("Retry-After")?.toIntOrNull()
        return ApiException(status, code, retryAfter)
    }
}

/**
 * Best compression (level 9). The server stores this gzip as sent and serves it as is, so the extra CPU is
 * spent once on the phone and every later download is smaller.
 */
internal fun gzipBest(data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(data.size / 4)
    object : GZIPOutputStream(out) {
        init {
            def.setLevel(Deflater.BEST_COMPRESSION)
        }
    }.use { it.write(data) }
    return out.toByteArray()
}
