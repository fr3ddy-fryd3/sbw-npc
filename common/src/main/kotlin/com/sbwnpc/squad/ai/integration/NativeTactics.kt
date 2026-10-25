package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.coordination.*
import com.sbwnpc.squad.ai.planning.*
import com.sbwnpc.squad.ai.tactics.*
import com.sbwnpc.squad.combat.*
import com.sbwnpc.squad.combat.tactics.*
import com.sbwnpc.squad.entity.NpcEntity
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
    private val LEG_RULES = setOf("mover", "fire_approach")

    /** A bounding half that has not finished a leg each by then hands over anyway, 15 s. */
    private const val BOUND_TURN_TICKS = 300L

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
        if (npc.vehicle == null && npc.npcClass in GROUND && npc.currentSquad() != null) world(npc).refresh(npc)
    }
    fun tick(npc: NpcEntity) = world(npc).tick(npc)
    fun assignment(npc: NpcEntity): NativeAssignment? {
        if (npc.vehicle != null || npc.npcClass !in GROUND || npc.currentSquad() == null) return null
        refresh(npc)
        return world(npc).actions[npc.uuid]?.takeIf { it.intent.commandsMovement }?.assignment
    }

    /** How many of [members] hold a slot [FireSlotSight] found no point for that sees the defence; GameTests read it. */
    internal fun slotsWithoutSight(level: ServerLevel, members: Collection<UUID>): Int = worlds[level]?.slotsWithoutSight(members) ?: 0

    fun ownsLook(npc: NpcEntity): Boolean = worlds[npc.level()]?.actions?.get(npc.uuid)?.ownsLook == true
    fun ownsMovement(npc: NpcEntity): Boolean {
        tick(npc)
        return assignment(npc) != null && movable(npc)
    }
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
    }
    fun snapshot(npc: NpcEntity): TacticalSnapshot? = npc.squadId?.let { world(npc).combatView(it) }
    fun reports(npc: NpcEntity) = world(npc).agents[AgentId(AgentLevel.NPC, npc.uuid)]?.runtime?.reports.orEmpty()

    /** How many post searches in a row [npc] failed round his task's anchor, while remembered. */
    fun postFailures(npc: NpcEntity): Int = world(npc).postFailuresOf(npc.uuid)
    fun movable(npc: NpcEntity) =
        npc.isAlive && npc.vehicle == null && !npc.busyWithRole() && !npc.resupplying && !npc.diggedIn &&
            !npc.evadingGrenade() && !npc.movementLockedByCover() && !npc.combatLockedByMedic() && npc.retreatPoint() == null
    private val GROUND = setOf(NpcClass.RIFLEMAN, NpcClass.SHOTGUNNER, NpcClass.MACHINE_GUNNER, NpcClass.SNIPER, NpcClass.GRENADIER, NpcClass.MEDIC, NpcClass.DRONE_OPERATOR)

    private class PendingDeath(val entity: LivingEntity, val cancelled: () -> Boolean)

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

        // Each sentry's last height post, offered back when an OVERWATCH duty returns to him.
        private val heldPosts = HashMap<UUID, HeldPost>()
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
                        if (event.report.status == ExecutionStatus.FAILED) DebugFlags.log(LogGroup.ORDER, "[htn] actor={} event=action_failed reason={}", id.id, event.report.reason)
                    },
                ) { feedback ->
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
        }
        private fun facts(id: AgentId, now: Long): FactSnapshot {
            val empty = FactSnapshot(id, now)
            return when (id.level) {
                AgentLevel.OPERATION -> operationFacts(empty)
                AgentLevel.SQUAD -> squadFacts(empty)
                AgentLevel.TEAM -> empty
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
            return result
        }
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
            val squad = reference.currentSquad() ?: return
            if (now < nextObservation && knownOrders[squad.id] == squad.orderStamp) return
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
                snapshots.keys.filter { it !in activeIds }.toList().forEach { id ->
                    snapshots.remove(id)
                    missions.remove(id)
                    situations.remove(id)
                    boundTurns.remove(id)
                    rallies.remove(id)
                    lastHits.remove(id)
                    lastAbove.remove(id)
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
                val groups = loaded.filter { knownOrders[it.first.id] != it.first.orderStamp }.groupBy { (it.first.command?.id ?: it.first.tacticalOperation ?: it.first.id) to it.first.order }
                groups.forEach { (group, selected) ->
                    val first = selected.first().first
                    val view = snapshots.getValue(first.id)
                    val objective = first.tacticalObjective ?: first.objective ?: net.minecraft.core.BlockPos.containing(view.home ?: view.center)
                    val direction = objective.center.subtract(view.center).multiply(1.0, 0.0, 1.0).normalize()
                    // An attack is joined short of the objective, on the side it was approached from.
                    // A held point (defence, patrol) is already occupied, so it is joined at the point.
                    val entry = if (first.order == SquadOrder.ATTACK) objective.center.subtract(direction.scale(48.0)) else objective.center
                    val participants = selected.map { (saved, _) -> SquadParticipant(saved.id, saved.owner, saved.faction.name, level.dimension().location().toString(), saved.orderStamp.toLong()) }
                    val commandId = UUID.nameUUIDFromBytes("$group:${selected.joinToString { "${it.first.id}:${it.first.orderStamp}" }}".toByteArray())
                    val key = first.command?.goal?.takeIf { first.commandOutcome == CommandOutcome.ACTIVE && first.command?.order == first.order.tactical() } ?: ObjectiveKey.Area(first.objectiveDimension?.location()?.toString() ?: level.dimension().location().toString(), objective.x, objective.y, objective.z)
                    val accepted = operations.command(CommandBatch(commandId, first.owner, first.order.tactical(), key, participants, now, EntryArea(entry.point(), 32.0, 16.0)))
                    DebugFlags.log(LogGroup.ORDER, "[htn] event=operation_command principal={} squads={} order={} objective={} result={}", first.owner, participants.map { it.id }, first.order, key, accepted)
                    selected.forEach { (saved, _) ->
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
                val opGroups = loaded.groupBy { operations.operationOf(it.first.id)?.id ?: it.first.id }
                val roots = HashSet<AgentId>()
                val desiredAgents = HashSet<AgentId>()
                opGroups.forEach { (operation, members) ->
                    val standing = standing(operation, now)
                    if (standing == OperationStanding.WITHDRAW) return@forEach
                    val squadMissions = layout(members.map { it.first }, now, holding = standing == OperationStanding.REVIEW)
                    val root = AgentId(AgentLevel.OPERATION, operation)
                    roots += root
                    desiredAgents += root
                    children[root] = squadMissions.mapTo(HashSet()) { it.agent }
                    squadMissions.forEach { mission ->
                        children[mission.agent] = mission.teams.mapTo(HashSet()) { it.agent }
                        desiredAgents += mission.agent
                        ensure(mission.agent)
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
                    val active = members.map { it.first }.filter { it.order == SquadOrder.ATTACK && it.commandOutcome == CommandOutcome.ACTIVE && operations.pending(it.id) == null }
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
                agents.keys.filter { it !in desiredAgents }.toList().forEach { id -> agents.remove(id)?.cancel(now, "actor_not_active") }
                children.keys.removeIf { it !in desiredAgents }
                failures.keys.removeIf { AgentId(AgentLevel.NPC, it) !in desiredAgents }
                allocationOrigins.keys.removeIf { AgentId(AgentLevel.NPC, it) !in desiredAgents }
                formationDuties.keys.removeIf { AgentId(AgentLevel.NPC, it) !in desiredAgents }
                plannedContact.keys.removeIf { AgentId(AgentLevel.NPC, it) !in desiredAgents }
                arcing.removeIf { AgentId(AgentLevel.NPC, it) !in desiredAgents }
                offArc.removeIf { AgentId(AgentLevel.NPC, it) !in desiredAgents }
                teamAllocators.keys.removeIf { manager.get(it) == null }
                manoeuvres.keys.removeIf { manager.get(it) == null }
                retiredAssignments.forEach(ledger::forget)
                retiredAssignments.clear()
            } finally {
                refreshing = false
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
            val fire = observers.any { it.incomingFire.recentPoint(now) != null || it.evadingGrenade() }
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
            if (progress.phase != before) DebugFlags.log(LogGroup.ORDER, "[htn] operation={} event=attack_phase from={} to={} inputs={}", operation, before, progress.phase, inputs)
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
            val all = (operations.operation(operation)?.members?.keys ?: setOf(operation))
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
            val inspect = UnverifiedContacts.inspectTarget(focus != null, marching, inspectAnchor, remembered.values.map { it.point() }, activeAttack)?.native()
            val existingScouts = squads.flatMap { missions[it.id]?.teams.orEmpty() }.flatMap { it.members }.filter { it.duty == CombatDuty.SEARCH }.mapTo(HashSet()) { it.actor.id }
            val candidates = squads.flatMap { snapshots.getValue(it.id).members }.filter { it.ready }.map { it.id to it.position.point() }
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
                if (focus == null) {
                    lastAbove.remove(saved.id)
                } else if (focus.y > view.center.y + 4) {
                    lastAbove[saved.id] = now
                }
                val situation = CombatSituation(
                    focus != null,
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
                    val man = if (following) level.getEntity(member.id) as? NpcEntity else null
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
                            inContact = focus != null,
                            elsewhere = saved.objectiveDimension?.let { it != level.dimension() } == true,
                            scout = member.id in scouts,
                            heightRole = member.role in HEIGHT_ROLES,
                            primaryAmmo = member.primaryAmmo,
                            hasPatrolRoute = view.hasPatrolRoute,
                            reachedMove = actions[member.id]?.let { it.intent.duty == CombatDuty.MOVE && it.reached() } == true,
                            wasFiring = oldIntents[member.id]?.duty == CombatDuty.FIRE,
                            arrival = com.sbwnpc.squad.combat.SquadFormation.defendArrivalRadius(saved.members.size),
                            wasObserving = oldIntents[member.id]?.let { it.duty == CombatDuty.OBSERVE && it.anchor == saved.objective?.center?.point() } == true,
                            flanking = waypoint != null,
                            following = following,
                            // Halves by place among the men here now, not by saved slot: after losses
                            // a slot parity can leave one half empty, so nobody covers or nobody runs.
                            followBound = following && OperationTactics.followBound(index, now, toLeader),
                            followSlot = leaderAt?.let { com.sbwnpc.squad.combat.FollowFormation.slot(it, heading, saved.members.indexOf(member.id), heading != null) },
                            followRide = leader?.vehicle != null || man?.activities?.has(com.sbwnpc.squad.entity.NpcActivity.VEHICLE) == true,
                            transit = transit,
                        ),
                        FIRE_POST_REACH,
                        CombatPosition.LOCAL_MOVE_RADIUS,
                    )
                    if (dutyRules.put(member.id, ruled.rule) != ruled.rule) {
                        DebugFlags.log(LogGroup.ORDER, "[htn] actor={} event=duty_rule rule={} duty={}", member.id, ruled.rule, ruled.choice.duty)
                    }
                    val choice = ruled.choice
                    val duty = choice.duty
                    val proposed = choice.anchor.native()
                    val before = oldIntents[member.id]
                    // A bound's place moves with the player: kept for the whole beat, it was where he
                    // stood five seconds before. Nor does it wait for cover: when the whole squad runs to
                    // catch up there is nobody left to give it, and the run was planned over and over.
                    val bounding = following && duty == CombatDuty.MOVE
                    val requireCover = PostRequirements.cover(duty, situation.incoming) && !bounding
                    val requireShelter = PostRequirements.shelter(duty, situation.incoming)
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
                // Only an attack bounds or regroups. Measured for every squad, the spread of one
                // walking behind its player changed its situation, and replanned it, nearly every time.
                situations[saved.id] = if (!situation.offensive) {
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
                updateRally(saved.id, strategy, view.center, focus)
                val teams = teamAllocators.getOrPut(saved.id, ::TaskTeamAllocator).assign(saved.id, intents)
                SquadMission(AgentId(AgentLevel.SQUAD, saved.id), teams).also { missions[saved.id] = it }
            }
        }

        /**
         * Starts or swaps the turn of a squad bounding by halves. A turn ends once every mover of
         * its moving half still moving has finished a leg more than when it began, or after
         * [BOUND_TURN_TICKS]; the other movers then move. The first halves alternate along the
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
                now - current.startedAt >= BOUND_TURN_TICKS -> "timeout"
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
            supportWaits.clear()
        }
    }
}
