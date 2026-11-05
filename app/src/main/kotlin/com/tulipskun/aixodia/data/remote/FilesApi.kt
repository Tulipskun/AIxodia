package com.tulipskun.aixodia.data.remote

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.tulipskun.aixodia.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class RemoteFile(
    val name: String,
    val path: String,
    val type: String,
    val size: Long,
    val modified: Long,
    val permissions: String,
    val uid: Long,
    val gid: Long,
) {
    val isDirectory: Boolean get() = type == "directory"
}

data class RemoteDirectory(
    val path: String,
    val parent: String?,
    val entries: List<RemoteFile>,
)

class FilesApi(
    private val settings: SettingsStore,
    private val resolver: ContentResolver,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private suspend fun requestBuilder(path: String): Request.Builder {
        val config = settings.current()
        val base = config.daemonUrl.trim().trimEnd('/')
        if (base.isBlank()) throw IllegalStateException("Daemon URL is not configured")
        if (config.token.isBlank()) throw IllegalStateException("Cloudflare API token is not configured")
        return Request.Builder()
            .url(base + path)
            .header("Authorization", "Bearer " + config.token)
    }

    private fun failure(code: Int, body: String): Nothing {
        val message = runCatching {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message") ?: json.optString("error")
        }.getOrNull().orEmpty()
        throw IllegalStateException(message.ifBlank { "HTTP " + code })
    }

    suspend fun list(path: String): RemoteDirectory = withContext(Dispatchers.IO) {
        val request = requestBuilder("/api/files?path=" + enc(path))
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) failure(response.code, raw)
            val json = JSONObject(raw)
            val rows = json.optJSONArray("entries")
            val entries = buildList {
                if (rows != null) for (i in 0 until rows.length()) {
                    val item = rows.optJSONObject(i) ?: continue
                    add(
                        RemoteFile(
                            name = item.optString("name"),
                            path = item.optString("path"),
                            type = item.optString("type"),
                            size = item.optLong("size"),
                            modified = item.optLong("modified"),
                            permissions = item.optString("permissions"),
                            uid = item.optLong("uid", -1),
                            gid = item.optLong("gid", -1),
                        ),
                    )
                }
            }
            RemoteDirectory(
                path = json.optString("path", path),
                parent = json.optString("parent").takeIf { it.isNotBlank() && it != "null" },
                entries = entries,
            )
        }
    }

    suspend fun upload(
        uri: Uri,
        directory: String,
        name: String,
        overwrite: Boolean = false,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): JSONObject = withContext(Dispatchers.IO) {
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val length = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val body = object : RequestBody() {
            override fun contentType(): MediaType = mime.toMediaType()
            override fun contentLength(): Long = length

            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(uri)
                    ?: throw IllegalStateException("Cannot open selected file")
                input.use {
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        sent += count
                        onProgress(sent, length)
                    }
                }
            }
        }
        val url = "/api/files/upload?path=" + enc(directory) +
            "&name=" + enc(name) + "&overwrite=" + overwrite
        val request = requestBuilder(url)
            .header("Content-Type", mime)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) failure(response.code, raw)
            JSONObject(raw)
        }
    }

    suspend fun download(
        path: String,
        destination: Uri,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val request = requestBuilder("/api/files/download?path=" + enc(path)).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) failure(response.code, response.body?.string().orEmpty())
            val body = response.body ?: throw IllegalStateException("Empty download response")
            val output = resolver.openOutputStream(destination, "wt")
                ?: throw IllegalStateException("Cannot open destination")
            body.byteStream().use { input ->
                output.use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    val total = body.contentLength()
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        received += count
                        onProgress(received, total)
                    }
                    out.flush()
                }
            }
        }
    }

    suspend fun mkdir(path: String) {
        mutate("/api/files/mkdir", JSONObject().put("path", path))
    }

    suspend fun rename(from: String, to: String) {
        mutate("/api/files/rename", JSONObject().put("from", from).put("to", to))
    }

    suspend fun chmod(path: String, mode: String) {
        val parsed = mode.trim().removePrefix("0").toInt(8)
        mutate(
            "/api/files/permissions",
            JSONObject().put("path", path).put("mode", parsed),
            "PUT",
        )
    }

    suspend fun delete(path: String, recursive: Boolean) = withContext(Dispatchers.IO) {
        val url = "/api/files?path=" + enc(path) + "&recursive=" + recursive
        val request = requestBuilder(url).delete().build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) failure(response.code, raw)
        }
    }

    private suspend fun mutate(path: String, json: JSONObject, method: String = "POST") =
        withContext(Dispatchers.IO) {
            val media = "application/json; charset=utf-8".toMediaType()
            val builder = requestBuilder(path)
            val body = RequestBody.create(media, json.toString())
            val request = if (method == "PUT") builder.put(body).build() else builder.post(body).build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) failure(response.code, raw)
            }
        }

    fun displayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty()
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "upload.bin"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
