package com.tulipskun.aixodia.term

import kotlin.math.abs

/** Which owner a drag that has just left the touch slop belongs to. */
internal enum class DragAxis { Horizontal, Vertical }

/**
 * How much more vertical travel than horizontal travel a drag needs before the
 * terminal claims it. At 2 the cut sits at about 63° from horizontal.
 *
 * A drawer is a horizontal gesture. Material's own drawer measures only the
 * horizontal axis and gives up only when a child consumed the stream first, and
 * the drawers of production apps either start in a slim edge band or activate
 * on horizontal offset while failing on vertical offset. None of them decide at
 * 45°: a thumb sweeping sideways travels in an arc, and the few millimetres of
 * downward drift in a natural swipe were enough to hand the gesture to the
 * page and leave the menu shut. The page claims a drag only when it is clearly
 * vertical; everything shallower belongs to the drawer.
 */
internal const val VERTICAL_DOMINANCE = 2f

/**
 * The axis a drag has committed to, or null while it is still inside the slop
 * in both directions. Once the terminal claims a gesture it holds it until the
 * finger lifts, so a sideways drift half-way through a scroll never turns into
 * a menu swipe.
 */
internal fun dragAxis(totalDx: Float, totalDy: Float, slop: Float): DragAxis? {
    val adx = abs(totalDx)
    val ady = abs(totalDy)
    if (adx < slop && ady < slop) return null
    return if (ady > adx * VERTICAL_DOMINANCE) DragAxis.Vertical else DragAxis.Horizontal
}
