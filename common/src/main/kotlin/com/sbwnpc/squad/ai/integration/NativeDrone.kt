package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.ActionExecution
import com.sbwnpc.squad.ai.planning.ActionSpec
import com.sbwnpc.squad.ai.planning.ActionUpdate
import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.FactSnapshot
import com.sbwnpc.squad.ai.tactics.AttackPhase
import com.sbwnpc.squad.ai.tactics.DroneFacts
import com.sbwnpc.squad.ai.tactics.DroneHold
import com.sbwnpc.squad.ai.tactics.StrikeDuty
import com.sbwnpc.squad.ai.tactics.StrikeOptions
import com.sbwnpc.squad.ai.tactics.StrikePoint
import com.sbwnpc.squad.ai.tactics.StrikeSource
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** The level's drone operators' stations (#259): their strike facts, and the action that flies their drone. */
internal class DroneWorld(private val level: ServerLevel) {
    private val stations = HashMap<UUID, DroneStation>()

    fun station(npc: NpcEntity) = stations.getOrPut(npc.uuid) { DroneStation(npc) }

    /**
     * [npc]'s strike facts. [team] is the enemy his team fights, and [planCovers] whether his plan
     * decides his cover: a squadless man's cover is a behaviour's, whose claims then keep his drone.
     */
    fun manFacts(facts: FactSnapshot, npc: NpcEntity, team: LivingEntity?, planCovers: Boolean): FactSnapshot {
        val station = station(npc)
        var result = facts.observe(DroneFacts.AIRBORNE, station.airborne)
            .observe(DroneFacts.HOLD, hold(npc, station, planCovers))
            .observe(DroneFacts.OPTIONS, options(npc, station, team))
        station.flying?.let { result = result.observe(DroneFacts.FLYING, it.strikePoint()) }
        return result
    }

    private fun hold(npc: NpcEntity, station: DroneStation, planCovers: Boolean): DroneHold =
        when {
            npc.currentSquad()?.order == SquadOrder.RETREAT -> DroneHold.RETREAT
            npc.dronesLeft <= 0 -> DroneHold.NO_DRONES
            station.coolingDown -> DroneHold.COOLDOWN
            station.threatened() -> DroneHold.THREAT
            !planCovers && npc.holdsAny(NpcActivity.COVER, NpcActivity.EVADE, NpcActivity.PEEK, NpcActivity.HEAL) -> DroneHold.BUSY
            else -> DroneHold.NONE
        }

    /** One target per source; the focus and an enemy known at the objective only under ATTACK, all within his launch envelope. */
    private fun options(npc: NpcEntity, station: DroneStation, team: LivingEntity?): StrikeOptions {
        val found = LinkedHashMap<StrikeSource, StrikePoint>()
        fun offer(source: StrikeSource, target: StrikeTarget?) {
            if (target != null && station.inLaunchRange(target.pos) && station.strikable(target)) found[source] = target.strikePoint()
        }
        val squad = npc.currentSquad()
        if (squad?.order == SquadOrder.ATTACK) {
            squad.focusEntity?.let { (level.getEntity(it) as? LivingEntity)?.takeIf(LivingEntity::isAlive) }?.let { offer(StrikeSource.FOCUS, StrikeTarget(it.uuid, it.position())) }
            squad.objective?.let { offer(StrikeSource.OBJECTIVE, station.reportedAt(it.center, AttackPhase.OBJECTIVE_RADIUS)) }
        }
        team?.let { offer(StrikeSource.TEAM, StrikeTarget(it.uuid, it.position())) }
        // His own eyes run at their rate whether or not a source above already gave a target: they
        // report to his side for everyone else.
        offer(StrikeSource.SPOTTED, station.spotted())
        offer(StrikeSource.REPORTED, station.reported())
        return if (found.isEmpty()) StrikeOptions.NONE else StrikeOptions(found)
    }

    fun action(duty: StrikeDuty, npc: NpcEntity) = StrikeAction(duty, station(npc), ::target)

    /** The live target for [point], at the entity's last known place, or the fixed point. */
    private fun target(point: StrikePoint): StrikeTarget {
        val live = point.entity?.let { level.getEntity(it) as? LivingEntity }?.takeIf(LivingEntity::isAlive)
        return StrikeTarget(point.entity, live?.position() ?: Vec3(point.at.x, point.at.y, point.at.z))
    }

    /** Flies [npc]'s drone on a tick his plan did not. */
    fun keepFlying(npc: NpcEntity) {
        stations[npc.uuid]?.takeIf { operates(npc) }?.keepFlying()
    }

    /**
     * Loses the drone's signal of every man who is no operator on foot any more: one who boarded,
     * dug in or left the level (a drone nobody flies must not fly on). One who died has lost it
     * already ([DroneStation.onOperatorDied]); the abort finds no drone. The station of a man still
     * here is kept, so his pause after a flight survives a ride.
     */
    fun sweep() {
        stations.entries.removeIf { (id, station) ->
            val npc = level.getEntity(id) as? NpcEntity
            if (npc == null || !operates(npc)) station.abort()
            npc == null || !npc.isAlive
        }
    }

    fun clear() {
        stations.values.forEach(DroneStation::abort)
        stations.clear()
    }

    companion object {
        /** Whether [npc] is a drone operator on foot: his strike plan flies his drone (#259). */
        fun operates(npc: NpcEntity) = npc.npcClass == NpcClass.DRONE_OPERATOR && npc.vehicle == null && npc.isAlive && !npc.holds(NpcActivity.DUG_IN)

        /** The enemy most of [team]'s men fight, the nearest to [npc] among equals, or null. */
        fun teamTarget(npc: NpcEntity, team: Collection<NpcEntity>): LivingEntity? =
            team.asSequence().filter { it !== npc }.mapNotNull { it.target?.takeIf { t -> t.isAlive && SquadTeams.isHostile(npc, t) } }
                .groupingBy { it }.eachCount().entries
                .maxWithOrNull(compareBy<Map.Entry<LivingEntity, Int>> { it.value }.thenByDescending { npc.distanceToSqr(it.key) })?.key
    }
}

private fun StrikeTarget.strikePoint() = StrikePoint(entityId, Point3(pos.x, pos.y, pos.z))

/**
 * His drone strike (#259): the station launches and flies at the plan's target, and turns the
 * drone onto the next one, or home, as the plan revises the duty. The drone outlives the action:
 * a cancel ends only a walk to open sky (see [DroneStation]).
 */
internal class StrikeAction(private var duty: StrikeDuty, private val station: DroneStation, private val target: (StrikePoint) -> StrikeTarget) : ActionExecution {
    private val actor: AgentId get() = duty.actor

    override fun start(now: Long): ActionUpdate {
        DebugFlags.log(LogGroup.DRONE, "{} event=strike_started source={} target={}", actor.id, duty.source, duty.target)
        station.aim(duty.target?.let(target))
        return tick(now)
    }

    override fun tick(now: Long): ActionUpdate =
        when (station.tick()) {
            DroneStation.Outcome.RUNNING -> ActionUpdate.Running
            DroneStation.Outcome.ENDED -> ActionUpdate.Completed(emptyList())
            DroneStation.Outcome.FAILED -> ActionUpdate.Failed("no_open_sky")
        }

    override fun suspend(now: Long, reason: String) = DebugFlags.log(LogGroup.DRONE, "{} event=strike_suspended reason={}", actor.id, reason)

    override fun resume(now: Long) = Unit

    override fun cancel(now: Long, reason: String) {
        DebugFlags.log(LogGroup.DRONE, "{} event=strike_cancelled reason={} airborne={}", actor.id, reason, station.airborne)
        station.release()
    }

    override fun revise(spec: ActionSpec<*>, now: Long): Boolean {
        val next = spec.parameters as? StrikeDuty ?: return false
        // A new source for the same target is the trace's, not the drone's.
        if (next.target != duty.target) {
            DebugFlags.log(LogGroup.DRONE, "{} event=strike_revised source={} -> {} target={}", actor.id, duty.source, next.source, next.target)
            station.aim(next.target?.let(target))
        }
        duty = next
        return true
    }
}
