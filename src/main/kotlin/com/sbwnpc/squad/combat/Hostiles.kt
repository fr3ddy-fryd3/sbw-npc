package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity

/**
 * Who is hostile to an NPC within a radius of it: NPCs from [NpcRegistry] and players from the
 * level's own list — everyone who fights, without an entity box query over the chunks in between.
 */
object Hostiles {
    /** Living hostiles within [radius] of [of], nearest first. */
    fun within(level: ServerLevel, of: NpcEntity, radius: Double): List<LivingEntity> {
        val found = ArrayList<LivingEntity>()
        NpcRegistry.forEachWithin(level, of.position(), radius, exclude = of) {
            if (it.isAlive && SquadTeams.isHostile(of, it)) found += it
        }
        val r2 = radius * radius
        for (player in level.players()) {
            if (player.isAlive && player.distanceToSqr(of) <= r2 && SquadTeams.isHostile(of, player)) found += player
        }
        found.sortBy { of.distanceToSqr(it) }
        return found
    }
}
