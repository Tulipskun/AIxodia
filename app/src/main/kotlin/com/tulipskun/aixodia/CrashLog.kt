package com.tulipskun.aixodia

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last uncaught exception, kept on the phone.
 *
 * A sideloaded build has no crash service: when the process dies, the stack
 * trace dies with it and logcat is gone on the next launch. This writes the
 * one thing needed to fix the crash to the app's own files, then lets the
 * platform handler finish the job exactly as before. Settings offers it as
 * plain text, so the operator can send it without adb or root.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"
    private const val LIMIT = 64 * 1024

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The recorded report, or null when the last run ended normally. */
    fun latest(context: Context): String? =
        runCatching {
            val file = File(context.filesDir, FILE)
            if (!file.isFile) null else file.readText().takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun write(app: Application, thread: Thread, error: Throwable) {
        val whenText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val report = buildString {
            appendLine("AIxodia ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("when $whenText")
            appendLine("device ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("thread ${thread.name}")
            appendLine()
            append(error.stackTraceToString())
        }.take(LIMIT)
        File(app.filesDir, FILE).writeText(report)
    }
}
