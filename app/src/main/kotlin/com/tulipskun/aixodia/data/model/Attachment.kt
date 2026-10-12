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

/** A user message split into what they typed and the files they attached. */
data class UserMessageParts(val text: String, val attachments: List<AttachmentRef>)

/**
 * Splits a stored user turn into its text and its attachments.
 *
 * The daemon appends a short note naming each file so the agent can open it;
 * showing that note verbatim in the bubble put a server path, a MIME type and an
 * instruction in the middle of the user's own words. The note stays in the
 * stored turn (the agent needs it) and is lifted out here for display.
 */
fun splitAttachmentNote(raw: String): UserMessageParts {
    val marker = "[ไฟล์แนบ]"
    val at = raw.indexOf(marker)
    if (at < 0) return UserMessageParts(raw, emptyList())
    val head = raw.take(at).trimEnd()
    val files = raw.substring(at + marker.length)
        .lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("- ") }
        .mapNotNull { line ->
            val body = line.removePrefix("- ").trim()
            if (body.isBlank()) return@mapNotNull null
            val name = body.substringBefore(" → ").trim()
            val rest = body.substringAfter(" → ", "")
            val path = rest.substringBefore(" (").trim()
            val mime = rest.substringAfter(" (", "").substringBeforeLast(')').trim()
            if (name.isBlank()) null else AttachmentRef(name = name, path = path, mime = mime)
        }
        .toList()
    return UserMessageParts(head, files)
}
