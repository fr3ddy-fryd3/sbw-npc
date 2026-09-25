package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.GrenadeHazard
import com.sbwnpc.squad.entity.GrenadeRegistry
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModMemories
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.util.DefaultRandomPos
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import net.tslat.smartbrainlib.util.BrainUtils

/**
 * A live grenade close by: drop everything and get out of its blast.
 *
 * Takes the mob over the same way cover does — [ModMemories.GRENADE_EVADE] makes
 * `NpcEntity.combatLockedByCover()` true, so shooting, throwing and investigating stand down, and
 * the few movers that don't read that lock check [NpcEntity.evadingGrenade] themselves. It is last
 * in the core list on purpose: behaviours that hold an NPC in place (the mortar, the drone
 * operator) stop its navigation every tick, and this has to be the one that moves it anyway.
 *
 * Any grenade, whoever threw it — a squadmate's lands just as hard.
 */
class GrenadeEvadeBehaviour : ExtendedBehaviour<NpcEntity>() {

    init {
        noTimeout()
    }

    private var grenade: Entity? = null
    private var fleeTo: Vec3? = null
    private var giveUpTick = 0

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean {
        if (entity.isPassenger) return false
        val threat = nearestThreat(level, entity) ?: return false
        grenade = threat
        return true
    }

    // Held until the grenade is gone, not just until the mob is out of range: stopping at the
    // edge handed it straight back to whatever had it walking toward the grenade in the first
    // place — its firing position, its formation slot — and it walked back in.
    override fun shouldKeepRunning(entity: NpcEntity): Boolean {
        val g = grenade ?: return false
        return g.isAlive && !entity.isPassenger && entity.tickCount < giveUpTick
    }

    override fun start(entity: NpcEntity) {
        val g = grenade ?: return
        giveUpTick = entity.tickCount + MAX_EVADE_TICKS
        fleeTo = fleePoint(entity, g.position())
        BrainUtils.setForgettableMemory(entity, ModMemories.GRENADE_EVADE.get(), true, MAX_EVADE_TICKS)
        fleeTo?.let { entity.navigation.moveTo(it.x, it.y, it.z, SPRINT_SPEED) }
        // The old spot is inside the blast; whatever was headed there has to pick again after.
        FiringSpots.release(entity.uuid)
        DebugFlags.log("[grenade-debug] {} ({}) running from a grenade {} blocks away",
            entity.uuid, entity.npcClass, "%.1f".format(entity.distanceTo(g)))
    }

    override fun tick(entity: NpcEntity) {
        val to = fleeTo ?: return
        // Out and clear: stand still until it goes off.
        if (entity.position().closerThan(to, ARRIVED_DISTANCE)) {
            entity.navigation.stop()
            return
        }
        // Something else stopped or redirected the navigation this tick — take it back.
        val current = entity.navigation.targetPos
        if (entity.navigation.isDone || current == null || current.distToCenterSqr(to.x, to.y, to.z) > 4.0) {
            entity.navigation.moveTo(to.x, to.y, to.z, SPRINT_SPEED)
        }
    }

    override fun stop(entity: NpcEntity) {
        BrainUtils.clearMemory(entity, ModMemories.GRENADE_EVADE.get())
        grenade = null
        fleeTo = null
    }

    private fun nearestThreat(level: ServerLevel, entity: NpcEntity): Entity? {
        val grenades = GrenadeRegistry.all(level)
        if (grenades.isEmpty()) return null
        val r2 = dangerRadius().let { it * it }
        return grenades.asSequence()
            // Only once it has come down: one sailing overhead toward the enemy would otherwise
            // scatter every ally it passes, and nobody knows where it lands until it does.
            .filter { it.isAlive && it.distanceToSqr(entity) <= r2 && (it.onGround() || it.deltaMovement.lengthSqr() < SETTLED_SPEED_SQR) }
            .minByOrNull { it.distanceToSqr(entity) }
    }

    /** A reachable spot well clear of the grenade — and of any other one lying about; a straight
     *  line away if the random search finds nothing (in a corridor, say). */
    private fun fleePoint(entity: NpcEntity, from: Vec3): Vec3 {
        val level = entity.level() as? ServerLevel
        val clear = dangerRadius() + FLEE_MARGIN
        repeat(FLEE_ATTEMPTS) {
            val pos = DefaultRandomPos.getPosAway(entity, FLEE_DISTANCE, FLEE_VERTICAL, from) ?: return@repeat
            if (pos.distanceTo(from) >= clear && (level == null || !GrenadeHazard.threatens(level, pos))) return pos
        }
        val away = entity.position().subtract(from).multiply(1.0, 0.0, 1.0)
        val dir = if (away.lengthSqr() < 1e-4) Vec3(1.0, 0.0, 0.0) else away.normalize()
        return Vec3(from.x, entity.y, from.z).add(dir.scale(clear))
    }

    private fun dangerRadius(): Double = GrenadeHazard.dangerRadius

    private companion object {
        /** How far past the danger radius to run: the edge of it is where the fragments still land. */
        const val FLEE_MARGIN = 4.0
        const val FLEE_ATTEMPTS = 6
        const val ARRIVED_DISTANCE = 1.5
        /** (blocks/tick)^2 below which a grenade counts as having landed. */
        const val SETTLED_SPEED_SQR = 0.3 * 0.3
        const val FLEE_DISTANCE = 14
        const val FLEE_VERTICAL = 4
        const val SPRINT_SPEED = 1.4
        /** Well past any fuse — if it hasn't gone off by then, it isn't going to matter. */
        const val MAX_EVADE_TICKS = 100
    }
}
