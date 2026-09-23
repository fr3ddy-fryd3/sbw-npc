package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.config.server.ExplosionConfig
import com.atsuishio.superbwarfare.entity.projectile.HandGrenadeEntity
import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.entity.GrenadeRegistry
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModMemories
import net.minecraft.server.level.ServerLevel
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

    private var grenade: HandGrenadeEntity? = null
    private var fleeTo: Vec3? = null
    private var giveUpTick = 0

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean {
        if (entity.isPassenger) return false
        val threat = nearestThreat(level, entity) ?: return false
        grenade = threat
        return true
    }

    override fun shouldKeepRunning(entity: NpcEntity): Boolean {
        val g = grenade ?: return false
        if (!g.isAlive || entity.isPassenger || entity.tickCount >= giveUpTick) return false
        return entity.distanceToSqr(g) <= dangerRadius().let { it * it }
    }

    override fun start(entity: NpcEntity) {
        val g = grenade ?: return
        giveUpTick = entity.tickCount + MAX_EVADE_TICKS
        fleeTo = fleePoint(entity, g.position())
        BrainUtils.setForgettableMemory(entity, ModMemories.GRENADE_EVADE.get(), true, MAX_EVADE_TICKS)
        fleeTo?.let { entity.navigation.moveTo(it.x, it.y, it.z, SPRINT_SPEED) }
        DebugFlags.log("[grenade-debug] {} ({}) running from a grenade {} blocks away",
            entity.uuid, entity.npcClass, "%.1f".format(entity.distanceTo(g)))
    }

    override fun tick(entity: NpcEntity) {
        val to = fleeTo ?: return
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

    private fun nearestThreat(level: ServerLevel, entity: NpcEntity): HandGrenadeEntity? {
        val grenades = GrenadeRegistry.all(level)
        if (grenades.isEmpty()) return null
        val r2 = dangerRadius().let { it * it }
        return grenades.asSequence()
            // Only once it has come down: one sailing overhead toward the enemy would otherwise
            // scatter every ally it passes, and nobody knows where it lands until it does.
            .filter { it.isAlive && it.distanceToSqr(entity) <= r2 && (it.onGround() || it.deltaMovement.lengthSqr() < SETTLED_SPEED_SQR) }
            .minByOrNull { it.distanceToSqr(entity) }
    }

    /** A reachable spot away from the grenade; a straight line away if the random search finds
     *  nothing (in a corridor, say). */
    private fun fleePoint(entity: NpcEntity, from: Vec3): Vec3 {
        DefaultRandomPos.getPosAway(entity, FLEE_DISTANCE, FLEE_VERTICAL, from)?.let { return it }
        val away = entity.position().subtract(from).multiply(1.0, 0.0, 1.0)
        val dir = if (away.lengthSqr() < 1e-4) Vec3(1.0, 0.0, 0.0) else away.normalize()
        return entity.position().add(dir.scale(FLEE_DISTANCE.toDouble()))
    }

    private fun dangerRadius(): Double =
        ExplosionConfig.M67_GRENADE_EXPLOSION_RADIUS.get().toDouble() + SAFETY_MARGIN

    private companion object {
        const val SAFETY_MARGIN = 2.0
        /** (blocks/tick)^2 below which a grenade counts as having landed. */
        const val SETTLED_SPEED_SQR = 0.3 * 0.3
        const val FLEE_DISTANCE = 10
        const val FLEE_VERTICAL = 4
        const val SPRINT_SPEED = 1.4
        /** Well past any fuse — if it hasn't gone off by then, it isn't going to matter. */
        const val MAX_EVADE_TICKS = 100
    }
}
