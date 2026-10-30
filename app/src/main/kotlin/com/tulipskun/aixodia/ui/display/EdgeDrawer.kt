package com.tulipskun.aixodia.ui.display

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * How much of a surface's width counts as its left edge. Wide enough that a hand
 * reaching in from the border lands inside it; narrow enough that a swipe begun
 * over a message, a terminal line or a selection is never mistaken for one.
 */
internal const val DRAWER_EDGE_SHARE = 0.15f

/** How far right the finger has to travel before an edge press becomes a swipe. */
internal val DrawerEdgeSlop: Dp = 24.dp

/**
 * The whole decision: did this drag, begun at [downX] on a surface [widthPx]
 * wide, move ([dx],[dy]) rightwards past [slopPx] and further sideways than
 * up-down?
 */
internal fun edgeSwipeOpensDrawer(
    downX: Float,
    widthPx: Int,
    dx: Float,
    dy: Float,
    slopPx: Float,
): Boolean = downX <= widthPx * DRAWER_EDGE_SHARE && dx > slopPx && dx > abs(dy)

/**
 * Opens [drawer] from a horizontal drag that starts at the left edge of the
 * screen, the way a side drawer opens everywhere else on the phone.
 *
 * It only watches — no drag is consumed here, so the thread still scrolls, a
 * long press still copies and the terminal still selects. The drawer's own
 * gesture has to be narrowed to the open state (`gesturesEnabled = isOpen`)
 * alongside it: left enabled it claims a horizontal swipe from anywhere on the
 * page, which is exactly the behaviour this replaces.
 *
 * [scope] is the caller's coroutine scope, not the pointer loop's: the gesture
 * body is a restricted suspension, so it can neither open the drawer itself
 * (that suspends until the animation settles, stalling the finger) nor launch
 * from its own scope.
 */
@Composable
fun Modifier.drawerEdgeSwipe(
    drawer: DrawerState,
    scope: CoroutineScope,
    enabled: Boolean = true,
): Modifier {
    val slopPx = with(LocalDensity.current) { DrawerEdgeSlop.toPx() }
    return pointerInput(drawer, scope, enabled, slopPx) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            val width = size.width
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (edgeSwipeOpensDrawer(
                        downX = down.position.x,
                        widthPx = width,
                        dx = change.position.x - down.position.x,
                        dy = change.position.y - down.position.y,
                        slopPx = slopPx,
                    )
                ) {
                    scope.launch { drawer.open() }
                    break
                }
            }
        }
    }
}
