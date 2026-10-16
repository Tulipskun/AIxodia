package com.tulipskun.aixodia.data.repo

import com.tulipskun.aixodia.data.local.MessageEntity

/** Apply a WS batch without treating a new tail as a replacement history page. */
internal fun mergeCommittedTurns(
    local: List<MessageEntity>,
    committed: List<MessageEntity>,
    clientMsgId: String,
): List<MessageEntity> {
    val rows = local.associateBy { it.seq }.toMutableMap()
    var nextSeq = (local + committed).maxOfOrNull { it.seq }?.plus(1) ?: 1
    for (incoming in committed.sortedBy { it.seq }) {
        val twin = rows.values.firstOrNull {
            it.role == incoming.role && (
                (incoming.role == "user" && clientMsgId.isNotBlank() && it.clientMsgId == clientMsgId) ||
                    (it.seq == incoming.seq && it.text == incoming.text)
                )
        }
        if (twin != null) rows.remove(twin.seq)
        val displaced = rows.remove(incoming.seq)
        if (displaced != null && committed.none { it.role == displaced.role && it.text == displaced.text }) {
            // Preserve another queued message when its optimistic seq collides.
            val movedSeq = nextSeq++
            rows[movedSeq] = displaced.copy(seq = movedSeq)
        }
        rows[incoming.seq] = incoming.copy(
            clientMsgId = twin?.clientMsgId.orEmpty(),
            thinkingMs = twin?.thinkingMs ?: 0,
        )
    }
    return rows.values.sortedBy { it.seq }
}
