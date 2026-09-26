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
 *  - DEFEND: return to within `SquadFormation.defendArrivalRadius` of home (objective point /
 *    guarded entity — currently 12 blocks, but derived rather than hardcoded, see that line), then
 *    hold in a loose SCATTER (see [SquadFormation] — deliberately not a tight ring).
 *  - PATROL: walk the squad's assigned Route in sequence if it has one (see RouteManager);
 *    otherwise wander within ~12 blocks of home as before.
 *  - ATTACK: advance to home; once EVERY member has actually arrived, the squad's own order flips
 *    to DEFEND automatically (see [allSquadArrived]) — taking a point means holding it next, not
 *    standing frozen in an assault wedge forever.
 *  - MOVE: walk calmly to the objective and hold the assigned infantry-grid slot.
 *
 * Destination points go through [SquadFormation.slotTarget] instead of the bare anchor — every
 * member walking toward the literal same coordinate was what actually caused the pileup/"snake"
 * (they'd shove past each other via vanilla collision avoidance the whole way in). Once a member is
 * actually at its objective ([approachSlot]'s `arrived`), ATTACK and DEFEND switch to a stationary
 * perimeter. MOVE deliberately keeps its infantry grid.
 */
class SquadOrderBehaviour : ExtendedBehaviour<NpcEntity>() {

    // ExtendedBehaviour defaults to a 60-tick runtime cap (confirmed via javap — see
    // SeekCoverBehaviour's doc comment for the full story); this needs to keep running for as long
    // as it stays eligible, not get force-stopped/restarted (resetting repathCooldown etc.) every
    // 3 seconds regardless of squad-order state.
    init {
        noTimeout()
    }

    private var repathCooldown = 0
    private var routeIndex = 0
    private var routeWaitUntil = 0
    private var moveAnchor: BlockPos? = null
    private var nextArrivalCheckTick = 0
    private var orderStamp = -1
    /** A defender's chosen place — see [holdDefendPost]. Kept until the point or the order changes. */
    private var defendPost: Vec3? = null
    /** On the way to a roaming spot — see [roamAround]. */
    private var roaming = false
    private var defendPostHome: Vec3? = null

    // No memory gate needed — eligibility is purely squad/order/target state, same as the old goal's
    // canUse(). Unlike GunAttackBehaviour/SeekCoverBehaviour/InvestigateBehaviour, nothing here is
    // driven by a SmartBrainLib memory.
    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    private fun eligible(entity: NpcEntity): Boolean {
        if (entity.target != null) return false
        // An alarm sends a mob to look — except while falling back, when looking is the one thing
        // it must not do.
        if (entity.isAlert() && entity.retreatPoint() == null) return false
        // A dug-in mob clears COVER_HOLD for its whole holding duration (see
        // SeekCoverBehaviour.enterDugInHolding) so GunAttackBehaviour can still fire from the hole —
        // this behaviour never checked combatLockedByCover() in the first place (only target/alert/
        // order), so without this a dug-in mob whose target happened to die/break LOS for even a
        // moment would get marched off toward its DEFEND/PATROL slot, right out of its own hole.
        if (entity.diggedIn) return false
        if (entity.vehicleTransport || entity.operatingDrone || entity.servingMortar || entity.antiDroneEngaged) return false
        if (entity.evadingGrenade()) return false
        val squad = entity.currentSquad() ?: return false
        return entity.homeCenter() != null
    }

    private val startCheck = StartCheckThrottle(START_CHECK_INTERVAL_TICKS)
    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean =
        startCheck.check(entity) { eligible(entity) }
    override fun shouldKeepRunning(entity: NpcEntity): Boolean = eligible(entity)

    override fun stop(entity: NpcEntity) {
        startCheck.reset()
        com.sbwnpc.squad.combat.FiringSpots.release(entity.uuid)
    }

    override fun start(entity: NpcEntity) {
        repathCooldown = 0
        moveAnchor = null
    }

    override fun tick(entity: NpcEntity) {
        val squad = entity.currentSquad() ?: return
        val order = squad.order
        val home = entity.homeCenter() ?: return
        val dist = entity.position().distanceTo(home)
        if (squad.orderStamp != orderStamp) {
            // Fresh command: path to it now, not after the cooldown left over from the last one.
            orderStamp = squad.orderStamp
            repathCooldown = 0
            defendPost = null
        }
        if (repathCooldown > 0) repathCooldown--

        when (order) {
            SquadOrder.ATTACK -> {
                val arrived = dist <= SquadFormation.ARRIVAL_RADIUS
                // Only ATTACK (taking a point) moves at RUN pace, same as actually being in combat
                // (GunAttackBehaviour's own movement already uses this same 1.0 modifier) — per user
                // request, MOVE/DEFEND/PATROL should read as a calm hold/patrol, not a constant jog.
                approachSlot(entity, home, arrived, RUN_SPEED_MODIFIER)
                // Per user request: "attack a point" means take it, then hold it — not stand
                // frozen in an assault wedge forever once there. Flips the squad's own order once
                // EVERY member (not just this one) has actually reached it, so the squad doesn't
                // start dispersing into DEFEND's looser SCATTER while stragglers are still catching
                // up. Checked only from this ATTACK branch, so it can never re-fire once already
                // DEFEND; harmless if two members both flip it the same tick (same value, idempotent).
                // Throttled: this resolves every member by UUID each call, and once arrived it
                // used to run every tick for every arrived member until the last straggler showed
                // up. A one-second delay before the flip is invisible in-game.
                if (arrived && entity.tickCount >= nextArrivalCheckTick) {
                    nextArrivalCheckTick = entity.tickCount + ARRIVAL_CHECK_INTERVAL_TICKS
                    if (allSquadArrived(entity, squad, home)) squad.order = SquadOrder.DEFEND
                }
            }
            // Own threshold (not SquadFormation.ARRIVAL_RADIUS) — DEFEND holds a wider perimeter
            // than ATTACK. Derived from ARRIVAL_RADIUS rather than a second hardcoded constant so
            // widening RING_RADIUS again later can't silently reintroduce the arrived/oscillation
            // bug documented on ARRIVAL_RADIUS itself (this was a flat `8.0` before, which the old
            // 3.5 RING_RADIUS made safe by accident — no longer safe by accident against 6.0).
            // A barraging mortar crew stays put by its tube exactly like DEFEND; only the aim
            // point differs, and that is MortarOperatorBehaviour's business.
            SquadOrder.DEFEND, SquadOrder.BARRAGE -> {
                val arrived = dist <= if (order == SquadOrder.DEFEND) SquadFormation.defendArrivalRadius(squad.members.size)
                    else SquadFormation.ARRIVAL_RADIUS + 4.5
                if (arrived && order == SquadOrder.DEFEND) holdDefendPost(entity, home)
                else approachSlot(entity, home, arrived, WALK_SPEED_MODIFIER)
            }
            SquadOrder.PATROL -> {
                val points = squad.routeId
                    ?.let { (entity.level() as? ServerLevel)?.let { lvl -> RouteManager.get(lvl).get(it) } }
                    ?.points
                if (!points.isNullOrEmpty()) {
                    tickRoute(entity, points)
                } else if (entity.navigation.isDone && roaming) {
                    // Got there: stand about for a while before the next spot.
                    roaming = false
                    repathCooldown = ROAM_PAUSE_MIN + entity.random.nextInt(ROAM_PAUSE_JITTER)
                } else if (entity.navigation.isDone && repathCooldown == 0) {
                    roamAround(entity, home)
                }
            }
            SquadOrder.MOVE -> tickMove(entity, home)
            // Nobody in sight: just get there at a run. Under fire GunAttackBehaviour takes over
            // and makes it a fighting withdrawal.
            SquadOrder.RETREAT -> {
                val arrived = dist <= SquadFormation.ARRIVAL_RADIUS
                // A squadmate is in a fight: this one's half may be the one holding to cover it.
                val bound = if (arrived) null else com.sbwnpc.squad.combat.Withdrawal.positionFor(entity, squad, home)
                if (!arrived && bound == null) entity.navigation.stop()
                else if (bound != null) moveToSlot(entity, bound, RUN_SPEED_MODIFIER)
                else approachSlot(entity, home, true, RUN_SPEED_MODIFIER)
                if (arrived && entity.tickCount >= nextArrivalCheckTick) {
                    nextArrivalCheckTick = entity.tickCount + ARRIVAL_CHECK_INTERVAL_TICKS
                    if (allSquadArrived(entity, squad, home)) squad.order = SquadOrder.DEFEND
                }
            }
        }
    }

    private fun tickMove(entity: NpcEntity, home: Vec3) {
        val squad = entity.currentSquad() ?: return
        val assembly = squad.moveAssembly?.center ?: home
        val anchor = BlockPos.containing(home)
        if (moveAnchor != anchor) {
            moveAnchor = anchor
            repathCooldown = 0
            entity.navigation.stop()
        }

        val heading = home.subtract(assembly)
        if (!squad.moveFormationReady) {
            val rallySlot = SquadFormation.moveSlotTarget(entity, assembly, heading)
            moveToSlot(entity, rallySlot, fallIn = true)
            // The rally is for looks; it must never hold the order up. It used to wait for every
            // member, so one man in a fight, at a mortar or stuck behind a wall kept the whole
            // squad standing at the rally point indefinitely.
            val level = entity.level()
            if (squad.moveRallySince == 0L) squad.moveRallySince = level.gameTime
            if (level.gameTime - squad.moveRallySince >= MOVE_RALLY_TIMEOUT_TICKS ||
                allMoveMembersInSlots(entity, squad, assembly, heading)
            ) {
                squad.moveFormationReady = true
            }
            return
        }

        val slot = SquadFormation.moveSlotTarget(entity, home, heading)
        moveToSlot(entity, slot)
    }

    /** [fallIn]: this is the squad forming up, and a member still far from its place runs to it.
     *  Otherwise the slot is out at the objective, and "far from it" just means "not there yet". */
    private fun moveToSlot(entity: NpcEntity, slot: Vec3, speed: Double = WALK_SPEED_MODIFIER, fallIn: Boolean = false) {
        if (entity.position().distanceTo(slot) > 1.5) {
            val pace = if (fallIn) paceTo(entity, slot, speed) else holdPace(entity, speed)
            if (repathCooldown == 0) {
                entity.navigation.moveTo(slot.x, slot.y, slot.z, pace)
                repathCooldown = 20
            }
        } else {
            entity.navigation.stop()
        }
    }

    private fun allMoveMembersInSlots(entity: NpcEntity, squad: Squad, anchor: Vec3, heading: Vec3): Boolean {
        val level = entity.level() as? ServerLevel ?: return false
        return squad.members.all { id ->
            val member = level.getEntity(id) as? NpcEntity ?: return@all true
            // Busy elsewhere: not coming to the rally, so not worth waiting for.
            if (member.target != null || member.vehicle != null || member.vehicleTransport ||
                member.servingMortar || member.operatingDrone || member.diggedIn
            ) return@all true
            member.position().distanceTo(SquadFormation.moveSlotTarget(member, anchor, heading)) <= MOVE_SLOT_RADIUS
        }
    }

    /** Walks toward [anchor]'s formation slot, switching to a held perimeter slot once [arrived].
     *  Shared by ATTACK/DEFEND — the only difference between them is the distance that counts as
     *  "arrived" and the pace ([speed], passed in by the caller — see [RUN_SPEED_MODIFIER]/
     *  [WALK_SPEED_MODIFIER]). */
    private fun approachSlot(entity: NpcEntity, anchor: Vec3, arrived: Boolean, speed: Double) {
        val slot = SquadFormation.slotTarget(entity, anchor, anchor.subtract(entity.position()), arrived)
        // Held and the path already ended close by: the slot can't be stood on exactly, and asking
        // for it again every second had the mob turning on the spot.
        val settled = arrived && entity.navigation.isDone && entity.position().distanceTo(slot) <= SLOT_SETTLE_DISTANCE
        if (!settled && entity.position().distanceTo(slot) > 1.5) {
            val pace = holdPace(entity, speed)
            if (repathCooldown == 0) {
                entity.navigation.moveTo(slot.x, slot.y, slot.z, pace)
                repathCooldown = 20
            }
        } else {
            entity.navigation.stop()
        }
        if (arrived && entity.target == null) faceOutward(entity, anchor)
    }

    /**
     * A defender's own place on the perimeter, picked once and then held: it walks there, stops,
     * and watches outward until the order or the point changes. Near its perimeter slot it prefers
     * a spot with low cover on the outward side — something to crouch behind that it can still see
     * over — and keeps clear of where squadmates already are.
     */
    private fun holdDefendPost(entity: NpcEntity, home: Vec3) {
        val level = entity.level() as? ServerLevel ?: return
        val post = defendPost?.takeIf { defendPostHome == home } ?: chooseDefendPost(entity, level, home).also {
            defendPost = it
            defendPostHome = home
            com.sbwnpc.squad.combat.FiringSpots.claim(entity.uuid, it)
        }
        val here = entity.position()
        val dx = here.x - post.x
        val dz = here.z - post.z
        val settled = entity.navigation.isDone && dx * dx + dz * dz <= DEFEND_SETTLE_DISTANCE * DEFEND_SETTLE_DISTANCE
        if (dx * dx + dz * dz > 1.0 && !settled) {
            val pace = holdPace(entity, WALK_SPEED_MODIFIER)
            if (repathCooldown == 0) {
                entity.navigation.moveTo(post.x, post.y, post.z, pace)
                repathCooldown = 20
            }
        } else {
            entity.navigation.stop()
        }
        if (entity.target == null) faceOutward(entity, home)
    }

    private fun chooseDefendPost(entity: NpcEntity, level: ServerLevel, home: Vec3): Vec3 {
        val slot = SquadFormation.slotTarget(entity, home, home.subtract(entity.position()), true)
        val out = Vec3(slot.x - home.x, 0.0, slot.z - home.z)
        val outward = if (out.lengthSqr() < 1.0e-4) Vec3(1.0, 0.0, 0.0) else out.normalize()
        val taken = com.sbwnpc.squad.combat.FiringSpots.nearbyWithBodies(level, entity, DEFEND_POST_SEARCH + 2.0)
        var best = slot
        var bestScore = Double.NEGATIVE_INFINITY
        val r = DEFEND_POST_SEARCH.toInt()
        for (ox in -r..r) for (oz in -r..r) {
            val ground = com.sbwnpc.squad.util.Terrain.standableOrNull(level, slot.x + ox, slot.y + 1.0, slot.z + oz) ?: continue
            if (com.sbwnpc.squad.combat.FiringSpots.crowded(ground, taken)) continue
            val score = coverScore(level, ground, outward) - Math.sqrt((ox * ox + oz * oz).toDouble()) * 0.3
            if (score > bestScore) {
                bestScore = score
                best = ground
            }
        }
        return best
    }

    /** Low cover on the outward side scores best (crouch behind it, see over it); a full-height
     *  wall some (cover, but blind); open ground nothing. */
    private fun coverScore(level: ServerLevel, spot: Vec3, outward: Vec3): Double {
        val front = BlockPos.containing(spot.x + outward.x, spot.y, spot.z + outward.z)
        val knee = !level.getBlockState(front).getCollisionShape(level, front).isEmpty
        val chest = !level.getBlockState(front.above()).getCollisionShape(level, front.above()).isEmpty
        return when {
            knee && !chest -> 3.0
            knee || chest -> 1.0
            else -> 0.0
        }
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
        if (entity.position().distanceTo(slot) > 1.5) {
            val pace = holdPace(entity, WALK_SPEED_MODIFIER)
            if (repathCooldown == 0) {
                entity.navigation.moveTo(slot.x, slot.y, slot.z, pace)
                repathCooldown = 20
            }
        }
    }

    /** Keeps a path already under way at [speed] — a member that was running to fall in drops
     *  back to a walk straight away, not at its next repath. */
    private fun holdPace(entity: NpcEntity, speed: Double): Double {
        if (!entity.navigation.isDone) entity.navigation.setSpeedModifier(speed)
        return speed
    }

    /** [base] once in formation; a run while still catching up to it, so the squad closes up
     *  quickly instead of strolling into place. Applied to a path already under way too, so a
     *  member that falls in drops to a walk without waiting for its next repath. */
    private fun paceTo(entity: NpcEntity, slot: Vec3, base: Double): Double {
        val pace = if (entity.position().distanceTo(slot) > FALL_IN_DISTANCE) maxOf(base, FALL_IN_SPEED_MODIFIER) else base
        if (!entity.navigation.isDone) entity.navigation.setSpeedModifier(pace)
        return pace
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

    /**
     * A patrol with no route: every member drifts about the area on its own — a spot somewhere in
     * [ROAM_RADIUS] of the point, a walk there, a pause to look around, and the next. Picking each
     * spot as "a step toward the point", as this used to, drew the whole squad into a knot at the
     * middle.
     */
    private fun roamAround(entity: NpcEntity, center: Vec3) {
        val level = entity.level() as? ServerLevel ?: return
        repeat(ROAM_ATTEMPTS) {
            val angle = entity.random.nextDouble() * Math.PI * 2
            val dist = Math.sqrt(entity.random.nextDouble()) * ROAM_RADIUS
            val spot = com.sbwnpc.squad.util.Terrain.standableOrNull(
                level, center.x + Math.cos(angle) * dist, center.y + 4.0, center.z + Math.sin(angle) * dist, 12
            ) ?: return@repeat
            if (entity.navigation.moveTo(spot.x, spot.y, spot.z, WALK_SPEED_MODIFIER)) {
                roaming = true
                return
            }
        }
        repathCooldown = ROAM_PAUSE_MIN
    }

    companion object {
        private const val ROUTE_DWELL_TICKS = 40
        private const val ROAM_RADIUS = 20.0
        private const val ROAM_ATTEMPTS = 4
        /** Standing about at a spot before heading for the next. */
        private const val ROAM_PAUSE_MIN = 100
        private const val ROAM_PAUSE_JITTER = 140
        private const val ARRIVAL_CHECK_INTERVAL_TICKS = 20
        private const val START_CHECK_INTERVAL_TICKS = 5
        private const val ROUTE_DWELL_JITTER = 40
        private const val MOVE_SLOT_RADIUS = 2.0
        private const val SLOT_SETTLE_DISTANCE = 3.0
        /** How far around its perimeter slot a defender looks for a better spot. */
        private const val DEFEND_POST_SEARCH = 3.0
        private const val DEFEND_SETTLE_DISTANCE = 2.5
        private const val MOVE_RALLY_TIMEOUT_TICKS = 100L
        // Per user request: MOVE/DEFEND/PATROL should read as a calm hold/patrol, not a constant
        // jog — only actually taking a point (ATTACK) or engaging (GunAttackBehaviour, which already
        // moves at a plain 1.0 modifier) should look urgent. Both are still just a navigation
        // speedModifier multiplying the entity's own per-class MOVEMENT_SPEED attribute (tuned in
        // NpcEntity.applyRole()) — this doesn't change how fast an NPC even CAN move, just how much
        // of that speed idle movement actually uses. RUN_SPEED_MODIFIER matches what ATTACK/combat
        // movement already used before this change (kept as a named constant here purely so both
        // paces are visible together, not because ATTACK's pace itself changed).
        // internal (not private) — NpcEntity.registerGoals() reuses this for vanilla idle wandering.
        internal const val WALK_SPEED_MODIFIER = 0.6
        private const val RUN_SPEED_MODIFIER = 1.0
        /** Out of formation by more than this, a member runs to its place. */
        private const val FALL_IN_DISTANCE = 4.0
        private const val FALL_IN_SPEED_MODIFIER = 1.3
    }
}
