package com.tulipskun.aixodia.ui.chat

/**
 * The text of the answer that is on screen right now.
 *
 * The phone gets the same answer by two routes: the stream arrives as deltas
 * a few characters at a time, and the daemon then sends the authoritative
 * message once — a provider can also report its content only at the end, and
 * some report it twice. Appending blindly is how a reply ends up on screen
 * twice ("read - Read UTF-8 … 1. read - Read UTF-8 …"), so every chunk goes
 * through here: a delta that repeats what is already there is dropped, and a
 * full message replaces the buffer instead of being added to it.
 *
 * Pure, so the rules are testable without a screen.
 */
internal fun mergeLiveAnswer(current: String, incoming: String, replace: Boolean): String {
    if (incoming.isEmpty()) return current
    if (replace) {
        // Keep the streamed body when the authoritative copy is the same text
        // in a plainer form: the deltas carry the Markdown the model wrote, the
        // final copy may not, and the longer reading of the same answer is the
        // one that renders correctly.
        val same = current.isNotBlank() &&
            (current == incoming || current.trim().contains(incoming.trim()))
        return if (same) current else incoming
    }
    if (current.isEmpty()) return incoming
    // A replayed delta (the daemon re-sending the tail it already sent) adds
    // nothing: the buffer already ends with it.
    if (current.endsWith(incoming)) return current
    if (current.contains(incoming) && incoming.length >= 8) return current
    return current + incoming
}
