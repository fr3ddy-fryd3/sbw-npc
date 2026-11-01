package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.planning.ActionExecution
import com.sbwnpc.squad.ai.planning.ActionSpec
import com.sbwnpc.squad.ai.planning.ActionUpdate
import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.AgentLevel
import com.sbwnpc.squad.ai.planning.FactSnapshot
import com.sbwnpc.squad.ai.tactics.AirFacts
import com.sbwnpc.squad.ai.tactics.AirOrder
import com.sbwnpc.squad.ai.tactics.AirSupport
import com.sbwnpc.squad.ai.tactics.Aircraft
import com.sbwnpc.squad.ai.tactics.Airframe
import com.sbwnpc.squad.ai.tactics.FlightDuty
import com.sbwnpc.squad.ai.tactics.GunDuty
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.VehicleCannonAmmo
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.entity.ai.AirContact
import com.sbwnpc.squad.entity.ai.HelicopterCockpit
import com.sbwnpc.squad.entity.ai.armedAboard
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.vehicle.Helicopters
import com.sbwnpc.squad.vehicle.PilotlessDrop
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import java.util.UUID

/** One aircraft as [AirRoster] found it this update: its [aircraft] for the plan, the [squad] it flies for, and its [vehicle]. */
internal class AirUnit(val aircraft: Aircraft, val squad: UUID?, val vehicle: UUID)

/**
 * Which helicopters have NPC aircrew this update (#260): an NPC pilot in seat 0, NPC gunners at the
 * turret, or both. The aircraft's squad is its pilot's, else its gunner's.
 */
internal class AirRoster(private val level: ServerLevel) {
    private var units: Map<AgentId, AirUnit> = emptyMap()
    private var byMan: Map<UUID, AirUnit> = emptyMap()

    fun collect() {
        val crews = NpcRegistry.all(level).filter { it.isAlive && !it.isRemoved && aircrew(it) }.groupBy { requireNotNull(it.vehicle) }
        val found = crews.map { (heli, men) ->
            val pilot = men.firstOrNull { it.npcClass == NpcClass.HELICOPTER_PILOT }
            val gunners = men.filter { it.npcClass == NpcClass.HELICOPTER_GUNNER }.sortedBy { it.uuid }
            val agent = AgentId(AgentLevel.TEAM, UUID.nameUUIDFromBytes("air:${heli.uuid}".toByteArray()))
            val aircraft = Aircraft(agent, pilot?.let { AgentId(AgentLevel.NPC, it.uuid) }, gunners.map { AgentId(AgentLevel.NPC, it.uuid) })
            // A squad id naming no squad is no squad: the aircraft is planned as a squadless one, not left unplanned.
            AirUnit(aircraft, (pilot ?: gunners.first()).squadId?.takeIf { SquadManager.get(level).get(it) != null }, heli.uuid)
        }
        units = found.associateBy { it.aircraft.agent }
        byMan = found.flatMap { unit -> (listOfNotNull(unit.aircraft.pilot) + unit.aircraft.gunners).map { it.id to unit } }.toMap()
    }

    fun unit(team: AgentId): AirUnit? = units[team]
    fun unitOf(man: UUID): AirUnit? = byMan[man]

    /** [squad]'s aircraft, or the squadless ones for null, in a stable order. */
    fun aircraft(squad: UUID?): List<Aircraft> = units.values.filter { it.squad == squad }.map { it.aircraft }.sortedBy { it.agent.id }
    fun squads(): Set<UUID> = units.values.mapNotNullTo(HashSet()) { it.squad }
    fun groupOf(team: AgentId): UUID? = units[team]?.squad
    fun vehicles(): Set<UUID> = units.values.mapTo(HashSet()) { it.vehicle }

    /** Where [squad]'s first aircraft is, standing in for a squad with no ground men as its centre. */
    fun center(squad: UUID) = units.values.firstOrNull { it.squad == squad }?.let { level.getEntity(it.vehicle) }?.position()

    fun clear() {
        units = emptyMap()
        byMan = emptyMap()
    }

    companion object {
        /** Whether [npc] is a helicopter's NPC pilot, in seat 0, or its gunner, at the turret. */
        fun aircrew(npc: NpcEntity): Boolean {
            val heli = npc.vehicle?.takeIf { Helicopters.isHelicopter(it) && Ports.vehicles.isOperational(it) } ?: return false
            return when (npc.npcClass) {
                NpcClass.HELICOPTER_PILOT -> heli.firstPassenger === npc
                NpcClass.HELICOPTER_GUNNER -> Helicopters.hasTurret(heli) && Ports.vehicles.seatOf(heli, npc) == Helicopters.GUNNER_SEAT
                else -> false
            }
        }
    }
}

/**
 * The level's aircraft (#260, #261): their facts, the contact each remembers, and each pilot's
 * cockpit, kept across the plan's revisions of his flight.
 */
internal class AirWorld(private val level: ServerLevel) {
    val roster = AirRoster(level)
    private val contacts = HashMap<UUID, AirContact>()
    private val cockpits = HashMap<UUID, HelicopterCockpit>()
    private val turrets = HashMap<UUID, TurretHand>()

    private val fitness = Fitness()

    fun teamFacts(empty: FactSnapshot): FactSnapshot {
        val unit = roster.unit(empty.owner) ?: return empty
        val heli = level.getEntity(unit.vehicle) ?: return empty
        val now = empty.now
        val target = target(unit, now)
        val contact = contactOf(unit)
        val squad = unit.squad?.let { SquadManager.get(level).get(it) }
        val order = airOrder(squad?.order)
        val pilot = heli.firstPassenger
        val focusAlive = order == AirOrder.ATTACK && (squad?.focusEntity?.let(level::getEntity) as? LivingEntity)?.isAlive == true
        var result = empty
            .observe(AirFacts.AIRFRAME, if (Helicopters.hasTurret(heli)) Airframe.GUNSHIP else Airframe.TRANSPORT)
            .observe(AirFacts.FIT, fitness.fit(heli, pilot))
            .observe(AirFacts.ORDER, order)
            .observe(AirFacts.HAS_OBJECTIVE, unit.aircraft.pilot?.let { (level.getEntity(it.id) as? NpcEntity)?.homeCenter() } != null)
            .observe(AirFacts.IN_CONTACT, contact.recent(now) || focusAlive)
            .observe(AirFacts.MET_ENEMY, contact.pos != null)
        target?.let { result = result.observe(AirFacts.TARGET, it.uuid) }
        return result
    }

    /** [npc]'s own fact: whether he still sits where his aircraft's plan has him. */
    fun manFacts(facts: FactSnapshot, npc: NpcEntity): FactSnapshot = facts.observe(AirFacts.ABOARD, AirRoster.aircrew(npc))

    /**
     * The aircraft's one target (#261), by today's priority: the turret gunner's, chosen by his
     * sensor with his own noticing. His sight of it comes and goes as the aircraft turns, so while
     * he is aboard the aircraft keeps his last target for [AirSupport.CONTACT_HOLD_TICKS]: falling
     * back to the pilot's own between his sightings parked the aircraft on one man while the turret
     * was laid on another. With no gunner, it is the pilot's.
     */
    fun target(unit: AirUnit, now: Long): LivingEntity? {
        val contact = contactOf(unit)
        val gunners = unit.aircraft.gunners.mapNotNull { level.getEntity(it.id) as? NpcEntity }
        val pilot = unit.aircraft.pilot?.let { level.getEntity(it.id) as? NpcEntity }
        val chosen = gunners.firstNotNullOfOrNull(::hostileTarget)
            ?: if (gunners.isNotEmpty()) {
                contact.target?.let(level::getEntity)?.let { it as? LivingEntity }?.takeIf { contact.recent(now) && it.isAlive && SquadTeams.isHostile(gunners.first(), it) }
            } else {
                pilot?.let(::hostileTarget)
            }
        contact.observe(chosen, now)
        return chosen
    }

    private fun contactOf(unit: AirUnit) = contacts.getOrPut(unit.vehicle, ::AirContact)

    /**
     * Drops the men no longer at their aircraft's controls or gun: out of the seat, dead, or the
     * aircraft gone. A man still seated keeps his cockpit even with no plan this update (a mixed
     * operation withdrawing replans its squads the next one), so [keepAloft] holds the aircraft
     * instead of neutral controls dropping it.
     */
    fun sweep() {
        cockpits.entries.removeIf { (id, cockpit) -> (roster.unitOf(id) == null).also { gone -> if (gone) cockpit.release() } }
        turrets.entries.removeIf { (id, hand) -> (roster.unitOf(id) == null).also { gone -> if (gone) hand.release() } }
        val flying = roster.vehicles()
        contacts.keys.removeIf { it !in flying }
        fitness.keep(flying)
    }

    fun clear() {
        cockpits.values.forEach(HelicopterCockpit::release)
        turrets.values.forEach(TurretHand::release)
        cockpits.clear()
        turrets.clear()
        contacts.clear()
        fitness.keep(emptySet())
        roster.clear()
    }

    fun flight(duty: FlightDuty, npc: NpcEntity): FlightAction {
        val unit = requireNotNull(roster.unitOf(npc.uuid))
        val cockpit = cockpitOf(npc, unit)
        return FlightAction(duty, cockpit) { roster.unitOf(npc.uuid)?.let { target(it, level.gameTime) } }
    }

    /** Holds [npc]'s aircraft if no flight of his plan flew it this tick. */
    fun keepAloft(npc: NpcEntity, now: Long) {
        if (npc.npcClass != NpcClass.HELICOPTER_PILOT) return
        val unit = roster.unitOf(npc.uuid) ?: return
        cockpitOf(npc, unit).takeIf { it.flownAt != now }?.hold()
    }

    private fun cockpitOf(npc: NpcEntity, unit: AirUnit) = cockpits.getOrPut(npc.uuid) { HelicopterCockpit(npc, contactOf(unit)) }

    fun turret(npc: NpcEntity) = TurretAction(turrets.getOrPut(npc.uuid) { TurretHand(npc) })
}

/** Whether each aircraft can fight, logged when that changes. */
private class Fitness {
    private val last = HashMap<UUID, Boolean>()

    /** It can fly and someone aboard can fight; logged when that changes. */
    fun fit(heli: Entity, pilot: Entity?): Boolean {
        val flyable = Helicopters.canFly(heli)
        val armed = pilot != null && armedAboard(pilot, heli)
        if (last.put(heli.uuid, flyable && armed) != (flyable && armed)) {
            DebugFlags.log(LogGroup.HELI, "{} event=fitness fit={} flyable={} armed={}", heli.uuid, flyable && armed, flyable, armed)
        }
        return flyable && armed
    }

    fun keep(vehicles: Set<UUID>) {
        last.keys.removeIf { it !in vehicles }
    }
}

/** [man]'s own target, while it lives and is his enemy. */
private fun hostileTarget(man: NpcEntity) = man.target?.takeIf { it.isAlive && SquadTeams.isHostile(man, it) }

/** The squad's [order] as its aircraft's plan reads it, [AirOrder.NONE] with no squad. */
private fun airOrder(order: SquadOrder?) =
    when (order) {
        null -> AirOrder.NONE
        SquadOrder.ATTACK -> AirOrder.ATTACK
        SquadOrder.DEFEND -> AirOrder.DEFEND
        SquadOrder.PATROL -> AirOrder.PATROL
        SquadOrder.MOVE -> AirOrder.MOVE
        SquadOrder.RETREAT -> AirOrder.RETREAT
        else -> AirOrder.OTHER
    }

/**
 * The pilot's flight, the plan's [duty] flown on his [cockpit]. Every new mode is revised into the
 * running action; while it is suspended or gone, [AirWorld.keepAloft] holds the aircraft in a hover.
 */
internal class FlightAction(private var duty: FlightDuty, private val cockpit: HelicopterCockpit, private val target: () -> LivingEntity?) :
    ActionExecution,
    PlanExecutor {
    override fun start(now: Long): ActionUpdate {
        DebugFlags.log(LogGroup.HELI, "{} event=flight_started mode={} target={}", duty.actor.id, duty.mode, duty.target)
        return tick(now)
    }

    override fun tick(now: Long): ActionUpdate {
        cockpit.fly(duty.mode, target())
        return ActionUpdate.Running
    }

    override fun suspend(now: Long, reason: String) = DebugFlags.log(LogGroup.HELI, "{} event=flight_suspended mode={} reason={}", duty.actor.id, duty.mode, reason)

    override fun resume(now: Long) = Unit

    override fun cancel(now: Long, reason: String) = DebugFlags.log(LogGroup.HELI, "{} event=flight_cancelled mode={} reason={}", duty.actor.id, duty.mode, reason)

    override fun revise(spec: ActionSpec<*>, now: Long): Boolean {
        val next = spec.parameters as? FlightDuty ?: return false
        if (next != duty) DebugFlags.log(LogGroup.HELI, "{} event=flight_revised mode={} -> {} target={}", duty.actor.id, duty.mode, next.mode, next.target)
        duty = next
        return true
    }
}

/** A gunner's hold on his turret: the claim on his seat's gun, and the drop of an aircraft nobody flies. */
internal class TurretHand(val npc: NpcEntity) : PlanExecutor {
    val pilotless = PilotlessDrop()

    fun turret(): Entity? = npc.vehicle?.takeIf { AirRoster.aircrew(npc) }

    fun release() {
        npc.vehicle?.let { Ports.vehicles.aimAt(it, npc, null) }
        npc.release(NpcActivity.VEHICLE, this)
    }
}

/**
 * A gunner's turret (#261). SBW flies the turret onto a target by itself once it has an auto-aim
 * UUID (`AutoAimableEntity`), as for tank and IFV gunners, so this keeps that UUID on his target and
 * the right round loaded. He lays on his own live target, the same choice his aircraft's target
 * reads, so the gun never waits a replan to switch. The round is weapon mechanics, not a decision.
 */
internal class TurretAction(private val hand: TurretHand) :
    ActionExecution,
    PlanExecutor {
    override fun start(now: Long) = tick(now)

    override fun tick(now: Long): ActionUpdate {
        val npc = hand.npc
        val gunship = hand.turret() ?: return ActionUpdate.Running
        npc.claim(NpcActivity.VEHICLE, hand)
        npc.navigation.stop()
        hand.pilotless.tick(npc.level() as ServerLevel, gunship)
        val target = npc.target?.takeIf { it.isAlive && SquadTeams.isHostile(npc, it) }
        if (target != null) VehicleCannonAmmo.select(gunship, Helicopters.GUNNER_SEAT, CANNON_WEAPON, target)
        Ports.vehicles.aimAt(gunship, npc, target)
        return ActionUpdate.Running
    }

    override fun suspend(now: Long, reason: String) = Unit
    override fun resume(now: Long) = Unit
    override fun cancel(now: Long, reason: String) = Unit

    override fun revise(spec: ActionSpec<*>, now: Long) = spec.parameters is GunDuty

    private companion object {
        const val CANNON_WEAPON = 0
    }
}
