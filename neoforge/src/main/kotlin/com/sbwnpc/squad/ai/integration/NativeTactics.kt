package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.coordination.*
import com.sbwnpc.squad.ai.planning.*
import com.sbwnpc.squad.ai.tactics.*
import com.sbwnpc.squad.combat.*
import com.sbwnpc.squad.combat.tactics.*
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.*
import com.sbwnpc.squad.team.Diplomacy
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.max

/** Production owner: world observation, domain evaluation and native adapters have separate boundaries. */
object NativeTactics {
    /** Farthest fire-post anchor whose local probes still fall inside the position query's reach. */
    private const val FIRE_POST_REACH = TacticalPositions.SEARCH_REACH - CombatPosition.LOCAL_MOVE_RADIUS

    /** Seen enemies this close to an attacked area keep it from counting as held. */
    private const val AREA_THREAT_RADIUS = 128.0
    private val worlds = HashMap<ServerLevel, World>()
    private fun world(npc: NpcEntity) = worlds.getOrPut(npc.level() as ServerLevel) { World(npc.level() as ServerLevel) }
    fun refresh(npc: NpcEntity) {
        if (npc.vehicle == null && npc.npcClass in GROUND && npc.currentSquad() != null) world(npc).refresh(npc)
    }
    fun tick(npc: NpcEntity) = world(npc).tick(npc)
    fun assignment(npc: NpcEntity): NativeAssignment? {
        if (npc.vehicle != null || npc.npcClass !in GROUND || npc.currentSquad() == null) return null
        refresh(npc)
        return world(npc).actions[npc.uuid]?.takeIf { it.intent.duty != CombatDuty.FOLLOW_ORDER }?.assignment
    }
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

    /** Whether [squad] currently counts towards some operation's area completion. */
    fun holdsArea(squad: UUID) = worlds.values.any { it.holdsArea(squad) }
    fun targetDeath(event: net.neoforged.neoforge.event.entity.living.LivingDeathEvent) {
        val entity = event.entity
        val level = entity.level() as? ServerLevel ?: return
        val world = worlds[level] ?: return
        if (SquadManager.get(level).all().any { it.commandOutcome == CommandOutcome.ACTIVE && (it.command?.goal as? ObjectiveKey.Entity)?.id == entity.uuid }) {
            world.pendingDeaths[entity.uuid] = event
        }
    }
    fun clearAll() {
        worlds.values.forEach { it.clear() }
        worlds.clear()
    }
    fun snapshot(npc: NpcEntity): TacticalSnapshot? = npc.squadId?.let { world(npc).combatView(it) }
    fun reports(npc: NpcEntity) = world(npc).agents[AgentId(AgentLevel.NPC, npc.uuid)]?.runtime?.reports.orEmpty()
    fun movable(npc: NpcEntity) =
        npc.isAlive && npc.vehicle == null && !npc.busyWithRole() && !npc.resupplying && !npc.diggedIn &&
            !npc.evadingGrenade() && !npc.movementLockedByCover() && !npc.combatLockedByMedic() && npc.retreatPoint() == null
    private val GROUND = setOf(NpcClass.RIFLEMAN, NpcClass.SHOTGUNNER, NpcClass.MACHINE_GUNNER, NpcClass.SNIPER, NpcClass.GRENADIER, NpcClass.MEDIC, NpcClass.DRONE_OPERATOR)

    private class World(val level: ServerLevel) {
        val pendingDeaths = HashMap<UUID, net.neoforged.neoforge.event.entity.living.LivingDeathEvent>()
        val agents = LinkedHashMap<AgentId, PlanningAgent>()
        val actions = HashMap<UUID, NativePositionAction>()
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
        private val strategies = HashMap<UUID, Pair<CombatStrategy, Long>>()
        private val stability = DecisionStability()
        private val allocationOrigins = HashMap<UUID, Point3>()
        private val formationDuties = HashMap<UUID, CombatDuty>()
        private val plannedContact = HashMap<UUID, Vec3?>()
        private val failures = HashMap<UUID, LocalPositionFailure>()
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
                            actions.remove(view.ticket.child.id)
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
                        ActionBinding(TacticalDomain.POSITION) { ticket, intent ->
                            require(ticket.owner.agent == intent.actor)
                            val original = goals[intent.actor] ?: intent
                            val npc = requireNotNull(level.getEntity(intent.actor.id) as? NpcEntity)
                            NativePositionAction(intent, npc, { requireNotNull(combatView(npc.squadId!!)) }, { covering(intent) }, { failed, rejections ->
                                failures[failed.actor.id] = LocalPositionFailure(original.anchor, level.gameTime, (failures[failed.actor.id]?.takeIf { it.anchor == original.anchor }?.attempts ?: 0) + 1, rejections.toMap())
                                DebugFlags.log(LogGroup.ORDER, "[htn] actor={} event=local_failure rejected={}", failed.actor.id, rejections)
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
                    DebugFlags.log(LogGroup.ORDER, "[htn] level={} actor={} event=plan_result result={}", id.level, id.id, feedback.result.javaClass.simpleName)
                }
            }
        private fun facts(id: AgentId, now: Long): FactSnapshot {
            var result = FactSnapshot(id, now)
            if (id.level == AgentLevel.OPERATION) {
                val observed = areaObservedAt[id.id]
                if (observed != null) result = result.observe(TacticalFacts.AREA_SECURED, areaClocks[id.id]?.completed == true, observedAt = observed, expiresAt = observed + 11)
            }
            if (id.level == AgentLevel.OPERATION) attacks[id.id]?.let { result = result.observe(TacticalFacts.ATTACK_PHASE, it.phase) }
            if (id.level == AgentLevel.SQUAD) result = result.observe(TacticalFacts.STRATEGY, strategies[id.id]?.first ?: CombatStrategy.ADVANCE)
            if (id.level == AgentLevel.NPC) {
                val npc = level.getEntity(id.id) as? NpcEntity
                val action = actions[id.id]
                result = result.observe(TacticalFacts.MOVABLE, npc != null && movable(npc))
                    .observe(TacticalFacts.COVER, goals[id]?.let(::covering) ?: false, expiresAt = now + 11)
                    .observe(TacticalFacts.ARRIVED, action?.reached() ?: false)
                failures[id.id]?.let { failure -> result = result.observe(TacticalFacts.POST_FAILURE, failure, observedAt = failure.failedAt, expiresAt = failure.failedAt + 200) }
            }
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
                val destroyedTargets = pendingDeaths.filterValues { !it.isCanceled && it.entity.isDeadOrDying }.keys.toSet()
                pendingDeaths.clear()
                val loaded = manager.all().mapNotNull { saved ->
                    val npcs = saved.members.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive && it.vehicle == null && it.npcClass in GROUND }
                    if (npcs.isEmpty()) null else saved to npcs
                }
                val activeIds = loaded.mapTo(HashSet()) { it.first.id }
                snapshots.keys.filter { it !in activeIds }.toList().forEach { id ->
                    snapshots.remove(id)
                    missions.remove(id)
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
                    val key = first.command?.goal?.takeIf { first.commandOutcome == CommandOutcome.ACTIVE && first.command?.order?.name == first.order.name } ?: ObjectiveKey.Area(first.objectiveDimension?.location()?.toString() ?: level.dimension().location().toString(), objective.x, objective.y, objective.z)
                    val accepted = operations.command(CommandBatch(commandId, first.owner, TacticalOrder.valueOf(first.order.name), key, participants, now, EntryArea(entry.point(), 32.0, 16.0)))
                    DebugFlags.log(LogGroup.ORDER, "[htn] event=operation_command principal={} squads={} order={} objective={} result={}", first.owner, participants.map { it.id }, first.order, key, accepted)
                    selected.forEach { (saved, _) ->
                        knownOrders[saved.id] = saved.orderStamp
                        missions.remove(saved.id)
                        saved.members.forEach {
                            allocationOrigins.remove(it)
                            formationDuties.remove(it)
                            plannedContact.remove(it)
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
                    val roles = if (secure != null) attackRoles(operation, secure, active, now) else AttackRoles.NONE.also { attacks.remove(operation) }
                    ensure(root).command(operation, TacticalDomain.OPERATION.call(OperationMission(squadMissions, secure, roles)), facts(root, now))
                }
                agents.keys.filter { it.level == AgentLevel.OPERATION && it !in roots }.toList().forEach { id ->
                    agents.remove(id)?.cancel(now, "operation_membership_changed")
                    children.remove(id)
                    areaClocks.remove(id.id)
                    areaOccupied.remove(id.id)
                    attacks.remove(id.id)
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
                teamAllocators.keys.removeIf { manager.get(it) == null }
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

        /** Reports the attack's observations to its [AttackProgress] and returns the roles of its phase. */
        private fun attackRoles(operation: UUID, task: AreaControlTask, squads: List<Squad>, now: Long): AttackRoles {
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
            )
            if (progress.phase != before) DebugFlags.log(LogGroup.ORDER, "[htn] operation={} event=attack_phase from={} to={} inputs={}", operation, before, progress.phase, inputs)
            val occupying = progress.occupying
            // Every ready man takes part in an assault, not only the front rows of the line: the
            // flanks and rear guard a reinforcement was added to never left the firing line (#54).
            return progress.roles(
                now,
                task.objective,
                members.filter { it.ready }.map { it.id },
                members.map { AttackMember(it.id, it.position.point(), it.ready) },
                (threats.map { it.position.point() } + defenders).distinct(),
            ).also {
                if (progress.occupying && !occupying) {
                    DebugFlags.log(LogGroup.ORDER, "[htn] operation={} event=assault_occupy ready={} defenders={} movers={}", operation, inputs.readyShooters, defenders.size, it.assault.size)
                }
            }
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
            }
            return standing
        }

        /** Whether a reinforcement on its way stops to fight, see [ReinforcementMarch]; logs each change. */
        private fun reinforcementEngaged(squad: UUID, contacts: List<TacticalContact>, members: List<TacticalMember>, now: Long): Boolean {
            if (snapshots[squad]?.incoming?.isNotEmpty() == true) reinforcementFire[squad] = now
            val nearest = contacts.minOfOrNull { contact -> members.minOfOrNull { it.position.distanceTo(contact.position) } ?: Double.MAX_VALUE }
            val sinceFire = reinforcementFire[squad]?.let { now - it } ?: Long.MAX_VALUE
            val engaged = ReinforcementMarch.engaged(nearest, sinceFire, fighting = reinforcementEngaged[squad] == true)
            if (reinforcementEngaged.put(squad, engaged) != engaged) {
                DebugFlags.log(LogGroup.ORDER, "[htn] squad={} event=reinforcement_march engaged={} nearestContact={} ticksSinceFire={}", squad, engaged, nearest?.let { "%.0f".format(it) }, sinceFire)
            }
            return engaged
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
            val inspect = if (focus == null && !marching) {
                val anchor = currentAreaTask(operation)?.objective?.native() ?: squads.first().objective?.let { Vec3.atBottomCenterOf(it) } ?: center
                UnverifiedContacts.scoutTarget(anchor.point(), remembered.values.map { it.point() })?.native()
                    ?: squads.firstOrNull { it.order == SquadOrder.ATTACK && it.commandOutcome == CommandOutcome.ACTIVE }?.objective?.let { Vec3.atBottomCenterOf(it) }
            } else {
                null
            }
            val existingScouts = squads.flatMap { missions[it.id]?.teams.orEmpty() }.flatMap { it.members }.filter { it.duty == CombatDuty.SEARCH }.mapTo(HashSet()) { it.actor.id }
            val scouts = if (inspect != null) squads.flatMap { snapshots.getValue(it.id).members }.filter { it.ready }.sortedWith(compareBy<TacticalMember> { it.id !in existingScouts }.thenBy { it.position.distanceTo(inspect) }).take(2).mapTo(HashSet()) { it.id } else emptySet()
            val proposals = OperationFront.propose(
                squads.flatMap { saved ->
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
                },
                contacts.map { it.position.point() },
                focus?.point(),
                formationDuties,
            )
            proposals.forEach { (id, proposal) -> formationDuties[id] = proposal.duty }
            return squads.map { saved ->
                val view = snapshots.getValue(saved.id)
                val situation = CombatSituation(
                    focus != null,
                    // An operation reviewing its losses holds the line it has instead of pushing on;
                    // a squad with a barracks presses on (#57).
                    saved.order == SquadOrder.ATTACK && (!holding || saved.barracks != null),
                    focus?.y?.let { it > view.center.y + 4 } == true,
                    view.incoming.isNotEmpty(),
                    view.fighting.size.toDouble() / max(1, view.members.size),
                    1.0 - saved.members.size.toDouble() / max(1, max(view.peakStrength, saved.members.size)),
                    view.fighting.isNotEmpty(),
                    reinforced = saved.barracks != null,
                )
                val evaluation = StrategyEvaluation.evaluate(facts(AgentId(AgentLevel.SQUAD, saved.id), now), situation)
                val oldStrategy = strategies[saved.id]
                val selected = stability.choose(evaluation, oldStrategy?.first?.name, oldStrategy?.second ?: now, now)!!.value
                if (oldStrategy?.first != selected) strategies[saved.id] = selected to now
                val old = missions[saved.id]
                val oldIntents = old?.teams?.flatMap { it.members }?.associateBy { it.actor.id }.orEmpty()
                // Marching on, an attack's reinforcement makes for the operation's entry itself: left to
                // the order's own march, each man's sighted target still drew him off after it.
                val entry = if (marching && saved.order == SquadOrder.ATTACK) operations.pending(saved.id)?.let { operations.operation(it.operation) }?.entry?.center else null
                val intents = view.members.map { member ->
                    val proposal = proposals.getValue(member.id)
                    val choice = entry?.let { DutyChoice(CombatDuty.MOVE, it) } ?: OperationTactics.duty(
                        DutyInputs(
                            order = TacticalOrder.valueOf(saved.order.name),
                            position = member.position.point(),
                            proposal = proposal,
                            objective = saved.objective?.center?.point(),
                            inspect = inspect?.point(),
                            inContact = focus != null,
                            elsewhere = saved.objectiveDimension?.let { it != level.dimension() } == true,
                            scout = member.id in scouts,
                            observer = member.primaryAmmo && member.role in setOf(NpcClass.MACHINE_GUNNER, NpcClass.SNIPER),
                            hasPatrolRoute = view.hasPatrolRoute,
                            reachedMove = actions[member.id]?.let { it.intent.duty == CombatDuty.MOVE && it.reached() } == true,
                            wasFiring = oldIntents[member.id]?.duty == CombatDuty.FIRE,
                        ),
                        FIRE_POST_REACH,
                        CombatPosition.LOCAL_MOVE_RADIUS,
                    )
                    val duty = choice.duty
                    val proposed = choice.anchor.native()
                    val before = oldIntents[member.id]
                    val requireCover = duty == CombatDuty.MOVE && situation.incoming
                    val requireShelter = situation.incoming && duty in setOf(CombatDuty.MOVE, CombatDuty.FIRE)
                    val retained = before?.takeIf { oldStrategy?.first == selected && it.duty == duty && it.requireCover == requireCover && it.requireShelter == requireShelter && (focus?.distanceTo(plannedContact[member.id] ?: focus) ?: 0.0) < 8 }
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
                            minimumHeight = if (duty == CombatDuty.OBSERVE) member.position.y - 2 else null,
                            inspection = if (duty == CombatDuty.SEARCH) inspect?.point() else null,
                            standoff = CombatPositionSafety.clearance(member.role),
                        )
                    }
                }
                val teams = teamAllocators.getOrPut(saved.id, ::TaskTeamAllocator).assign(saved.id, intents)
                SquadMission(AgentId(AgentLevel.SQUAD, saved.id), teams).also { missions[saved.id] = it }
            }
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
            if (agent.runtime.completed && actions[npc.uuid]?.reached() == false) agent.repair(observation)
            agent.tick(observation, planningWork = 4)
            actions[npc.uuid]?.maintainLook()
            report(agent, now)
        }
        private val areaTasks = HashMap<UUID, AreaControlTask>()
        fun holdsArea(squad: UUID) = areaTasks.values.any { squad in it.commands.keys }
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
        }
    }
}
