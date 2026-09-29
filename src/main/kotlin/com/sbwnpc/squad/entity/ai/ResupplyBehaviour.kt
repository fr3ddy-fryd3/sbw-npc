package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/**
 * In the Idle activity: low on ammunition, no one to fight and a Supply in reach — go and stand by
 * it until the block has topped the NPC up ([SupplyBlockEntity]), then let the squad order take it
 * back to whatever it was doing. [NpcEntity.resupplying] keeps [SquadOrderBehaviour] and
 * [InvestigateBehaviour] off it meanwhile.
 *
 * A target ends it at once: the Fight activity takes over, and if the NPC is all but out by then
 * [GunAttackBehaviour] falls back on the Supply itself, firing.
 */
class ResupplyBehaviour : ExtendedBehaviour<NpcEntity>() {

    init {
        noTimeout()
    }

    private var startedTick = 0
    private var nextRepathTick = 0

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    private fun free(entity: NpcEntity): Boolean =
        entity.target == null && !entity.isPassenger && !entity.busyWithRole() && !entity.diggedIn &&
            !entity.combatLockedByCover() && !entity.evadingGrenade() && entity.retreatPoint() == null

    private val startCheck = StartCheckThrottle(START_CHECK_INTERVAL_TICKS)

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean =
        startCheck.check(entity) { free(entity) && entity.wantsResupply() }

    override fun shouldKeepRunning(entity: NpcEntity): Boolean =
        free(entity) && entity.ammoFraction() < TOPPED_UP_FRACTION && entity.nearestSupply() != null &&
            entity.tickCount - startedTick < GIVE_UP_TICKS

    override fun start(entity: NpcEntity) {
        entity.resupplying = true
        entity.clearAlert()
        startedTick = entity.tickCount
        nextRepathTick = 0
        FiringSpots.release(entity.uuid)
        DebugFlags.log("[supply-debug] {} ({}) low on ammo ({}), going to {}",
            entity.uuid, entity.npcClass, "%.2f".format(entity.ammoFraction()), entity.nearestSupply())
    }

    override fun tick(entity: NpcEntity) {
        val supply = entity.nearestSupply() ?: return
        if (entity.position().closerThan(supply, ARRIVE_DISTANCE)) {
            entity.navigation.stop()
            return
        }
        if (entity.tickCount < nextRepathTick && !entity.navigation.isDone) return
        nextRepathTick = entity.tickCount + REPATH_TICKS
        entity.navigateTo(supply, 1.0)
    }

    override fun stop(entity: NpcEntity) {
        entity.resupplying = false
        DebugFlags.log("[supply-debug] {} ({}) done resupplying ({})",
            entity.uuid, entity.npcClass, "%.2f".format(entity.ammoFraction()))
    }

    private companion object {
        const val START_CHECK_INTERVAL_TICKS = 20
        const val REPATH_TICKS = 40
        /** Well inside the block's reach, so the next issue round catches it. */
        const val ARRIVE_DISTANCE = SupplyBlockEntity.RADIUS - 3.0
        /** The reserve comes back whole, the magazine may still be half-spent. */
        const val TOPPED_UP_FRACTION = 0.8
        const val GIVE_UP_TICKS = 20 * 90
    }
}
