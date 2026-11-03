package com.tulipskun.aixodia.ui.display

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Is a press at [downY] on a surface [heightPx] tall standing on the floor — the
 * strip of the bottom reserved for another control — rather than on the page the
 * drawer is swiped open from?
 *
 * The floor is whatever is pinned to the bottom of the surface: the composer,
 * the session strip, the terminal's key rows and command field. A horizontal
 * drag begun there is a reach for those — a caret dragged through a line, a
 * thumb hooked over a key — and the full-height reach the drawer uses for
 * itself cannot see the difference.
 */
internal fun isOnDrawerFloor(downY: Float, heightPx: Int, bottomReservedPx: Int): Boolean =
    downY > (heightPx - bottomReservedPx).coerceAtLeast(0)

/**
 * Takes the drawer's drag off the floor of a surface, and only off the floor.
 *
 * Across the rest of the page the drawer answers a horizontal drag from any
 * width position, exactly as Material writes it: the drag follows the finger,
 * needs real travel or a flick to settle open and springs back when it gets
 * neither. That is the gesture users of side-drawer apps actually make, and it
 * is deliberately not confined to the left border — on a phone in gesture
 * navigation the true edge belongs to the system's back swipe, and an app that
 * wants its menu to open reliably has to answer the drag where the thumb
 * naturally lands, a hand's width inside the screen.
 *
 * What it must not answer is a drag that belongs to something else. A Material
 * drawer begins following the finger at its own touch slop wherever the drag
 * started, so a refusal that arrives later — at a bigger threshold of the app's
 * own — has already pulled the menu out a few millimetres and snapped it back.
 * That flash was the complaint, and it is not a policy anyone would choose:
 * either the menu opens or nothing moves. So a gesture begun on the floor has
 * its very first move consumed here, sooner than the drawer's slop can elapse,
 * and the drag never starts.
 *
 * Consuming a move cannot rob a child of anything, because a pointer event goes
 * to the deepest node first and the drawer is an ancestor of everything on the
 * page: a scroll, a text selection, a caret dragged through a command line and
 * a code block sliding sideways are already consumed by the time this runs, and
 * are stepped over untouched. A tap has no move to lose, since the release it
 * lands on is not consumed either.
 *
 * [bottomReservedPx] is the measured height of the bar pinned to the bottom of
 * the surface, including whatever safe-area strip sits under it.
 *
 * The guard steps aside while the drawer is open, so the drag that closes it
 * still works from wherever the thumb happens to be.
 */
@Composable
fun Modifier.drawerFloorGuard(
    drawer: DrawerState,
    bottomReservedPx: Int = 0,
    enabled: Boolean = true,
): Modifier {
    val closed = drawer.isClosed
    return pointerInput(closed, enabled, bottomReservedPx) {
        if (!enabled || !closed || bottomReservedPx <= 0) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!isOnDrawerFloor(down.position.y, size.height, bottomReservedPx)) {
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
