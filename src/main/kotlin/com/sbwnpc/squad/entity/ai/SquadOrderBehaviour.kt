package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.RouteManager
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadOrder
import com.mojang.datafixers.util.Pair
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.util.DefaultRandomPos
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/**
 * SmartBrain migration step 8 — direct port of the old `SquadOrderGoal` onto `ExtendedBehaviour`,
 * placed in `NpcEntity.getIdleTasks()` alongside [InvestigateBehaviour] (migration step 7).
 * [SquadFormation]'s slot math is untouched (pure functions, never was a Goal) — only the glue
 * moved.
 *
 * Squad-order movement. Low priority — the gun behaviour (chase & shoot, Fight activity) always
 * wins when there's an enemy (`ATTACK_TARGET` present outranks Idle automatically); within Idle,
 * [InvestigateBehaviour] additionally outranks this one — see [eligible] below, which requires
 * `!entity.isAlert()` for exactly that reason (Idle behaviours don't have GoalSelector's automatic
 * per-Flag exclusivity, so the old goal-priority order 4-vs-5 has to be reproduced by hand here,
 * same idiom as `combatLockedByCover()` already is elsewhere in this migration).
 *
 *  - DEFEND: return to within `SquadFormation.ARRIVAL_RADIUS + 4.5` of home (objective point /
 *    guarded entity — currently 12 blocks, but derived rather than hardcoded, see that line), then
 *    hold in a loose SCATTER (see [SquadFormation] — deliberately not a tight ring, "almost FREE,
 *    just bounded to a radius" per user request).
 *  - PATROL: walk the squad's assigned Route in sequence if it has one (see RouteManager);
 *    otherwise wander within ~12 blocks of home as before.
 *  - ATTACK: advance to home; once EVERY member has actually arrived, the squad's own order flips
 *    to DEFEND automatically (see [allSquadArrived]) — taking a point means holding it next, not
 *    standing frozen in an assault wedge forever.
 *  - FREE / no squad / no home: inactive.
 *
 * Destination points go through [SquadFormation.slotTarget] instead of the bare anchor — every
 * member walking toward the literal same coordinate was what actually caused the pileup/"snake"
 * (they'd shove past each other via vanilla collision avoidance the whole way in). Once a member is
 * actually at its objective ([approachSlot]'s `arrived`), the formation switches to a stationary
 * perimeter (ring, facing outward) instead of staying frozen in a marching wedge/line.
 */
class SquadOrderBehaviour : ExtendedBehaviour<NpcEntity>() {

    private var repathCooldown = 0
    private var routeIndex = 0
    private var routeWaitUntil = 0

    // No memory gate needed — eligibility is purely squad/order/target state, same as the old goal's
    // canUse(). Unlike GunAttackBehaviour/SeekCoverBehaviour/InvestigateBehaviour, nothing here is
    // driven by a SmartBrainLib memory.
    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    private fun eligible(entity: NpcEntity): Boolean {
        if (entity.target != null) return false
        if (entity.isAlert()) return false
        val squad = entity.currentSquad() ?: return false
        return squad.order != SquadOrder.FREE && entity.homeCenter() != null
    }

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean = eligible(entity)
    override fun shouldKeepRunning(entity: NpcEntity): Boolean = eligible(entity)

    override fun start(entity: NpcEntity) {
        repathCooldown = 0
    }

    override fun tick(entity: NpcEntity) {
        val squad = entity.currentSquad() ?: return
        val order = squad.order
        val home = entity.homeCenter() ?: return
        val dist = entity.position().distanceTo(home)
        if (repathCooldown > 0) repathCooldown--

        when (order) {
            SquadOrder.ATTACK -> {
                val arrived = dist <= SquadFormation.ARRIVAL_RADIUS
                approachSlot(entity, home, arrived)
                // Per user request: "attack a point" means take it, then hold it — not stand
                // frozen in an assault wedge forever once there. Flips the squad's own order once
                // EVERY member (not just this one) has actually reached it, so the squad doesn't
                // start dispersing into DEFEND's looser SCATTER while stragglers are still catching
                // up. Checked only from this ATTACK branch, so it can never re-fire once already
                // DEFEND; harmless if two members both flip it the same tick (same value, idempotent).
                if (arrived && allSquadArrived(entity, squad, home)) squad.order = SquadOrder.DEFEND
            }
            // Own threshold (not SquadFormation.ARRIVAL_RADIUS) — DEFEND holds a wider perimeter
            // than ATTACK. Derived from ARRIVAL_RADIUS rather than a second hardcoded constant so
            // widening RING_RADIUS again later can't silently reintroduce the arrived/oscillation
            // bug documented on ARRIVAL_RADIUS itself (this was a flat `8.0` before, which the old
            // 3.5 RING_RADIUS made safe by accident — no longer safe by accident against 6.0).
            SquadOrder.DEFEND -> approachSlot(entity, home, arrived = dist <= SquadFormation.ARRIVAL_RADIUS + 4.5)
            SquadOrder.PATROL -> {
                val points = squad.routeId
                    ?.let { (entity.level() as? ServerLevel)?.let { lvl -> RouteManager.get(lvl).get(it) } }
                    ?.points
                if (!points.isNullOrEmpty()) {
                    tickRoute(entity, points)
                } else if (entity.navigation.isDone && repathCooldown == 0) {
                    wanderNear(entity, home)
                    repathCooldown = 40 + entity.random.nextInt(40)
                }
            }
            SquadOrder.FREE -> {}
        }
    }

    /** Walks toward [anchor]'s formation slot, switching to a held perimeter slot once [arrived].
     *  Shared by ATTACK/DEFEND — the only difference between them is the distance that counts as
     *  "arrived" (passed in by the caller). */
    private fun approachSlot(entity: NpcEntity, anchor: Vec3, arrived: Boolean) {
        val slot = SquadFormation.slotTarget(entity, anchor, anchor.subtract(entity.position()), arrived)
        if (entity.position().distanceTo(slot) > 1.5) {
            if (repathCooldown == 0) {
                entity.navigation.moveTo(slot.x, slot.y, slot.z, 1.0)
                repathCooldown = 20
            }
        } else {
            entity.navigation.stop()
        }
        if (arrived && entity.target == null) faceOutward(entity, anchor)
    }

    /** While holding a perimeter slot with nothing to shoot at, look away from the anchor instead
     *  of standing there facing an arbitrary direction — "guns pointing outward" per user feedback.
     *  No-op once there's a real target: GunAttackBehaviour's own lookAt (higher priority) takes over. */
    private fun faceOutward(entity: NpcEntity, anchor: Vec3) {
        val out = entity.position().subtract(anchor)
        if (out.lengthSqr() < 1.0e-6) return
        val dir = out.normalize()
        val lookAt = entity.position().add(dir.scale(10.0))
        entity.lookControl.setLookAt(lookAt.x, entity.eyePosition.y, lookAt.z)
    }

    /** Walks [points] in order, waiting a couple of seconds at each before moving to the next —
     *  falls back to the old random-wander behavior (see [tick]) when the squad has no route. The
     *  dwell at each point uses the RING/outward-facing perimeter too, same as ATTACK/DEFEND. */
    private fun tickRoute(entity: NpcEntity, points: List<BlockPos>) {
        val target = points[routeIndex.coerceIn(points.indices)]
        val center = target.center
        val dwelling = entity.tickCount < routeWaitUntil
        val slot = SquadFormation.slotTarget(entity, center, center.subtract(entity.position()), dwelling)

        if (!dwelling && entity.position().distanceTo(slot) <= 2.5) {
            routeIndex = (routeIndex + 1) % points.size
            routeWaitUntil = entity.tickCount + ROUTE_DWELL_TICKS + entity.random.nextInt(ROUTE_DWELL_JITTER)
            return
        }
        if (dwelling && entity.target == null) faceOutward(entity, center)
        if (entity.position().distanceTo(slot) > 1.5 && repathCooldown == 0) {
            entity.navigation.moveTo(slot.x, slot.y, slot.z, 1.0)
            repathCooldown = 20
        }
    }

    /** Resolves every squad member (not just this one) and checks it's within ARRIVAL_RADIUS of
     *  [home] — an unloaded/dead-but-not-yet-cleaned-up member counts as "not arrived" (conservative:
     *  better to keep waiting than switch the whole squad to DEFEND while unsure). */
    private fun allSquadArrived(entity: NpcEntity, squad: Squad, home: Vec3): Boolean {
        val level = entity.level() as? ServerLevel ?: return false
        return squad.members.all { id ->
            val member = level.getEntity(id) as? NpcEntity ?: return@all false
            member.position().distanceTo(home) <= SquadFormation.ARRIVAL_RADIUS
        }
    }

    private fun wanderNear(entity: NpcEntity, center: Vec3) {
        val target = DefaultRandomPos.getPosTowards(entity, 12, 6, center, Math.PI / 2.0) ?: return
        entity.navigation.moveTo(target.x, target.y, target.z, 0.9)
    }

    companion object {
        private const val ROUTE_DWELL_TICKS = 40
        private const val ROUTE_DWELL_JITTER = 40
    }
}
