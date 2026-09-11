package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.item.misc.FiringParametersItem
import com.atsuishio.superbwarfare.item.misc.firingParameters
import com.atsuishio.superbwarfare.tools.TrajectoryCalculator
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import java.util.EnumSet

/**
 * Requires the mortar to actually have shells loaded (normally kept topped up by a squadmate
 * running [MortarLoaderGoal]). Two ways to get a fire mission:
 *  - commanded: squad order ATTACK with an objective/focus set (always wins).
 *  - autonomous: nearest hostile within the rank-scaled detection radius, even under
 *    DEFEND/PATROL/FREE — a mortar crew doesn't just sit idle while enemies close in.
 *
 * Minimum range / friendly-safety-radius are heuristics, not a faithful read of the mortar's own
 * internal aim-solver state — needs in-game tuning.
 */
class MortarOperatorGoal(private val mob: NpcEntity) : Goal() {

    private var mortar: MortarEntity? = null
    private var nextAimTick = 0
    private var nextScanTick = 0
    private var nextFireTick = 0

    init {
        setFlags(EnumSet.of(Flag.MOVE))
    }

    override fun canUse(): Boolean {
        if (mob.npcClass != NpcClass.MORTAR_OPERATOR) return false
        if (mob.target != null) return false // don't abandon self-defence
        if (fireTarget() == null) return false

        val current = mortar
        if (current != null && current.isAlive && !MortarClaims.isOperatorClaimedByOther(current.uuid, mob.uuid)) return true

        val level = mob.level() as? ServerLevel ?: return false
        val found = level.getEntitiesOfClass(
            MortarEntity::class.java, AABB.ofSize(mob.position(), SEARCH_RANGE, SEARCH_RANGE, SEARCH_RANGE)
        ).firstOrNull { !MortarClaims.isOperatorClaimedByOther(it.uuid, mob.uuid) } ?: return false

        MortarClaims.claimOperator(found.uuid, mob.uuid)
        mortar = found
        return true
    }

    override fun canContinueToUse() = canUse()

    override fun stop() {
        MortarClaims.releaseOperator(mob.uuid)
        mortar = null
    }

    override fun tick() {
        val m = mortar ?: return
        val level = mob.level() as? ServerLevel ?: return
        val target = fireTarget() ?: return

        val dist = mob.position().distanceTo(m.position())
        if (dist > 2.5) {
            mob.navigation.moveTo(m.x, m.y, m.z, 1.0)
            return
        }
        mob.navigation.stop()

        if (target.distSqr(BlockPos.containing(m.position())) < MIN_RANGE_SQR) return
        // The mortar's own aim solver fails silently (keeps its previous/default aim) when a
        // target is out of ballistic range or beyond the turret's pitch limits. We used to fire
        // regardless, launching shells at whatever stale aim was left over — looked like firing
        // at max range into nothing. Ask the same solver ourselves first and just don't shoot
        // this tick if it can't actually hit the point.
        if (!canHitTarget(m, target)) return
        if (mob.currentSquad()?.let { friendlyNear(level, target) } == true) return

        if (mob.tickCount >= nextAimTick) {
            val stack = ItemStack(ModItems.FIRING_PARAMETERS.get())
            stack.firingParameters = FiringParametersItem.Parameters(target, scatterRadius(), false)
            m.setTarget(stack, mob, "Main")
            nextAimTick = mob.tickCount + 20
        }
        if (mob.tickCount >= nextFireTick) {
            m.vehicleShoot(mob, "Main", null)
            nextFireTick = mob.tickCount + FIRE_COOLDOWN_TICKS
        }
    }

    /** Mirrors the feasibility check `MortarEntity.setTarget` does internally (both a flat and a
     *  lofted trajectory are computed; at least one must exist and fit the turret's pitch limits)
     *  so we never fire at a target the solver actually rejected. */
    private fun canHitTarget(m: MortarEntity, target: BlockPos): Boolean {
        val v = m.getProjectileVelocity("Main").toDouble()
        val g = m.getProjectileGravity("Main").toDouble()
        val aimPoint = target.center.add(0.0, -1.0, 0.0)
        val flat = TrajectoryCalculator.calculateLaunchVector(m.eyePosition, aimPoint, v, g, true)
        val high = TrajectoryCalculator.calculateLaunchVector(m.eyePosition, aimPoint, v, g, false)
        if (flat == null || high == null) return false
        val angle = -VehicleVecUtils.getXRotFromVector(flat).toFloat()
        val angle2 = -VehicleVecUtils.getXRotFromVector(high).toFloat()
        val minPitch = m.turretMinPitch
        val maxPitch = m.turretMaxPitch
        if (angle < -maxPitch || angle > -minPitch) {
            return angle2 > -maxPitch && angle2 < -minPitch
        }
        return true
    }

    /** Squad-commanded target first, else the nearest hostile within detection radius. */
    private fun fireTarget(): BlockPos? {
        val squad = mob.currentSquad()
        if (squad != null && squad.order == SquadOrder.ATTACK) {
            commandedTarget(squad)?.let { return it }
        }
        return scanForEnemy()
    }

    private fun commandedTarget(squad: Squad): BlockPos? {
        squad.focusEntity?.let { fid ->
            (mob.level() as? ServerLevel)?.getEntity(fid)?.takeIf { it.isAlive }?.let { return BlockPos.containing(it.position()) }
        }
        return squad.objective
    }

    private fun scanForEnemy(): BlockPos? {
        if (mob.tickCount < nextScanTick) return lastScanResult
        nextScanTick = mob.tickCount + 20
        val level = mob.level() as? ServerLevel ?: return null
        val radius = detectionRadius()
        val enemy = level.getEntitiesOfClass(
            LivingEntity::class.java, AABB.ofSize(mob.position(), radius * 2, radius * 2, radius * 2)
        ).firstOrNull { (it is NpcEntity || it is Player) && SquadTeams.isHostile(mob, it) && it.isAlive }
        lastScanResult = enemy?.let { BlockPos.containing(it.position()) }
        return lastScanResult
    }

    private var lastScanResult: BlockPos? = null

    private fun detectionRadius(): Double {
        val t = mob.npcRank.ordinal / (com.sbwnpc.squad.npc.NpcRank.entries.size - 1).toDouble()
        return MIN_DETECTION + t * (MAX_DETECTION - MIN_DETECTION)
    }

    /** Impact-point scatter radius (blocks), fed straight into SBW's own `ArtilleryEntity`
     *  dispersion (`targetPos.center.randomPos(radius)`). Recruits scatter widest, elites
     *  land almost dead-on. */
    private fun scatterRadius(): Int {
        val t = mob.npcRank.ordinal / (com.sbwnpc.squad.npc.NpcRank.entries.size - 1).toDouble()
        return Math.round(MAX_SCATTER - t * (MAX_SCATTER - MIN_SCATTER)).toInt()
    }

    private fun friendlyNear(level: ServerLevel, target: BlockPos): Boolean {
        val center = target.center
        return level.getEntitiesOfClass(NpcEntity::class.java, AABB.ofSize(center, SAFE_RADIUS * 2, SAFE_RADIUS * 2, SAFE_RADIUS * 2))
            .any { it.squadId != null && !SquadTeams.isHostile(mob, it) }
    }

    companion object {
        private const val SEARCH_RANGE = 30.0
        private const val MIN_RANGE_SQR = 15.0 * 15.0
        private const val SAFE_RADIUS = 10.0
        private const val MIN_DETECTION = 80.0
        private const val MAX_DETECTION = 160.0
        private const val MIN_SCATTER = 5.0
        private const val MAX_SCATTER = 10.0
        private const val FIRE_COOLDOWN_TICKS = 50
    }
}
