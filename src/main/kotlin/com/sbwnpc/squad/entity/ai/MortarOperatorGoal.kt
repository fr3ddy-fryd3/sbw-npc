package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.item.misc.FiringParametersItem
import com.atsuishio.superbwarfare.item.misc.firingParameters
import com.atsuishio.superbwarfare.tools.TrajectoryCalculator
import com.sbwnpc.squad.combat.TeamAwareness
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
        // Genuine personal danger only (an enemy right on top of the operator) — NOT just "some
        // goal set mob.target", which also happens from the generic 10-block-LOS
        // NearestAttackableTargetGoal/HurtByTargetGoal every NpcEntity has regardless of class.
        // Bailing out on ANY mob.target used to silently disable the whole mortar fire-mission /
        // TeamAwareness path in ordinary combat conditions, not just real self-defense.
        val personalThreat = mob.target?.takeIf { it.isAlive && mob.distanceToSqr(it) <= SELF_DEFENSE_RANGE_SQR }
        if (personalThreat != null) return false
        if (fireTarget() == null) return false

        val current = mortar
        if (current != null && current.isAlive && !MortarClaims.isOperatorClaimedByOther(current.uuid, mob.uuid)) return true

        val level = mob.level() as? ServerLevel ?: return false
        val found = level.getEntitiesOfClass(
            MortarEntity::class.java, AABB.ofSize(mob.position(), SEARCH_RANGE, SEARCH_RANGE, SEARCH_RANGE)
        ).firstOrNull { !MortarClaims.isOperatorClaimedByOther(it.uuid, mob.uuid) } ?: return false

        MortarClaims.claimOperator(found.uuid, mob.uuid)
        mortar = found
        // See MortarLoaderGoal: non-"intelligent" mortars auto-fire on any inventory change, so
        // make sure this is set even if we claim the mortar before a loader ever does.
        found.intelligent = true
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

    /** No more blind radius scanning: a target is only usable if THIS mortar's own operator can
     *  personally see it right now (also reports it — he's a spotter too, with binoculars, not
     *  just a passive report recipient), OR the rest of the faction has relayed a fresh sighting
     *  via [TeamAwareness] — never a target nobody has actually spotted (hiding in a building/
     *  trench stays safe from indirect fire, as it should). */
    private fun scanForEnemy(): BlockPos? {
        if (mob.tickCount < nextScanTick) return lastScanResult
        nextScanTick = mob.tickCount + 20
        val level = mob.level() as? ServerLevel ?: return null
        val tick = mob.tickCount.toLong()
        val faction = SquadTeams.factionOf(mob)
        val radius = detectionRadius()
        val candidates = level.getEntitiesOfClass(
            LivingEntity::class.java, AABB.ofSize(mob.position(), radius * 2, radius * 2, radius * 2)
        ).filter { (it is NpcEntity || it is Player) && SquadTeams.isHostile(mob, it) && it.isAlive }

        var selfSpotted: LivingEntity? = null
        for (c in candidates) {
            if (!mob.sensing.hasLineOfSight(c)) continue
            if (faction != null) TeamAwareness.report(faction, c.uuid, tick)
            if (selfSpotted == null) selfSpotted = c
        }
        if (selfSpotted != null) {
            lastScanResult = BlockPos.containing(selfSpotted.position())
            return lastScanResult
        }

        val relayed = faction?.let { TeamAwareness.relayedContacts(it, tick) } ?: emptyList()
        val target = relayed.asSequence().mapNotNull { level.getEntity(it) as? LivingEntity }.firstOrNull { it.isAlive }
        lastScanResult = target?.let { BlockPos.containing(it.position()) }
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
        private const val MIN_RANGE_SQR = 25.0 * 25.0
        private const val SAFE_RADIUS = 10.0
        private const val SELF_DEFENSE_RANGE_SQR = 6.0 * 6.0
        private const val MIN_DETECTION = 80.0
        private const val MAX_DETECTION = 160.0
        private const val MIN_SCATTER = 5.0
        private const val MAX_SCATTER = 10.0
        private const val FIRE_COOLDOWN_TICKS = 50
    }
}
