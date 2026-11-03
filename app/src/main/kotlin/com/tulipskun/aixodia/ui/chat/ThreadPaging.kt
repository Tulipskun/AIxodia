package com.tulipskun.aixodia.ui.chat

import com.tulipskun.aixodia.data.model.ChatMessage

/**
 * Number of lazy items before the stored messages. The older-page marker is the
 * only one: the goals card lives in the bottom bar now, so it is never an index
 * in this list and must not be counted when a row is put back where it was.
 */
internal fun threadHeaderItems(showOlder: Boolean): Int = if (showOlder) 1 else 0

/** A visible stored row and its pixel position, owned by one visit to its chat. */
internal data class ThreadPageAnchor(
    val sessionId: String,
    val visit: Long,
    val messageId: String,
    val scrollOffset: Int,
    val loadedFloor: Long? = null,
)

/** Headers have their own positions: never lend their offset to the first message. */
internal fun pageAnchor(
    sessionId: String,
    visit: Long,
    messages: List<ChatMessage>,
    visibleItems: List<Pair<String, Int>>,
): ThreadPageAnchor? {
    val ids = messages.filter { it.sessionId == sessionId }.map { it.id }.toSet()
    val visible = visibleItems.firstOrNull { it.first in ids } ?: return null
    // LazyListState scroll offsets have the opposite sign of an item's y offset.
    return ThreadPageAnchor(sessionId, visit, visible.first, -visible.second)
}

/** Wait for the fetched rows, and reject an anchor from any departed visit. */
internal fun anchorItemIndex(
    anchor: ThreadPageAnchor,
    sessionId: String,
    visit: Long,
    messages: List<ChatMessage>,
    headerItems: Int,
): Int? {
    if (anchor.sessionId != sessionId || anchor.visit != visit) return null
    val floor = anchor.loadedFloor ?: return null
    if (messages.firstOrNull()?.seq?.let { it > floor } != false) return null
    val index = messages.indexOfFirst { it.id == anchor.messageId && it.sessionId == sessionId }
    return if (index < 0) null else index + headerItems
}
