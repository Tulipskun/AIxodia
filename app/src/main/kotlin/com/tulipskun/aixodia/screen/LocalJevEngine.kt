package com.tulipskun.aixodia.screen

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class LocalJevEngine(private val context: Context) {
    companion object {
        private const val MODEL_NAME = "laya_multilingual_q4_k_m.gguf"
        private const val MODEL_URL = "https://huggingface.co/mys/laya-multilingual-GGUF/resolve/main/laya_multilingual_q4_k_m.gguf?download=true"
        init { System.loadLibrary("aixodia_jev") }
    }

    @Volatile private var handle: Long = 0L

    private val modelFile: File
        get() = File(File(context.filesDir, "models"), MODEL_NAME)

    suspend fun prepare(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = modelFile.parentFile ?: error("model directory unavailable")
            if (!dir.exists()) check(dir.mkdirs() || dir.exists()) { "cannot create model directory" }
            if (!modelFile.exists() || modelFile.length() < 10_000_000L) {
                val tmp = File(dir, MODEL_NAME + ".part")
                val c = URL(MODEL_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 120_000
                c.requestMethod = "GET"
                c.connect()
                check(c.responseCode in 200..299) { "model download HTTP " + c.responseCode }
                c.inputStream.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
                }
                check(tmp.length() > 10_000_000L) { "downloaded model is incomplete" }
                check(tmp.renameTo(modelFile)) { "cannot finalize model file" }
            }
            if (handle == 0L) handle = nativeOpen(modelFile.absolutePath)
            check(handle != 0L) { "local JEV model failed to load" }
        }
    }

    suspend fun choose(goal: String, options: List<String>): Result<Pair<Int, Float>> =
        withContext(Dispatchers.Default) {
            runCatching {
                check(handle != 0L) { "local JEV model is not ready" }
                val raw = nativeChoose(handle, goal, options.toTypedArray())
                val p = raw.split('|', limit = 2)
                check(p.size == 2) { "local JEV returned invalid decision" }
                Pair(p[0].toInt(), p[1].toFloat())
            }
        }

    fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    private external fun nativeOpen(path: String): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeChoose(handle: Long, goal: String, options: Array<String>): String
}
