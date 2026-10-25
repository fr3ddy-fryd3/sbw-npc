package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.mixin.MobGoalsAccessor
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.NeutralMob
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.ai.targeting.TargetingConditions
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.phys.AABB
import java.util.concurrent.ConcurrentHashMap

/** A target goal that may search for players; implemented on vanilla's nearest-target goal by a mixin. */
interface PlayerHuntingGoal {
    fun sbwnpcHuntsPlayers(): Boolean
}

/**
 * Mobs outside the factions that fight squad NPCs, and are fought by them, as they would a player (#135).
 *
 * The mob side is the vanilla player search, widened to NPCs by a mixin with the mob's own conditions.
 * The NPC side, here, needs no list of mob types: a mob is an enemy while it goes for this side, and
 * an unprovoked one is an enemy when it is a monster that hunts players by vanilla's target goal.
 * A neutral mob (wolf, bee, enderman, golem), a pet, a piglin or hoglin is an enemy only once it
 * goes for the side; until then a squad leaves it alone.
 */
object HostileMobs {
    private val huntsPlayers = ConcurrentHashMap<Class<*>, Boolean>()

    /** Blocks within which a mob that is not attacking a man himself is still his enemy. */
    private const val ENGAGE_RANGE = 32.0

    /** The nearest squad NPC in [area] that [mob] may target under [conditions], as for a player. */
    fun nearestNpc(mob: Mob, conditions: TargetingConditions, area: AABB): LivingEntity? = mob.level().getNearestEntity(mob.level().getEntitiesOfClass(NpcEntity::class.java, area) { it.currentSquad() != null }, conditions, mob, mob.x, mob.eyeY, mob.z)

    /** Whether [mob], a mob on no faction, is an enemy of [side], an NPC or a player on one. */
    fun threatens(mob: Mob, side: Entity): Boolean {
        val target = mob.target
        // Most mobs in a sensor's box are animals: out before the alliance lookups.
        if (target == null && mob !is Enemy) return false
        if (mob is NpcEntity || !mob.isAlive || allied(mob, side)) return false
        if (target === side) return true
        // Squads drew on zombies across their whole 128-block detection range (in-game 24.10): a mob
        // that is not on this man himself only counts within about a zombie's own follow range.
        if (mob.distanceToSqr(side) > ENGAGE_RANGE * ENGAGE_RANGE) return false
        if (target != null && SquadTeams.sameSide(target, side)) return true
        if (mob is NeutralMob || (mob is OwnableEntity && mob.ownerUUID != null)) return false
        return mob is Enemy && huntsPlayers.computeIfAbsent(mob.javaClass) { (mob as MobGoalsAccessor).sbwnpcTargetSelector().availableGoals.any { (it.goal as? PlayerHuntingGoal)?.sbwnpcHuntsPlayers() == true } }
    }

    /** Either way round: a tame pet or another mod's ally may answer only from its own side. */
    private fun allied(mob: Mob, side: Entity) = mob.isAlliedTo(side) || side.isAlliedTo(mob)
}
