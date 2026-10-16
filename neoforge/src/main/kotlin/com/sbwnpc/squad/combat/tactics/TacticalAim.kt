package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Strategy focus selects a sector; an actual firing position is tested against the gun's target. */
data class TacticalAim(val target: UUID?, val position: Vec3, val eye: Vec3) {
    companion object {
        fun resolve(entity: NpcEntity, task: TacticalTask): TacticalAim? {
            if (task.usesObservedAim) return task.observedAim
            val target = entity.target?.takeIf { it.isAlive && entity.isEnemy(it) }
            return choose(target?.let { TacticalAim(it.uuid, it.position(), it.eyePosition) }, task.focus)
        }

        fun choose(current: TacticalAim?, sector: Vec3?): TacticalAim? = current ?: sector?.let { TacticalAim(null, it, it.add(0.0, 1.5, 0.0)) }
    }
}
