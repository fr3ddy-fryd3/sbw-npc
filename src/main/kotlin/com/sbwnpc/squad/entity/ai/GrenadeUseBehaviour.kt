package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.GrenadeThrower
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import java.util.UUID

/**
 * The one reserve grenade every fighter carries, and the two situations it is for.
 *
 * It used to have exactly one outlet: a 20% roll taken *after* digging in, and digging in itself
 * needs the NPC to be badly hurt with a squadmate covering. The combination almost never came up,
 * so in practice the grenades were never thrown at all — which is what prompted this.
 *
 * Two rules now, both of them things a grenade is actually for:
 *  - **Flushing.** The target is close, its position is known, and it has been behind something
 *    for long enough that shooting at it is going nowhere.
 *  - **Breaking suppression.** The NPC is pinned by fire it cannot answer, and knows where from.
 *
 * Throws are rationed per squad, not per NPC: without that, a squad that walks into an ambush
 * answers with eight grenades in the same second.
 */
class GrenadeUseBehaviour : ExtendedBehaviour<NpcEntity>() {

    private var aimPoint: Vec3? = null

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean {
        // The grenadier has its own, unlimited mechanic; this is the ordinary fighter's single one.
        if (entity.npcClass == NpcClass.GRENADIER) return false
        if (!entity.hasReserveGrenade) return false
        if (entity.vehicleTransport || entity.operatingDrone || entity.servingMortar || entity.antiDroneEngaged) return false
        if (entity.tickCount < nextThrowTick) return false
        if (!squadMayThrow(entity)) return false

        val point = flushPoint(entity) ?: suppressionPoint(entity) ?: return false
        if (!inRange(entity, point)) return false
        if (!GrenadeThrower.isSafeToThrow(entity, point)) return false
        aimPoint = point
        return true
    }

    override fun shouldKeepRunning(entity: NpcEntity): Boolean = false

    override fun start(entity: NpcEntity) {
        val level = entity.level() as? ServerLevel ?: return
        val point = aimPoint ?: return
        GrenadeThrower.throwAt(entity, level, point)
        entity.hasReserveGrenade = false
        aimPoint = null
        nextThrowTick = entity.tickCount + SELF_COOLDOWN_TICKS
        entity.currentSquad()?.let { lastSquadThrow[it.id] = level.gameTime }
    }

    /**
     * A target that is known but not shootable. [NpcEntity.blockedSightSince] is stamped by
     * `GunAttackBehaviour` every tick it has a target it cannot see, so this asks the shooter what
     * it already knows rather than raycasting again.
     */
    private fun flushPoint(entity: NpcEntity): Vec3? {
        val target = entity.target ?: return null
        if (!target.isAlive) return null
        val since = entity.blockedSightSince ?: return null
        if (entity.tickCount - since < BLOCKED_TICKS) return null
        return target.position()
    }

    /** Pinned down, and the memory of where the fire is coming from is still live. */
    private fun suppressionPoint(entity: NpcEntity): Vec3? {
        if (!entity.isSuppressed()) return null
        return entity.threatPos
    }

    private fun inRange(entity: NpcEntity, point: Vec3): Boolean {
        val dist = entity.position().distanceTo(point)
        return dist in MIN_RANGE..MAX_RANGE
    }

    /** One grenade per squad per [SQUAD_COOLDOWN_TICKS], so an ambush draws an answer, not a volley. */
    private fun squadMayThrow(entity: NpcEntity): Boolean {
        val squad = entity.currentSquad() ?: return true
        val level = entity.level() as? ServerLevel ?: return false
        val last = lastSquadThrow[squad.id] ?: return true
        return level.gameTime - last >= SQUAD_COOLDOWN_TICKS
    }

    private var nextThrowTick = 0

    private companion object {
        /** Close enough to reach, far enough not to catch the blast. Matches the grenadier's own. */
        const val MIN_RANGE = 5.0
        const val MAX_RANGE = 16.0
        /** How long a target has to stay out of sight before it counts as dug in rather than
         *  momentarily behind a tree. */
        const val BLOCKED_TICKS = 60
        const val SELF_COOLDOWN_TICKS = 200
        const val SQUAD_COOLDOWN_TICKS = 120L

        /** Keyed by squad, not by NPC — see [squadMayThrow]. Entries are stale-but-harmless for
         *  disbanded squads: the value is only ever compared against the current game time. */
        val lastSquadThrow = HashMap<UUID, Long>()
    }
}
