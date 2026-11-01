package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.combat.CombatPosition
import com.sbwnpc.squad.combat.tactics.TacticalFirePosts
import net.minecraft.world.phys.Vec3

/**
 * Where a man may shift to fire from: inside his task's front, near the post his fire recovery
 * started from, and inside every area his order keeps him in.
 *
 * A value, built once from his task and order by [of]; testing a point reads nothing else, so the
 * spot search can ask it for every candidate.
 */
class RelocationBounds(private val task: NativeAssignment?, private val areas: List<Area>) {
    /** A circle on the ground he must stay inside. */
    data class Area(val centre: Vec3, val radius: Double)

    /** Whether he may shift to [point]. */
    fun permits(point: Vec3): Boolean {
        if (task?.permitsFront(point) == false) return false
        val recoveryPost = task?.localFirePost
        if (recoveryPost != null && !TacticalFirePosts.withinPost(recoveryPost, point, recovery = true)) return false
        return areas.all { CombatPosition.withinArea(it.centre, point, it.radius) }
    }

    companion object {
        /**
         * The bounds of a man with [task] whose order [holds] a position: a holding man shifts only
         * [CombatPosition.MAX_ADVANCE_DISTANCE] from [post], where his fight started, and a defender
         * stays inside his defend radius round [defendHome] too.
         */
        fun of(task: NativeAssignment?, holds: Boolean, post: Vec3, defendHome: Vec3?, defendRadius: Double): RelocationBounds {
            if (!holds) return RelocationBounds(task, emptyList())
            val areas = listOfNotNull(Area(post, CombatPosition.MAX_ADVANCE_DISTANCE), defendHome?.let { Area(it, defendRadius) })
            return RelocationBounds(task, areas)
        }
    }
}
