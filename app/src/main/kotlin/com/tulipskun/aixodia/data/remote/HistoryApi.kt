package com.tulipskun.aixodia.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tulipskun.aixodia.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@JsonClass(generateAdapter = true)
data class TurnRow(
    @Json(name = "seq") val seq: Long = 0,
    @Json(name = "role") val role: String = "",
    @Json(name = "text") val text: String = "",
    @Json(name = "created_at") val createdAt: Long = 0,
)

@JsonClass(generateAdapter = true)
data class TurnsPage(@Json(name = "turns") val turns: List<TurnRow> = emptyList())

@JsonClass(generateAdapter = true)
data class SessionRow(
    @Json(name = "id") val id: String = "",
    @Json(name = "model") val model: String = "",
    @Json(name = "updated_at") val updatedAt: Long = 0,
)

/** Cloudflare Worker REST for D1 history (see worker/src/index.ts). */
class HistoryApi(private val settings: SettingsStore) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val client = OkHttpClient()

    suspend fun sessions(): List<SessionRow> = withContext(Dispatchers.IO) {
        val c = settings.current()
        val req = Request.Builder().url("${c.workerUrl}/api/sessions")
            .header("Authorization", "Bearer ${c.token}").get().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext emptyList()
            val a = moshi.adapter(TurnsPage::class.java)
            // sessions endpoint returns {"turns":[...]}-shaped or array; parse leniently
            return@withContext try {
                val sa = moshi.adapter(Array<SessionRow>::class.java)
                sa.fromJson(r.body!!.source())?.toList() ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }
    }

    suspend fun turns(sessionId: String, beforeSeq: Long = Long.MAX_VALUE, limit: Int = 50): List<TurnRow> =
        withContext(Dispatchers.IO) {
            val c = settings.current()
            val url = "${c.workerUrl}/api/sessions/$sessionId/turns?before_seq=$beforeSeq&limit=$limit"
            val req = Request.Builder().url(url).header("Authorization", "Bearer ${c.token}").get().build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext emptyList()
                return@withContext moshi.adapter(TurnsPage::class.java)
                    .fromJson(r.body!!.source())?.turns ?: emptyList()
            }
        }
}
