package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.coordination.*
import com.sbwnpc.squad.ai.diagnostics.DecisionTrace
import com.sbwnpc.squad.ai.planning.*
import com.sbwnpc.squad.ai.tactics.*
import com.sbwnpc.squad.combat.*
import com.sbwnpc.squad.combat.tactics.*
import com.sbwnpc.squad.compat.VersionCompat
import com.sbwnpc.squad.entity.Cap
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.entity.ai.CoverMechanics
import com.sbwnpc.squad.entity.ai.SquadTransit
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.*
import com.sbwnpc.squad.team.Diplomacy
import com.sbwnpc.squad.util.Terrain
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import com.sbwnpc.squad.ai.tactics.AirSupport as AirPlan
import com.sbwnpc.squad.ai.tactics.FireSupport as CrewPlan

/** Production owner: world observation, domain evaluation and native adapters have separate boundaries. */
object NativeTactics {
    /** Farthest fire-post anchor whose local probes still fall inside the position query's reach. */
    private const val FIRE_POST_REACH = TacticalPositions.SEARCH_REACH - CombatPosition.LOCAL_MOVE_RADIUS

    /** Seen enemies this close to an attacked area keep it from counting as held. */
    private const val AREA_THREAT_RADIUS = 128.0

    /** Roles whose long-range weapons fight from a height post, see [MemberDuties]. */
    private val HEIGHT_ROLES = setOf(NpcClass.MACHINE_GUNNER, NpcClass.SNIPER)

    /** The longest a mover fires from a leg's post before the next rush, 3 s. */
    private const val FIRE_PAUSE_TICKS = 60L

    /** With nothing to fire at, he rushes on after half a second. */
    private const val NO_TARGET_PAUSE_TICKS = 10L

    /** Duty rules whose movers advance by legs in a fight, see [PositionIntent.legs]. */
    private val LEG_RULES = setOf("mover", "fire_approach", "retreat_bound")

    /** A bounding half that has not finished a leg each by then hands over anyway, 15 s. */
    private const val BOUND_TURN_TICKS = 300L

    /**
     * The same for a retreat, 5 s: the old fighting withdrawal swapped its halves within 3 s, and a
     * man who found no post back held his whole half where it was ordered away from.
     */
    private const val RETREAT_BOUND_TURN_TICKS = 100L

    /** A rush waits this long at most, after the leg before it, for the men covering it to fire, 3 s. */
    private const val SUPPORT_FALLBACK_TICKS = 60L

    /** How long a failed post search is remembered, 10 s. */
    private const val FAILURE_MEMORY_TICKS = 200L

    /** A squad smaller than this is never counted as scattered. */
    private const val REGROUP_MIN_MEN = 4

    /** Fewer men than this far from the squad never count it as scattered. */
    private const val REGROUP_MIN_SCATTERED = 2

    /** How long after its incoming fire lapsed a squad still counts as under fire, see [CombatSituation.underFire]. */
    private const val UNDER_FIRE_TICKS = 200L

    /**
     * How long a retreating squad's close contact outlasts the last sight of it, 2 s. Taken as it
     * came and went, every man's task flipped between a bound and a run, and was planned again.
     */
    private const val RETREAT_CONTACT_TICKS = 40L

    /**
     * How long after the focus was last seen well above the squad it still counts as above, see
     * [CombatSituation.higherEnemy].
     *
     * In-game 24.10 the focus crossed the height line every second or so as the men moved and the
     * target changed, and the squad flipped between bounding and gaining height with every cross:
     * neither manner lasted long enough for a single swap of halves.
     */
    private const val ENEMY_ABOVE_TICKS = 200L

    /**
     * A regroup goes on this long at most, 20 s. A man who found no post round the rally, left to
     * his order, kept it from gathering for good, and a squad still scattered regrouped to the end
     * of the fight.
     */
    private const val REGROUP_MAX_TICKS = 400L

    /** How long after a regroup ended the squad does not regroup again, 30 s. */
    private const val REGROUP_COOLDOWN_TICKS = 600L

    /** How far behind its centre, away from the enemy, a regrouping squad gathers. */
    private const val REGROUP_PULLBACK = 12.0

    /** A flank found blocked is not tried again for this long, 30 s. */
    private const val FLANK_BLOCK_TICKS = 600L

    /** Post searches failed in a row on the arc that count a flanker as stuck there. */
    private const val FLANK_BLOCK_FAILURES = 3

    /** Fire this recent from the firing half covers a rush, 2 s. */
    private const val SUPPORT_FIRE_TICKS = 40
    private const val EYE_HEIGHT = 1.6

    private val worlds = HashMap<ServerLevel, World>()

    /** Test hook: called with the level of every agent whose plan search finished, to measure how often each level replans. */
    internal var planResultObserver: ((AgentLevel) -> Unit)? = null
    private fun world(npc: NpcEntity) = worlds.getOrPut(npc.level() as ServerLevel) { World(npc.level() as ServerLevel) }
    fun refresh(npc: NpcEntity) {
        if (planned(npc) || supports(npc)) world(npc).refresh(npc)
    }

    /** A mortarman, aircrew or drone operator: a support role his own plan runs, in a squad or not. */
    private fun supports(npc: NpcEntity) = crewed(npc) || aircrew(npc) || operatesDrone(npc)

    /** Whether [npc] is a drone operator on foot: his plan flies his drone, a squadless one's in a root of its own (#259). */
    fun operatesDrone(npc: NpcEntity) = DroneWorld.operates(npc)

    /** Flies [npc]'s drone on a tick his plan did not (#259). */
    fun keepFlying(npc: NpcEntity) = world(npc).keepFlying(npc)

    /** Whether [npc] flies a helicopter or works its turret: his aircraft's plan runs him (#260, #261). */
    fun aircrew(npc: NpcEntity) = AirRoster.aircrew(npc)

    /** Whether [npc] is a mortarman on foot: his crew's plan, not his squad's ground plan, runs his tube (#258). */
    fun crewed(npc: NpcEntity) = npc.vehicle == null && npc.npcClass == NpcClass.MORTAR

    /** Whether [npc] is one his squad plans for, a ground role on foot in a squad: he gets a full NPC agent. */
    fun planned(npc: NpcEntity) = npc.vehicle == null && npc.npcClass in GROUND && npc.currentSquad() != null
    fun tick(npc: NpcEntity) = world(npc).tick(npc)

    /** Holds [npc]'s aircraft in a hover on a tick his plan did not fly it (#260). */
    fun keepAloft(npc: NpcEntity) = world(npc).keepAloft(npc)
    fun assignment(npc: NpcEntity): NativeAssignment? {
        if (!planned(npc)) return null
        refresh(npc)
        return world(npc).actions[npc.uuid]?.takeIf { it.intent.commandsMovement }?.assignment
    }

    /** The team agent [npc] is planned in, or null; GameTests read it to see a team kept across a strike (#259). */
    internal fun teamOf(npc: NpcEntity): AgentId? = worlds[npc.level()]?.teamOf(AgentId(AgentLevel.NPC, npc.uuid))

    /** How many of [members] hold a slot [FireSlotSight] found no point for that sees the defence; GameTests read it. */
    internal fun slotsWithoutSight(level: ServerLevel, members: Collection<UUID>): Int = worlds[level]?.slotsWithoutSight(members) ?: 0

    fun ownsLook(npc: NpcEntity): Boolean = worlds[npc.level()]?.actions?.get(npc.uuid)?.ownsLook == true
    fun ownsMovement(npc: NpcEntity): Boolean {
        tick(npc)
        return movable(npc) && (assignment(npc) != null || world(npc).engages(npc))
    }

    /**
     * [npc] fights with no plan of his own (a role outside [GROUND], no squad): an engage-only
     * agent plans his moves in the fight while he keeps reporting this (#251 step B). His gun
     * behaviour reports it every tick it fights, as it reports what it sees.
     */
    fun engaging(npc: NpcEntity) {
        if (npc.vehicle == null) world(npc).engaging(npc)
    }

    /** Whether [npc] has an NPC agent of either kind, full or engage-only: what ticks it. */
    fun hasAgent(npc: NpcEntity): Boolean = worlds[npc.level()]?.agents?.containsKey(AgentId(AgentLevel.NPC, npc.uuid)) == true

    /** A man of [npc]'s squad heard a noise at [at]: his squad decides whether anyone goes to look (#254). */
    fun heard(npc: NpcEntity, at: Vec3) {
        val squad = npc.squadId ?: return
        world(npc).heard(squad, at.point(), npc.level().gameTime)
    }

    /** Whether [npc]'s plan has sent him to look at a spot: his post is a SEARCH one. */
    fun scouting(npc: NpcEntity): Boolean = worlds[npc.level()]?.actions?.get(npc.uuid)?.intent?.duty == CombatDuty.SEARCH

    fun reposition(npc: NpcEntity) {
        world(npc).actions[npc.uuid]?.requestFireRecovery(npc.level().gameTime)
    }
    fun operation(npc: NpcEntity): OperationView? = npc.currentSquad()?.let { world(npc).operations.operationOf(it.id) }
    fun operationOf(level: ServerLevel, squad: UUID): OperationView? = worlds[level]?.operations?.operationOf(squad)
    fun coordinated(npc: NpcEntity) = (operation(npc)?.members?.size ?: 0) > 1

    /** The attack phase of [npc]'s operation, or null when it is not attacking. */
    fun attackPhase(npc: NpcEntity): AttackPhase? = operation(npc)?.let { world(npc).attackPhase(it.id) }

    /** The strategy [squad]'s plan chose, or null before its first plan. */
    fun strategy(level: ServerLevel, squad: UUID): CombatStrategy? = worlds[level]?.strategyOf(squad)

    /** The approach [squad]'s plan chose while it could flank, or null. */
    fun approach(level: ServerLevel, squad: UUID): SquadApproach? = worlds[level]?.approachOf(squad)

    /** The number of [squad]'s current bounding turn, or null while it does not bound. */
    fun boundTurn(level: ServerLevel, squad: UUID): Int? = worlds[level]?.boundTurnOf(squad)

    /** Whether [squad] currently counts towards some operation's area completion. */
    fun holdsArea(squad: UUID) = worlds.values.any { it.holdsArea(squad) }

    /**
     * Notes the death of a commanded objective target. [cancelled] is read at the next tactics tick,
     * so a later handler of the loader's death event can still call the death off.
     */
    fun targetDeath(entity: LivingEntity, cancelled: () -> Boolean) {
        val level = entity.level() as? ServerLevel ?: return
        val world = worlds[level] ?: return
        if (SquadManager.get(level).all().any { it.commandOutcome == CommandOutcome.ACTIVE && (it.command?.goal as? ObjectiveKey.Entity)?.id == entity.uuid }) {
            world.pendingDeaths[entity.uuid] = PendingDeath(entity, cancelled)
        }
    }
    fun clearAll() {
        worlds.values.forEach { it.clear() }
        worlds.clear()
        DecisionTrace.clearAll()
    }

    /** Every squad of every level as the decision trace shows it; records of what is gone are dropped. */
    fun traceSections(): List<DecisionTrace.Section> {
        val sections = worlds.values.flatMap { it.traceSections() }
        DecisionTrace.retain(worlds.values.flatMapTo(HashSet()) { it.traceIds() })
        return sections
    }
    fun snapshot(npc: NpcEntity): TacticalSnapshot? = npc.squadId?.let { world(npc).combatView(it) }
    fun reports(npc: NpcEntity) = world(npc).agents[AgentId(AgentLevel.NPC, npc.uuid)]?.runtime?.reports.orEmpty()

    /** How many post searches in a row [npc] failed round his task's anchor, while remembered. */
    fun postFailures(npc: NpcEntity): Int = world(npc).postFailuresOf(npc.uuid)

    /**
     * Whether his plan may move him. His own plan's cover actions hold his feet as his plan
     * ([PlanExecutor]), so they do not suspend it; any other claim ranked above [NpcActivity.TACTIC] does.
     */
    fun movable(npc: NpcEntity) = npc.isAlive && npc.vehicle == null && npc.mayFor(NpcActivity.TACTIC, Cap.FEET) { it is PlanExecutor }

    /**
     * Whether the planner plans for [npc]: he has a full NPC agent, a ground role in a loaded squad,
     * whose plan decides his cover too. An engage-only agent plans only his moves in a fight, a crewman's only his tube (#258).
     */
    fun plans(npc: NpcEntity): Boolean = hasAgent(npc) && worlds[npc.level()]?.coversFor(npc.uuid) == true
    private val GROUND = setOf(NpcClass.RIFLEMAN, NpcClass.SHOTGUNNER, NpcClass.MACHINE_GUNNER, NpcClass.SNIPER, NpcClass.GRENADIER, NpcClass.MEDIC, NpcClass.DRONE_OPERATOR)

    private class PendingDeath(val entity: LivingEntity, val cancelled: () -> Boolean)

    /** A man fighting with no full agent: his engage-only agent's [command], kept until [until]; [stamp] his squad's order then. */
    private data class Engager(val until: Long, val command: UUID, val stamp: Int)

    /**
     * An engager's agent outlives his last report by 2 s: his gun behaviour stops and starts again
     * while a target is lost and found for a moment, and the agent, its plan and his firing spot
     * must not be dropped and made again each time.
     */
    private const val ENGAGER_TICKS = 40L

    /** One group of an attack that takes an approach sector: a squad, or a lone squad's manoeuvre group. */
    private data class SectorGroup(val squad: UUID, val manoeuvre: Boolean)

    /** The [sighted] post [FireSlotSight] chose at [searchedAt] for a slot at [slot]; null when none sees the defence. */
    private class SlotSight(val slot: Point3, val sighted: FireSlotSight.Sighted?, val searchedAt: Long) {
        fun answers(anchor: Point3, now: Long) = FireSlotSight.stillAnswers(hypot(slot.x - anchor.x, slot.z - anchor.z), sighted != null, now - searchedAt)
    }

    private class World(val level: ServerLevel) {
        val pendingDeaths = HashMap<UUID, PendingDeath>()
        val agents = LinkedHashMap<AgentId, PlanningAgent>()
        val actions = HashMap<UUID, NativePositionAction>()

        /** What each man's cover actions found and did, read back as his facts (#251). */
        private val cover = HashMap<UUID, CoverState>()

        /** What each man's engage keeps across its units (#251 step B). */
        private val engageStates = HashMap<UUID, EngageState>()

        /** Men fighting with no full agent, until their last report expires; see [NativeTactics.engaging]. */
        private val engagers = HashMap<UUID, Engager>()

        /** Whether [id]'s plan decides his cover: not an engager's, nor a crewman's, whose plans have no cover (#258). */
        fun coversFor(id: UUID) = id !in engagers && id !in loneOperators && fireSupport.roster.unitOf(id) == null

        private fun engageOf(npc: NpcEntity) = engageStates.getOrPut(npc.uuid) { EngageState(npc) }

        // His engage facts' band memory, dropped with his agent like [engageStates].
        private val engageBands = HashMap<UUID, EngageBands>()

        // Who may throw a grenade and at what (#263).
        private val grenades = GrenadeChoice(level)

        /** Whether one of [npc]'s engage units runs now: his plan moves him. */
        fun engages(npc: NpcEntity): Boolean = agents[AgentId(AgentLevel.NPC, npc.uuid)]?.runtime?.reports?.values?.any { Engage.isUnit(it.ticket.identity) && it.status == ExecutionStatus.RUNNING } == true

        fun engaging(npc: NpcEntity) {
            // A man with a full agent plans his fight in it; who gets one is [planned]'s alone.
            if (AgentId(AgentLevel.NPC, npc.uuid) in agents && npc.uuid !in engagers) return
            val known = engagers[npc.uuid]
            val stamp = npc.currentSquad()?.orderStamp ?: 0
            engagers[npc.uuid] = Engager(level.gameTime + ENGAGER_TICKS, known?.takeIf { it.stamp == stamp }?.command ?: UUID.nameUUIDFromBytes("engage:${npc.uuid}:$stamp".toByteArray()), stamp)
            if (known == null) refresh(npc)
        }

        // Each sentry's last height post, offered back when an OVERWATCH duty returns to him.
        private val heldPosts = HashMap<UUID, HeldPost>()

        /** The level's mortar crews, their facts and their duties (#258). */
        private val fireSupport = FireSupportWorld(level)

        /** The level's helicopters, their facts and their crews' flight and turret (#260, #261). */
        private val air = AirWorld(level)

        /** The level's drone operators' stations and strike facts (#259). */
        private val drones = DroneWorld(level)

        /** The squadless drone operators, each planned in a root of his own (#259); no plan decides their cover. */
        private val loneOperators = HashSet<UUID>()
        val operations = OperationRegistry({ a, b -> Diplomacy.allied(SquadFaction.valueOf(a), SquadFaction.valueOf(b)) }, { _, _ -> true })
        private val snapshots = HashMap<UUID, TacticalSnapshot>()
        private val sharedViews = HashMap<UUID, TacticalSnapshot>()
        private val teamAllocators = HashMap<UUID, TaskTeamAllocator>()
        private val children = HashMap<AgentId, Set<AgentId>>()
        private val goals = HashMap<AgentId, PositionIntent>()
        private val assignments = HashMap<AgentId, AssignmentView>()
        private val areaClocks = HashMap<UUID, AreaControlClock>()
        private val areaObservedAt = HashMap<UUID, Long>()
        private val areaTraces = HashMap<UUID, String>()
        private val areaOccupied = HashMap<UUID, Boolean>()
        private val attacks = HashMap<UUID, AttackProgress>()
        private val sectors = HashMap<UUID, ApproachSectors<SectorGroup>>()
        private val sectorTraces = HashMap<UUID, Map<SectorGroup, Double>>()
        private val flankTraces = HashMap<UUID, Int>()

        // The sighted post chosen for each man's slot, see [withSight].
        private val slotSights = HashMap<UUID, SlotSight>()
        fun slotsWithoutSight(members: Collection<UUID>) = members.count { id -> slotSights[id]?.let { it.sighted == null } == true }
        private val sectorsHeld = HashMap<UUID, Boolean>()

        // Members on the arc to their sector at the last layout: their intent is not kept once the
        // arc ends, or they would stay on its last waypoint.
        private val arcing = HashSet<UUID>()

        // Members of an attack measured for the arc and not on it: they need
        // [ApproachSectors.ARC_RESUME_SLACK] more to be sent onto it.
        private val offArc = HashSet<UUID>()

        // Per squad attacking alone: the members it sends round a flank, see [approachBearings].
        private val manoeuvres = HashMap<UUID, Set<UUID>>()
        private val boundTraces = HashMap<UUID, Map<UUID, ClosingPost>>()
        private val losses = HashMap<UUID, OperationLosses>()
        private val standings = HashMap<UUID, OperationStanding>()
        private val unverified = HashMap<UUID, MutableMap<UUID, Vec3>>()

        // When each remembered position was last seen, so one nobody can reach expires.
        private val unverifiedSeen = HashMap<UUID, MutableMap<UUID, Long>>()
        private val completions = HashMap<UUID, AreaControlTask>()

        // Per squad: the noises its men heard and the spots its scouts checked, see [SquadAlarms].
        private val alarms = HashMap<UUID, SquadAlarms>()

        // Per squad on its way as a reinforcement: when it was last fired on, and whether it fights.
        private val reinforcementFire = HashMap<UUID, Long>()
        private val reinforcementEngaged = HashMap<UUID, Boolean>()
        private val sequences = HashMap<AssignmentTicket, Long>()
        private val retiredAssignments = ArrayList<AssignmentTicket>()
        private val leases = ResourceLeases()
        private val knownOrders = HashMap<UUID, Int>()
        private val missions = HashMap<UUID, SquadMission>()

        // Each squad's strategy as its installed plan chose it, and since when; see SquadManners.
        private val strategies = HashMap<UUID, Pair<CombatStrategy, Long>>()

        // The strategy each squad's layout last saw, to tell whether it changed since.
        private val seenStrategies = HashMap<UUID, CombatStrategy>()
        private val situations = HashMap<UUID, CombatSituation>()

        // Each squad's approach as its installed plan chose it; see SquadApproaches.
        private val approaches = HashMap<UUID, SquadApproach>()

        // Squads attacking alone that can split off a manoeuvre group, and when each last found its flank blocked.
        private val flankable = HashSet<UUID>()
        private val flankBlockedAt = HashMap<UUID, Long>()

        // Each bounding squad's turn, when it began, and the legs its movers had finished by then.
        private class BoundState(val turn: BoundTurn, val startedAt: Long, val legsAtStart: Map<UUID, Int>)
        private val boundTurns = HashMap<UUID, BoundState>()

        // The leg each covered mover waits to start, and since when; see supportFiring.
        private val supportWaits = HashMap<UUID, Pair<Int, Long>>()

        // Where each regrouping squad gathers, fixed when it began to regroup.
        private val rallies = HashMap<UUID, Point3>()

        // When a man of each squad was last hit, see CombatSituation.underFire.
        private val lastHits = HashMap<UUID, Long>()

        // When each squad last had a close contact, see TacticalSnapshot.closeContact.
        private val closeContacts = HashMap<UUID, Long>()

        private val retreatSmoke = RetreatSmoke(level)

        // When each squad's focus was last well above it, see ENEMY_ABOVE_TICKS.
        private val lastAbove = HashMap<UUID, Long>()

        // When each squad last stopped regrouping, see [CombatSituation.regroupSpent].
        private val regroupEnds = HashMap<UUID, Long>()

        /** The last bound logged for each following squad in a fight. */
        private val followBeats = HashMap<UUID, Long>()
        private val allocationOrigins = HashMap<UUID, Point3>()
        private val formationDuties = HashMap<UUID, CombatDuty>()
        private val plannedContact = HashMap<UUID, Vec3?>()

        // The MemberDuties case each member's duty last came from, to log only when it changes.
        private val dutyRules = HashMap<UUID, String>()
        private val failures = HashMap<UUID, LocalPositionFailure>()

        // Legs each mover has finished of an advance by legs, see [TacticalFacts.LEGS_DONE].
        private val legs = HashMap<UUID, Int>()
        private var nextObservation = Long.MIN_VALUE
        private var parentTick = Long.MIN_VALUE
        private var refreshing = false
        private val ledger = AssignmentLedger(
            { ticket -> agents[ticket.owner.agent]?.runtime?.isLive(ticket) == true },
            { parent, child -> child in children[parent].orEmpty() },
            { event ->
                val view = event.assignment
                val agent = ensure(view.ticket.child)
                when {
                    view.status == AssignmentStatus.REVOKED -> {
                        agent.cancel(event.at, event.reason)
                        assignments.remove(view.ticket.child)
                        sequences.remove(view.ticket)
                        retiredAssignments += view.ticket
                        if (view.ticket.child.level == AgentLevel.NPC) {
                            actions.remove(view.ticket.child.id)?.overwatchPost?.let { heldPosts[view.ticket.child.id] = it }
                            legs.remove(view.ticket.child.id)
                            supportWaits.remove(view.ticket.child.id)
                            goals.remove(view.ticket.child)
                        }
                    }

                    event.reason in setOf("assigned", "assignment_revised") -> {
                        assignments[view.ticket.child] = view
                        if (view.task.parameters is PositionIntent) goals[view.ticket.child] = view.task.parameters
                        agent.command(view.ticket.id, view.task, facts(view.ticket.child, event.at))
                    }

                    event.reason == "parent_held" -> agent.hold(event.at, view.holdReason!!)

                    event.reason == "parent_resumed" -> agent.resume()
                }
            },
        )

        /** The plan's engage units (#251 step B), each run on the man's own [EngageState]. */
        private fun engageBinding() =
            listOf(
                ActionBinding(TacticalDomain.ENGAGE) { ticket, unit ->
                    require(ticket.owner.agent == unit.actor)
                    val npc = requireNotNull(level.getEntity(unit.actor.id) as? NpcEntity)
                    // His position action, if any, is over: the engage moves him now.
                    actions.remove(unit.actor.id)?.overwatchPost?.let { heldPosts[unit.actor.id] = it }
                    EngageAction.of(unit, npc, engageOf(npc))
                },
            )

        /** A grenade throw (#263), on the aim [GrenadeChoice] observed for it. */
        private fun grenadeBinding() =
            listOf(
                ActionBinding(Grenades.ACTION) { ticket, call ->
                    require(ticket.owner.agent == call.actor)
                    grenades.action(requireNotNull(level.getEntity(call.actor.id) as? NpcEntity))
                },
            )

        /** A crewman's duty (#258), run on his own [CrewHand]. */
        private fun crewBinding() =
            listOf(
                ActionBinding(CrewPlan.ACTION) { ticket, duty ->
                    require(ticket.owner.agent == duty.actor)
                    fireSupport.action(duty, requireNotNull(level.getEntity(duty.actor.id) as? NpcEntity))
                },
            )

        /** A pilot's flight and a gunner's turret (#260, #261), each kept on its man across the plan's revisions. */
        private fun airBindings() =
            listOf(
                ActionBinding(AirPlan.FLIGHT) { ticket, duty ->
                    require(ticket.owner.agent == duty.actor)
                    air.flight(duty, requireNotNull(level.getEntity(duty.actor.id) as? NpcEntity))
                },
                ActionBinding(AirPlan.TURRET) { ticket, duty ->
                    require(ticket.owner.agent == duty.actor)
                    air.turret(requireNotNull(level.getEntity(duty.actor.id) as? NpcEntity))
                },
            )

        /** A drone operator's strike (#259), flown by his station. */
        private fun droneBinding() =
            listOf(
                ActionBinding(DroneStrike.ACTION) { ticket, duty ->
                    require(ticket.owner.agent == duty.actor)
                    drones.action(duty, requireNotNull(level.getEntity(duty.actor.id) as? NpcEntity))
                },
            )

        /** The plan's cover actions (#251), each run on the man's own [CoverState]. */
        private fun coverBindings() =
            listOf(
                ActionBinding(TacticalDomain.COVER_MOVE) { ticket, move ->
                    require(ticket.owner.agent == move.actor)
                    val npc = coverActor(move.actor)
                    NativeCoverMove(move, npc, coverOf(npc)) { npc.threatPos ?: npc.incomingFire.point(level.gameTime) }
                },
                ActionBinding(TacticalDomain.COVER_CYCLE) { ticket, cycle ->
                    require(ticket.owner.agent == cycle.actor)
                    val npc = coverActor(cycle.actor)
                    NativeCoverCycle(cycle, npc, coverOf(npc))
                },
                ActionBinding(TacticalDomain.DIG) { ticket, dig ->
                    require(ticket.owner.agent == dig.actor)
                    val npc = coverActor(dig.actor)
                    NativeDig(dig, npc, coverOf(npc))
                },
                ActionBinding(TacticalDomain.HOLD_HOLE) { ticket, hold ->
                    require(ticket.owner.agent == hold.actor)
                    val npc = coverActor(hold.actor)
                    NativeHoldHole(npc, coverOf(npc))
                },
                ActionBinding(TacticalDomain.LEAVE_HOLE) { ticket, leave ->
                    require(ticket.owner.agent == leave.actor)
                    val npc = coverActor(leave.actor)
                    NativeLeaveHole(npc, coverOf(npc))
                },
            )

        private fun ensure(id: AgentId): PlanningAgent =
            agents.getOrPut(id) {
                val factory = ActionRegistry(
                    listOf(
                        ActionBinding(TacticalDomain.SECURE) { ticket, task ->
                            require(ticket.owner.agent == AgentId(AgentLevel.OPERATION, task.operation))
                            object : ActionExecution {
                                private fun update(now: Long): ActionUpdate =
                                    if (areaClocks[task.operation]?.completed == true) {
                                        ActionUpdate.Completed(listOf(ActionEvidence("occupied_and_inspected_area", now)))
                                    } else {
                                        ActionUpdate.Running
                                    }
                                override fun start(now: Long) = update(now)
                                override fun tick(now: Long) = update(now)
                                override fun suspend(now: Long, reason: String) = Unit
                                override fun resume(now: Long) = Unit
                                override fun cancel(now: Long, reason: String) = Unit
                            }
                        },
                        ActionBinding(DelegationAction.KEY) { ticket, task -> DelegationAction(ticket, task, ledger) },
                        ActionBinding(TacticalDomain.FIRE_PAUSE) { ticket, pause ->
                            require(ticket.owner.agent == pause.actor)
                            firePause(pause, requireNotNull(level.getEntity(pause.actor.id) as? NpcEntity))
                        },
                    ) + coverBindings() + engageBinding() + grenadeBinding() + crewBinding() + airBindings() + droneBinding() + listOf(
                        ActionBinding(TacticalDomain.POSITION) { ticket, intent ->
                            require(ticket.owner.agent == intent.actor)
                            val original = goals[intent.actor] ?: intent
                            val npc = requireNotNull(level.getEntity(intent.actor.id) as? NpcEntity)
                            actions[intent.actor.id]?.overwatchPost?.let { heldPosts[intent.actor.id] = it }
                            NativePositionAction(intent, npc, { npc.squadId?.let(::combatView) }, heldPost = heldPosts[intent.actor.id], exhausted = { failed, rejections, searched ->
                                failures[failed.actor.id] = LocalPositionFailure(original.anchor, level.gameTime, (failures[failed.actor.id]?.takeIf { it.anchor == original.anchor }?.attempts ?: 0) + 1, rejections.toMap())
                                // Heights tell a front anchor off the ground (a slope, a ridge) from a crowded area (#55).
                                // The planned anchor is put on the ground before the search; searched is where it looked.
                                val ground = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, net.minecraft.util.Mth.floor(searched.x), net.minecraft.util.Mth.floor(searched.z))
                                DecisionTrace.man(failed.actor.id, level.gameTime, "post_failed", "${failed.duty} rejected=$rejections")
                                DebugFlags.log(
                                    LogGroup.ORDER, "[htn] actor={} event=local_failure duty={} rejected={} anchorY={} searchedY={} groundY={} actorY={} distance={}",
                                    failed.actor.id, failed.duty, rejections, "%.0f".format(failed.anchor.y), "%.0f".format(searched.y), ground, "%.0f".format(npc.y), "%.0f".format(npc.position().distanceTo(searched)),
                                )
                            }).also { actions[intent.actor.id] = it }
                        },
                    ),
                )
                PlanningAgent(
                    id,
                    TacticalDomain.at(id.level),
                    PlanRuntime(factory, leases) { event ->
                        if (event.report.status == ExecutionStatus.FAILED) {
                            DebugFlags.log(LogGroup.ORDER, "[htn] actor={} event=action_failed reason={}", id.id, event.report.reason)
                            if (DecisionTrace.on) traceAction(id, event.report.reason)
                        }
                    },
                    events = { if (DecisionTrace.on) tracePlanning(id, it) },
                ) { feedback ->
                    if (DecisionTrace.on) flushPlanning(id, feedback.result)
                    planResultObserver?.invoke(id.level)
                    if (id.level == AgentLevel.SQUAD) (feedback.result as? PlanningResult.Ready)?.let { recordManner(id.id, it.plan, level.gameTime) }
                    DebugFlags.log(LogGroup.ORDER, "[htn] level={} actor={} event=plan_result result={}", id.level, id.id, feedback.result.javaClass.simpleName)
                }
            }

        /**
         * He fires from the post his leg ended on: done once he has fired, at once when he has
         * no target in sight, or after [FIRE_PAUSE_TICKS]. Aim and fire stay with his gun
         * behaviour; this only holds him at the post meanwhile.
         */
        private fun firePause(pause: FirePause, npc: NpcEntity) =
            object : ActionExecution {
                private var startedAt = 0L
                private fun update(now: Long): ActionUpdate {
                    val waited = now - startedAt
                    val reason = when {
                        waited > 0 && npc.firedRecently(waited.toInt()) -> "fired"

                        // A target he remembers but cannot see is nothing to fire at either: he
                        // stood out every pause blind behind a ridge instead of going on.
                        npc.target?.let { npc.sensing.hasLineOfSight(it) } != true && waited >= NO_TARGET_PAUSE_TICKS -> "no_target"

                        waited >= FIRE_PAUSE_TICKS -> "timeout"

                        else -> return ActionUpdate.Running
                    }
                    legs.merge(npc.uuid, 1, Int::plus)
                    DebugFlags.log(LogGroup.ORDER, "[htn] actor={} event=fire_pause_done leg={} reason={} waited={}", npc.uuid, pause.leg, reason, waited)
                    return ActionUpdate.Completed(listOf(ActionEvidence("fire_pause_$reason", now)))
                }
                override fun start(now: Long): ActionUpdate {
                    startedAt = now
                    return update(now)
                }
                override fun tick(now: Long) = update(now)
                override fun suspend(now: Long, reason: String) = Unit
                override fun resume(now: Long) = Unit
                override fun cancel(now: Long, reason: String) = Unit
            }

        /**
         * Records the strategy and the approach whose methods the squad's new [plan] took. Every
         * task's node reads `root/<strategy>/0/<approach>/...`; a plan without tasks keeps the old ones.
         */
        private fun recordManner(squad: UUID, plan: HtnPlan, now: Long) {
            val parts = plan.tasks.firstOrNull()?.node?.split('/') ?: return
            // Without a flank to take, FRONTAL is no choice: held, its advantage would keep the squad
            // from flanking once it could.
            val approach = parts.getOrNull(3)?.let(SquadApproaches::of)?.takeIf { flankOpen(squad, now) }
            if (approach == null) {
                approaches.remove(squad)
            } else if (approaches.put(squad, approach) != approach) {
                DebugFlags.log(LogGroup.ORDER, "[htn] squad={} event=approach to={}", squad, approach)
            }
            val strategy = parts.getOrNull(1)?.let(SquadManners::of) ?: return
            val old = strategies[squad]?.first
            if (old == strategy) return
            if (old == CombatStrategy.REGROUP) regroupEnds[squad] = now
            strategies[squad] = strategy to now
            val scores = situations[squad]?.let { situation -> CombatStrategy.entries.filter { it.rules.allowed(situation) }.associate { it.name to "%.2f".format(it.rules.score(situation)) } }
            DebugFlags.log(LogGroup.ORDER, "[htn] squad={} event=strategy from={} to={} scores={} situation={}", squad, old, strategy, scores, situations[squad])
            DecisionTrace.group(squad, now, "squad", "strategy", "$old -> $strategy, allowed and scored: $scores")
        }
        private fun facts(id: AgentId, now: Long): FactSnapshot {
            val empty = FactSnapshot(id, now)
            return when (id.level) {
                AgentLevel.OPERATION -> operationFacts(empty)

                AgentLevel.SQUAD -> squadFacts(empty)

                AgentLevel.TEAM -> when {
                    fireSupport.roster.unit(id) != null -> fireSupport.teamFacts(empty)
                    air.roster.unit(id) != null -> air.teamFacts(empty)
                    else -> empty
                }

                AgentLevel.NPC -> npcFacts(empty)
            }
        }
        private fun operationFacts(empty: FactSnapshot): FactSnapshot {
            var result = empty
            val id = empty.owner.id
            val observed = areaObservedAt[id]
            if (observed != null) result = result.observe(TacticalFacts.AREA_SECURED, areaClocks[id]?.completed == true, observedAt = observed, expiresAt = observed + 11)
            attacks[id]?.let { result = result.observe(TacticalFacts.ATTACK_PHASE, it.phase) }
            return result
        }
        private fun squadFacts(empty: FactSnapshot): FactSnapshot {
            val id = empty.owner.id
            val now = empty.now
            var result = empty.observe(TacticalFacts.FLANK_OPEN, flankOpen(id, now))
            situations[id]?.let { result = result.observe(TacticalFacts.SITUATION, it) }
            boundTurns[id]?.let { result = result.observe(TacticalFacts.BOUND_TURN, it.turn) }
            rallies[id]?.let { result = result.observe(TacticalFacts.RALLY, it) }
            approaches[id]?.let { result = result.observe(TacticalFacts.APPROACH, it) }
            strategies[id]?.let { (strategy, since) ->
                result = result.observe(TacticalFacts.MANNER, strategy)
                if (now < since + SquadManners.MINIMUM_TICKS) result = result.observe(TacticalFacts.MANNER_LOCK, true, observedAt = since, expiresAt = since + SquadManners.MINIMUM_TICKS)
            }
            return result
        }
        private fun coverOf(npc: NpcEntity) = cover.getOrPut(npc.uuid, ::CoverState)

        /**
         * The man a cover action is for. His post action is over while it runs: dropped here, so his
         * stale post neither answers for his assignment nor keeps his plan repairing.
         */
        private fun coverActor(actor: AgentId): NpcEntity {
            actions.remove(actor.id)?.overwatchPost?.let { heldPosts[actor.id] = it }
            return requireNotNull(level.getEntity(actor.id) as? NpcEntity)
        }

        /** His facts about fire, cover and his foxhole (#251); the costly ones only while they matter. */
        private fun coverFacts(facts: FactSnapshot, npc: NpcEntity?, now: Long): FactSnapshot {
            npc ?: return facts.observe(TacticalFacts.UNDER_FIRE, false).observe(TacticalFacts.DIGS, 0)
            val underFire = npc.isSuppressed()
            val sector = npc.threatPos?.takeIf { underFire }?.let { coverOf(npc).fireSector(it) }
            val state = cover[npc.uuid]
            val digs = state?.digs ?: 0
            var result = facts.observe(TacticalFacts.UNDER_FIRE, underFire).observe(TacticalFacts.DIGS, digs)
                .observe(TacticalFacts.COVER_CYCLES, state?.cycles ?: 0)
                .observe(TacticalFacts.SHELTERED, state?.sheltered(npc, sector) == true)
            sector?.let { result = result.observe(TacticalFacts.FIRE_SECTOR, it) }
            state?.failure?.let { result = result.observe(TacticalFacts.COVER_FAILURE, it, observedAt = it.failedAt, expiresAt = it.failedAt + FAILURE_MEMORY_TICKS) }
            // Only the answers to no cover and the hole read these, and a man peeking from cover
            // sees his target and loses it again: observed always, they would replan him each time.
            if ((state?.failure == null || !underFire) && digs == 0) return result
            val level = npc.level() as ServerLevel
            result = result.observe(TacticalFacts.ENGAGED, engaged(npc, now))
                .observe(TacticalFacts.DIG_POSSIBLE, CoverMechanics.canDigIn(npc, level, npc.blockPosition()))
            if (digs > 0) {
                result = result.observe(TacticalFacts.HIT_IN_HOLE, npc.health < state!!.healthAtDig)
                    .observe(TacticalFacts.HEALED, npc.health >= npc.maxHealth * CoverMechanics.DIG_HEALTH_FRACTION)
            }
            return result
        }

        /**
         * A live target he sees, or one not shooting at him now: the cover behaviour's test for
         * holding and firing in the open rather than falling back across it.
         */
        private fun engaged(npc: NpcEntity, now: Long): Boolean {
            val target = npc.target?.takeIf { it.isAlive } ?: return false
            return !npc.incomingFire.pending(now) || com.sbwnpc.squad.combat.DetectionSightline.canSeeWithin(npc, target, NpcEntity.DETECTION_RANGE)
        }

        private fun npcFacts(empty: FactSnapshot): FactSnapshot {
            val id = empty.owner
            val now = empty.now
            val npc = level.getEntity(id.id) as? NpcEntity
            val action = actions[id.id]
            var result = empty.observe(TacticalFacts.MOVABLE, npc != null && movable(npc))
                .observe(TacticalFacts.COVER, goals[id]?.let(::covering) ?: false, expiresAt = now + 11)
                .observe(TacticalFacts.ARRIVED, action?.reached() ?: false)
                .observe(TacticalFacts.AT_POST, action?.atPost() ?: false)
                .observe(TacticalFacts.LEGS_DONE, legs[id.id] ?: 0)
                .observe(TacticalFacts.SUPPORT_FIRING, supportFiring(id, now), expiresAt = now + 11)
            failures[id.id]?.let { failure -> result = result.observe(TacticalFacts.POST_FAILURE, failure, observedAt = failure.failedAt, expiresAt = failure.failedAt + FAILURE_MEMORY_TICKS) }
            result = coverFacts(result, npc, now)
            return if (npc != null) manFacts(result, id, npc, now) else result
        }

        /** What [npc]'s own fight, grenades and role add to his facts. */
        private fun manFacts(facts: FactSnapshot, id: AgentId, npc: NpcEntity, now: Long): FactSnapshot {
            var result = EngageFacts.observe(facts, npc, engageOf(npc), engageBands.getOrPut(npc.uuid, ::EngageBands), now)
            result = grenades.observe(result, npc, now)
            if (fireSupport.roster.unitOf(npc.uuid) != null) result = fireSupport.manFacts(result, npc)
            if (air.roster.unitOf(npc.uuid) != null) result = air.manFacts(result, npc)
            if (DroneWorld.operates(npc)) result = drones.manFacts(result, npc, teamTarget(id, npc), coversFor(npc.uuid))
            return result
        }
        fun heard(squad: UUID, at: Point3, now: Long) = alarms.getOrPut(squad, ::SquadAlarms).heard(at, now)

        fun combatView(squad: UUID): TacticalSnapshot? {
            sharedViews[squad]?.let { return it }
            val own = snapshots[squad] ?: return null
            val scope = operations.operationOf(squad) ?: operations.pending(squad)?.operation?.let(operations::operation)
            val faction = SquadManager.get(level).get(squad)?.faction ?: return own
            val contacts = (
                listOf(own) + scope?.members?.keys.orEmpty().mapNotNull { id ->
                    snapshots[id]?.takeIf { SquadManager.get(level).get(id)?.let { saved -> Diplomacy.allied(faction, saved.faction) } == true }
                }
                ).flatMap { it.contacts }.groupBy { it.id }.values.map { reports -> reports.maxBy { it.seenAt } }
            return own.copy(contacts = contacts).also { sharedViews[squad] = it }
        }
        private fun covering(intent: PositionIntent): Boolean {
            val focus = intent.focus?.native() ?: return false
            val npc = level.getEntity(intent.actor.id) as? NpcEntity ?: return false
            val own = npc.currentSquad() ?: return false
            val op = operations.operationOf(own.id)
            val squads = (op?.members?.keys ?: setOf(own.id)).filter { id ->
                SquadManager.get(level).get(id)?.let { Diplomacy.allied(own.faction, it.faction) } == true
            }
            return squads.asSequence().mapNotNull(snapshots::get).flatMap { it.members.asSequence() }.any {
                it.id != npc.uuid && it.ready && it.canFire && it.recentFire && it.recentFireAt?.distanceTo(focus)?.let { distance -> distance <= 24 } == true
            }
        }
        fun refresh(reference: NpcEntity) {
            if (refreshing) return
            val now = level.gameTime
            val squad = reference.currentSquad()
            if (now < nextObservation && (squad == null || knownOrders[squad.id] == squad.orderStamp)) return
            refreshing = true
            try {
                nextObservation = now + 10
                sharedViews.clear()
                val manager = SquadManager.get(level)
                // Inspect after the complete event dispatch: a cancelled death is not success.
                val destroyedTargets = pendingDeaths.filterValues { !it.cancelled() && it.entity.isDeadOrDying }.keys.toSet()
                pendingDeaths.clear()
                val loaded = manager.all().mapNotNull { saved ->
                    // A member whose entity names no squad or another one is not planned for: such an NPC,
                    // left over in a world from an interrupted run, got an agent and crashed the server
                    // on its first post search (#64).
                    val npcs = saved.members.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive && it.vehicle == null && it.npcClass in GROUND && it.squadId == saved.id }
                    if (npcs.isEmpty()) null else saved to npcs
                }
                val activeIds = loaded.mapTo(HashSet()) { it.first.id }
                // Crews and aircraft join only where their squad's mission is built: none of the ground
                // picture below (snapshots, losses, arrival, layout) counts them (#258, #260).
                fireSupport.roster.collect(now)
                air.roster.collect()
                val supportOnly = (fireSupport.roster.squads() + air.roster.squads()).filter { it !in activeIds }.mapNotNull(manager::get)
                snapshots.keys.filter { it !in activeIds }.toList().forEach { id ->
                    snapshots.remove(id)
                    missions.remove(id)
                    situations.remove(id)
                    boundTurns.remove(id)
                    rallies.remove(id)
                    lastHits.remove(id)
                    closeContacts.remove(id)
                    retreatSmoke.forget(id)
                    grenades.forgetSquad(id)
                    lastAbove.remove(id)
                    alarms.remove(id)
                    regroupEnds.remove(id)
                    approaches.remove(id)
                    flankable.remove(id)
                    flankBlockedAt.remove(id)
                    strategies.remove(id)
                    seenStrategies.remove(id)
                    if (manager.get(id) == null) {
                        knownOrders.remove(id)
                        operations.detach(id)
                    }
                }
                loaded.forEach { (saved, npcs) ->
                    var view = TacticalSnapshotCollector.collect(saved, level, npcs, npcs.first())
                    if (AttackAmmoWithdrawal.apply(saved, level, view)) view = TacticalSnapshotCollector.collect(saved, level, npcs, npcs.first())
                    snapshots[saved.id] = view
                    saved.tactics.snapshot = view
                    val goal = saved.command?.goal as? ObjectiveKey.Entity
                    if (saved.order == SquadOrder.ATTACK && goal?.id in destroyedTargets && saved.commandOutcome == CommandOutcome.ACTIVE) {
                        com.sbwnpc.squad.experience.ExperienceAwards.objective(level.server, saved)
                        manager.completeCommand(saved.id, "target_destroyed")
                        manager.setOrder(saved.id, SquadOrder.DEFEND)
                        manager.setObjective(level, saved.id, net.minecraft.core.BlockPos.containing(view.center))
                    }
                }
                val commanded = loaded.map { it.first } + supportOnly
                val groups = commanded.filter { knownOrders[it.id] != it.orderStamp }.groupBy { (it.command?.id ?: it.tacticalOperation ?: it.id) to it.order }
                groups.forEach { (group, selected) ->
                    val first = selected.firstOrNull { it.id in activeIds } ?: selected.first()
                    val view = snapshots[first.id]
                    val center = view?.center ?: fireSupport.roster.center(first.id) ?: air.roster.center(first.id) ?: return@forEach
                    val objective = first.tacticalObjective ?: first.objective ?: net.minecraft.core.BlockPos.containing(view?.home ?: center)
                    val direction = objective.center.subtract(center).multiply(1.0, 0.0, 1.0).normalize()
                    // An attack is joined short of the objective, on the side it was approached from.
                    // A held point (defence, patrol) is already occupied, so it is joined at the point.
                    val entry = if (first.order == SquadOrder.ATTACK) objective.center.subtract(direction.scale(48.0)) else objective.center
                    val participants = selected.map { saved ->
                        val role = if (saved.id in activeIds) ParticipantRole.GROUND else ParticipantRole.SUPPORT
                        SquadParticipant(saved.id, saved.owner, saved.faction.name, level.dimension().location().toString(), saved.orderStamp.toLong(), role)
                    }
                    val commandId = UUID.nameUUIDFromBytes("$group:${selected.joinToString { "${it.id}:${it.orderStamp}" }}".toByteArray())
                    val key = first.command?.goal?.takeIf { first.commandOutcome == CommandOutcome.ACTIVE && first.command?.order == first.order.tactical() } ?: ObjectiveKey.Area(first.objectiveDimension?.location()?.toString() ?: level.dimension().location().toString(), objective.x, objective.y, objective.z)
                    val accepted = operations.command(CommandBatch(commandId, first.owner, first.order.tactical(), key, participants, now, EntryArea(entry.point(), 32.0, 16.0)))
                    DebugFlags.log(LogGroup.ORDER, "[htn] event=operation_command principal={} squads={} order={} objective={} result={}", first.owner, participants.map { it.id }, first.order, key, accepted)
                    selected.forEach { saved ->
                        knownOrders[saved.id] = saved.orderStamp
                        missions.remove(saved.id)
                        saved.members.forEach {
                            allocationOrigins.remove(it)
                            formationDuties.remove(it)
                            plannedContact.remove(it)
                            dutyRules.remove(it)
                            slotSights.remove(it)
                        }
                    }
                }
                loaded.forEach { (saved, _) ->
                    val pending = operations.pending(saved.id) ?: return@forEach
                    val view = snapshots.getValue(saved.id)
                    // Squads holding one point get adjacent areas, so a joining squad settles in its
                    // own area beside the point: being inside it is its arrival at the held point.
                    val target = operations.operation(pending.operation)
                    val joined = target?.takeIf { it.order != TacticalOrder.ATTACK }
                    val settled = joined != null && SquadAreas.assigned(level, saved.id)?.let { view.center.subtract(it.center).horizontalDistance() <= it.request.radius } == true
                    // A squad reaches the entry when its nearest ready man does: pinned by fire, its
                    // centre can stop just outside while the squad already stands at the fight.
                    val front = target?.let { op -> view.members.filter { it.ready }.minByOrNull { it.position.point().horizontalDistanceSquared(op.entry.center) }?.position }
                    operations.arrive(
                        ArrivalReport(
                            saved.id,
                            saved.orderStamp.toLong(),
                            pending.operation,
                            now,
                            if (settled) joined!!.entry.center else (front ?: view.center).point(),
                            // Reinforcements join a fight in progress: incoming fire does not hold them out.
                            !view.grenade,
                            view.members.any { it.ready },
                        ),
                        now,
                    )
                }
                sharedViews.clear()
                val opGroups = commanded.groupBy { operations.operationOf(it.id)?.id ?: it.id }
                val roots = HashSet<AgentId>()
                val desiredAgents = HashSet<AgentId>()
                opGroups.forEach { (operation, members) ->
                    val ground = members.filter { it.id in activeIds }
                    val standing = if (ground.isEmpty()) OperationStanding.STEADY else standing(operation, now)
                    // Its crews too lose their plan for this one update: the next one finds their squads
                    // retreating on their own, and plans the carry to the rally point under them.
                    if (standing == OperationStanding.WITHDRAW) return@forEach
                    val grounded = if (ground.isEmpty()) emptyList() else layout(ground, now, holding = standing == OperationStanding.REVIEW)
                    val squadMissions = grounded.map { it.copy(support = fireSupport.roster.crews(it.agent.id), air = air.roster.aircraft(it.agent.id)) } +
                        members.filter { it.id !in activeIds }.map { SquadMission(AgentId(AgentLevel.SQUAD, it.id), emptyList(), fireSupport.roster.crews(it.id), air.roster.aircraft(it.id)) }
                    val root = AgentId(AgentLevel.OPERATION, operation)
                    roots += root
                    desiredAgents += root
                    children[root] = squadMissions.mapTo(HashSet()) { it.agent }
                    squadMissions.forEach { mission ->
                        children[mission.agent] = mission.teams.mapTo(HashSet()) { it.agent } + mission.support.map { it.agent } + mission.air.map { it.agent }
                        desiredAgents += mission.agent
                        ensure(mission.agent)
                        mission.support.forEach { crew -> ensureCrew(crew, desiredAgents) }
                        mission.air.forEach { aircraft -> ensureAircraft(aircraft, desiredAgents) }
                        mission.teams.forEach { team ->
                            children[team.agent] = team.members.mapTo(HashSet()) { it.actor }
                            desiredAgents += team.agent
                            ensure(team.agent)
                            team.members.forEach {
                                desiredAgents += it.actor
                                ensure(it.actor)
                            }
                        }
                    }
                    // A reinforcement still on its way only approaches: the area it is going to
                    // is held and completed by the operation it joins, never by itself.
                    val active = ground.filter { it.order == SquadOrder.ATTACK && it.commandOutcome == CommandOutcome.ACTIVE && operations.pending(it.id) == null }
                    val first = active.firstOrNull()
                    val area = first?.command?.goal as? ObjectiveKey.Area ?: first?.objective?.let { point ->
                        if (first.command == null) ObjectiveKey.Area(level.dimension().location().toString(), point.x, point.y, point.z) else null
                    }
                    val secure = if (area != null && area.dimension == level.dimension().location().toString()) {
                        AreaControlTask(operation, Point3(area.x + 0.5, area.y.toDouble(), area.z + 0.5), active.associate { it.id to commandToken(it) }).also { observeArea(it, active, now) }
                    } else {
                        null
                    }
                    val roles = if (secure != null) {
                        attackRoles(operation, secure, active, now, holding = standing == OperationStanding.REVIEW)
                    } else {
                        attacks.remove(operation)
                        boundTraces.remove(operation)
                        AttackRoles.NONE
                    }
                    ensure(root).command(operation, TacticalDomain.OPERATION.call(OperationMission(squadMissions, secure, roles)), facts(root, now))
                }
                agents.keys.filter { it.level == AgentLevel.OPERATION && it !in roots }.toList().forEach { id ->
                    agents.remove(id)?.cancel(now, "operation_membership_changed")
                    children.remove(id)
                    areaClocks.remove(id.id)
                    areaOccupied.remove(id.id)
                    attacks.remove(id.id)
                    sectors.remove(id.id)
                    sectorTraces.remove(id.id)
                    flankTraces.remove(id.id)
                    boundTraces.remove(id.id)
                    sectorsHeld.remove(id.id)
                    areaObservedAt.remove(id.id)
                    areaTraces.remove(id.id)
                    unverified.remove(id.id)
                    unverifiedSeen.remove(id.id)
                    areaTasks.remove(id.id)
                    operations.forget(id.id)
                }
                commandCrews(now, desiredAgents)
                commandAircraft(now, desiredAgents)
                commandLoneOperators(now, desiredAgents)
                commandEngagers(now, desiredAgents)
                agents.keys.filter { it !in desiredAgents }.toList().forEach { id ->
                    DebugFlags.log(LogGroup.ORDER, "[htn] actor={} level={} event=agent_dropped reason=actor_not_active", id.id, id.level)
                    agents.remove(id)?.cancel(now, "actor_not_active")
                }
                fireSupport.sweep(desiredAgents)
                air.sweep()
                drones.sweep()
                children.keys.removeIf { it !in desiredAgents }
                failures.keys.removeIf { AgentId(AgentLevel.NPC, it) !in desiredAgents }
                // A man leaving the plan keeps no foxhole claim nobody would release.
                cover.entries.removeIf { (id, state) ->
                    (AgentId(AgentLevel.NPC, id) !in desiredAgents).also { gone -> if (gone) (level.getEntity(id) as? NpcEntity)?.release(NpcActivity.DUG_IN, state) }
                }
                // Nor a firing spot nobody would release.
                engageStates.entries.removeIf { (id, state) -> (AgentId(AgentLevel.NPC, id) !in desiredAgents).also { gone -> if (gone) state.dispose() } }
                engageBands.keys.removeIf { AgentId(AgentLevel.NPC, it) !in desiredAgents }
                grenades.keep { AgentId(AgentLevel.NPC, it) in desiredAgents }
                allocationOrigins.keys.removeIf { AgentId(AgentLevel.NPC, it) !in desiredAgents }
                formationDuties.keys.removeIf { AgentId(AgentLevel.NPC, it) !in desiredAgents }
                plannedContact.keys.removeIf { AgentId(AgentLevel.NPC, it) !in desiredAgents }
                arcing.removeIf { AgentId(AgentLevel.NPC, it) !in desiredAgents }
                offArc.removeIf { AgentId(AgentLevel.NPC, it) !in desiredAgents }
                teamAllocators.keys.removeIf { manager.get(it) == null }
                // A disbanded squad of crews alone had no snapshot to be dropped with above.
                knownOrders.keys.filter { manager.get(it) == null }.forEach { id ->
                    knownOrders.remove(id)
                    operations.detach(id)
                }
                manoeuvres.keys.removeIf { manager.get(it) == null }
                retiredAssignments.forEach(ledger::forget)
                retiredAssignments.clear()
            } finally {
                refreshing = false
            }
        }

        /**
         * Keeps an engage-only agent for every man still reporting a fight with no full agent, its
         * root `npc.engage`, recommanded when his squad's order changes; the rest are dropped by
         * the sweep that follows. A man who gained a full agent is no engager any more.
         */
        private fun commandEngagers(now: Long, desired: MutableSet<AgentId>) {
            engagers.entries.removeIf { (id, engager) -> engager.until < now || AgentId(AgentLevel.NPC, id) in desired || (level.getEntity(id) as? NpcEntity)?.isAlive != true }
            engagers.forEach { (id, engager) ->
                val agentId = AgentId(AgentLevel.NPC, id)
                desired += agentId
                val fresh = agentId !in agents
                ensure(agentId).command(engager.command, TacticalDomain.FIGHT_TASK.call(EngageTask(agentId)), facts(agentId, now))
                if (fresh) DecisionTrace.man(id, now, "agent", "engager")
            }
        }

        /** [crew]'s team agent and its men's, kept as [desired]. */
        private fun ensureCrew(crew: SupportCrew, desired: MutableSet<AgentId>) {
            children[crew.agent] = crew.members.toSet()
            desired += crew.agent
            ensure(crew.agent)
            crew.members.forEach {
                desired += it
                ensure(it)
            }
        }

        /**
         * Keeps a root crew agent for every squadless crew (#258), as [commandEngagers] does for
         * a squadless man's fight: it has no squad to delegate it, so it is commanded here.
         */
        private fun commandCrews(now: Long, desired: MutableSet<AgentId>) {
            fireSupport.roster.crews(null).forEach { crew ->
                val fresh = crew.agent !in agents
                ensureCrew(crew, desired)
                agents.getValue(crew.agent).command(UUID.nameUUIDFromBytes("crew:${crew.agent.id}".toByteArray()), CrewPlan.TEAM_TASK.call(crew), facts(crew.agent, now))
                if (fresh) DecisionTrace.man(crew.members.first().id, now, "agent", "squadless_crew")
            }
        }

        /** [aircraft]'s team agent and its men's, kept as [desired]. */
        private fun ensureAircraft(aircraft: Aircraft, desired: MutableSet<AgentId>) {
            val men = listOfNotNull(aircraft.pilot) + aircraft.gunners
            children[aircraft.agent] = men.toSet()
            desired += aircraft.agent
            ensure(aircraft.agent)
            men.forEach {
                desired += it
                ensure(it)
            }
        }

        /**
         * Keeps a root agent for every squadless drone operator (#259), as [commandCrews] does for
         * a squadless crew: his strike, his engage in a fight, else nothing. It stands in for the
         * engage-only agent such a man would get, so he has one NPC agent, not two.
         */
        private fun commandLoneOperators(now: Long, desired: MutableSet<AgentId>) {
            val lone = NpcRegistry.all(level).filter { DroneWorld.operates(it) && it.currentSquad() == null && AgentId(AgentLevel.NPC, it.uuid) !in desired }
            // Kept between passes, not cleared first: his facts read it for whether his cover is planned.
            loneOperators.retainAll(lone.mapTo(HashSet()) { it.uuid })
            lone.forEach { npc ->
                val agent = AgentId(AgentLevel.NPC, npc.uuid)
                loneOperators += npc.uuid
                desired += agent
                val fresh = agent !in agents
                ensure(agent).command(UUID.nameUUIDFromBytes("drone:${npc.uuid}".toByteArray()), DroneStrike.ALONE.call(StrikeCall(agent)), facts(agent, now))
                if (fresh) DecisionTrace.man(npc.uuid, now, "agent", "squadless_operator")
            }
        }

        /** The team agent [man] is planned in, or null. */
        fun teamOf(man: AgentId): AgentId? = children.entries.firstOrNull { (id, members) -> id.level == AgentLevel.TEAM && man in members }?.key

        /** The enemy most of [man]'s team fight (#259): his team's operator strikes it first. */
        private fun teamTarget(man: AgentId, npc: NpcEntity): LivingEntity? {
            val team = teamOf(man)?.let(children::get) ?: return null
            return DroneWorld.teamTarget(npc, team.mapNotNull { level.getEntity(it.id) as? NpcEntity })
        }

        /** Keeps a root agent for every squadless aircraft (#260), as [commandCrews] does for a squadless crew. */
        private fun commandAircraft(now: Long, desired: MutableSet<AgentId>) {
            air.roster.aircraft(null).forEach { aircraft ->
                val fresh = aircraft.agent !in agents
                ensureAircraft(aircraft, desired)
                agents.getValue(aircraft.agent).command(UUID.nameUUIDFromBytes("air:${aircraft.agent.id}".toByteArray()), AirPlan.TEAM_TASK.call(aircraft), facts(aircraft.agent, now))
                if (fresh) DecisionTrace.man((aircraft.pilot ?: aircraft.gunners.first()).id, now, "agent", "squadless_aircraft")
            }
        }

        private fun commandToken(squad: Squad): UUID = squad.command?.id ?: UUID.nameUUIDFromBytes("${squad.id}:${squad.orderStamp}".toByteArray())

        private fun observeArea(task: AreaControlTask, squads: List<Squad>, now: Long) {
            areaTasks[task.operation] = task
            val point = task.objective.native()
            val observers = squads.flatMap { saved -> saved.members.mapNotNull { level.getEntity(it) as? NpcEntity } }.filter { it.isAlive && it.vehicle == null }
            val contacts = squads.flatMap { combatView(it.id)?.contacts.orEmpty() }.filter { now - it.seenAt <= 200 && it.position.distanceTo(point) <= AREA_THREAT_RADIUS }.distinctBy { it.id }
            val remaining = unverified.getOrPut(task.operation, ::LinkedHashMap)
            val seen = unverifiedSeen.getOrPut(task.operation, ::HashMap)
            contacts.forEach {
                remaining[it.id] = it.position
                seen[it.id] = now
            }
            for ((id, position) in remaining.toMap()) {
                val unseen = now - seen.getOrPut(id) { now }
                if (UnverifiedContacts.expired(position.distanceTo(point), unseen)) {
                    remaining.remove(id)
                    seen.remove(id)
                    DebugFlags.log(LogGroup.ORDER, "[htn] operation={} event=unverified_expired at={} distance={} unseen={}", task.operation, net.minecraft.core.BlockPos.containing(position).toShortString(), position.distanceTo(point).toInt(), unseen)
                }
            }
            val known = contacts.mapTo(HashSet()) { it.id }
            var discovered = false
            // Only inspect remembered positions which no longer have a fresh contact report.
            for ((id, position) in remaining.toMap().entries.filter { it.key !in known }.take(2)) {
                if (!level.hasChunkAt(net.minecraft.core.BlockPos.containing(position))) continue
                val witness = observers.firstOrNull { actor -> actor.position().distanceTo(position) <= 160 && Vision.inCone(actor.position(), actor.yHeadRot, position) && DetectionSightline.visible(level, actor.eyePosition, position.add(0.0, 1.5, 0.0), actor) } ?: continue
                val enemies = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity::class.java, net.minecraft.world.phys.AABB(position, position).inflate(4.0)) {
                    it.isAlive && witness.isEnemy(it) && DetectionSightline.canSee(witness, it)
                }
                if (enemies.isEmpty()) {
                    remaining.remove(id)
                    seen.remove(id)
                } else {
                    discovered = true
                    enemies.forEach { SquadTactics.observe(witness, it) }
                }
            }
            val present = observers.count { it.position().distanceTo(point) <= 24 }
            val required = max(1, (squads.sumOf { it.members.size } + 1) / 2)
            val occupied = present >= required
            areaOccupied[task.operation] = occupied
            if (occupied && contacts.isEmpty()) {
                val reference = observers.firstOrNull()
                if (reference != null) {
                    val candidates = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity::class.java, net.minecraft.world.phys.AABB(point, point).inflate(24.0)) { it.isAlive && reference.isEnemy(it) }
                    for (enemy in candidates.take(16)) {
                        val witness = observers.take(4).firstOrNull { com.sbwnpc.squad.combat.Vision.inCone(it.position(), it.yHeadRot, enemy.position()) && DetectionSightline.canSee(it, enemy) }
                        if (witness != null) {
                            SquadTactics.observe(witness, enemy)
                            remaining[enemy.uuid] = enemy.position()
                            seen[enemy.uuid] = now
                            discovered = true
                        }
                    }
                }
            }
            val inspected = occupied && observers.any { it.position().distanceTo(point) <= 24 && Vision.inCone(it.position(), it.yHeadRot, point) && DetectionSightline.visible(level, it.eyePosition, point.add(0.0, 1.5, 0.0), it) } && remaining.isEmpty()
            val loaded = listOf(-24, 24).all { x -> listOf(-24, 24).all { z -> level.hasChunkAt(net.minecraft.core.BlockPos.containing(point.add(x.toDouble(), 0.0, z.toDouble()))) } }
            val fire = observers.any { it.incomingFire.recentPoint(now) != null || it.holds(NpcActivity.EVADE) }
            val clock = areaClocks.getOrPut(task.operation, ::AreaControlClock)
            clock.observe(now, loaded, occupied, inspected, contacts.isNotEmpty() || discovered, fire)
            areaObservedAt[task.operation] = now
            if (DebugFlags.on(LogGroup.ORDER)) {
                val inputs = "present=$present/$required inspected=$inspected unverified=${remaining.size} contacts=${contacts.size} discovered=$discovered fire=$fire loaded=$loaded counting=${clock.heldTicks > 0}"
                if (areaTraces.put(task.operation, inputs) != inputs) {
                    DebugFlags.log(LogGroup.ORDER, "[htn] operation={} event=area_clock {} heldTicks={} nearestContact={} unverifiedAt={}", task.operation, inputs, clock.heldTicks, contacts.minOfOrNull { it.position.distanceTo(point).toInt() }, remaining.values.take(3).map { net.minecraft.core.BlockPos.containing(it).toShortString() })
                }
            }
        }

        /**
         * Reports the attack's observations to its [AttackProgress] and returns the roles of its
         * phase. A lone squad's manoeuvre group closes in by bounds unless the squad withdraws or
         * holds, or the operation is [holding] while it reviews its losses: a bound lifts the
         * squad's own strategy for those men.
         */
        private fun attackRoles(operation: UUID, task: AreaControlTask, squads: List<Squad>, now: Long, holding: Boolean): AttackRoles {
            val progress = attacks.getOrPut(operation) { AttackProgress(now) }
            val objective = task.objective.native()
            val contacts = squads.flatMap { combatView(it.id)?.contacts.orEmpty() }.filter { now - it.seenAt <= 200 }.distinctBy { it.id }
            val members = squads.flatMap { snapshots[it.id]?.members.orEmpty() }
            val threats = contacts.filter { it.position.distanceTo(objective) <= AREA_THREAT_RADIUS }
            val defenders = progress.defenders(
                now,
                contacts.filter { it.position.distanceTo(objective) <= AttackPhase.OBJECTIVE_RADIUS }.associate { it.id to it.position.point() },
            ) { id -> (level.getEntity(id) as? net.minecraft.world.entity.LivingEntity)?.isAlive == true }
            val before = progress.phase
            val inputs = progress.observe(
                now,
                contactSeen = contacts.isNotEmpty(),
                incoming = squads.any { snapshots[it.id]?.incoming?.isNotEmpty() == true },
                readyShooters = members.count { it.ready },
                enemiesAtObjective = defenders.size,
                occupied = areaOccupied[operation] == true,
                threatsNearby = threats.size,
                sectorsHeld = sectorsHeld[operation] != false,
            )
            if (progress.phase != before) {
                DebugFlags.log(LogGroup.ORDER, "[htn] operation={} event=attack_phase from={} to={} inputs={}", operation, before, progress.phase, inputs)
                DecisionTrace.group(operation, level.gameTime, "operation", "attack_phase", "$before -> ${progress.phase}, $inputs")
            }
            val occupying = progress.occupying
            val bounders = squads.singleOrNull()
                ?.takeIf { !holding && strategies[it.id]?.first in setOf(CombatStrategy.ADVANCE, CombatStrategy.GAIN_HEIGHT, CombatStrategy.BOUND_OVERWATCH) }
                ?.let { squad -> manoeuvres[squad.id].orEmpty() - arcing }
                .orEmpty()
            val attackers = members.map { AttackMember(it.id, it.position.point(), it.ready) }
            val known = (threats.map { it.position.point() } + defenders).distinct()
            // Every ready man takes part in an assault, not only the front rows of the line: the
            // flanks and rear guard a reinforcement was added to never left the firing line (#54).
            val roles = progress.roles(now, task.objective, members.filter { it.ready }.map { it.id }, attackers, known)
            if (progress.occupying && !occupying) {
                DebugFlags.log(LogGroup.ORDER, "[htn] operation={} event=assault_occupy ready={} defenders={} movers={}", operation, inputs.readyShooters, defenders.size, roles.assault.size)
            }
            // Bounds come only while deploying, when the roles have no closing posts of their own.
            val bounds = progress.bounds(now, task.objective, bounders, attackers, known)
            if (boundTraces.put(operation, bounds) != bounds && bounds.isNotEmpty()) {
                DebugFlags.log(LogGroup.ORDER, "[htn] operation={} event=bound rangeToPost={}", operation, bounds.mapValues { (id, post) -> "%.0f->%.0f".format(nearest(defenders, attackers.first { it.id == id }.position), nearest(defenders, post.post)) })
            }
            return if (bounds.isEmpty()) roles else roles.copy(closing = bounds)
        }

        /**
         * The attacking operation's standing after its losses, over every squad in it, loaded or
         * not. On [OperationStanding.WITHDRAW] the attack is called off here and each squad falls back.
         *
         * Squads with a barracks are left out: it replaces their fallen, so they fight on whatever
         * they lose (#57). Their men do not count, and a withdrawal pulls back only the squads
         * without one; the attack goes on while a squad with a barracks is still in it.
         */
        private fun standing(operation: UUID, now: Long): OperationStanding {
            val manager = SquadManager.get(level)
            val all = (operations.operation(operation)?.ground?.keys ?: setOf(operation))
                .mapNotNull(manager::get)
                .filter { it.order == SquadOrder.ATTACK && it.commandOutcome == CommandOutcome.ACTIVE && operations.pending(it.id) == null }
            val attacking = all.filter { it.barracks == null }
            if (attacking.isEmpty()) {
                losses.remove(operation)
                standings.remove(operation)
                return OperationStanding.STEADY
            }
            val record = losses.getOrPut(operation, ::OperationLosses)
            record.retain(attacking.mapTo(HashSet()) { it.id })
            attacking.forEach { record.observe(it.id, it.members) }
            val fraction = record.fraction { id -> manager.get(id)?.members.orEmpty() }
            val standing = OperationLosses.standing(fraction)
            if (standings.put(operation, standing) != standing) {
                DebugFlags.log(LogGroup.ORDER, "[htn] operation={} event=attack_standing standing={} losses={}", operation, standing, "%.2f".format(fraction))
            }
            if (standing == OperationStanding.WITHDRAW) {
                attacking.forEach { squad ->
                    val view = snapshots[squad.id]
                    val reference = squad.members.firstNotNullOfOrNull { level.getEntity(it) as? NpcEntity }
                    manager.withdraw(squad.id, "operation_losses")
                    if (view != null && reference != null) manager.setObjective(level, squad.id, net.minecraft.core.BlockPos.containing(AttackAmmoWithdrawal.fallBack(level, reference, view)))
                    com.sbwnpc.squad.squad.SquadReports.attackFailedLosses(level.server, squad, (fraction * 100).toInt())
                }
                losses.remove(operation)
                standings.remove(operation)
                // The squads with a barracks fight on. This update still lists the withdrawn squads
                // with the operation's, so it is skipped like a full withdrawal; the next one plans
                // the attack without them.
                if (attacking.size < all.size) {
                    attacking.forEach { operations.detach(it.id) }
                    return standing
                }
                // Its support falls back with it, packing its tubes for the rally point (#258).
                operations.operation(operation)?.support?.keys?.forEach { manager.withdraw(it, "operation_losses") }
                operations.finish(operation, OperationStatus.CANCELLED)
                attacks.remove(operation)
                sectors.remove(operation)
                sectorTraces.remove(operation)
                flankTraces.remove(operation)
                boundTraces.remove(operation)
                sectorsHeld.remove(operation)
            }
            return standing
        }

        private fun nearest(points: List<Point3>, from: Point3) = points.minOfOrNull { hypot(it.x - from.x, it.z - from.z) } ?: 0.0

        /** Whether a reinforcement on its way stops to fight, see [ReinforcementMarch]; logs each change. */
        private fun reinforcementEngaged(squad: UUID, contacts: List<TacticalContact>, members: List<TacticalMember>, now: Long): Boolean {
            if (snapshots[squad]?.incoming?.isNotEmpty() == true) reinforcementFire[squad] = now
            val nearest = ReinforcementMarch.nearestContact(contacts.map { it.position.point() }, members.map { it.position.point() })
            val sinceFire = reinforcementFire[squad]?.let { now - it } ?: Long.MAX_VALUE
            val engaged = ReinforcementMarch.engaged(nearest, sinceFire, fighting = reinforcementEngaged[squad] == true)
            if (reinforcementEngaged.put(squad, engaged) != engaged) {
                DebugFlags.log(LogGroup.ORDER, "[htn] squad={} event=reinforcement_march engaged={} nearestContact={} ticksSinceFire={}", squad, engaged, nearest?.let { "%.0f".format(it) }, sinceFire)
            }
            return engaged
        }

        /** Where an attacking operation's squads close on: its area objective, or the order's own. */
        private fun attackObjective(operation: UUID, squads: List<Squad>): Point3? {
            if (squads.any { it.order != SquadOrder.ATTACK }) return null
            return currentAreaTask(operation)?.objective ?: squads.first().objective?.center?.point()
        }

        /**
         * Each member of an attack mapped to the bearing of its group's approach sector (see
         * [ApproachSectors]); empty before the first contact or outside an attack. A squad
         * attacking alone splits into its fire group, which pins head-on, and a manoeuvre group
         * ([ApproachSectors.manoeuvreGroup]) that takes a flank. Logs each change.
         */
        private fun approachBearings(operation: UUID, squads: List<Squad>, contact: Boolean): Map<UUID, Double> {
            val objective = attackObjective(operation, squads)
            val single = squads.singleOrNull()
            // Joined by another squad, a squad no longer splits; its old split must not bound on.
            if (objective == null || single == null) {
                squads.forEach {
                    manoeuvres.remove(it.id)
                    flankable.remove(it.id)
                }
            }
            if (objective == null) return emptyMap()
            val manoeuvre = single?.let(::manoeuvreGroup).orEmpty()
            if (single != null && manoeuvre.isEmpty()) return emptyMap()
            val members = squads.flatMap { saved -> snapshots.getValue(saved.id).members.map { SectorGroup(saved.id, it.id in manoeuvre) to it } }
            val centres = members.groupBy({ it.first }, { it.second.position }).mapValues { (_, at) -> Point3(at.map { it.x }.average(), at.map { it.y }.average(), at.map { it.z }.average()) }
            val bearings = sectors.getOrPut(operation, ::ApproachSectors).bearings(objective, centres, contact, single?.let { SectorGroup(it.id, false) })
            if (bearings.isNotEmpty() && sectorTraces.put(operation, bearings) != bearings) {
                DebugFlags.log(LogGroup.ORDER, "[htn] operation={} event=approach_sectors objective={} bearings={}", operation, objective, bearings.entries.associate { (group, bearing) -> group.toString() to Math.toDegrees(bearing).toInt() })
            }
            return members.mapNotNull { (group, member) -> bearings[group]?.let { member.id to it } }.toMap()
        }

        /**
         * The men of [squad], attacking alone, who go round to a flank, if it splits; empty while
         * it comes on frontally or its flank is blocked. Until its plan chose, it flanks as it always did.
         */
        private fun manoeuvreGroup(squad: Squad): Set<UUID> {
            // Counted over every man, not only the ready ones: a man dodging a grenade or with
            // the medic for a moment would be swapped for one of the fire group, who would run
            // off to the flank, or would end the split for the whole squad.
            val members = snapshots.getValue(squad.id).members
            val riflemen = members.filter { it.role == NpcClass.RIFLEMAN }.map { it.id }
            val assault = members.filter { it.role == NpcClass.GRENADIER || it.role == NpcClass.SHOTGUNNER }.map { it.id }
            val group = ApproachSectors.manoeuvreGroup(riflemen, assault, manoeuvres[squad.id].orEmpty(), members.size)
            if (group.isEmpty()) flankable -= squad.id else flankable += squad.id
            val split = approaches[squad.id] != SquadApproach.FRONTAL && flankOpen(squad.id, level.gameTime)
            return (if (split) group else emptySet()).also { manoeuvres[squad.id] = it }
        }

        /**
         * [proposal] with its post moved to a point that sees [defenders]. Chosen once per post and
         * kept, so a man is not shuffled about by every re-layout; a post that moves is searched anew.
         * A post with no sight anywhere near it is kept and traced, and searched again shortly (#93).
         */
        private fun withSight(operation: UUID, id: UUID, proposal: FrontProposal, defenders: List<Point3>, role: NpcClass): FrontProposal {
            if (proposal.duty == CombatDuty.REAR_GUARD || defenders.isEmpty()) return proposal
            val now = level.gameTime
            val kept = slotSights[id]?.takeIf { it.answers(proposal.anchor, now) }
            val chosen = if (kept != null) {
                kept.sighted
            } else {
                val sighted = FireSlotSight.choose(proposal.anchor, defenders, CombatPositionSafety.clearance(role)) { post, enemy ->
                    groundAt(post)?.let { feet ->
                        DetectionSightline.visible(level, feet.add(0.0, EYE_HEIGHT, 0.0), Vec3(enemy.x, enemy.y + 1.0, enemy.z), CollisionContext.empty())
                    } == true
                }?.let { found -> found.copy(post = groundAt(found.post)?.let { Point3(found.post.x, it.y, found.post.z) } ?: found.post) }
                slotSights[id] = SlotSight(proposal.anchor, sighted, now)
                if (sighted == null && DebugFlags.on(LogGroup.ORDER)) {
                    DebugFlags.log(
                        LogGroup.ORDER,
                        "[htn] operation={} event=slot_no_sight member={} slot={} ground={} clearance={} nearestDefender={}",
                        operation,
                        id,
                        proposal.anchor,
                        groundAt(proposal.anchor),
                        CombatPositionSafety.clearance(role),
                        defenders.minByOrNull { hypot(it.x - proposal.anchor.x, it.z - proposal.anchor.z) },
                    )
                }
                sighted
            }
            return if (chosen == null) proposal else proposal.copy(anchor = chosen.post)
        }

        /**
         * The standable floor under [point]'s column. A slot is planned at the objective's height,
         * so on a rise or in a hollow the floor is far from it: searching from there found none and
         * every candidate counted as blind, or buried the eye in a hill (#93).
         */
        private fun groundAt(point: Point3): Vec3? {
            val surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, net.minecraft.util.Mth.floor(point.x), net.minecraft.util.Mth.floor(point.z))
            return Terrain.feetAt(level, Vec3(point.x, surface.toDouble(), point.z))
        }

        /**
         * The noise [squads] send their scouts to look at, or null (#254): nothing in a fight or on a
         * reinforcement's march ([busy], which also forgets what was heard of it), nor while any of them is under an order that sends nobody
         * ([MemberDuties.SCOUTING_ORDERS]), nor one farther than [UnverifiedContacts.SCOUT_RADIUS] from
         * the point the squads hold or take. A spot a scout of [scouts] has come near or sees is checked
         * on the way, so the next one heard is the answer.
         */
        private fun alarmToCheck(squads: List<Squad>, scouts: Set<UUID>, busy: Boolean, anchor: () -> Point3, now: Long): Point3? {
            val boards = squads.mapNotNull { alarms[it.id] }
            // The noise of its own fight is no spot to look at once it is over.
            if (busy) boards.forEach { it.forget() }
            if (busy || boards.isEmpty() || squads.any { it.order.tactical() !in MemberDuties.SCOUTING_ORDERS }) return null
            val from = anchor()
            val pending = { boards.firstNotNullOfOrNull { it.pending(now, from, UnverifiedContacts.SCOUT_RADIUS) } }
            val spot = pending() ?: return null
            val seenBy = scouts.mapNotNull { level.getEntity(it) as? NpcEntity }.firstOrNull {
                it.position().point().horizontalDistanceSquared(spot) <= SquadAlarms.ARRIVAL * SquadAlarms.ARRIVAL || DeathSites.canConfirm(it, spot.native())
            } ?: return spot
            boards.forEach { it.checked(spot, now) }
            DebugFlags.log(LogGroup.HEARING, "[htn] squad={} event=alarm_checked spot={} by={}", squads.first().id, spot, seenBy.uuid)
            return pending()
        }

        /** Geometry proposes areas; actual arrival, useful fire and native reachability remain observations. */
        private fun layout(squads: List<Squad>, now: Long, holding: Boolean = false): List<SquadMission> {
            val seen = squads.flatMap { combatView(it.id)!!.contacts }.filter { now - it.seenAt <= 200 }.distinctBy { it.id }
            val members = squads.flatMap { snapshots.getValue(it.id).members }.sortedBy { it.id.toString() }
            // A reinforcement on its way keeps marching past the operation's distant fight.
            val reinforcement = squads.singleOrNull()?.takeIf { operations.pending(it.id) != null }
            val marching = reinforcement != null && !reinforcementEngaged(reinforcement.id, seen, members, now)
            val contacts = if (marching) emptyList() else seen
            if (reinforcement == null) {
                squads.forEach {
                    reinforcementFire.remove(it.id)
                    reinforcementEngaged.remove(it.id)
                }
            }
            val center = Vec3(members.map { it.position.x }.average(), members.map { it.position.y }.average(), members.map { it.position.z }.average())
            val focus = contacts.maxByOrNull { it.priority }?.position
            val operation = operations.operationOf(squads.first().id)?.id ?: squads.first().id
            val remembered = unverified.getOrPut(operation, ::LinkedHashMap)
            val rememberedSeen = unverifiedSeen.getOrPut(operation, ::HashMap)
            contacts.forEach {
                remembered[it.id] = it.position
                rememberedSeen[it.id] = now
            }
            val inspectAnchor = { UnverifiedContacts.inspectAnchor(currentAreaTask(operation)?.objective, squads.first().objective?.let { Vec3.atBottomCenterOf(it).point() }, center.point()) }
            val activeAttack = squads.firstOrNull { it.order == SquadOrder.ATTACK && it.commandOutcome == CommandOutcome.ACTIVE }?.objective?.let { Vec3.atBottomCenterOf(it).point() }
            val previousDuties = squads.flatMap { missions[it.id]?.teams.orEmpty() }.flatMap { it.members }.associate { it.actor.id to it.duty }
            val existingScouts = previousDuties.filterValues { it == CombatDuty.SEARCH }.keys
            val attacking = squads.all { it.order == SquadOrder.ATTACK }
            val busy = focus != null || marching
            val alarm = alarmToCheck(squads, existingScouts, busy, inspectAnchor, now)
            // Only an attack goes looking for an enemy it saw; under any other order it is the noise alone (#254).
            val inspect = UnverifiedContacts.inspectTarget(busy, inspectAnchor, if (attacking) remembered.values.map { it.point() } else emptyList(), alarm, activeAttack)?.native()
            // A sentry is no scout, on his height or still on his way to it: the scout rule comes before
            // his post and would take him off it. A height man of a squad that keeps sentries counts as one.
            val keepsSentries = squads.filter { it.order == SquadOrder.DEFEND || (it.order == SquadOrder.PATROL && !snapshots.getValue(it.id).hasPatrolRoute) }.mapTo(HashSet()) { it.id }
            val candidates = squads.flatMap { saved -> snapshots.getValue(saved.id).members.filterNot { saved.id in keepsSentries && it.role in HEIGHT_ROLES && it.primaryAmmo } }
                .filter { it.ready && previousDuties[it.id] != CombatDuty.OBSERVE }.map { it.id to it.position.point() }
            val scouts = UnverifiedContacts.scouts(candidates, existingScouts, inspect?.point())
            val actors = squads.flatMap { saved ->
                snapshots.getValue(saved.id).members.map { member ->
                    val previous = missions[saved.id]?.teams?.flatMap { it.members }?.firstOrNull { it.actor.id == member.id }
                    if (previous?.duty == CombatDuty.FOLLOW_ORDER) allocationOrigins.remove(member.id)
                    FormationActor(
                        member.id,
                        saved.id,
                        allocationOrigins.getOrPut(member.id) { member.position.point() },
                        max(CombatPositionSafety.clearance(member.role) + 8, member.role.assaultDistance + 8),
                    )
                }
            }
            val enemies = contacts.map { it.position.point() }
            val bearings = approachBearings(operation, squads, focus != null)
            val objective = attackObjective(operation, squads)
            val proposals = if (bearings.isEmpty() || objective == null) {
                OperationFront.propose(actors, enemies, focus?.point(), formationDuties)
            } else {
                // Only the point's defenders set the sector lines back: a contact off to one side
                // would push a line far from the fight.
                val defenders = enemies.filter { OperationTactics.atObjective(it, objective) }
                actors.groupBy { bearings.getValue(it.id) }.flatMap { (bearing, sector) ->
                    OperationFront.propose(sector, defenders, objective, formationDuties, bearing).entries
                }.associate { (id, proposal) -> id to withSight(operation, id, proposal, defenders, members.first { it.id == id }.role) }
            }
            proposals.forEach { (id, proposal) -> formationDuties[id] = proposal.duty }
            // A man far round the objective from his sector gets there along the arc, not across the
            // point. Once the assault is on, its closing posts lead him to the point instead.
            val waypoints = if (bearings.isEmpty() || objective == null || (attacks[operation]?.phase ?: AttackPhase.DEPLOY) != AttackPhase.DEPLOY) {
                emptyMap()
            } else {
                // A man closing in by bounds may stray off his sector's bearing; called back onto
                // the arc, he would leave the bound and come back to it.
                val bounders = attacks[operation]?.bounders.orEmpty()
                members.filter { it.id !in bounders }.mapNotNull { member ->
                    val post = proposals.getValue(member.id).anchor
                    val slack = if (member.id in offArc) ApproachSectors.ARC_RESUME_SLACK else 0.0
                    ApproachSectors.arcWaypoint(objective, member.position.point(), bearings.getValue(member.id), hypot(post.x - objective.x, post.z - objective.z), slack)?.let { member.id to it }
                }.toMap()
            }
            val wasArcing = members.filter { it.id in arcing }.mapTo(HashSet()) { it.id }
            members.forEach { if (it.id in waypoints) arcing += it.id else arcing -= it.id }
            members.forEach { if (it.id in waypoints || it.id !in bearings) offArc -= it.id else offArc += it.id }
            // A sector is held once at most half its ready men are still on the arc.
            val held = members.filter { it.ready }.groupBy { bearings[it.id] }.values.all { group -> group.count { it.id in waypoints } * 2 <= group.size }
            sectorsHeld[operation] = held
            squads.singleOrNull()?.let { noteBlockedFlank(it.id, waypoints.keys, now) }
            if (flankTraces.put(operation, waypoints.size) != waypoints.size && bearings.isNotEmpty()) {
                DebugFlags.log(LogGroup.ORDER, "[htn] operation={} event=flank_march flanking={} of={} held={}", operation, waypoints.size, members.size, held)
            }
            return squads.map { saved ->
                val view = snapshots.getValue(saved.id)
                if (view.incoming.isNotEmpty()) lastHits[saved.id] = now
                if (view.closeContact) closeContacts[saved.id] = now
                // Falling back, only an enemy its men see near is a fight: on reported ones the
                // squad bounded all the way back (#253).
                val retreating = saved.order == SquadOrder.RETREAT
                val contact = if (retreating) closeContacts[saved.id]?.let { now - it <= RETREAT_CONTACT_TICKS } == true else focus != null
                if (focus == null) {
                    lastAbove.remove(saved.id)
                } else if (focus.y > view.center.y + 4) {
                    lastAbove[saved.id] = now
                }
                val situation = CombatSituation(
                    contact,
                    // An operation reviewing its losses holds the line it has instead of pushing on;
                    // a squad with a barracks presses on (#57).
                    saved.order == SquadOrder.ATTACK && (!holding || saved.barracks != null),
                    focus != null && lastAbove[saved.id]?.let { now - it <= ENEMY_ABOVE_TICKS } == true,
                    view.incoming.isNotEmpty(),
                    view.fighting.size.toDouble() / max(1, view.members.size),
                    1.0 - saved.members.size.toDouble() / max(1, max(view.peakStrength, saved.members.size)),
                    view.fighting.isNotEmpty(),
                    reinforced = saved.barracks != null,
                    underFire = lastHits[saved.id]?.let { now - it <= UNDER_FIRE_TICKS } == true,
                    retreating = retreating,
                )
                val strategy = strategies[saved.id]?.first
                val strategyKept = strategy == null || seenStrategies.put(saved.id, strategy) == strategy
                val old = missions[saved.id]
                val oldIntents = old?.teams?.flatMap { it.members }?.associateBy { it.actor.id }.orEmpty()
                // Marching on, an attack's reinforcement makes for the operation's entry itself: left to
                // the order's own march, each man's sighted target still drew him off after it.
                val entry = if (marching && saved.order == SquadOrder.ATTACK) operations.pending(saved.id)?.let { operations.operation(it.operation) }?.entry?.center else null
                val following = saved.order == com.sbwnpc.squad.squad.SquadOrder.FOLLOW
                val transit = SquadTransit.distant(saved, level)
                val leader = if (following) level.getPlayerByUUID(saved.owner) else null
                val heading = saved.followHeading.takeIf { it != net.minecraft.world.phys.Vec3.ZERO }?.let { it.x to it.z }
                val intents = view.members.mapIndexed { index, member ->
                    val man = if (following || retreating) level.getEntity(member.id) as? NpcEntity else null
                    val leaderAt = leader?.position()?.point()
                    val toLeader = leaderAt?.let { kotlin.math.hypot(member.position.x - it.x, member.position.z - it.z) } ?: 0.0
                    val waypoint = waypoints[member.id]
                    val proposal = waypoint?.let { FrontProposal(CombatDuty.MOVE, it, null) } ?: proposals.getValue(member.id)
                    val ruled = entry?.takeUnless { transit }?.let { RuledDuty("reinforcement_entry", DutyChoice(CombatDuty.MOVE, it)) } ?: MemberDuties.choose(
                        DutyInputs(
                            order = saved.order.tactical(),
                            position = member.position.point(),
                            proposal = proposal,
                            objective = saved.objective?.center?.point(),
                            inspect = inspect?.point(),
                            inContact = contact,
                            elsewhere = saved.objectiveDimension?.let { it != level.dimension() } == true,
                            scout = member.id in scouts,
                            heightRole = member.role in HEIGHT_ROLES,
                            primaryAmmo = member.primaryAmmo,
                            sidearm = member.sidearmOnly,
                            hasPatrolRoute = view.hasPatrolRoute,
                            reachedMove = actions[member.id]?.let { it.intent.duty == CombatDuty.MOVE && it.reached() } == true,
                            // A pistol's FIRE is no post he settled on: with his main weapon back he moves on with the others.
                            wasFiring = oldIntents[member.id]?.duty == CombatDuty.FIRE && dutyRules[member.id] != "sidearm_fire",
                            // A patrol roams its whole area, so a sentry inside it has arrived; the defenders' ring radius
                            // sent him roaming with the riflemen until he happened to pass near the objective.
                            arrival = if (saved.order == SquadOrder.PATROL) {
                                com.sbwnpc.squad.combat.SquadFormation.patrolRadius(saved.members.size)
                            } else {
                                com.sbwnpc.squad.combat.SquadFormation.defendArrivalRadius(saved.members.size)
                            },
                            wasObserving = oldIntents[member.id]?.let { it.duty == CombatDuty.OBSERVE && it.anchor == saved.objective?.center?.point() } == true,
                            flanking = waypoint != null,
                            following = following,
                            // Halves by place among the men here now, not by saved slot: after losses
                            // a slot parity can leave one half empty, so nobody covers or nobody runs.
                            followBound = following && OperationTactics.followBound(index, now, toLeader),
                            followSlot = leaderAt?.let { com.sbwnpc.squad.combat.FollowFormation.slot(it, heading, saved.members.indexOf(member.id), heading != null) },
                            followRide = leader?.vehicle != null || man?.holds(com.sbwnpc.squad.entity.NpcActivity.VEHICLE) == true,
                            transit = transit,
                            // A man his out-of-ammunition rule runs back is not his squad's to move (#253).
                            retreatSlot = man?.takeIf { retreating && movable(it) }?.let { m -> m.homeCenter()?.let { com.sbwnpc.squad.combat.SquadAreas.rallySlot(m, it).point() } },
                        ),
                        FIRE_POST_REACH,
                        CombatPosition.LOCAL_MOVE_RADIUS,
                    )
                    if (dutyRules.put(member.id, ruled.rule) != ruled.rule) {
                        DebugFlags.log(LogGroup.ORDER, "[htn] actor={} event=duty_rule rule={} duty={}", member.id, ruled.rule, ruled.choice.duty)
                        DecisionTrace.man(member.id, now, "duty", "${ruled.choice.duty} <- ${ruled.rule}")
                    }
                    val choice = ruled.choice
                    val duty = choice.duty
                    val proposed = choice.anchor.native()
                    val before = oldIntents[member.id]
                    // A bound's place moves with the player: kept for the whole beat, it was where he
                    // stood five seconds before. Nor does it wait for cover: when the whole squad runs to
                    // catch up there is nobody left to give it, and the run was planned over and over.
                    val bounding = following && duty == CombatDuty.MOVE
                    // Falling back he runs for his place whatever fires at him, as an ordered retreat
                    // always did: waiting for covering fire or shelter, a squad with nobody left to
                    // give it stayed where it was ordered away from. In contact its halves cover
                    // each other instead ([CombatStrategy.BOUND_OVERWATCH]).
                    val fallingBack = retreating && duty == CombatDuty.MOVE
                    val requireCover = PostRequirements.cover(duty, situation.incoming) && !bounding && !fallingBack
                    val requireShelter = PostRequirements.shelter(duty, situation.incoming) && !fallingBack
                    val settled = strategyKept && (focus?.distanceTo(plannedContact[member.id] ?: focus) ?: 0.0) < 8
                    val moving = waypoint != null || member.id in wasArcing || bounding
                    val byLegs = ruled.rule in LEG_RULES
                    // A blind slot brought nearer than the role's clearance lets its man come that near (#93).
                    val sightClearance = slotSights[member.id]?.sighted?.takeIf { waypoint == null && it.post == proposal.anchor }?.clearance
                    val retained = before?.takeIf { MemberDuties.keeps(it, WantedPost(duty, proposed.point(), requireCover, requireShelter, byLegs), settled, moving) }
                    if (retained != null) {
                        retained
                    } else {
                        val anchor = proposed
                        val watch = if (duty == CombatDuty.REAR_GUARD) {
                            proposal.watch?.native()
                        } else if (duty == CombatDuty.SEARCH) {
                            inspect
                        } else {
                            focus
                        }
                        plannedContact[member.id] = focus
                        PositionIntent(
                            AgentId(AgentLevel.NPC, member.id),
                            duty,
                            member.position.point(),
                            anchor.point(),
                            watch?.point(),
                            requireCover = requireCover,
                            requireShelter = requireShelter,
                            minimumHeight = if (duty.heightPost) member.position.y - MemberDuties.HEIGHT_POST_DROP else null,
                            inspection = if (duty == CombatDuty.SEARCH) inspect?.point() else null,
                            standoff = sightClearance?.let { minOf(it, CombatPositionSafety.clearance(member.role)) } ?: CombatPositionSafety.clearance(member.role),
                            sightClearance = sightClearance,
                            ordered = bounding,
                            legs = byLegs,
                        )
                    }
                }
                if (following && focus != null && followBeats.put(saved.id, now / OperationTactics.BOUND_TICKS) != now / OperationTactics.BOUND_TICKS) {
                    DebugFlags.log(LogGroup.ORDER, "[htn] squad={} event=follow_bound running={} of={}", saved.id, intents.count { it.duty == CombatDuty.MOVE }, intents.size)
                }
                val movers = intents.filter { it.legs && it.duty == CombatDuty.MOVE && !it.assault && !it.ordered }
                // Only an attack or a retreat bounds, and only an attack regroups. Measured for every
                // squad, the spread of one walking behind its player changed its situation, and
                // replanned it, nearly every time.
                situations[saved.id] = if (!situation.offensive && !retreating) {
                    situation
                } else {
                    val radius = com.sbwnpc.squad.combat.SquadFormation.perimeterRadius(view.members.size)
                    // Sentries, rear guards and scouts stand off from the squad on purpose: counted, they
                    // were called down to the rally and sent back again.
                    val distances = intents.filter { !it.duty.watching && it.duty.commandsMovement && it.actor.id !in waypoints }
                        .mapNotNull { intent -> view.members.firstOrNull { it.id == intent.actor.id }?.position?.let { hypot(it.x - view.center.x, it.z - view.center.z) } }
                    situation.copy(
                        movers = movers.size,
                        flanking = view.members.any { it.id in waypoints },
                        scattered = distances.size >= REGROUP_MIN_MEN && distances.count { it > 2 * radius }.let { it >= REGROUP_MIN_SCATTERED && it * 3 >= distances.size },
                        gathered = distances.all { it <= radius },
                        regrouping = strategy == CombatStrategy.REGROUP && strategies[saved.id]!!.second + REGROUP_MAX_TICKS > now,
                        regroupSpent = (strategy == CombatStrategy.REGROUP && strategies[saved.id]!!.second + REGROUP_MAX_TICKS <= now) ||
                            regroupEnds[saved.id]?.let { now - it < REGROUP_COOLDOWN_TICKS } == true,
                    )
                }
                updateBoundTurn(saved.id, movers, view, focus, now)
                if (retreating) coverRetreat(saved, view, contact) else retreatSmoke.forget(saved.id)
                updateRally(saved.id, strategy, view.center, focus)
                val teams = teamAllocators.getOrPut(saved.id, ::TaskTeamAllocator).assign(saved.id, intents)
                SquadMission(AgentId(AgentLevel.SQUAD, saved.id), teams).also { missions[saved.id] = it }
            }
        }

        /**
         * Screens a retreating squad with smoke once it is fired on ([RetreatSmoke]): towards the
         * nearest enemy its men see in [contact], or else where the fire came from. Squads pulled
         * back out of contact mostly threw nothing until fire from an unseen enemy counted too
         * (in-game 21.10).
         */
        private fun coverRetreat(saved: Squad, view: TacticalSnapshot, contact: Boolean) {
            val fireFrom = view.incoming.firstOrNull()
            val men = view.members.mapNotNull { level.getEntity(it.id) as? NpcEntity }
            // A target one of them fights, not a contact only reported to the squad.
            val seen = men.mapNotNull { it.target?.takeIf { target -> target.isAlive } }.minByOrNull { it.position().distanceToSqr(view.center) }?.position()
            val toward = if (contact) seen ?: fireFrom else fireFrom
            retreatSmoke.cover(saved.id, saved.orderStamp, men, boundTurns[saved.id]?.turn?.posts?.keys.orEmpty(), toward)
        }

        /**
         * Starts or swaps the turn of a squad bounding by halves. A turn ends once every mover of
         * its moving half still moving has finished a leg more than when it began, or after
         * [BOUND_TURN_TICKS] ([RETREAT_BOUND_TURN_TICKS] in a retreat); the other movers then move. The first halves alternate along the
         * front, so each half covers all of it.
         */
        private fun updateBoundTurn(squad: UUID, movers: List<PositionIntent>, view: TacticalSnapshot, focus: Vec3?, now: Long) {
            if (strategies[squad]?.first != CombatStrategy.BOUND_OVERWATCH) {
                boundTurns.remove(squad)
                return
            }
            val ids = movers.map { it.actor.id }
            val current = boundTurns[squad]
            val reason = when {
                current == null -> "start"
                now - current.startedAt >= if (situations[squad]?.retreating == true) RETREAT_BOUND_TURN_TICKS else BOUND_TURN_TICKS -> "timeout"
                current.turn.moving.filter { it in ids }.all { (legs[it] ?: 0) > (current.legsAtStart[it] ?: 0) } -> "legs_done"
                else -> return
            }
            val waiting = ids.filter { current != null && it !in current.turn.moving }
            val moving = waiting.ifEmpty { alongFront(movers, view.center, focus).filterIndexed { index, _ -> index % 2 == 0 } }.toSet()
            val posts = view.members.filter { it.id !in moving }.associate { it.id to it.position.point() }
            val turn = BoundTurn((current?.turn?.number ?: -1) + 1, moving, posts)
            boundTurns[squad] = BoundState(turn, now, moving.associateWith { legs[it] ?: 0 })
            DebugFlags.log(LogGroup.ORDER, "[htn] squad={} event=bound_turn number={} reason={} moving={} firing={}", squad, turn.number, reason, moving.size, ids.size - moving.size)
        }

        /**
         * Whether [squad] may choose to flank: it can split off a manoeuvre group, and its flank was
         * not found blocked within [FLANK_BLOCK_TICKS].
         */
        // A scattered squad gathers before a group splits off it again: split, its flankers kept
        // it from regrouping, as Regroup waits for them.
        private fun flankOpen(squad: UUID, now: Long) =
            squad in flankable && flankBlockedAt[squad]?.let { now - it < FLANK_BLOCK_TICKS } != true &&
                situations[squad]?.let { it.scattered || it.regrouping } != true

        /**
         * Notes the flank of [squad] blocked when at least half of its manoeuvre group still on the
         * arc ([flanking]) failed [FLANK_BLOCK_FAILURES] post searches in a row there.
         */
        private fun noteBlockedFlank(squad: UUID, flanking: Set<UUID>, now: Long) {
            val group = manoeuvres[squad].orEmpty().filter { it in flanking }
            if (group.isEmpty()) return
            val stuck = group.count { id -> failures[id]?.let { now < it.failedAt + FAILURE_MEMORY_TICKS && it.attempts >= FLANK_BLOCK_FAILURES } == true }
            if (stuck * 2 < group.size || flankBlockedAt[squad]?.let { now - it < FLANK_BLOCK_TICKS } == true) return
            flankBlockedAt[squad] = now
            DebugFlags.log(LogGroup.ORDER, "[htn] squad={} event=flank_blocked stuck={} of={}", squad, stuck, group.size)
        }

        /** Fixes where [squad] gathers once it regroups: [REGROUP_PULLBACK] behind its centre, away from [focus]. */
        private fun updateRally(squad: UUID, strategy: CombatStrategy?, center: Vec3, focus: Vec3?) {
            if (strategy != CombatStrategy.REGROUP) {
                rallies.remove(squad)
                return
            }
            if (squad in rallies) return
            val away = focus?.let { Vec3(center.x - it.x, 0.0, center.z - it.z) }?.takeIf { it.length() >= 1.0 }?.normalize()
            val rally = away?.let { center.add(it.scale(REGROUP_PULLBACK)) } ?: center
            rallies[squad] = rally.point()
            DebugFlags.log(LogGroup.ORDER, "[htn] squad={} event=regroup rally={}", squad, net.minecraft.core.BlockPos.containing(rally).toShortString())
        }

        /** The movers' ids in order along the front, across the line from [center] to [focus]. */
        private fun alongFront(movers: List<PositionIntent>, center: Vec3, focus: Vec3?): List<UUID> {
            val dx = (focus?.x ?: center.x) - center.x
            val dz = (focus?.z ?: (center.z + 1)) - center.z
            return movers.sortedBy { (it.origin.x - center.x) * dz - (it.origin.z - center.z) * dx }.map { it.actor.id }
        }

        /**
         * Whether [actor]'s next rush is covered ([PositionIntent.coveredBy]): one of the men covering
         * it fired lately, none of them has anything to fire at, or he has waited
         * [SUPPORT_FALLBACK_TICKS] since his last leg.
         */
        private fun supportFiring(actor: AgentId, now: Long): Boolean {
            val cover = goals[actor]?.coveredBy?.takeIf { it.isNotEmpty() } ?: return true
            val leg = legs[actor.id] ?: 0
            val since = supportWaits[actor.id]?.takeIf { it.first == leg }?.second ?: now.also { supportWaits[actor.id] = leg to it }
            if (now - since >= SUPPORT_FALLBACK_TICKS) return true
            val men = cover.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive }
            return men.none { it.target != null } || men.any { it.firedRecently(SUPPORT_FIRE_TICKS) }
        }
        fun keepAloft(npc: NpcEntity) = air.keepAloft(npc, level.gameTime)

        fun keepFlying(npc: NpcEntity) = drones.keepFlying(npc)

        fun tick(npc: NpcEntity) {
            refresh(npc)
            val now = level.gameTime
            if (parentTick != now) {
                parentTick = now
                agents.values.filter { it.agent.level != AgentLevel.NPC }.sortedBy { it.agent.level.ordinal }.forEach { agent ->
                    agent.tick(facts(agent.agent, now), planningWork = 8)
                    report(agent, now)
                    if (agent.agent.level == AgentLevel.OPERATION && agent.runtime.reports["secure"]?.status == ExecutionStatus.COMPLETED) {
                        val task = currentAreaTask(agent.agent.id)
                        if (task != null) completions[agent.agent.id] = task
                    }
                }
                finishAreas()
                if (DecisionTrace.on) {
                    DecisionTrace.tick(level.server.tickCount.toLong(), VersionCompat.serverDirectory(level.server).resolve("logs").resolve(DecisionTrace.FILE)) { NativeTactics.traceSections() }
                }
            }
            val id = AgentId(AgentLevel.NPC, npc.uuid)
            val agent = agents[id] ?: return
            val observation = facts(id, now)
            // Not while a search runs: restarted every tick, a plan of more steps than one tick's
            // planning work would never finish.
            if (agent.runtime.completed && agent.status != PlanningStatus.PLANNING && actions[npc.uuid]?.reached() == false) agent.repair(observation)
            agent.tick(observation, planningWork = 4)
            actions[npc.uuid]?.maintainLook()
            report(agent, now)
        }

        /** The options each agent's current plan search weighed per task, until its result comes in. */
        private val weighing = HashMap<AgentId, LinkedHashMap<String, MutableList<String>>>()

        /** The squad or operation an agent's decisions are filed under; a man's own go to him. */
        private fun traceGroup(id: AgentId): UUID? =
            when (id.level) {
                AgentLevel.OPERATION, AgentLevel.SQUAD -> id.id
                AgentLevel.TEAM -> fireSupport.roster.groupOf(id) ?: air.roster.groupOf(id) ?: missions.entries.firstOrNull { (_, mission) -> mission.teams.any { it.agent == id } }?.key
                AgentLevel.NPC -> null
            }

        /** Gathers what the planner weighed; the decision is recorded once its result is in ([flushPlanning]). */
        private fun tracePlanning(id: AgentId, event: PlanningEvent) {
            val method = event.method ?: return
            val option = when (val verdict = event.verdict) {
                Verdict.Allowed -> "$method ${event.score?.value?.let { "%.2f".format(it) } ?: "-"}"
                is Verdict.Rejected -> "$method rejected: ${verdict.reason}"
                is Verdict.Unknown -> "$method unknown: ${verdict.facts}"
            }
            val options = weighing.getOrPut(id, ::LinkedHashMap).getOrPut(event.node, ::ArrayList)
            if (option !in options) options += option
        }

        /**
         * Records each task the search had a real choice at (more than one option, or a rejection),
         * the option the plan took marked `*`, and how the search ended.
         */
        private fun flushPlanning(id: AgentId, result: PlanningResult) {
            val weighed = weighing.remove(id) ?: return
            val taken = (result as? PlanningResult.Ready)?.plan?.tasks?.map { it.node }.orEmpty()
            val record = { event: String, detail: String ->
                val group = traceGroup(id)
                if (group != null) {
                    DecisionTrace.group(group, level.gameTime, "${id.level} ${DecisionTrace.short(id.id)}", event, detail)
                } else if (id.level == AgentLevel.NPC) {
                    DecisionTrace.man(id.id, level.gameTime, event, detail)
                }
            }
            weighed.filterValues { options -> options.size > 1 || options.any { "rejected" in it } }.forEach { (node, options) ->
                val marked = options.map { option ->
                    val method = option.substringBefore(' ')
                    if (taken.any { it == "$node/$method" || it.startsWith("$node/$method/") }) "*$option" else option
                }
                record("plan", "$node: ${marked.joinToString("; ")}")
            }
            when (result) {
                is PlanningResult.Failed -> record("plan_failed", result.reasons.distinct().joinToString("; ") + if (result.limitReached) " (search limit)" else "")
                is PlanningResult.WaitingForFacts -> record("plan_waiting", result.facts.joinToString(", "))
                else -> Unit
            }
        }

        private fun traceAction(id: AgentId, reason: String?) {
            val now = level.gameTime
            val group = traceGroup(id)
            if (group != null) DecisionTrace.group(group, now, "${id.level} ${DecisionTrace.short(id.id)}", "action_failed", reason.orEmpty()) else DecisionTrace.man(id.id, now, "action_failed", reason.orEmpty())
        }

        /** The ids whose trace records are still wanted: squads, their operations and men in this level. */
        fun traceIds(): Set<UUID> {
            weighing.keys.retainAll(agents.keys)
            val manager = SquadManager.get(level)
            return manager.all().flatMapTo(HashSet()) { squad -> squad.members + squad.id + listOfNotNull(operations.operationOf(squad.id)?.id) }
        }

        /** Each squad with men in this level, its chain per man and its last decisions, for [DecisionTrace]. */
        fun traceSections(): List<DecisionTrace.Section> {
            val manager = SquadManager.get(level)
            return manager.all().mapNotNull { squad ->
                val men = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }
                if (men.isEmpty()) return@mapNotNull null
                val operation = operations.operationOf(squad.id)
                val command = squad.command
                val header = listOf(
                    "order=${squad.order} command=${command?.order}/${command?.id?.let(DecisionTrace::short)} goal=${command?.goal} outcome=${squad.commandOutcome}${squad.commandReason?.let { " reason=$it" }.orEmpty()} objective=${squad.objective?.toShortString()}",
                    "operation=${operation?.id?.let(DecisionTrace::short)} squads=${operation?.members?.size ?: 1} phase=${operation?.let { attackPhase(it.id) }} standing=${operation?.let { standings[it.id] }}",
                    "strategy=${strategyOf(squad.id)} approach=${approachOf(squad.id)}",
                )
                val teams = missions[squad.id]?.teams.orEmpty()
                val chains = men.map { npc -> chainOf(npc, teams) }
                val decisions = (
                    DecisionTrace.decisionsOf(squad.id) + operation?.let { DecisionTrace.decisionsOf(it.id) }.orEmpty() +
                        men.flatMap { DecisionTrace.decisionsOf(it.uuid) }
                    ).sortedBy { it.tick }.takeLast(DecisionTrace.LAST_DECISIONS)
                DecisionTrace.Section("${squad.name} ${squad.id}", header, chains, decisions)
            }
        }

        /** One man's line: his duty and the rule that set it, his post, his action or why he waits, and what else holds him. */
        private fun chainOf(npc: NpcEntity, teams: List<TeamMission>): String {
            val action = actions[npc.uuid]
            val intent = action?.intent
            val team = teams.firstOrNull { team -> team.members.any { it.actor.id == npc.uuid } }?.agent?.id?.let(DecisionTrace::short)
            val reports = agents[AgentId(AgentLevel.NPC, npc.uuid)]?.runtime?.reports.orEmpty()
                .entries.joinToString(",") { (task, report) -> "$task:${report.status}${report.reason?.let { "($it)" }.orEmpty()}" }
            val failure = failures[npc.uuid]?.takeIf { level.gameTime < it.failedAt + FAILURE_MEMORY_TICKS }
            val target = npc.target?.let { "${DecisionTrace.short(it.uuid)}@${npc.distanceTo(it).toInt()}" }
            val flags = listOfNotNull("suppressed".takeIf { npc.isSuppressed() }, "alert".takeIf { npc.isAlert() }, "in_vehicle".takeIf { npc.vehicle != null })
            return "${DecisionTrace.short(npc.uuid)} ${npc.npcClass} team=$team duty=${intent?.duty} <- ${dutyRules[npc.uuid]} anchor=${intent?.anchor?.let { "%.0f,%.0f,%.0f".format(it.x, it.y, it.z) }}" +
                " post=${action?.assignment?.position?.let { "%.0f,%.0f,%.0f".format(it.x, it.y, it.z) }} reached=${action?.reached()} action=[$reports]" +
                (failure?.let { " failed=${it.attempts}x ${it.rejections}" }.orEmpty()) +
                " target=$target holds=${npc.activitiesHeld()} $flags"
        }

        private val areaTasks = HashMap<UUID, AreaControlTask>()
        fun holdsArea(squad: UUID) = areaTasks.values.any { squad in it.commands.keys }
        fun strategyOf(squad: UUID) = strategies[squad]?.first
        fun approachOf(squad: UUID) = approaches[squad]
        fun boundTurnOf(squad: UUID) = boundTurns[squad]?.turn?.number
        fun postFailuresOf(actor: UUID) = failures[actor]?.takeIf { level.gameTime < it.failedAt + FAILURE_MEMORY_TICKS }?.attempts ?: 0
        fun attackPhase(operation: UUID): AttackPhase? = attacks[operation]?.phase
        private fun currentAreaTask(operation: UUID): AreaControlTask? = areaTasks[operation]
        private fun finishAreas() {
            val manager = SquadManager.get(level)
            completions.values.toList().forEach { task ->
                if (areaClocks[task.operation]?.completed != true) return@forEach
                val squads = task.commands.keys.mapNotNull(manager::get)
                if (squads.size != task.commands.size || squads.any { commandToken(it) != task.commands[it.id] || it.order != SquadOrder.ATTACK }) return@forEach
                squads.forEach { squad ->
                    com.sbwnpc.squad.experience.ExperienceAwards.objective(level.server, squad)
                    manager.completeCommand(squad.id, "occupied_and_inspected_area")
                    com.sbwnpc.squad.squad.SquadReports.holding(level.server, squad, SquadOrder.ATTACK)
                    manager.setOrder(squad.id, SquadOrder.DEFEND)
                }
                operations.finish(task.operation, OperationStatus.COMPLETED)
                DebugFlags.log(LogGroup.ORDER, "[htn] operation={} event=attack_completed reason=occupied_and_inspected_area heldTicks={}", task.operation, areaClocks[task.operation]?.heldTicks)
            }
            completions.clear()
        }
        private fun report(agent: PlanningAgent, now: Long) {
            val assignment = assignments[agent.agent] ?: return
            val reports = agent.runtime.reports.values
            val status = when {
                agent.status == PlanningStatus.COMPLETED && agent.runtime.completed -> AssignmentStatus.SUCCEEDED
                reports.any { it.status == ExecutionStatus.FAILED } -> AssignmentStatus.WAITING
                reports.isNotEmpty() && reports.all { it.status == ExecutionStatus.SUSPENDED } -> AssignmentStatus.WAITING
                else -> AssignmentStatus.ACTIVE
            }
            val sequence = sequences.merge(assignment.ticket, 1, Long::plus)!!
            ledger.report(
                ChildReport(
                    assignment.ticket,
                    sequence,
                    now,
                    status,
                    if (status == AssignmentStatus.SUCCEEDED) reports.flatMap { it.evidence } else emptyList(),
                    reports.firstOrNull { it.status in setOf(ExecutionStatus.FAILED, ExecutionStatus.SUSPENDED) }?.reason,
                    revision = assignment.revision,
                ),
                now,
            )
        }
        fun clear() {
            agents.values.filter { it.agent.level == AgentLevel.OPERATION }.toList().forEach { it.cancel(level.gameTime, "server_stopped") }
            agents.clear()
            actions.clear()
            heldPosts.clear()
            legs.clear()
            cover.clear()
            supportWaits.clear()
            fireSupport.clear()
            air.clear()
            drones.clear()
            loneOperators.clear()
            alarms.clear()
            closeContacts.clear()
            retreatSmoke.clear()
            grenades.clear()
        }
    }
}
