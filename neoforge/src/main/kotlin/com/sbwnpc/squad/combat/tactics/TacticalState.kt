package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.CombatPositionSafety
import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import java.util.UUID

data class TacticalStability(
    val committed: Boolean = false,
    val passive: Boolean = false,
    val quickReplacement: Boolean = false,
    val phaseLimit: Long = 240,
    val keepsThreatSector: Boolean = false,
    val canLeaveImmediately: Boolean = false,
    val carriesOrigin: Boolean = false,
    val expires: Boolean = true,
)

/** An immutable assessment plus the plan's owned assignments; no world queries here. */
data class TacticalContext(
    val squad: UUID,
    val plan: TacticalPlan,
    val view: TacticalSnapshot,
    val owner: SquadTacticalState? = null,
) {
    val state get() = plan.behavior
    val tasks get() = plan.assignments
    fun fail(reason: TacticalFailure) {
        if (owner?.plan === plan) owner.fail(view.now, reason) else plan.fail(view.now, reason)
    }
}

/** One instance per plan. Shared classes implement common mechanics, never shared runtime data. */
abstract class TacticalState {
    open val stability = TacticalStability()
    open val assignmentRange = 48.0
    open val positions: TacticalPositionPolicy = DefaultTacticalPositions
    open val supportsDefensiveOverwatch = false
    open val firingLaneExtension = 0.0
    open val holdsFailedMembers = false
    open val alternatesSideAfterFailure = false

    /** A phase with its own bounded deadline must not also consume the execution deadline. */
    open fun ownsPhaseDeadline(): Boolean = false
    open fun lifetimeElapsed(started: Long, now: Long): Long = now - started
    open fun usesAttackFront(member: TacticalMember): Boolean = true
    open fun attackFrontCenter(context: TacticalContext): Vec3 = context.view.center
    internal var attached = false
    internal var origin: Vec3? = null
    internal var medicGuard: UUID? = null
    internal var phaseSince = 0L
    internal var bounds = 0
    internal var lastCover = 0L
    val phases = TacticalPhaseMachine(this)
    private var executedTicks = 0L
    private var executingSince: Long? = null
    protected var phaseTimingStarted = false
        private set

    internal fun phaseTimingEntered(phase: TacticalPhase, now: Long) {
        phaseTimingStarted = true
        executingSince = if (phase in setOf(TacticalPhase.EXECUTING, TacticalPhase.WITHDRAWING)) now else null
    }

    internal fun phaseTimingExited(now: Long) {
        executingSince?.let { executedTicks += (now - it).coerceAtLeast(0) }
        executingSince = null
    }

    protected fun executionElapsed(now: Long): Long = executedTicks + (executingSince?.let { (now - it).coerceAtLeast(0) } ?: 0)

    fun enter(context: TacticalContext) {
        if (origin == null) origin = context.view.center
        phases.enter(context)
        onEnter(context)
        if (context.plan.status !in setOf(TacticalStatus.FAILED, TacticalStatus.COMPLETED)) {
            assign(context, trigger = "state_enter")
        }
    }

    fun tick(context: TacticalContext) {
        context.tasks.prune(context.view.members.map { it.id }.toSet(), context.view.now)
        reconcileWeapons(context)
        if (!stability.expires) reconcileHoldingAssignments(context)
        beforeTick(context)
        phases.tick(context)
        afterTick(context)
    }

    fun exit(context: TacticalContext, trigger: String) {
        phases.exit(context, trigger)
        onExit(context, trigger)
    }
    protected open fun onEnter(context: TacticalContext) {}
    protected open fun beforeTick(context: TacticalContext) {}

    private fun reconcileWeapons(context: TacticalContext) {
        val layout = TacticalLayout(context)
        for (member in context.view.members) {
            val current = context.plan.tasks[member.id] ?: continue
            if (current.job == TacticalJob.OVERWATCH && !member.primaryAmmo) {
                context.tasks.remove(member.id, context.view.now, "primary_ammo_exhausted")
            } else if (member.sidearmOnly && member.ready && member.health >= 0.3 && member.role != NpcClass.MEDIC &&
                this !is WithdrawalState && this !is EvadeState && layout.eligible(member) &&
                (
                    current.job in setOf(TacticalJob.ADVANCE, TacticalJob.FLANK, TacticalJob.SEARCH, TacticalJob.ANTI_ARMOUR, TacticalJob.WAIT) ||
                        (current.job == TacticalJob.COVER && current.frontage != null)
                    )
            ) {
                context.tasks.put(member.id, layout.cover(member), context.view.now, "pistol_local_defence")
            }
        }
    }

    /** Reinforcements and recovered members join a lasting defence without resetting existing posts. */
    protected open fun reconcileHoldingAssignments(context: TacticalContext) {
        val layout = TacticalLayout(context)
        for ((index, member) in context.view.members.withIndex()) {
            if (context.plan.tasks[member.id]?.job == TacticalJob.OVERWATCH && !DefensiveOverwatch.enabled(context.view, member, this)) {
                context.tasks.remove(member.id, context.view.now, "overwatch_member_unavailable")
            }
            if (member.id in context.plan.failedMembers || !layout.eligible(member)) continue
            val desired = layout.applyRoles(member, index, task(layout, member, index))
            val current = context.plan.tasks[member.id]
            if (current == null || current.job != desired.job) {
                context.tasks.put(member.id, desired, context.view.now, "holding_member_role_changed")
            }
        }
    }
    protected open fun afterTick(context: TacticalContext) {}
    protected open fun onExit(context: TacticalContext, trigger: String) {}
    open fun refreshSectors(context: TacticalContext) {}

    /** A temporary occupied destination can wait without abandoning a long-lived assignment. */
    open fun searchFailureRetry(task: TacticalTask, rejections: Map<String, Int>): Long? = null

    open fun assign(context: TacticalContext, retainCover: Boolean = false, trigger: String = "reassign") {
        val layout = TacticalLayout(context)
        prepareAssignments(layout)
        val desired = LinkedHashMap<UUID, TacticalTask>()
        for ((index, member) in context.view.members.withIndex()) {
            if (member.id in context.plan.failedMembers || !layout.eligible(member)) continue
            val intent = task(layout, member, index)
            val next = layout.applyRoles(member, index, intent)
            val previous = context.plan.tasks[member.id]
            val recovering = previous?.localFireRecovery == true &&
                (previous.position != null || previous.search != null || previous.expandedFireSearch || previous.fireTransit != null)
            val holding = member.grounded && TacticalEvidence.covers(member, previous) && TacticalEvidence.settled(member, previous)
            val exchangingCoverAndWait = (previous?.job == TacticalJob.COVER && next.job == TacticalJob.WAIT) ||
                (previous?.job == TacticalJob.WAIT && next.job == TacticalJob.COVER)
            desired[member.id] = if (retainCover && previous != null && (previous.job == next.job || exchangingCoverAndWait) && next.job in TacticalJob.FIRE_SUPPORT &&
                (recovering || holding)
            ) {
                if (previous.job == next.job) previous else previous.withJob(next.job, previous.anchor)
            } else {
                next
            }
        }
        context.tasks.replace(desired, context.view.now, trigger, retainCover)
    }

    protected open fun prepareAssignments(layout: TacticalLayout) {}
    protected abstract fun task(layout: TacticalLayout, member: TacticalMember, index: Int): TacticalTask
    open fun guardsPatient(): Boolean = true

    internal open fun prepare(context: TacticalContext) {
        phases.transition(ExecutingPhase(), context, "holding_behavior_ready")
    }
    internal open fun execute(context: TacticalContext) {}

    internal open fun phaseEntered(context: TacticalContext, from: TacticalPhase?) {}
    internal open fun phaseExited(context: TacticalContext, to: TacticalPhase?) {}
}

/** Geometry and universal role constraints, shared by state-specific assignment builders. */
class TacticalLayout(val context: TacticalContext) {
    val plan get() = context.plan
    val state get() = context.state
    val view get() = context.view
    val focus = plan.focus ?: view.home ?: view.center
    val forward = focus.subtract(view.center).multiply(1.0, 0.0, 1.0).normalize()
        .let { if (it.lengthSqr() < 0.01) Vec3(0.0, 0.0, 1.0) else it }
    val side = Vec3(-forward.z, 0.0, forward.x)
    private val frontage = plan.frontage.also { it.observe(view) }
    val fighters = view.members.filter { it.ready && it.primaryAmmo && it.health >= 0.3 && it.role != NpcClass.MEDIC && it.id !in plan.failedMembers }
        .sortedWith(
            compareBy<TacticalMember> { if (state is FlankingState && it.recentFire && it.canFire) 0 else 1 }
                .thenBy { if (it.canFire) 0 else 1 }.thenBy { if (it.role in SUPPORT_ROLES) 0 else 1 }.thenBy { it.id },
        )
    val support = fighters.take(maxOf(1, fighters.size / 3)).map { it.id }.toSet()
    val patient = view.members.filter { it.health < 0.6 || it.role == NpcClass.MEDIC }.minByOrNull { it.health }
    val guard = state.medicGuard?.takeIf { id -> patient != null && fighters.any { it.id == id } }
        ?: if (fighters.size >= 4 && patient != null) {
            fighters.filter { it.id != patient.id && it.id !in support }
                .minByOrNull { it.position.distanceToSqr(patient.position) }?.id
        } else {
            null
        }

    init {
        state.medicGuard = guard
    }

    fun lateral(index: Int, center: Vec3 = view.center): Vec3 {
        val member = view.members.getOrNull(index)
        if (view.offensive && member != null && member.role != NpcClass.MEDIC && state.usesAttackFront(member)) {
            return frontage.offsetFrom(member.id, center) ?: side.scale(SquadFormation.lineOffset(index, view.members.size).x)
        }
        return side.scale((index % 3 - 1) * 5.0)
    }
    fun intent(job: TacticalJob, anchor: Vec3, focus: Vec3? = plan.focus): TacticalTask = TacticalTask(job, anchor, focus, plan.id)
    fun cover(member: TacticalMember): TacticalTask = intent(TacticalJob.COVER, member.position)

    fun eligible(member: TacticalMember): Boolean {
        val overwatch = DefensiveOverwatch.enabled(view, member, state)
        val range = if (overwatch) {
            80.0
        } else if (view.offensive) {
            if (state.usesAttackFront(member)) maxOf(state.assignmentRange, frontage.halfWidth + 24.0) else state.assignmentRange
        } else {
            48.0
        }
        val homeRadius = SquadFormation.perimeterRadius(view.members.size) + 10.0 + if (overwatch) DefensiveOverwatch.RADIUS else 0.0
        val home = view.home
        // Reinforcements and stragglers are still squad members. Their route is planned in short
        // legs; proximity to the current median is not a prerequisite for joining the attack front.
        return (view.offensive || member.position.distanceTo(view.center) <= range) &&
            (view.order != SquadOrder.DEFEND || home == null || member.position.distanceTo(home) <= homeRadius)
    }

    fun applyRoles(member: TacticalMember, index: Int, original: TacticalTask): TacticalTask {
        var intent = original
        if (member.role == NpcClass.MEDIC || !member.ready || member.health < 0.3) {
            intent = intent(TacticalJob.RESERVE, view.center.subtract(forward.scale(8.0)).add(lateral(index)))
        }
        if (member.id == guard && patient != null && state.guardsPatient()) {
            intent = intent(TacticalJob.COVER, patient.position.subtract(forward.scale(4.0)).add(side.scale(4.0)))
        }
        if (member.sidearmOnly && member.ready && member.health >= 0.3 && member.role != NpcClass.MEDIC &&
            original.job !in setOf(TacticalJob.RETREAT, TacticalJob.REGROUP) && state !is WithdrawalState
        ) {
            intent = cover(member)
        }
        if (DefensiveOverwatch.enabled(view, member, state) && member.id != guard) {
            val angle = index * Math.PI * 2.0 / view.members.size.coerceAtLeast(1)
            intent = intent(
                TacticalJob.OVERWATCH,
                (view.home ?: view.center)
                    .add(Vec3(kotlin.math.cos(angle), 0.0, kotlin.math.sin(angle)).scale(SquadFormation.perimeterRadius(view.members.size))),
                intent.focus,
            )
        }
        if (intent.job == TacticalJob.ADVANCE) {
            val safeAnchor = TacticalClearance.threats(view).fold(intent.anchor) { point, threat ->
                CombatPositionSafety.approach(member.position, point, threat, CombatPositionSafety.clearance(member.role) + CombatPositionSafety.APPROACH_MARGIN)
            }
            if (safeAnchor != intent.anchor) intent = intent(intent.job, safeAnchor, intent.focus)
        }
        if (intent.job == TacticalJob.WAIT && intent.staging == null) intent.staging = member.position
        if (view.offensive && member.primaryAmmo && state.usesAttackFront(member) && intent.job in TacticalJob.FIRING_POSITIONS + TacticalJob.ADVANCE &&
            member.ready && member.health >= 0.3 && member.role != NpcClass.MEDIC && member.id != guard
        ) {
            val post = if (intent.job == TacticalJob.ADVANCE) {
                TacticalFrontPost(intent.anchor, frontage.forward)
            } else {
                frontage.post(member.id, state.attackFrontCenter(context), member.position)?.let {
                    TacticalContactApproach.post(view, member, it, intent.focus)
                }
            }
            if (post != null) {
                intent = TacticalTask(intent.job, if (intent.job == TacticalJob.WAIT) intent.anchor else post.point, intent.focus, intent.plan).also {
                    it.staging = if (intent.job == TacticalJob.WAIT) post.point else null
                    it.frontage = post
                    if (it.job in TacticalJob.FIRE_SUPPORT && member.id !in plan.tasks) it.coverOrigin = member.position
                }
            }
        }
        if (state is CoordinatedAttackState) intent.operationMember = member.id
        return intent
    }

    fun escape(): Vec3 {
        val threats = view.visible.map { it.position } + view.incoming
        if (threats.isEmpty()) return forward.scale(-1.0)
        return (0..7).map { Vec3(kotlin.math.cos(it * Math.PI / 4.0), 0.0, kotlin.math.sin(it * Math.PI / 4.0)) }
            .maxBy { direction ->
                val next = view.center.add(direction.scale(12.0))
                threats.minOf { it.distanceTo(next) } - (view.home?.let { next.distanceTo(it) * 0.1 } ?: 0.0)
            }
    }

    companion object {
        val SUPPORT_ROLES = setOf(NpcClass.MACHINE_GUNNER, NpcClass.SNIPER)
        fun towards(from: Vec3, to: Vec3, distance: Double): Vec3 = from.add(to.subtract(from).normalize().scale(minOf(distance, from.distanceTo(to))))
    }
}
