package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.ActionEvidence
import com.sbwnpc.squad.ai.planning.ActionExecution
import com.sbwnpc.squad.ai.planning.ActionUpdate
import com.sbwnpc.squad.ai.planning.FactSnapshot
import com.sbwnpc.squad.ai.tactics.Resupply
import com.sbwnpc.squad.ai.tactics.SupplyCandidate
import com.sbwnpc.squad.ai.tactics.SupplyFacts
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.AmmoRetreat
import com.sbwnpc.squad.entity.ai.MandatoryRule
import com.sbwnpc.squad.entity.ai.MandatoryRuleOwner
import com.sbwnpc.squad.entity.ai.MandatoryRules
import com.sbwnpc.squad.squad.Squad
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * The ammunition of a level's men (#189): who of each squad goes to resupply ([Resupply.pick], at
 * most half its living men at once, owner 09.11), observed for the plan as [SupplyFacts.TRIP], and the
 * two executors the plan's methods run: the trip to the Supply, and the withdrawal of a man left
 * with an empty gun in a fight, as ResupplyBehaviour and OutOfAmmoBehaviour did before.
 *
 * The mandatory rules RESUPPLY and OUT_OF_AMMO still rank them against the rules above (a grenade,
 * a drone): a higher one pauses the executor, and both keep [NpcActivity.RESUPPLY] while they run,
 * which the squad order, the transport and the attack's strength read.
 */
internal class SupplyCare(private val level: ServerLevel) {
    private val squads = HashMap<UUID, SquadTrips>()
    private val lone = HashMap<UUID, SquadTrips>()

    // A Supply he could not reach is off for a while: picked again at once, he held a place forever.
    private val unreachable = HashMap<UUID, Long>()

    // Men whose trip executor runs now: topped up, a man still walking holds his place until it ends,
    // or the next man set off while he was still counted away.
    private val running = HashSet<UUID>()

    // A man's empty-gun episode outlives each withdrawal: a target lost and found again starts a new
    // executor, and a fresh allowance each time let him walk off without a limit.
    private val retreats = HashMap<UUID, AmmoRetreat>()

    /** [npc]'s ammunition facts at [now]: his trip, if the allocation gave him one, and an empty gun. */
    fun observe(facts: FactSnapshot, npc: NpcEntity, now: Long): FactSnapshot {
        var result = facts
        if (empty(npc)) result = result.observe(SupplyFacts.EMPTY, true)
        val squad = npc.currentSquad()
        val trips = if (squad != null) squads.getOrPut(squad.id, ::SquadTrips) else lone.getOrPut(npc.uuid, ::SquadTrips)
        if (now >= trips.nextScan) {
            trips.nextScan = now + SCAN_TICKS
            allocate(trips, squad, npc, now)
        }
        if (npc.uuid !in trips.men) return result
        val supply = npc.nearestSupply() ?: return result
        return result.observe(SupplyFacts.TRIP, supply.point())
    }

    /** Whether [npc] holds a trip now: the fire task walks a man all but out only on one. */
    fun onTrip(npc: NpcEntity): Boolean = npc.uuid in ((npc.currentSquad()?.let { squads[it.id] } ?: lone[npc.uuid])?.men ?: emptySet<UUID>())

    /** Whether [npc] is squadless and needs his own root for a trip or a withdrawal. */
    fun needsLoneRoot(npc: NpcEntity): Boolean = npc.currentSquad() == null && (candidate(npc) || empty(npc))

    fun action(npc: NpcEntity, supply: Point3): ActionExecution = Trip(npc, supply)

    fun withdrawal(npc: NpcEntity): ActionExecution = AmmoWithdraw(level, npc, retreats.getOrPut(npc.uuid, ::AmmoRetreat))

    /** Keeps only squads and men still about. */
    fun keep(squad: (UUID) -> Boolean, man: (UUID) -> Boolean) {
        squads.keys.removeIf { !squad(it) }
        lone.keys.removeIf { !man(it) }
        running.removeIf { !man(it) }
        // Not by his agent: between two targets he has none, and the episode must survive that.
        retreats.keys.removeIf { (level.getEntity(it) as? NpcEntity)?.isAlive != true }
    }

    fun clear() {
        squads.clear()
        lone.clear()
        unreachable.clear()
        running.clear()
        retreats.clear()
    }

    private fun allocate(trips: SquadTrips, squad: Squad?, npc: NpcEntity, now: Long) {
        unreachable.entries.removeIf { it.value <= now }
        val men = if (squad == null) listOf(npc) else squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive && it.squadId == squad.id }
        // Ordered to a Supply as a squad (the attack's strength, AttackStrength), it walks there whole.
        val candidates = if (squad?.resupplyAttack != null) {
            emptyList()
        } else {
            men.filter { candidate(it) || (it.uuid in running && it.uuid in trips.men) }.mapNotNull { man ->
                val supply = man.nearestSupply() ?: return@mapNotNull null
                SupplyCandidate(man.uuid, empty(man), man.ammoFraction(), man.position().distanceTo(supply))
            }
        }
        val picked = Resupply.pick(trips.men, candidates, men.size, squadless = squad == null)
        if (picked != trips.men) {
            (picked - trips.men).forEach { id -> (level.getEntity(id) as? NpcEntity)?.let { com.sbwnpc.squad.squad.SquadReports.goingToResupply(it) } }
            DebugFlags.log(LogGroup.SUPPLY, "[htn] squad={} event=resupply_allocated men={} cap={} candidates={} living={}", squad?.id ?: npc.uuid, picked, Resupply.cap(men.size), candidates.size, men.size)
        }
        trips.men = picked
    }

    // Who needs a trip, as before: empty in a fight, low with nobody to fight, all but out while firing.
    private fun candidate(npc: NpcEntity): Boolean {
        if (vetoed(npc) || npc.uuid in unreachable || npc.nearestSupply() == null) return false
        return empty(npc) || (npc.target == null && npc.wantsResupply() && npc.retreatPoint() == null) || (npc.target != null && npc.allButOut())
    }

    private fun drop(npc: NpcEntity, now: Long, failed: Boolean) {
        val trips = npc.currentSquad()?.let { squads[it.id] } ?: lone[npc.uuid] ?: return
        trips.men = trips.men - npc.uuid
        trips.nextScan = now
        if (failed) unreachable[npc.uuid] = now + UNREACHABLE_TICKS
    }

    /**
     * One man's trip: the walk to the Supply, then standing in its reach until the block has topped
     * him up. A minute without coming nearer gives it up; that Supply is then off for him a while.
     */
    private inner class Trip(private val npc: NpcEntity, private val supply: Point3) :
        ActionExecution,
        PlanExecutor,
        MandatoryRuleOwner {
        override val rule = MandatoryRule.RESUPPLY
        private var closest = Double.MAX_VALUE
        private var progressAt = 0L
        private var nextRepath = 0L

        override fun start(now: Long): ActionUpdate {
            progressAt = now
            running += npc.uuid
            FiringSpots.release(npc.uuid)
            npc.clearAlert()
            DebugFlags.log(LogGroup.SUPPLY, "[htn] actor={} event=resupply_started supply={} ammo={}", npc.uuid, supply, "%.2f".format(npc.ammoFraction()))
            return tick(now)
        }

        override fun tick(now: Long): ActionUpdate {
            if (npc.ammoFraction() >= TOPPED_UP_FRACTION) return end(now, ActionUpdate.Completed(listOf(ActionEvidence("topped_up", now, "%.2f".format(npc.ammoFraction())))), failed = false)
            val here = npc.nearestSupply() ?: return end(now, ActionUpdate.Failed("supply_gone"), failed = false)
            if (!MandatoryRules.may(MandatoryRule.RESUPPLY, npc)) return ActionUpdate.Running
            npc.claim(NpcActivity.RESUPPLY, this)
            val distance = npc.position().distanceTo(here)
            if (distance <= ARRIVE_DISTANCE) {
                npc.navigation.stop()
                progressAt = now
                return ActionUpdate.Running
            }
            if (distance < closest - PROGRESS_STEP) {
                closest = distance
                progressAt = now
            }
            if (now - progressAt >= STALL_TICKS) {
                DebugFlags.log(LogGroup.SUPPLY, "[htn] actor={} event=resupply_unreachable supply={} distance={}", npc.uuid, here, distance)
                return end(now, ActionUpdate.Failed("supply_unreachable"), failed = true)
            }
            if (now >= nextRepath || npc.navigation.isDone) {
                nextRepath = now + REPATH_TICKS
                npc.navigateTo(here, if (empty(npc)) RUN_SPEED else 1.0)
            }
            return ActionUpdate.Running
        }

        private fun end(now: Long, update: ActionUpdate, failed: Boolean): ActionUpdate {
            stop()
            running -= npc.uuid
            drop(npc, now, failed)
            DebugFlags.log(LogGroup.SUPPLY, "[htn] actor={} event=resupply_ended result={} ammo={}", npc.uuid, update, "%.2f".format(npc.ammoFraction()))
            return update
        }

        private fun stop() {
            npc.navigation.stop()
            npc.release(NpcActivity.RESUPPLY, this)
        }

        override fun suspend(now: Long, reason: String) = stop()

        override fun resume(now: Long) = Unit

        override fun cancel(now: Long, reason: String) {
            stop()
            running -= npc.uuid
        }
    }

    /** One squad's men on a trip now, or a squadless man's own, and when it is looked at again. */
    private class SquadTrips {
        var men: Set<UUID> = emptySet()
        var nextScan = Long.MIN_VALUE
    }

    private companion object {
        const val SCAN_TICKS = 20L
        const val REPATH_TICKS = 30L
        const val STALL_TICKS = 1200L
        const val UNREACHABLE_TICKS = 600L
        const val PROGRESS_STEP = 1.0
        const val RUN_SPEED = 1.4

        /** Well inside the block's reach, so the next issue round catches it. */
        const val ARRIVE_DISTANCE = SupplyBlockEntity.RADIUS - 3.0

        /** The reserve comes back whole, the magazine may still be half-spent. */
        const val TOPPED_UP_FRACTION = 0.8
    }
}

// Riding, crewing, flying, dug in or treating, he is not his own to send.
private fun vetoed(npc: NpcEntity) = npc.isPassenger || npc.busyWithRole() || npc.holdsAny(NpcActivity.VEHICLE, NpcActivity.MORTAR_CREW, NpcActivity.DRONE, NpcActivity.DUG_IN, NpcActivity.HEAL)

private fun empty(npc: NpcEntity): Boolean {
    if (npc.isPassenger || npc.holdsAny(NpcActivity.VEHICLE, NpcActivity.MORTAR_CREW, NpcActivity.DRONE)) return false
    if (npc.target?.isAlive != true) return false
    return Ports.guns.inHand(npc)?.hasAmmo() == false
}
