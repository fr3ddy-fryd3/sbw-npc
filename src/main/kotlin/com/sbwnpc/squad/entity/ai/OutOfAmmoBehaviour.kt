package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.util.DefaultRandomPos
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/**
 * Nothing left to shoot with: get away from the enemy.
 *
 * An empty gun takes the NPC out of [GunAttackBehaviour], but its target stays, and a target keeps
 * it out of its squad orders too — so it stood where it ran dry, facing the enemy, doing nothing
 * until it was shot. Now it runs, a stretch at a time, away from whoever it was fighting, until it
 * has lost sight of them and the target lapses — or, with a Supply of its side in reach, to that.
 */
class OutOfAmmoBehaviour : ExtendedBehaviour<NpcEntity>() {

    init {
        noTimeout()
    }

    private var nextStepTick = 0

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
        FiringSpots.release(entity.uuid)
        DebugFlags.log("[ammo-debug] {} ({}) out of ammo, running from {}", entity.uuid, entity.npcClass, entity.target?.name?.string)
    }

    override fun tick(entity: NpcEntity) {
        val target = entity.target ?: return
        if (entity.tickCount < nextStepTick && !entity.navigation.isDone) return
        nextStepTick = entity.tickCount + REPATH_TICKS
        // A Supply in reach is where the ammunition is: run there rather than just away.
        entity.nearestSupply()?.let {
            entity.navigation.moveTo(it.x, it.y, it.z, SPEED)
            return
        }
        val away = DefaultRandomPos.getPosAway(entity, STEP, VERTICAL, target.position()) ?: return
        entity.navigation.moveTo(away.x, away.y, away.z, SPEED)
    }

    private companion object {
        const val STEP = 16
        const val VERTICAL = 7
        const val REPATH_TICKS = 30
        const val SPEED = 1.4
    }
}
