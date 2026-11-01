package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.ActionExecution
import com.sbwnpc.squad.ai.planning.ActionUpdate
import com.sbwnpc.squad.ai.tactics.CrewDuty
import com.sbwnpc.squad.ai.tactics.CrewWork
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.combat.FiringPostArrival
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.entity.ai.MortarClaims
import com.sbwnpc.squad.entity.ai.MortarDeployment
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * One mortarman's execution state across his crew duties (#258): where he last laid, the barrage
 * point he holds, the friendly check he caches, his loading post. It owns his
 * [NpcActivity.MORTAR_CREW] and [NpcActivity.MORTAR_ERRAND] claims, as part of his plan.
 */
internal class CrewHand(private val npc: NpcEntity) : PlanExecutor {
    /** When he lays next, and whether his last lay found the target in reach. */
    var nextAimTick = 0L
    var reachable = true

    /** The barrage point he holds, for which order, until when. */
    var barrageAim: BlockPos? = null
    var barrageFor: String? = null
    var nextBarrageShift = 0L

    /** His last friendly-near-impact check: when, for which aim, and its answer. */
    var friendlyAt = Long.MIN_VALUE / 2
    var friendlyTarget: BlockPos? = null
    var friendlyResult = false
    private var post: Pair<Entity, Vec3>? = null

    /** Holds the claims [crew] and [errand] say, and only those. */
    fun hold(crew: Boolean, errand: Boolean) {
        if (crew) npc.claim(NpcActivity.MORTAR_CREW, this) else npc.release(NpcActivity.MORTAR_CREW, this)
        if (errand) npc.claim(NpcActivity.MORTAR_ERRAND, this) else npc.release(NpcActivity.MORTAR_ERRAND, this)
    }

    fun release() = hold(crew = false, errand = false)

    /** His tube: the one he is assigned to, while it is set up, working and friendly. */
    fun tube(): Entity? = npc.assignedMortarId?.let((npc.level() as ServerLevel)::getEntity)?.takeIf { it.isAlive && Ports.vehicles.isOperational(it) && !SquadTeams.isHostile(npc, it) }

    /** The loader's post beside [m], found once per tube. */
    fun loadingPost(m: Entity): Vec3 {
        post?.takeIf { it.first === m }?.let { return it.second }
        val yaw = Math.toRadians(m.yRot.toDouble())
        val found = Terrain.standableOrNull(npc.level(), m.x + Math.cos(yaw) * LOADER_OFFSET, m.y + 1.0, m.z + Math.sin(yaw) * LOADER_OFFSET) ?: m.position()
        post = m to found
        return found
    }

    private companion object {
        const val LOADER_OFFSET = 1.8
    }
}

/**
 * Carries out one man's [CrewDuty] (#258): walks to his post and loads, and as operator has
 * [TubeLaying] lay and fire; the moves of the other duties are [CrewMoves]'. What to do is the
 * plan's; the executor only refuses what would be unsafe.
 */
internal class CrewDutyAction(
    private val duty: CrewDuty,
    private val npc: NpcEntity,
    private val hand: CrewHand,
    world: FireSupportWorld,
) : ActionExecution,
    PlanExecutor {
    private val laying = TubeLaying(npc, hand, world)
    private val moves = CrewMoves(npc, hand, world, laying)

    override fun start(now: Long) = tick(now)

    override fun tick(now: Long): ActionUpdate {
        when (duty.work) {
            CrewWork.OPERATE -> serve(fire = true, now)
            CrewWork.LOAD -> serve(fire = false, now)
            CrewWork.FETCH_SHELLS -> if (!moves.fetchShells()) serve(fire = false, now)
            CrewWork.DISPLACE -> moves.displace(duty.to)
            CrewWork.FOLLOW -> moves.follow()
            CrewWork.TAKE_TUBE -> if (moves.takeTube(now)) serve(fire = false, now)
            CrewWork.RESERVE -> moves.reserve()
        }
        return ActionUpdate.Running
    }

    override fun suspend(now: Long, reason: String) = hand.release()
    override fun resume(now: Long) = Unit
    override fun cancel(now: Long, reason: String) = hand.release()

    private fun serve(fire: Boolean, now: Long) {
        val m = hand.tube() ?: return hand.release()
        val role = MortarClaims.assign(m, npc) ?: return hand.release()
        // The ammunition rule as before: a man out of shells fetches them, his mate keeping the tube.
        if (npc.mortarShellsLeft <= 0 && moves.fetchShells()) return
        if (walkToPost(m, role)) return
        hand.hold(crew = true, errand = false)
        npc.navigation.stop()
        npc.lookControl.setLookAt(m.x, m.y + LOOK_HEIGHT, m.z)
        if (!Ports.mortars.hasShell(m) && !Ports.mortars.isFiring(m) && npc.mortarShellsLeft > 0) {
            Ports.mortars.loadShell(m)
            npc.mortarShellsLeft--
        }
        if (role == MortarClaims.Role.OPERATOR && fire) duty.target?.let { laying.fire(m, it, now) }
    }

    /** Walks him to his post at [m] while he is away from it; false once he is there. */
    private fun walkToPost(m: Entity, role: MortarClaims.Role): Boolean {
        val distance = npc.distanceTo(m)
        if (distance <= AT_TUBE) return false
        val post = if (role == MortarClaims.Role.OPERATOR) m.position() else hand.loadingPost(m)
        hand.hold(crew = true, errand = true)
        val speed = if (distance > RUN_TO_TUBE_DISTANCE) CrewMoves.DISPLACE_SPEED else 1.0
        if (!FiringPostArrival.steer(npc, post, speed)) npc.navigateTo(post, speed)
        return true
    }

    companion object {
        /** Within this of the tube he is at it. */
        const val AT_TUBE = 2.5

        /** Farther than this from the tube, he runs to it. */
        private const val RUN_TO_TUBE_DISTANCE = 8.0
        private const val LOOK_HEIGHT = 0.5
    }
}

/**
 * The moves of a crew's duties besides serving at the tube (#258): fetching shells, packing,
 * carrying and setting up the tube, following its carrier, taking a free tube, waiting in reserve.
 */
internal class CrewMoves(private val npc: NpcEntity, private val hand: CrewHand, private val world: FireSupportWorld, private val laying: TubeLaying) {
    private val level = npc.level() as ServerLevel

    /** Walks for shells, the other man taking the tube over meanwhile; false with no supply to go to. */
    fun fetchShells(): Boolean {
        val supply = npc.nearestSupply() ?: return false
        hand.tube()?.let { MortarClaims.handOver(it, npc) }
        hand.hold(crew = true, errand = true)
        if (npc.position().closerThan(supply, SupplyBlockEntity.RADIUS - SUPPLY_MARGIN)) npc.navigation.stop() else npc.navigateTo(supply, DISPLACE_SPEED)
        return true
    }

    /** Packs the tube, carries it to [to] and sets it up there, or where he stands with no [to]. */
    fun displace(to: Point3?) {
        if (!npc.holds(NpcActivity.CARRYING_MORTAR)) {
            val m = hand.tube() ?: return hand.release()
            if (npc.distanceTo(m) > CrewDutyAction.AT_TUBE) {
                hand.hold(crew = false, errand = true)
                npc.navigateTo(m.x, m.y, m.z, DISPLACE_SPEED)
            } else {
                MortarDeployment.pack(m, npc)
            }
            return
        }
        hand.release()
        val point = to?.native()
        if (point != null && npc.position().distanceTo(point) > FireSupportWorld.DEPLOY_RANGE) {
            npc.navigateTo(point, DISPLACE_SPEED)
            return
        }
        npc.navigation.stop()
        val facing = world.stateOf(npc.uuid)?.target?.let(laying::resolve)?.center
        if (MortarDeployment.deploy(level, npc, facing) != null) return
        // Nowhere to set up on this spot: try a step aside round where he was sent, never towards
        // the enemy; sent nowhere, walk on towards the target as before.
        if (point == null) {
            facing?.let { npc.navigateTo(it, DISPLACE_SPEED) }
            return
        }
        npc.navigateTo(point.add(npc.random.nextDouble() * SIDE_STEP * 2 - SIDE_STEP, 0.0, npc.random.nextDouble() * SIDE_STEP * 2 - SIDE_STEP), DISPLACE_SPEED)
    }

    /** Keeps up with the man of his crew carrying the tube. */
    fun follow() {
        val carrier = world.roster.unitOf(npc.uuid)?.crew?.members?.firstNotNullOfOrNull { (level.getEntity(it.id) as? NpcEntity)?.takeIf { man -> man !== npc && man.holds(NpcActivity.CARRYING_MORTAR) } }
            ?: return hand.release()
        hand.hold(crew = true, errand = true)
        if (npc.distanceToSqr(carrier) > FOLLOW_DISTANCE_SQR) npc.navigateTo(carrier.position(), DISPLACE_SPEED) else npc.navigation.stop()
    }

    /** Takes a post at the free tube in reach, which makes him one of its crew at the next update; false with none. */
    fun takeTube(now: Long): Boolean {
        val free = world.roster.freeTube(npc, now)?.let(level::getEntity)
        if (free != null && npc.assignedMortarId == free.uuid) return true
        if (free == null || MortarClaims.assign(free, npc) == null) {
            hand.release()
            return false
        }
        Ports.mortars.takeControl(free)
        return true
    }

    /** Stands by the nearest tube of his squad, to take a post that frees up; with none, leaves his feet to his order. */
    fun reserve() {
        val squad = npc.squadId ?: return hand.release()
        val tube = NpcRegistry.all(level).asSequence().filter { it !== npc && it.squadId == squad && it.isAlive }
            .mapNotNull { it.assignedMortarId?.let(level::getEntity) }.filter { it.isAlive }.minByOrNull { npc.distanceToSqr(it) } ?: return hand.release()
        if (npc.distanceTo(tube) <= FireSupportWorld.RELOCATE_RANGE) {
            // He stands by it: holding his feet, so his order does not walk him off meanwhile.
            hand.hold(crew = false, errand = true)
            npc.navigation.stop()
            return
        }
        hand.hold(crew = false, errand = true)
        npc.navigateTo(tube.position(), 1.0)
    }

    companion object {
        /** At a run, like a man falling in: a crew displacing its tube has fire to get back to. */
        const val DISPLACE_SPEED = 1.3
        private const val SUPPLY_MARGIN = 3.0
        private const val FOLLOW_DISTANCE_SQR = 25.0
        private const val SIDE_STEP = 3.0
    }
}
