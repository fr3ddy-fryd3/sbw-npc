package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.*
import com.sbwnpc.squad.ai.tactics.*
import com.sbwnpc.squad.combat.*
import com.sbwnpc.squad.combat.tactics.*
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

internal fun Point3.native() = Vec3(x, y, z)
internal fun Vec3.point() = Point3(x, y, z)

/** Read-only compatibility view for gun/posture behaviours; no search or navigator state escapes. */
data class NativeAssignment(val job: TacticalJob, val anchor: Vec3, val focus: Vec3?, val position: Vec3?, val localFirePost: Vec3? = null, val localFireRecovery: Boolean = false) {
    fun permitsFront(point: Vec3) = point.distanceTo(anchor) <= 32.0 || localFirePost?.let { TacticalFirePosts.withinPost(it, point, recovery = true) } == true
}

/** A sentry's height [post] and the [anchor] it was searched for. */
internal data class HeldPost(val anchor: Vec3, val post: Vec3)

/** The existing collision/shot/native-A* query is used with explicit constraints and a private cursor. */
internal class NativePositionAction(
    intent: PositionIntent,
    private val npc: NpcEntity,
    private val snapshot: () -> TacticalSnapshot?,
    private val exhausted: (PositionIntent, Map<String, Int>, Vec3) -> Unit,
    heldPost: HeldPost? = null,
) : ActionExecution {
    var intent = intent
        private set
    private val claimOwner = java.util.UUID.randomUUID()
    private val task = TacticalTask(
        when (intent.duty) {
            CombatDuty.FIRE -> if (AntiArmourKit.loaded(npc) && snapshot()?.contacts.orEmpty().any { it.armoured }) TacticalJob.ANTI_ARMOUR else TacticalJob.COVER
            CombatDuty.MOVE -> TacticalJob.ADVANCE
            CombatDuty.SEARCH -> TacticalJob.SEARCH
            CombatDuty.REAR_GUARD -> TacticalJob.RESERVE
            CombatDuty.OBSERVE -> if (intent.minimumHeight != null) TacticalJob.OVERWATCH else TacticalJob.OBSERVE
            CombatDuty.FOLLOW_ORDER -> TacticalJob.WAIT
        },
        DryGround.near(npc.level() as ServerLevel, intent.anchor.native(), npc.position()),
        intent.focus?.native(),
        intent.hashCode(),
    )
    private val policy = object : TacticalPositionPolicy {
        override fun score(view: TacticalSnapshot, task: TacticalTask, point: Vec3, exposure: Int) = exposure * intent.exposureWeight - (point.y - intent.origin.y).coerceIn(-2.0, 8.0) * intent.elevationWeight
        override fun requiresShelter(view: TacticalSnapshot, task: TacticalTask) = intent.requireShelter
        override fun clearance(role: NpcClass) = CombatPositionSafety.clearance(role, intent.assault)
        override fun candidate(view: TacticalSnapshot, task: TacticalTask, point: Vec3, localAnchor: Vec3): Boolean {
            if (intent.minimumHeight?.let { point.y < it } == true) return false
            if (intent.inspection != null && point.distanceTo(intent.inspection.native()) <= 24 && !DetectionSightline.visible(npc.level() as ServerLevel, point.add(0.0, npc.eyeHeight.toDouble(), 0.0), intent.inspection.native().add(0.0, 1.5, 0.0), npc)) return false
            return !intent.duty.advancing || advances(npc.position(), task.anchor, localAnchor, point)
        }
        override fun route(view: TacticalSnapshot, point: Vec3) = intent.minimumHeight?.let { point.y >= it } != false
    }

    init {
        // A sentry that left to resupply comes back to his own platform. Without it the fresh
        // search started again from the ground and settled on another post (#86).
        if (task.job == TacticalJob.OVERWATCH && heldPost != null && stillServes(heldPost)) task.position = heldPost.post
        if (intent.hold) task.position = npc.position()
    }

    /** Only for the same anchor: a new order's post must come from a new search. */
    private fun stillServes(held: HeldPost) =
        held.anchor.distanceTo(task.anchor) <= 1.0 && intent.minimumHeight?.let { held.post.y >= it } != false &&
            FiringSpots.nearby(held.post, 1.0, npc.uuid).isEmpty()

    /** The height post this action holds or heads for, kept by [NativeTactics] for the next OVERWATCH action. */
    val overwatchPost get() = task.position?.takeIf { task.job == TacticalJob.OVERWATCH && !intent.hold }?.let { HeldPost(task.anchor, it) }
    private var ownedLook: net.minecraft.world.entity.ai.behavior.PositionTracker? = null
    private var idleDirection: Pair<Double, Double>? = null
    private var glanceOffset = 0.0
    private var nextGlanceAt = 0
    val ownsLook get() = ownedLook != null && npc.target == null && NativeTactics.movable(npc)
    private var deferredAt: Long? = null
    private var lastProgress = 0L
    private var closest = Double.MAX_VALUE
    private var pendingStart = true
    private var released = false
    val assignment get() = NativeAssignment(task.job, task.anchor, task.focus, task.position, task.localFirePost, task.localFireRecovery)
    fun reached(): Boolean =
        task.position?.let { FiringPostArrival.reached(npc.position(), it) } == true && !intent.hold &&
            (!intent.duty.advancing || task.position!!.distanceTo(task.anchor) <= 4) &&
            (intent.inspection?.let { DetectionSightline.visible(npc.level() as ServerLevel, npc.eyePosition, it.native().add(0.0, 1.5, 0.0), npc) } != false)

    /** He stands on the post this action found, the anchor's or one on the way to it. */
    fun atPost(): Boolean = task.position?.let { FiringPostArrival.reached(npc.position(), it) } == true

    // Where this action found him, from which a leg is measured.
    private val legStart = npc.position()

    override fun start(now: Long): ActionUpdate {
        lastProgress = now
        DebugFlags.log(LogGroup.ORDER, "[htn] actor={} event=post_action_started duty={} job={} anchor={} focus={} hold={}", npc.uuid, intent.duty, task.job, task.anchor, task.focus, intent.hold)
        return update(now)
    }
    override fun tick(now: Long) = update(now)

    override fun reopen(now: Long): Boolean {
        if (released || task.position == null) return false
        // Keep the reserved post through displacement; native A* must wait for landing.
        pendingStart = true
        deferredAt = null
        lastProgress = now
        closest = Double.MAX_VALUE
        return true
    }
    private fun update(now: Long): ActionUpdate {
        deferredAt?.let { lastProgress += now - it }
        deferredAt = null
        if (!intent.duty.commandsMovement) return ActionUpdate.Running
        // Gone with its squad (disbanded, deleted) while the plan still ran: fail, never throw (#64).
        val view = snapshot() ?: return ActionUpdate.Failed("no_squad_view")
        if (pendingStart) {
            // Finish native jumps before relinquishing a previous ordinary march route.
            if ((npc.navigation as? VehicleAwareNavigation)?.canPlan == false) return deferred(now, "native_jump")
            pendingStart = false
            npc.navigation.stop()
        }
        // An assault post is measured from one enemy (ClosingPost): the search looks for a shot at
        // him, not at the man's own target, who may be far beyond his weapon's reach (#65).
        val enemy = intent.focus?.takeIf { intent.assault && intent.duty == CombatDuty.FIRE }?.native()
        val observation = if (enemy != null) {
            view.contacts.filter { it.position.distanceTo(enemy) <= ENEMY_MOVED }.minByOrNull { it.position.distanceToSqr(enemy) }
        } else {
            view.contacts.firstOrNull { it.id == npc.target?.uuid } ?: view.contacts.maxByOrNull { it.priority }
        }
        task.usesObservedAim = true
        task.observedAim = observation?.let { TacticalAim(it.id, it.position, it.position.add(0.0, 1.5, 0.0)) }
            ?: intent.focus?.native()?.let { TacticalAim(null, it, it.add(0.0, 1.5, 0.0)) }
        if (task.position != null && ((!TacticalClearance.permitsPosition(policy.clearance(npc.npcClass), npc.position(), task.position!!, view)) || GrenadeHazard.threatens(npc.level() as ServerLevel, task.position!!))) invalidate(now, "post_not_permitted")
        val path = npc.navigation.path
        if (path != null && path === task.navigationPath && !path.isDone && !TacticalClearance.permitsPath(policy.clearance(npc.npcClass), npc.position(), path, view)) invalidate(now, "path_not_permitted")
        if (reached()) {
            releasePath()
            if (npc.target == null && intent.duty.watching) {
                val direction = intent.focus?.native() ?: intent.anchor.native().add(npc.lookAngle.scale(8.0))
                npc.lookControl.setLookAt(direction.x, direction.y + 1.5, direction.z)
            }
            DebugFlags.log(LogGroup.ORDER, "[htn] actor={} event=post_action_completed duty={} position={}", npc.uuid, intent.duty, task.position)
            return ActionUpdate.Completed(listOf(ActionEvidence("physical_post_arrival", now, "${npc.uuid}:${task.position}")))
        }
        // Holding, he keeps the spot he stands on, taken again once a push cleared it. Never a spot
        // a grenade threatens: put straight back, the hazard check could never move him off it.
        if (intent.hold && task.position == null) {
            if (GrenadeHazard.threatens(npc.level() as ServerLevel, npc.position())) return deferred(now, "hold_spot_threatened")
            task.position = npc.position()
        }
        if (task.position == null || task.search != null) {
            val result = TacticalPositions.find(npc, task, view, TacticalPositions.Query(policy, claimOwner))
            when (result.outcome) {
                TacticalPositions.Outcome.DEFERRED -> return deferred(now, "position_query_budget")

                TacticalPositions.Outcome.UNREACHABLE -> {
                    exhausted(intent, result.rejections, task.anchor)
                    return ActionUpdate.Failed("local_position_query_exhausted")
                }

                TacticalPositions.Outcome.FOUND -> {
                    task.position = result.position
                    lastProgress = now
                    closest = Double.MAX_VALUE
                }
            }
        }
        val destination = task.position ?: return deferred(now, "position_not_available")
        FiringSpots.claim(npc.uuid, destination, claimOwner)
        val distance = npc.position().distanceTo(destination)
        if (distance < closest - 0.5) {
            closest = distance
            lastProgress = now
        }
        if (FiringPostArrival.reached(npc.position(), destination)) {
            if (intent.duty.advancing && !intent.hold && destination.distanceTo(task.anchor) > 4) {
                // A leg of an advance by legs ends here: he fires from this post before the next rush.
                // A post a few steps on is passed: a pause every two blocks crawled the attack.
                if (intent.legs && destination.distanceTo(legStart) >= MIN_LEG) {
                    releasePath()
                    DebugFlags.log(LogGroup.ORDER, "[htn] actor={} event=leg_post_reached position={} toAnchor={}", npc.uuid, destination, "%.0f".format(destination.distanceTo(task.anchor)))
                    return ActionUpdate.Completed(listOf(ActionEvidence("leg_post_arrival", now, "${npc.uuid}:$destination")))
                }
                invalidate(now, "intermediate_reached")
                return ActionUpdate.Running
            }
            if (intent.duty == CombatDuty.FIRE && !intent.hold && !TacticalPositions.firingReady(npc, task, npc.position())) {
                task.localFirePost = npc.position()
                invalidate(now, "no_firing_lane")
                return deferred(now, "firing_lane_recovery")
            }
            return ActionUpdate.Running
        }
        if (now - lastProgress >= 120 && (npc.navigation as? VehicleAwareNavigation)?.canPlan != false) {
            exhausted(intent, mapOf("movement_stalled" to 1), task.anchor)
            return ActionUpdate.Failed("local_movement_stalled")
        }
        if (FiringPostArrival.steer(npc, destination, 1.3)) return ActionUpdate.Running
        if (npc.navigation.isDone) {
            if (!TacticalBudget.path(npc)) return deferred(now, "native_path_budget")
            val route = TacticalPositions.pathToPosition(npc, destination)
            if (!TacticalPositions.reaches(route, destination) || !TacticalClearance.permitsPath(policy.clearance(npc.npcClass), npc.position(), route!!, view)) {
                invalidate(now, "route_unreachable")
                return ActionUpdate.Running
            }
            if (npc.navigation.moveTo(route, 1.3)) task.navigationPath = route
        }
        return ActionUpdate.Running
    }
    private fun deferred(now: Long, reason: String): ActionUpdate {
        deferredAt = now
        return ActionUpdate.Deferred(reason)
    }
    fun maintainLook() {
        if (npc.target != null || !NativeTactics.movable(npc)) {
            releaseLook()
            return
        }
        if (!intent.duty.watching || !reached()) return
        val sweep = glance()
        val watching = intent.focus?.native()?.let { focusGaze(it, sweep) } ?: idleGaze(sweep)
        val tracker = net.minecraft.world.entity.ai.behavior.BlockPosTracker(net.minecraft.core.BlockPos.containing(watching))
        ownedLook = tracker
        net.tslat.smartbrainlib.util.BrainUtils.setMemory(npc, net.minecraft.world.entity.ai.memory.MemoryModuleType.LOOK_TARGET, tracker)
        npc.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, watching)
        npc.lookControl.setLookAt(watching.x, watching.y, watching.z)
    }

    /**
     * The sideways offset of a man's current glance from what he watches, kept for a few random seconds.
     *
     * A sine of [NpcEntity.tickCount] turned the heads of men deployed together in step, all
     * towards the same squad front, like a dance troupe (#167).
     */
    private fun glance(): Double {
        if (npc.tickCount >= nextGlanceAt) {
            glanceOffset = (npc.random.nextDouble() * 2.0 - 1.0) * GLANCE_SPREAD
            nextGlanceAt = npc.tickCount + GLANCE_MIN_TICKS + npc.random.nextInt(GLANCE_EXTRA_TICKS)
        }
        return glanceOffset
    }

    /**
     * A point beside [focus], across his line of sight to it. Offset along a world diagonal, the
     * glance ran along that line for a man facing it and barely turned his head.
     */
    private fun focusGaze(focus: Vec3, sweep: Double): Vec3 {
        val dx = focus.x - npc.eyePosition.x
        val dz = focus.z - npc.eyePosition.z
        val length = kotlin.math.hypot(dx, dz).coerceAtLeast(MIN_GAZE_LENGTH)
        return focus.add(-dz / length * sweep, FOCUS_GAZE_RISE, dx / length * sweep)
    }

    /**
     * Eye-level point to watch with no focus: towards the squad's front, swept sideways. Built from
     * his own gaze and raised, it made him look further up every tick (#120). The last resort is
     * the way he faced when he first needed one, kept.
     */
    private fun idleGaze(sweep: Double): Vec3 {
        val (dx, dz) = squadFront() ?: idleDirection ?: facing().also { idleDirection = it }
        val eye = npc.eyePosition
        return Vec3(eye.x + dx * IDLE_GAZE_DISTANCE - dz * sweep, eye.y, eye.z + dz * IDLE_GAZE_DISTANCE + dx * sweep)
    }

    private fun squadFront(): Pair<Double, Double>? {
        val view = snapshot() ?: return null
        val nearest = view.contacts.minByOrNull { it.position.distanceToSqr(npc.position()) }
        return ObserverGaze.direction(
            npc.position().point(),
            ObserverGaze.Cues(nearest?.position?.point(), view.threatOrigin?.point(), view.home?.point(), view.order == SquadOrder.ATTACK, view.center.point()),
        )
    }

    private fun facing(): Pair<Double, Double> {
        val yaw = Math.toRadians(npc.yHeadRot.toDouble())
        return Pair(-kotlin.math.sin(yaw), kotlin.math.cos(yaw))
    }

    private fun releaseLook() {
        if (ownedLook != null && npc.brain.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.LOOK_TARGET).orElse(null) === ownedLook) {
            npc.brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.LOOK_TARGET)
        }
        ownedLook = null
    }
    fun requestFireRecovery(now: Long) {
        if (intent.duty != CombatDuty.FIRE || !npc.onGround() || task.position?.let { FiringPostArrival.reached(npc.position(), it) } != true) return
        task.localFirePost = npc.position()
        invalidate(now, "fire_recovery_requested")
    }
    fun invalidate(now: Long, reason: String) {
        DebugFlags.log(LogGroup.ORDER, "[htn] actor={} event=post_invalidated reason={} duty={}", npc.uuid, reason, intent.duty)
        releasePath()
        task.position = null
        task.search = null
        task.nextSearch = now
        FiringSpots.release(npc.uuid, claimOwner)
    }
    private fun releasePath() = task.releaseNavigation(npc.navigation.path) { npc.navigation.stop() }

    /**
     * A revised intent keeps this execution, its post and route while the post still serves it:
     * the enemy moving or fire starting and stopping must not send every man to a new post.
     */
    override fun revise(spec: ActionSpec<*>, now: Long): Boolean {
        val next = spec.parameters as? PositionIntent ?: return false
        val rejection = when {
            spec.key != TacticalDomain.POSITION || released -> "not_running"

            next.actor != intent.actor || next.duty != intent.duty -> "duty_changed"

            next.inspection != intent.inspection || next.hold != intent.hold || next.legs != intent.legs -> "post_kind_changed"

            // GAIN_HEIGHT sets the floor from where the man stands now, so it moved with every
            // step up or down a slope and threw away a post that was still high enough.
            next.minimumHeight != intent.minimumHeight && !heightServed(next) -> "post_kind_changed"

            (next.requireShelter && !intent.requireShelter) || (next.requireCover && !intent.requireCover) -> "shelter_now_required"

            !servedBy(next) -> "post_outside_new_anchor"

            else -> null
        }
        if (rejection != null) {
            DebugFlags.log(LogGroup.ORDER, "[htn] actor={} event=post_replaced reason={} duty={}", npc.uuid, rejection, next.duty)
            return false
        }
        intent = next
        task.focus = next.focus?.native()
        return true
    }

    // The last anchor revised in and where it stands dry: revisions repeat the same point.
    private var dryAnchor = intent.anchor to task.anchor
    private fun dry(anchor: Point3): Vec3 {
        if (dryAnchor.first != anchor) dryAnchor = anchor to DryGround.near(npc.level() as ServerLevel, anchor.native(), npc.position())
        return dryAnchor.second
    }

    private fun heightServed(next: PositionIntent): Boolean {
        val post = task.position ?: return false
        return next.minimumHeight?.let { post.y >= it } != false
    }

    private fun servedBy(next: PositionIntent): Boolean {
        val anchor = dry(next.anchor)
        if (next.duty.advancing) return anchor.distanceTo(task.anchor) <= 4.0
        return task.position?.let { TacticalFirePosts.withinPost(anchor, it, recovery = true) } ?: (anchor.distanceTo(task.anchor) <= CombatPosition.LOCAL_MOVE_RADIUS)
    }

    override fun suspend(now: Long, reason: String) {
        releaseLook()
        releasePath()
        released = true
        FiringSpots.release(npc.uuid, claimOwner)
    }
    override fun resume(now: Long) {
        if (released) {
            lastProgress = now
            closest = Double.MAX_VALUE
            released = false
        }
    }
    override fun dispose(now: Long, reason: String) {
        releaseLook()
        releasePath()
        FiringSpots.release(npc.uuid, claimOwner)
    }
    override fun cancel(now: Long, reason: String) {
        releaseLook()
        releasePath()
        FiringSpots.release(npc.uuid, claimOwner)
    }

    internal companion object {
        /** The shortest leg of an advance by legs; a nearer post on the way is passed without a pause. */
        private const val MIN_LEG = 8.0

        /** How far ahead an observer with no focus watches. */
        private const val IDLE_GAZE_DISTANCE = 8.0

        /** How far above a watched focus point he looks, roughly a standing man's chest. */
        private const val FOCUS_GAZE_RISE = 1.5

        /** Keeps a focus right above him from dividing by zero. */
        private const val MIN_GAZE_LENGTH = 0.01

        /** Farthest sideways, in blocks, of what he watches that a glance goes. */
        private const val GLANCE_SPREAD = 4.0

        /** The least a glance lasts. */
        private const val GLANCE_MIN_TICKS = 40

        /** The most a glance lasts beyond [GLANCE_MIN_TICKS]. */
        private const val GLANCE_EXTRA_TICKS = 60

        /** A candidate this near the route leg is on the way, even while it leads away from the anchor. */
        private const val ROUTE_LEG_REACH = 6.0

        /** How much nearer the anchor, or the leg, a MOVE or SEARCH post has to be than the man. */
        private const val PROGRESS = 1.5

        /** A MOVE or SEARCH post this near its anchor is the anchor itself, as in [reached]. */
        private const val AT_ANCHOR = 4.0

        /** How far an assault post's enemy may have moved since the post was planned and still be him. */
        private const val ENEMY_MOVED = 8.0

        /**
         * Whether a MOVE or SEARCH post at [point] takes a man at [from] on towards [anchor]: it is
         * at the anchor, nearer it, or near the route leg [localAnchor] and nearer that leg.
         *
         * Lying near the leg alone is not enough. A leg next to the man let his own spot through;
         * the post search turned it into his position, he "arrived", dropped it as an intermediate
         * post and picked it again every other tick, never failing the query that would have
         * replanned him (#50).
         */
        internal fun advances(from: Vec3, anchor: Vec3, localAnchor: Vec3, point: Vec3): Boolean {
            if (point.distanceTo(anchor) <= AT_ANCHOR || point.distanceTo(anchor) < from.distanceTo(anchor) - PROGRESS) return true
            // A leg along the walked route may lead away from the anchor for a while to get round.
            return point.distanceTo(localAnchor) <= ROUTE_LEG_REACH && point.distanceTo(localAnchor) < from.distanceTo(localAnchor) - PROGRESS
        }
    }
}
