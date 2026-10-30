package com.tulipskun.aixodia.ui.display

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * How much of a surface's width counts as its left edge. Wide enough that a hand
 * reaching in from the border lands inside it; narrow enough that a swipe begun
 * over a message, a terminal line or a selection is never mistaken for one.
 */
internal const val DRAWER_EDGE_SHARE = 0.15f

/** How far the finger has to travel before a press is a drag rather than a tap. */
internal val DrawerEdgeSlop: Dp = 24.dp

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
 * Is this drag sideways — further left or right than up or down, and far enough
 * that it is a swipe rather than a tap or a scroll drifting off the vertical?
 */
internal fun isSidewaysDrag(dx: Float, dy: Float, slopPx: Float): Boolean =
    abs(dx) > slopPx && abs(dx) > abs(dy)

/**
 * Reserves the drawer's own drag for the left edge of the screen.
 *
 * Nothing here moves the drawer, and that is the point. A side drawer is opened
 * by the drag the drawer puts over its content — one that follows the finger,
 * needs real travel or a flick to settle open, and springs back when it gets
 * neither. What that drag has never looked at is *where the finger came down*,
 * so a horizontal swipe begun in the middle of a conversation opened the menu as
 * readily as one begun at the border.
 *
 * This sits in the way of exactly those. The drawer's draggable is an ancestor
 * of the content it covers, so on the pass the content gets first, a drag begun
 * outside the edge band and clearly sideways is consumed here, which cancels the
 * drawer's claim on it. A drag begun inside the band is left completely alone,
 * and the drawer answers it the way it always has. A tap, a scroll, and anything
 * a child already took — a code block sliding sideways, a terminal selecting
 * text — are seen through, not consumed.
 *
 * [bottomReservedPx] shortens the band by the height of the bar pinned to the
 * bottom of the surface. Without it a thumb hooking sideways over the composer
 * or the session strip opened the menu, because the band was measured across the
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
    val slopPx = with(LocalDensity.current) { DrawerEdgeSlop.toPx() }
    return pointerInput(closed, enabled, slopPx, bottomReservedPx) {
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
                if (change.isConsumed) continue
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (isSidewaysDrag(dx, dy, slopPx)) change.consume()
            }
        }
    }
}
