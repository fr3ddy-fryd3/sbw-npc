package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Auditory-style stimulus: alerts nearby allied NPCs toward [investigatePos] WITHOUT granting them a
 * target — they go investigate ("go check that out"), they don't get a free lock on an enemy they
 * haven't actually seen themselves. Distinct from [TeamAwareness], which is "someone has direct line
 * of sight on a specific hostile" (a real lead); this is "something happened over there, worth a
 * look". [hearOrigin] and [investigatePos] are deliberately separate: who's in earshot is decided
 * from where the SOUND came from (the shooter), but where they should actually walk to is the
 * THREAT, not the ally who happened to be standing there — an earlier version conflated the two
 * (both were the shooter's own position), which put every alerted ally on a beeline to stand right
 * next to whichever squadmate fired, reported in-game as "why are they all running to stack on the
 * guy who spotted the enemy instead of doing something else".
 *
 * Two callers: [com.sbwnpc.squad.entity.ai.NpcGunAttackGoal] raises this every time it actually
 * fires (hearOrigin = the shooter, investigatePos = the target it's shooting at), and
 * [NpcEntity.die] raises it at the death position for both (no better lead available) when the
 * killer can't be resolved into a proper [TeamAwareness] report.
 */
object Alarm {
    fun raise(source: NpcEntity, hearOrigin: Vec3, investigatePos: Vec3, radius: Double) {
        val box = AABB(hearOrigin, hearOrigin).inflate(radius)
        source.level().getEntitiesOfClass(NpcEntity::class.java, box) { it !== source && !SquadTeams.isHostile(source, it) }
            .forEach { it.alert(investigatePos) }
    }
}
