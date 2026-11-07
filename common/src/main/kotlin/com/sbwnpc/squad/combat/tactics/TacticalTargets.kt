package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.SquadKnowledge
import net.minecraft.world.phys.Vec3

/** Reported squad contacts remain actionable regardless of the receiving member's detection radius. */
object TacticalTargets {
    fun candidates(view: TacticalSnapshot, task: TacticalTask?, position: Vec3, allowArmour: Boolean): List<TacticalContact> {
        val known = view.contacts.filter { view.now - it.seenAt <= SquadKnowledge.KNOWN_TICKS && (!it.armoured || allowArmour) }
        val focus = task?.focus ?: position
        val nearest = known.minOfOrNull { it.position.distanceTo(focus) } ?: return emptyList()
        return known.sortedWith(
            compareBy<TacticalContact> { if (it.position.distanceTo(focus) <= nearest + 12.0) 0 else 1 }
                .thenBy { it.position.distanceToSqr(position) },
        )
    }
}
