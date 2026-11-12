package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.Condition
import com.sbwnpc.squad.ai.planning.Verdict
import kotlin.math.hypot

/**
 * Shelter from shelling whose source he cannot see (owner 08.11): a man shelled with no
 * fire sector and no target makes for a spot under a roof within [RADIUS] blocks, found by the
 * integration's roof search and observed as [TacticalFacts.ROOF]. No roof found, the fact is absent
 * and the answers to fire (cover, digging in) apply as before; there is no prone posture.
 */
object Shelter {
    /** How far, as the crow flies, he looks for a roof (owner 08.11: 24). */
    const val RADIUS = 24

    /** A roof counts this many blocks above his feet at the nearest, and at the furthest [ROOF_MAX]. */
    const val ROOF_MIN = 2

    const val ROOF_MAX = 5

    /**
     * Whether a column over a standing spot shelters him: one solid block that is not leaves between
     * [ROOF_MIN] and [ROOF_MAX] above his feet. [above] lists, from his feet up, whether each block is
     * solid and whether it is leaves; leaves stop no shell.
     */
    fun covered(above: List<Pair<Boolean, Boolean>>): Boolean = above.withIndex().any { (height, block) -> height in ROOF_MIN..ROOF_MAX && block.first && !block.second }

    /**
     * The shelter method applies: a roof spot is known (the integration observes it only for a man
     * shelled with no fire sector and no target), he is not in his hole, and he answers fire rather
     * than moving for an assault or an order.
     */
    fun eligible(intent: PositionIntent) =
        Condition { facts ->
            when {
                facts.value(TacticalFacts.ROOF) == null -> Verdict.Rejected("no_roof")
                failedAt(facts.value(TacticalFacts.ROOF_FAILURE), facts.value(TacticalFacts.ROOF)!!) -> Verdict.Rejected("roof_unreachable")
                (facts.value(TacticalFacts.DIGS) ?: 0) > 0 -> Verdict.Rejected("in_hole")
                facts.value(TacticalFacts.FIRE_SECTOR) != null -> Verdict.Rejected("fire_sector")
                intent.assault || intent.ordered -> Verdict.Rejected("keeps_moving")
                else -> Verdict.Allowed
            }
        }

    // His post search under this roof failed lately: another method answers until the failure expires.
    private fun failedAt(failed: Point3?, roof: Point3) = failed != null && hypot(failed.x - roof.x, failed.z - roof.z) <= FAILED_NEAR

    private const val FAILED_NEAR = 3.0

    /**
     * His post under the roof: kept back, with no lane, on a roofed spot near the one found. A
     * sentry's post floor is dropped with his duty: kept, it refused every spot under a roof lower
     * than his tower, and sentries stood on their towers through the shelling.
     */
    fun post(intent: PositionIntent, roof: Point3) = intent.copy(duty = CombatDuty.TAKE_COVER, anchor = roof, hold = false, holdFeet = false, requireCover = false, requireShelter = false, legs = false, coveredBy = emptySet(), spread = false, roofed = true, minimumHeight = null, elevationWeight = 0.0, inspection = null)
}
