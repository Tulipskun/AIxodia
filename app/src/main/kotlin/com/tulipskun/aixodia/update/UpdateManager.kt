package com.tulipskun.aixodia.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.tulipskun.aixodia.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * In-app updater: checks the latest AIxodia GitHub Release,
 * downloads the APK and fires an install intent. Install goes OVER the
 * existing install (same applicationId + same stable signature +
 * higher versionCode), so Room history and settings survive —
 * never uninstalls, never clears data.
 *
 * The repo is public, so no Authorization header is sent at all. The only
 * token the app stores is the Cloudflare API token, which must never leave
 * the Cloudflare/D1/daemon path — sending it to api.github.com answers 401
 * and the button wrongly reports "already latest".
 */
object UpdateManager {
    const val REPO = "Tulipskun/AIxodia"
    private const val VERSION_ASSET = "AIxodia-version.json"
    private val client = OkHttpClient()

    data class Update(
        val tag: String,
        val apkUrl: String,
        val size: Long = 0,
        val versionName: String = "",
    ) {
        /** What the update row calls this build: the version name when known. */
        val label: String get() = versionName.ifBlank { tag }
    }

    /**
     * Returns an Update when the published build is newer than this one.
     *
     * The release tag is now the rolling `latest`, so it carries no version.
     * The release script publishes [VERSION_ASSET] beside the APK; its
     * versionCode is the same value burned into the APK, which makes the
     * comparison exact. Older v0.1.N releases have no metadata and still fall
     * back to the tag comparison.
     */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        val o = getJson("https://api.github.com/repos/$REPO/releases/latest")
            ?: return@withContext null
        val tag = o.optString("tag_name").trim()
        if (tag.isEmpty()) return@withContext null
        val assets = o.optJSONArray("assets") ?: return@withContext null

        var apkUrl = ""
        var apkSize = 0L
        var metadataUrl = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            when {
                name == VERSION_ASSET -> metadataUrl = a.optString("browser_download_url")
                name.endsWith(".apk") -> {
                    apkUrl = a.optString("browser_download_url")
                    apkSize = a.optLong("size")
                }
            }
        }
        if (apkUrl.isEmpty()) return@withContext null

        if (metadataUrl.isNotEmpty()) {
            val meta = getJson(metadataUrl) ?: return@withContext null
            val remoteCode = meta.optLong("versionCode", -1L)
            if (!rollingNeedsUpdate(remoteCode, BuildConfig.VERSION_CODE)) {
                return@withContext null
            }
            return@withContext Update(
                tag = tag,
                apkUrl = apkUrl,
                size = apkSize,
                versionName = meta.optString("versionName").trim(),
            )
        }

        if (!legacyNeedsUpdate(tag, "v" + BuildConfig.VERSION_NAME)) {
            return@withContext null
        }
        Update(tag, apkUrl, apkSize)
    }

    private fun getJson(url: String): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .get().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            return JSONObject(r.body!!.string())
        }
    }

    /** Downloads the APK to private storage and returns the file. */
    suspend fun download(ctx: Context, update: Update, onProgress: (Int) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val dir = File(ctx.filesDir, "updates").apply { mkdirs() }
            val out = File(dir, "AIxodia-${update.tag}.apk")
            val req = Request.Builder()
                .url(update.apkUrl)
                .header("Accept", "application/octet-stream")
                .get().build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("download failed: HTTP ${r.code}")
                val body = r.body!!
                val total = body.contentLength()
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(((done * 100) / total).toInt())
                        }
                    }
                }
            }
            out
        }

    /** Fires the package installer for [apk]. Update installs over existing data. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    /** A rolling release is newer only when its metadata versionCode is higher. */
    internal fun rollingNeedsUpdate(remoteCode: Long, currentCode: Int): Boolean =
        remoteCode > 0 && remoteCode > currentCode.toLong()

    /** Legacy tag format v0.1.N — newer means larger N. */
    internal fun legacyNeedsUpdate(latest: String, current: String): Boolean {
        fun num(tag: String) = tag.trimStart('v').split(".").lastOrNull()?.toIntOrNull() ?: -1
        return num(latest) > num(current)
    }
}
