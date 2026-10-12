package com.tulipskun.aixodia.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * One file the phone uploaded to the daemon. [path] is where the daemon stored
 * it; that path travels with the message so the agent can read the file with
 * its own tools.
 */
@JsonClass(generateAdapter = true)
data class AttachmentRef(
    @Json(name = "name") val name: String = "",
    @Json(name = "path") val path: String = "",
    @Json(name = "mime") val mime: String = "",
    @Json(name = "size") val size: Long = 0,
) {
    /** "photo.jpg · 1.2 MB" for a chip or a footer. */
    fun label(): String = buildString {
        append(name.ifBlank { "ไฟล์" })
        if (size > 0) append(" · ").append(humanSize(size))
    }

    companion object {
        fun humanSize(bytes: Long): String = when {
            bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
            bytes >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
