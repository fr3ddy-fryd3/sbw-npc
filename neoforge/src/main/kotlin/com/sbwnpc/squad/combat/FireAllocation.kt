package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity

/**
 * Spreads a squad's fire over the enemies it can see instead of piling it all on the nearest one.
 *
 * Each target already taken by squadmates costs [BLOCKS_PER_SHOOTER] blocks of extra "distance",
 * so a second enemy a little further off wins over the first once a couple of rifles are on it.
 * Several enemies under fire at once keeps more of them suppressed — which is what lets the rest
 * of the squad move, withdrawing or advancing.
 */
object FireAllocation {
    private const val BLOCKS_PER_SHOOTER = 12.0

    /** [candidates] must be sorted nearest first; returns the best of them for [mob]. */
    fun pick(mob: NpcEntity, level: ServerLevel, candidates: List<LivingEntity>): LivingEntity? {
        if (candidates.size <= 1) return candidates.firstOrNull()
        val squad = mob.currentSquad() ?: return candidates.first()
        val taken = HashMap<LivingEntity, Int>()
        for (id in squad.members) {
            if (id == mob.uuid) continue
            val target = (level.getEntity(id) as? NpcEntity)?.target ?: continue
            taken.merge(target, 1, Int::plus)
        }
        if (taken.isEmpty()) return candidates.first()
        return candidates.minByOrNull { c ->
            Math.sqrt(mob.distanceToSqr(c)) + (taken[c] ?: 0) * BLOCKS_PER_SHOOTER
        }
    }
}
