package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Auditory-style stimulus: alerts nearby allied NPCs toward [origin] WITHOUT granting them a target
 * — they go investigate ("go check that out"), they don't get a free lock on an enemy they haven't
 * actually seen themselves. Distinct from [TeamAwareness], which is "someone has direct line of
 * sight on a specific hostile" (a real lead); this is "something happened over there, worth a look".
 *
 * Two callers: [com.sbwnpc.squad.entity.ai.NpcGunAttackGoal] raises this at the shooter's own
 * position every time it actually fires (heard gunfire), and [NpcEntity.die] raises it at the death
 * position when the killer can't be resolved into a proper [TeamAwareness] report.
 */
object Alarm {
    fun raise(source: NpcEntity, origin: Vec3, radius: Double) {
        val box = AABB(origin, origin).inflate(radius)
        source.level().getEntitiesOfClass(NpcEntity::class.java, box) { it !== source && !SquadTeams.isHostile(source, it) }
            .forEach { it.alert(origin) }
    }
}
