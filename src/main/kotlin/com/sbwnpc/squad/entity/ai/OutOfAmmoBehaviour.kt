package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FiringPostArrival
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.GrenadeHazard
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.TickBudget
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.util.DefaultRandomPos
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/**
 * Empty NPCs go to a known friendly Supply, even far away. Without Supply they seek nearby
 * cover or make a bounded withdrawal; a relayed target cannot make them flee indefinitely.
 */
class OutOfAmmoBehaviour : ExtendedBehaviour<NpcEntity>() {

    init {
        noTimeout()
    }

    private var nextStepTick = 0
    private val retreat = AmmoRetreat()
    private val failedCover = HashSet<BlockPos>()
    private var destination: Vec3? = null
    private var hiding = false
    private var ownedPath: Path? = null
    private var lastMode: String? = null

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    private fun empty(entity: NpcEntity): Boolean {
        if (entity.isPassenger || entity.vehicleTransport || entity.servingMortar || entity.operatingDrone) return false
        val target = entity.target ?: return false
        if (!target.isAlive) return false
        val gun = Ports.guns.inHand(entity) ?: return false
        return !gun.hasAmmo()
    }

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean = empty(entity)

    override fun shouldKeepRunning(entity: NpcEntity): Boolean = empty(entity)

    override fun start(entity: NpcEntity) {
        nextStepTick = 0
        entity.resupplying = entity.nearestSupply() != null
        FiringSpots.release(entity.uuid)
        DebugFlags.log(LogGroup.AMMO, "{} ({}) out of ammo, running from {}", entity.uuid, entity.npcClass, entity.target?.name?.string)
        beginEpisode(entity)
    }

    override fun tick(entity: NpcEntity) {
        val target = entity.target ?: return
        beginEpisode(entity)
        if (entity.movementLockedByCover() || entity.coverPeekPoint != null || entity.evadingGrenade() || entity.combatLockedByMedic()) return
        // A fully empty NPC can return to a known Supply even outside the ordinary search radius.
        entity.nearestSupply()?.let {
            entity.resupplying = true
            trace(entity, "supply", it)
            if (entity.tickCount < nextStepTick && !entity.navigation.isDone) return
            nextStepTick = entity.tickCount + REPATH_TICKS
            entity.navigateTo(it, SPEED)
            ownedPath = entity.navigation.path
            return
        }
        entity.resupplying = false
        if (retreat.exhausted(entity.position(), entity.level().gameTime)) {
            hold(entity, "limit_reached")
            return
        }
        destination?.let { post ->
            val arrived = if (hiding) FiringPostArrival.reached(entity.position(), post) else entity.position().distanceTo(post) <= 2.0
            if (arrived && hiding) {
                hold(entity, "cover_reached")
                return
            }
            if (!arrived && hiding && FiringPostArrival.steer(entity, post, SPEED)) return
            if (!arrived && !entity.navigation.isDone) return
            if (!arrived && hiding) failedCover += BlockPos.containing(post)
            destination = null
        }
        if (entity.tickCount < nextStepTick) return
        nextStepTick = entity.tickCount + REPATH_TICKS
        val level = entity.level() as ServerLevel
        if (!TickBudget.hasRaycasts(level)) return
        val cover = CoverSearch.find(entity, level, target.position(), failedCover, retreat::permits)
        if (cover != null && move(entity, cover.bottomCenter, hidden = true)) return
        if (cover != null) failedCover += cover
        val away = (1..6).mapNotNull { DefaultRandomPos.getPosAway(entity, STEP, VERTICAL, target.position()) }
            .filter { retreat.permits(it) && !GrenadeHazard.threatens(level, it) }
            .filter { it.subtract(target.position()).horizontalDistance() > entity.position().subtract(target.position()).horizontalDistance() }
            .maxByOrNull { it.subtract(target.position()).horizontalDistance() }
        if (away == null || !move(entity, away, hidden = false)) hold(entity, "no_safe_step")
    }

    private fun beginEpisode(entity: NpcEntity) {
        val squad = entity.currentSquad()
        if (!retreat.begin(entity.position(), entity.level().gameTime, squad?.id, squad?.orderStamp)) return
        stopOwnedPath(entity)
        destination = null
        failedCover.clear()
        lastMode = null
        nextStepTick = 0
        trace(entity, "started", entity.position())
    }

    private fun move(entity: NpcEntity, point: Vec3, hidden: Boolean): Boolean {
        if (!entity.navigation.moveTo(point.x, point.y, point.z, SPEED)) return false
        ownedPath = entity.navigation.path
        destination = point
        hiding = hidden
        trace(entity, if (hidden) "seek_cover" else "withdraw", point)
        return true
    }

    private fun hold(entity: NpcEntity, reason: String) {
        retreat.hold()
        stopOwnedPath(entity)
        trace(entity, reason, entity.position())
    }

    private fun stopOwnedPath(entity: NpcEntity) {
        if (ownedPath != null && entity.navigation.path === ownedPath) entity.navigation.stop()
        ownedPath = null
    }

    private fun trace(entity: NpcEntity, mode: String, point: Vec3) {
        if (lastMode == mode) return
        lastMode = mode
        DebugFlags.log(
            LogGroup.ORDER, "[tactics] {} squad={} tick={} event=ammo_retreat mode={} origin={} position={} destination={} target={}",
            entity.uuid, entity.currentSquad()?.id, entity.level().gameTime, mode, retreat.origin, entity.position(), point, entity.target?.uuid,
        )
    }

    override fun stop(entity: NpcEntity) {
        entity.resupplying = false
        stopOwnedPath(entity)
        // Target loss is temporary when contacts are relayed by the squad. Only ammo restores the allowance.
        if (Ports.guns.inHand(entity)?.hasAmmo() != false) retreat.reset()
    }

    private companion object {
        const val STEP = 16
        const val VERTICAL = 7
        const val REPATH_TICKS = 30
        const val SPEED = 1.4
    }
}
