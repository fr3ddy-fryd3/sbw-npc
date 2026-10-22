package com.sbwnpc.squad.combat

import com.sbwnpc.squad.ai.coordination.Point3
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where each man of a following squad stands near the player: spread loosely round a point ahead
 * of him, not in a column behind.
 *
 * The point leads the player along his heading, so the men keep up with where he is going instead
 * of trailing where he was. The spread is not rotated by the heading: it would turn every slot
 * round the point whenever the heading wobbled, which circled other squads in place
 * (see [SquadFormation.slotTarget]).
 */
object FollowFormation {
    /** How far ahead of a walking player the squad's middle is. */
    const val LEAD = 5.0

    /** Nearest and widening rings the men stand on. */
    private const val INNER_RADIUS = 3.5
    private const val RING_STEP = 2.5
    private const val RINGS = 3

    /** Successive men are this far round apart, so neighbours land on different sides. */
    private const val GOLDEN_ANGLE = 2.399963

    fun slot(player: Point3, heading: Pair<Double, Double>?, index: Int, moving: Boolean): Point3 {
        val lead = if (moving && heading != null) LEAD else 0.0
        val (hx, hz) = heading ?: (0.0 to 0.0)
        val radius = INNER_RADIUS + (index.coerceAtLeast(0) % RINGS) * RING_STEP
        val angle = index.coerceAtLeast(0) * GOLDEN_ANGLE
        return Point3(player.x + hx * lead + cos(angle) * radius, player.y, player.z + hz * lead + sin(angle) * radius)
    }

    /** Whether [from] is close enough to the player for the men to hold slots instead of walking to him. */
    fun near(from: Point3, player: Point3): Boolean = hypot(from.x - player.x, from.z - player.z) <= NEAR_DISTANCE

    const val NEAR_DISTANCE = 16.0
}
