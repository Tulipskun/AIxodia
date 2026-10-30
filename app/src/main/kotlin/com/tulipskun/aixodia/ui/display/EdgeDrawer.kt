package com.tulipskun.aixodia.ui.display

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * How much of a surface's width counts as its left edge. Wide enough that a hand
 * reaching in from the border lands inside it; narrow enough that a swipe begun
 * over a message, a terminal line or a selection is never mistaken for one.
 */
internal const val DRAWER_EDGE_SHARE = 0.15f

/** Was this press begun inside the left band, where a side drawer lives? */
internal fun isInDrawerEdgeBand(downX: Float, widthPx: Int): Boolean =
    downX <= widthPx * DRAWER_EDGE_SHARE

/**
 * Is a press at ([downX],[downY]) inside the drawer's edge band on a surface
 * [widthPx] wide whose bottom [bottomReservedPx] belongs to another control?
 *
 * The band is the left slice of the surface, cut short by whatever is pinned to
 * its bottom: a swipe begun over the composer, the session strip or the goals
 * card is a reach for those, not for the menu, and the full-height band the
 * drawer uses for itself cannot see the difference.
 */
internal fun isDrawerEdgePress(
    downX: Float,
    downY: Float,
    widthPx: Int,
    heightPx: Int,
    bottomReservedPx: Int,
): Boolean = isInDrawerEdgeBand(downX, widthPx) &&
    downY <= (heightPx - bottomReservedPx).coerceAtLeast(0)

/**
 * Reserves the drawer's own drag for the left edge of the screen.
 *
 * A Material side drawer follows the finger from the first pixel of a sideways
 * drag, wherever that drag began, and only settles at the release — so a swipe
 * across the middle of a thread pulled the menu out a few millimetres and
 * snapped it back before anything could object. The drawer was never *opened*,
 * but it was certainly seen, and that flash is what this is for: refusing the
 * drawer the drag, not merely the settling of it.
 *
 * The drawer's draggable is an ancestor of the content it covers, and a pointer
 * event goes to the deepest node first, so every move arrives here before the
 * drawer is asked about it. A gesture begun inside the edge band is left
 * completely alone, and the drawer answers it exactly as Material wrote it —
 * finger-following, settled by travel or a flick, springing back when it gets
 * neither. Every other gesture has its moves consumed at the first event after
 * the finger moves, which is sooner than the drawer's own drag threshold
 * elapses: the drag never starts, so nothing slides, nothing bounces, nothing
 * appears.
 *
 * Consuming a move cannot rob a child of anything, because the child was asked
 * first — a scroll, a terminal selection, a code block sliding sideways are
 * already consumed by the time this runs and are stepped over untouched, and a
 * tap has no move to lose, since the release it lands on is not consumed either.
 *
 * [bottomReservedPx] shortens the band by the height of the bar pinned to the
 * bottom of the surface. Without it a thumb hooking sideways over the composer
 * or the session strip moved the menu, because the band was measured across the
 * full height and only ever asked *how far left* a press came down.
 *
 * The guard steps aside while the drawer is open, so the drag that closes it
 * still works from wherever the thumb happens to be.
 */
@Composable
fun Modifier.drawerEdgeGuard(
    drawer: DrawerState,
    bottomReservedPx: Int = 0,
    enabled: Boolean = true,
): Modifier {
    val closed = drawer.isClosed
    return pointerInput(closed, enabled, bottomReservedPx) {
        if (!enabled || !closed) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (
                isDrawerEdgePress(
                    downX = down.position.x,
                    downY = down.position.y,
                    widthPx = size.width,
                    heightPx = size.height,
                    bottomReservedPx = bottomReservedPx,
                )
            ) {
                return@awaitEachGesture
            }
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (!change.isConsumed) change.consume()
            }
        }
    }
}
