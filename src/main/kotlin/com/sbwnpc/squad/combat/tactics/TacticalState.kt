package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import java.util.UUID

data class TacticalStability(
    val committed: Boolean = false, val passive: Boolean = false,
    val quickReplacement: Boolean = false, val phaseLimit: Long = 240,
    val keepsThreatSector: Boolean = false, val canLeaveImmediately: Boolean = false,
    val carriesOrigin: Boolean = false
)

/** An immutable assessment plus the plan's owned assignments; no world queries here. */
data class TacticalContext(
    val squad: UUID, val plan: TacticalPlan, val view: TacticalSnapshot,
    val owner: SquadTacticalState? = null
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
    internal var attached = false
    internal var origin: Vec3? = null
    internal var medicGuard: UUID? = null
    internal var phaseSince = 0L
    internal var bounds = 0
    internal var lastCover = 0L
    val phases = TacticalPhaseMachine(this)

    fun enter(context: TacticalContext) {
        if (origin == null) origin = context.view.center
        phases.enter(context)
        onEnter(context)
        assign(context, trigger = "state_enter")
    }

    fun tick(context: TacticalContext) {
        context.tasks.prune(context.view.members.map { it.id }.toSet(), context.view.now)
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
    protected open fun afterTick(context: TacticalContext) {}
    protected open fun onExit(context: TacticalContext, trigger: String) {}
    open fun refreshSectors(context: TacticalContext) {}

    open fun assign(context: TacticalContext, retainCover: Boolean = false, trigger: String = "reassign") {
        val layout = TacticalLayout(context)
        prepareAssignments(layout)
        val desired = LinkedHashMap<UUID, TacticalTask>()
        for ((index, member) in context.view.members.withIndex()) {
            if (member.id in context.plan.failedMembers || !layout.eligible(member)) continue
            val intent = task(layout, member, index)
            desired[member.id] = layout.applyRoles(member, index, intent)
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
    val fighters = view.members.filter { it.ready && it.health >= 0.3 && it.role != NpcClass.MEDIC && it.id !in plan.failedMembers }
        .sortedWith(compareBy<TacticalMember> { if (state is FlankingState && it.recentFire && it.canFire) 0 else 1 }
            .thenBy { if (it.canFire) 0 else 1 }.thenBy { if (it.role in SUPPORT_ROLES) 0 else 1 }.thenBy { it.id })
    val support = fighters.take(maxOf(1, fighters.size / 3)).map { it.id }.toSet()
    val patient = view.members.filter { it.health < 0.6 || it.role == NpcClass.MEDIC }.minByOrNull { it.health }
    val guard = state.medicGuard?.takeIf { id -> patient != null && fighters.any { it.id == id } }
        ?: if (fighters.size >= 4 && patient != null) fighters.filter { it.id != patient.id && it.id !in support }
            .minByOrNull { it.position.distanceToSqr(patient.position) }?.id else null

    init { state.medicGuard = guard }

    fun lateral(index: Int): Vec3 = side.scale((index % 3 - 1) * 5.0)
    fun intent(job: TacticalJob, anchor: Vec3, focus: Vec3? = plan.focus): TacticalTask = TacticalTask(job, anchor, focus, plan.id)
    fun cover(member: TacticalMember): TacticalTask = intent(TacticalJob.COVER, member.position)

    fun eligible(member: TacticalMember): Boolean {
        val overwatch = DefensiveOverwatch.enabled(view, member, state)
        val range = if (overwatch) 80.0 else if (view.offensive) state.assignmentRange else 48.0
        val homeRadius = SquadFormation.perimeterRadius(view.members.size) + 10.0 + if (overwatch) DefensiveOverwatch.RADIUS else 0.0
        val home = view.home
        return member.position.distanceTo(view.center) <= range &&
            (view.order != SquadOrder.DEFEND || home == null || member.position.distanceTo(home) <= homeRadius)
    }

    fun applyRoles(member: TacticalMember, index: Int, original: TacticalTask): TacticalTask {
        var intent = original
        if (member.role == NpcClass.MEDIC || !member.ready || member.health < 0.3)
            intent = intent(TacticalJob.RESERVE, view.center.subtract(forward.scale(8.0)).add(lateral(index)))
        if (member.id == guard && patient != null && state.guardsPatient())
            intent = intent(TacticalJob.COVER, patient.position.subtract(forward.scale(4.0)).add(side.scale(4.0)))
        if (DefensiveOverwatch.enabled(view, member, state) && member.id != guard) {
            val angle = index * Math.PI * 2.0 / view.members.size.coerceAtLeast(1)
            intent = intent(TacticalJob.OVERWATCH, (view.home ?: view.center)
                .add(Vec3(kotlin.math.cos(angle), 0.0, kotlin.math.sin(angle)).scale(SquadFormation.perimeterRadius(view.members.size))), intent.focus)
        }
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
        fun towards(from: Vec3, to: Vec3, distance: Double): Vec3 =
            from.add(to.subtract(from).normalize().scale(minOf(distance, from.distanceTo(to))))
    }
}
