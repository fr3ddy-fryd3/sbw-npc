package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.planning.ActionExecution
import com.sbwnpc.squad.ai.planning.ActionSpec
import com.sbwnpc.squad.ai.planning.ActionUpdate
import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.AgentLevel
import com.sbwnpc.squad.ai.planning.FactSnapshot
import com.sbwnpc.squad.ai.tactics.DriveDuty
import com.sbwnpc.squad.ai.tactics.DriveMode
import com.sbwnpc.squad.ai.tactics.EscortedSquads
import com.sbwnpc.squad.ai.tactics.Tank
import com.sbwnpc.squad.ai.tactics.TankFacts
import com.sbwnpc.squad.ai.tactics.TankSupport
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.entity.ai.VehicleTransportClaims
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.FireSupport
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadManager
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** One tank as [TankRoster] found it this update: its [tank] for the plan, the [squad] it drives for, and its [vehicle]. */
internal class TankUnit(val tank: Tank, val squad: UUID, val vehicle: UUID)

/**
 * Which tanks have an NPC driver this update (#185): a [NpcClass.TANK_CREW] seated in his own
 * assigned vehicle and holding its wheel ([VehicleTransportClaims.driverOf]), the man the drive
 * executor steers for. A squadless tank is not planned: it drives as before.
 */
internal class TankRoster(private val level: ServerLevel) {
    private var units: Map<AgentId, TankUnit> = emptyMap()
    private var byMan: Map<UUID, TankUnit> = emptyMap()

    fun collect() {
        val manager = SquadManager.get(level)
        val found = NpcRegistry.all(level).filter { it.isAlive && !it.isRemoved && atWheel(it) }.mapNotNull { driver ->
            val squad = driver.squadId?.takeIf { manager.get(it) != null } ?: return@mapNotNull null
            val vehicle = requireNotNull(driver.vehicle).uuid
            val agent = AgentId(AgentLevel.TEAM, UUID.nameUUIDFromBytes("tank:$vehicle".toByteArray()))
            TankUnit(Tank(agent, AgentId(AgentLevel.NPC, driver.uuid)), squad, vehicle)
        }
        val next = found.associateBy { it.tank.agent }
        if (next.keys != units.keys) DebugFlags.log(LogGroup.VEHICLE, "[tank] event=roster tanks={}", found.map { "${it.vehicle}:${it.squad}" })
        units = next
        byMan = found.associateBy { it.tank.driver.id }
    }

    fun unit(team: AgentId): TankUnit? = units[team]
    fun unitOf(man: UUID): TankUnit? = byMan[man]

    /** [squad]'s tanks in a stable order. */
    fun tanks(squad: UUID): List<Tank> = units.values.filter { it.squad == squad }.map { it.tank }.sortedBy { it.agent.id }
    fun squads(): Set<UUID> = units.values.mapTo(HashSet()) { it.squad }
    fun groupOf(team: AgentId): UUID? = units[team]?.squad

    /** Where [squad]'s first tank is, standing in for a squad with no ground men as its centre. */
    fun center(squad: UUID) = units.values.firstOrNull { it.squad == squad }?.let { level.getEntity(it.vehicle) }?.position()

    fun clear() {
        units = emptyMap()
        byMan = emptyMap()
    }

    companion object {
        /** Whether [npc] is a tank crewman seated in his own operational tank and holding its wheel. */
        fun atWheel(npc: NpcEntity): Boolean {
            if (npc.npcClass != NpcClass.TANK_CREW) return false
            val vehicle = npc.vehicle ?: return false
            return vehicle.uuid == npc.assignedVehicleId && Ports.vehicles.isOperational(vehicle) && VehicleTransportClaims.driverOf(vehicle.uuid) == npc.uuid
        }
    }
}

/**
 * The level's tanks (#185): their facts, which commands have met the enemy, and each driver's
 * duty, which the drive executor reads every tick through [holdsPace].
 */
internal class TankWorld(private val level: ServerLevel) {
    val roster = TankRoster(level)

    /** Commands whose squads have made contact: the escort is over for the rest of the command. */
    private val engaged = HashSet<UUID>()
    private val duties = HashMap<UUID, DriveDuty>()

    fun teamFacts(empty: FactSnapshot): FactSnapshot {
        val unit = roster.unit(empty.owner) ?: return empty
        val squad = SquadManager.get(level).get(unit.squad) ?: return empty
        val partners = FireSupport.infantry(level, squad)
        var result = empty.observe(TankFacts.ENGAGED, engaged(squad, partners))
        if (partners.isNotEmpty()) result = result.observe(TankFacts.ESCORTED, EscortedSquads(partners.map { it.id }))
        return result
    }

    fun manFacts(facts: FactSnapshot, npc: NpcEntity): FactSnapshot = facts.observe(TankFacts.AT_WHEEL, TankRoster.atWheel(npc))

    /**
     * Whether [squad]'s command has met the enemy, latched once it has. The plan reads it to choose
     * the escort; [holdsPace] checks it again every tick, and that check is the one that lets the
     * tank go the tick contact is made, not at the next replan.
     */
    private fun engaged(squad: Squad, partners: List<Squad>): Boolean {
        val command = squad.command?.id ?: return false
        if (command in engaged) return true
        if ((partners + squad).any { it.tactics.snapshot?.contacts?.isNotEmpty() == true }) {
            engaged += command
            return true
        }
        return false
    }

    /**
     * Whether [driver], keeping the pace his tank's plan gave him, lets the engine idle this tick:
     * his tank at [vehicle] is more than [TankSupport.ESCORT_LEAD] blocks nearer [destination] than
     * the leading man of the infantry he escorts. With no duty, or [DriveMode.FREE], he drives.
     */
    fun holdsPace(driver: NpcEntity, vehicle: Vec3, destination: Vec3): Boolean {
        val duty = duties[driver.uuid]?.takeIf { it.mode == DriveMode.PACE } ?: return false
        val squad = driver.currentSquad() ?: return false
        // The infantry still attacking under the command now: one that left it since the plan stops counting at once.
        val partners = FireSupport.infantry(level, squad).filter { it.id in duty.escorted }
        if (partners.isEmpty() || engaged(squad, partners)) return false
        val lead = partners.flatMap { it.members }.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive }
            .minOfOrNull { horizontal(it.position(), destination) } ?: return false
        return horizontal(vehicle, destination) < lead - TankSupport.ESCORT_LEAD
    }

    fun drive(duty: DriveDuty) = DriveAction(duty, duties)

    /** Drops the duties of men no longer at a planned tank's wheel, and the latches of commands no squad holds. */
    fun sweep() {
        duties.keys.removeIf { roster.unitOf(it) == null }
        val commands = SquadManager.get(level).all().mapNotNullTo(HashSet()) { it.command?.id }
        engaged.retainAll(commands)
    }

    fun clear() {
        duties.clear()
        engaged.clear()
        roster.clear()
    }
}

private fun horizontal(a: Vec3, b: Vec3) = Math.hypot(a.x - b.x, a.z - b.z)

/**
 * The driver's driving, the plan's [duty] recorded where the drive executor reads it. The steering
 * itself stays with `VehicleTransportBehaviour`, as a boarded seat does (#255).
 */
internal class DriveAction(private var duty: DriveDuty, private val duties: MutableMap<UUID, DriveDuty>) :
    ActionExecution,
    PlanExecutor {
    override fun start(now: Long): ActionUpdate {
        DebugFlags.log(LogGroup.VEHICLE, "{} event=tank_drive_started mode={} escorted={}", duty.actor.id, duty.mode, duty.escorted)
        duties[duty.actor.id] = duty
        return ActionUpdate.Running
    }

    override fun tick(now: Long): ActionUpdate {
        duties[duty.actor.id] = duty
        return ActionUpdate.Running
    }

    override fun suspend(now: Long, reason: String) {
        duties.remove(duty.actor.id)
    }

    override fun resume(now: Long) {
        duties[duty.actor.id] = duty
    }

    override fun cancel(now: Long, reason: String) {
        DebugFlags.log(LogGroup.VEHICLE, "{} event=tank_drive_cancelled mode={} reason={}", duty.actor.id, duty.mode, reason)
        duties.remove(duty.actor.id)
    }

    override fun revise(spec: ActionSpec<*>, now: Long): Boolean {
        val next = spec.parameters as? DriveDuty ?: return false
        if (next != duty) DebugFlags.log(LogGroup.VEHICLE, "{} event=tank_drive_revised mode={} -> {} escorted={}", duty.actor.id, duty.mode, next.mode, next.escorted)
        duty = next
        duties[duty.actor.id] = duty
        return true
    }
}
