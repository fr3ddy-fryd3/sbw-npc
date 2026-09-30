package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.util.Terrain
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/**
 * In the Core tasks, for the same reason as [MortarOperatorBehaviour]: a loader keeps resupplying
 * whichever of Fight or Idle is active.
 *
 * Loads the tube from the shells it carries ([NpcEntity.mortarShellsLeft]); out of them, it goes to
 * its side's nearest Supply for more and comes back, while the operator stays with the mortar. With
 * no Supply in reach the mortar falls silent. Separate claim from the operator so both can post at
 * the same mortar.
 */
class MortarLoaderBehaviour : ExtendedBehaviour<NpcEntity>() {

    // ExtendedBehaviour defaults to a 60-tick runtime cap (confirmed via javap — see
    // SeekCoverBehaviour's doc comment for the full story); resupplying a mortar is meant to be
    // indefinite, not force-interrupted and immediately re-evaluated every 3 seconds.
    init {
        noTimeout()
    }

    private var mortar: Entity? = null
    private var nextCheckTick = 0
    private var nextMortarSearchTick = 0
    /** The squadmate currently carrying the crew's mortar, if it is being displaced. */
    private var carrier: NpcEntity? = null
    /** Picked once per mortar: the tube turns as it is laid, and a post that turned with it would
     *  have the loader walking circles round it. */
    private var postOf: kotlin.Pair<Entity, Vec3>? = null

    companion object {
        private const val SEARCH_RANGE = 30.0
        private const val MORTAR_SEARCH_INTERVAL_TICKS = 40
        private const val START_CHECK_INTERVAL_TICKS = 5
        private const val SELF_DEFENSE_RANGE_SQR = 6.0 * 6.0
        /** How close the loader keeps to an operator carrying the mortar. */
        private const val FOLLOW_DISTANCE_SQR = 5.0 * 5.0
        /** At a run, like the operator carrying the tube, or he falls behind it. */
        private const val FOLLOW_SPEED = 1.3
        /** Fetching shells: the tube is silent until he's back. */
        private const val ERRAND_SPEED = 1.3
        /** Farther than this from his post, he runs back to it. */
        private const val RUN_BACK_DISTANCE = 8.0
        /** The loader's place beside the tube. The operator takes the tube itself; two men walking
         *  to the same point shove each other off it and both keep walking back. */
        private const val POST_OFFSET = 1.8
        private const val POST_TOLERANCE = 1.2
        /** Inside a Supply's reach, so the next issue round catches him. */
        private const val SUPPLY_ARRIVE_DISTANCE = com.sbwnpc.squad.block.entity.SupplyBlockEntity.RADIUS - 3.0
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    private fun eligible(entity: NpcEntity): Boolean {
        if (entity.npcClass != NpcClass.MORTAR_LOADER) return false
        val personalThreat = entity.target?.takeIf { it.isAlive && entity.distanceToSqr(it) <= SELF_DEFENSE_RANGE_SQR }
        if (personalThreat != null) return false
        // A dug-in loader (badly hurt, took cover) must stay put like everything else that respects
        // NpcEntity.diggedIn (PM review finding — this was the one Core task that still didn't) —
        // resupplying the mortar can wait until it's healed/no longer holding.
        if (entity.diggedIn) return false
        if (entity.vehicleTransport) return false

        // The crew is displacing (MortarOperatorBehaviour broke the tube down): stay with the
        // operator rather than falling back to the squad's own movement, which would walk the
        // loader all the way to the objective and leave the mortar unsupplied behind it.
        carrier = carryingSquadmate(entity)
        if (carrier != null) return true

        val current = mortar
        if (current != null && Ports.vehicles.isOperational(current) && !MortarClaims.isLoaderClaimedByOther(current.uuid, entity.uuid)) return true

        val level = entity.level() as? ServerLevel ?: return false
        // Same every-tick-search problem as MortarOperatorBehaviour — see its MORTAR_SEARCH_INTERVAL_TICKS.
        if (entity.tickCount < nextMortarSearchTick) return false
        nextMortarSearchTick = entity.tickCount + MORTAR_SEARCH_INTERVAL_TICKS
        val found = Ports.mortars.within(
            level, AABB.ofSize(entity.position(), SEARCH_RANGE * 2, SEARCH_RANGE * 2, SEARCH_RANGE * 2)
        ).firstOrNull {
            Ports.vehicles.isOperational(it) && entity.distanceToSqr(it) <= SEARCH_RANGE * SEARCH_RANGE &&
                !MortarClaims.isLoaderClaimedByOther(it.uuid, entity.uuid)
        } ?: return false

        MortarClaims.claimLoader(found.uuid, entity.uuid)
        mortar = found
        // We drive firing ourselves through MortarOperatorBehaviour's own aim/cooldown checks, so a
        // shell going in below must not trigger an uncontrolled shot every time we resupply.
        Ports.mortars.takeControl(found)
        return true
    }

    private val startCheck = StartCheckThrottle(START_CHECK_INTERVAL_TICKS)
    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean =
        startCheck.check(entity) { eligible(entity) }
    override fun shouldKeepRunning(entity: NpcEntity): Boolean = eligible(entity)

    /** A squadmate with the mortar on its back. Only ever a handful of members to check, and only
     *  when there is no mortar in range to claim. */
    private fun carryingSquadmate(entity: NpcEntity): NpcEntity? {
        val level = entity.level() as? ServerLevel ?: return null
        val members = entity.currentSquad()?.members ?: return null
        return members.asSequence()
            .mapNotNull { level.getEntity(it) as? NpcEntity }
            .firstOrNull { it !== entity && it.isAlive && it.carryingMortar }
    }

    override fun stop(entity: NpcEntity) {
        startCheck.reset()
        MortarClaims.releaseLoader(entity.uuid)
        mortar = null
        carrier = null
        postOf = null
        entity.servingMortar = false
        entity.mortarErrand = false
    }

    /** Beside the tube, square to where it points. */
    private fun post(entity: NpcEntity, m: Entity): Vec3 {
        postOf?.takeIf { it.first === m }?.let { return it.second }
        val yaw = Math.toRadians(m.yRot.toDouble())
        val x = m.x + Math.cos(yaw) * POST_OFFSET
        val z = m.z + Math.sin(yaw) * POST_OFFSET
        val post = Terrain.standableOrNull(entity.level(), x, m.y + 1.0, z) ?: m.position()
        postOf = kotlin.Pair(m, post)
        return post
    }

    override fun tick(entity: NpcEntity) {
        entity.mortarErrand = false
        val with = carrier?.takeIf { it.isAlive && it.carryingMortar }
        if (with != null) {
            entity.mortarErrand = true
            entity.servingMortar = false
            if (entity.distanceToSqr(with) > FOLLOW_DISTANCE_SQR) {
                entity.navigateTo(with.x, with.y, with.z, FOLLOW_SPEED)
            } else {
                entity.navigation.stop()
            }
            return
        }
        val m = mortar ?: return
        if (entity.mortarShellsLeft <= 0) {
            val supply = entity.nearestSupply()
            if (supply != null) {
                // Still the crew's errand: squad orders keep off him until he's back at the tube.
                entity.servingMortar = true
                if (entity.position().closerThan(supply, SUPPLY_ARRIVE_DISTANCE)) entity.navigation.stop()
                else entity.navigateTo(supply, ERRAND_SPEED)
                return
            }
        }
        val post = post(entity, m)
        if (entity.position().distanceTo(post) > POST_TOLERANCE) {
            entity.servingMortar = false
            entity.mortarErrand = true
            entity.navigateTo(post, if (entity.position().distanceTo(post) > RUN_BACK_DISTANCE) ERRAND_SPEED else 1.0)
            return
        }
        entity.servingMortar = true
        entity.navigation.stop()
        entity.lookControl.setLookAt(m.x, m.y + 0.5, m.z)

        if (entity.tickCount < nextCheckTick) return
        nextCheckTick = entity.tickCount + 60

        // Only touch it when actually empty; re-loading a full tube every check just spams SBW's
        // "exceeding max stack size" clamp warning for nothing.
        if (!Ports.mortars.hasShell(m) && entity.mortarShellsLeft > 0) {
            Ports.mortars.loadShell(m)
            entity.mortarShellsLeft--
        }
    }
}
