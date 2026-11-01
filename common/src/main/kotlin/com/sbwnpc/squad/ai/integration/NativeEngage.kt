package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.planning.ActionExecution
import com.sbwnpc.squad.ai.planning.ActionUpdate
import com.sbwnpc.squad.ai.planning.FactSnapshot
import com.sbwnpc.squad.ai.tactics.Engage
import com.sbwnpc.squad.ai.tactics.EngageMove
import com.sbwnpc.squad.ai.tactics.EngageUnit
import com.sbwnpc.squad.ai.tactics.TacticalFacts
import com.sbwnpc.squad.combat.CombatPosition
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FireObservation
import com.sbwnpc.squad.combat.FiringPostArrival
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.GrenadeHazard
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.TickBudget
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.SquadTransit
import com.sbwnpc.squad.util.Terrain
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.util.DefaultRandomPos
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * What one man's engage keeps across its units (#251 step B), held by [NativeTactics] beside his
 * cover state: the firing spot he holds and its claim in [FiringSpots], where his fight started,
 * his advance from a post, the units he finished, the rest after a batch of sidesteps, and the
 * last values of his band facts. Each unit's own timing lives in its action.
 *
 * Released however his engage ends: a cancelled unit lets the spot go, and so does dropping him
 * from the plan ([dispose]).
 */
internal class EngageState(private val npc: NpcEntity) {
    val navigator = CombatNavigator()
    val search = FiringSpotSearch(navigator)

    var spot: Vec3? = null
        private set

    /** When the spot he holds is looked at again: until then he keeps it. */
    var nextCheck = 0

    /** Where his fight started, for a holding man's shift and his one advance. */
    var origin: Vec3? = null
        private set
    var advanceGoal: Vec3? = null
    var advanceDone = false
    var units = 0
        private set
    var sidestepRestUntil = 0

    // The band facts' last values, written only by [EngageFacts].
    var inReach = false
    var outside = false
    var keepAway = false
    private var orderStamp: Int? = null
    private var sidearm: Boolean? = null

    /**
     * A unit starts on [weapon]: a new order of his squad starts his fight over from where he
     * stands, and so does drawing or holstering his pistol, as the gun behaviour did before (#251).
     */
    fun begin(weapon: Boolean) {
        val stamp = npc.currentSquad()?.orderStamp
        if (stamp != orderStamp || (sidearm != null && sidearm != weapon)) {
            letGo()
            origin = npc.position()
            advanceGoal = null
            advanceDone = false
            navigator.reset()
            npc.navigation.stop()
        }
        if (origin == null) origin = npc.position()
        orderStamp = stamp
        sidearm = weapon
    }

    /** He holds [at] from now; others' searches keep off it. */
    fun hold(at: Vec3) {
        spot = at
        FiringSpots.claim(npc.uuid, at)
    }

    /** He holds no spot: a move takes him off it, or his engage ended. */
    fun letGo() {
        if (spot == null) return
        spot = null
        search.clear()
        navigator.forgetSelection()
        FiringSpots.release(npc.uuid)
    }

    /** A unit finished; the count is his plan's [TacticalFacts.ENGAGE_UNITS]. */
    fun finished() {
        units++
    }

    /** Where he may shift to fire from now. */
    fun bounds() = CombatGeometry.bounds(npc, origin ?: npc.position())

    /** He has left the plan: nothing he held stays claimed. */
    fun dispose() {
        letGo()
    }

    /**
     * Walks to [pos] safely, the last steps by steering once vanilla navigation has used up its
     * waypoints; stands once there.
     */
    fun walkTo(target: LivingEntity, pos: Vec3) {
        when {
            FiringPostArrival.reached(npc.position(), pos) -> npc.navigation.stop()
            navigator.safePosition(npc, target, pos) && FiringPostArrival.steer(npc, pos, 1.0) -> Unit
            else -> navigator.navigate(npc, target, pos)
        }
    }

    /** Stops a path that has turned unsafe towards [target], as every move's first step. */
    fun stopUnsafePath(target: LivingEntity): Boolean {
        val path = npc.navigation.path
        if (path != null && !path.isDone && !navigator.safePath(npc, target, path)) {
            npc.navigation.stop()
            return true
        }
        return false
    }
}

/**
 * The facts of one man's engage, observed every tick: what his gun saw ([FireObservation]) and the
 * distances [CombatGeometry] measures, with the bands that keep an edge from flipping his move.
 */
internal object EngageFacts {
    fun observe(facts: FactSnapshot, npc: NpcEntity, state: EngageState, now: Long): FactSnapshot {
        val target = npc.target?.takeIf { it.isAlive }
        val seen = npc.fireObservation
        val squad = npc.currentSquad()
        val holding = CombatPosition.holdsPosition(squad?.order)
        var result = facts.observe(TacticalFacts.IN_CONTACT, target != null && !SquadTransit.marching(npc))
            .observe(TacticalFacts.SIDEARM, npc.usingSidearm)
            .observe(TacticalFacts.HOLDING_ORDER, holding)
            .observe(TacticalFacts.CLEAR_SHOT, now - seen.clearShotAt < Engage.CLEAR_SHOT_TICKS)
            .observe(TacticalFacts.LANE_BLOCKED_ALLY, seen.allyBlockedChecks >= Engage.LANE_DWELL_CHECKS)
            .observe(TacticalFacts.LANE_BLOCKED_HULL, seen.hullBlockedChecks >= Engage.LANE_DWELL_CHECKS)
            .observe(TacticalFacts.OPENING_DUE, openingDue(seen, target?.uuid, now))
            .observe(TacticalFacts.RESUPPLYING, npc.lowAmmoFallback() != null || npc.retreatPoint() != null)
            .observe(TacticalFacts.ENGAGE_UNITS, state.units)
        val distance = target?.let { CombatGeometry.flat(npc.position(), it.position()) }
        val keepAway = CombatGeometry.keepAway(npc)
        state.keepAway = keepAway > 0.0 && band(state.keepAway, distance, keepAway, within = true)
        result = result.observe(TacticalFacts.KEEP_AWAY, state.keepAway)
        state.inReach = band(state.inReach, distance, CombatGeometry.stoppingDistance(npc), within = true)
        result = result.observe(TacticalFacts.IN_REACH, state.inReach)
        val home = CombatGeometry.defendHome(npc)
        state.outside = home != null && band(state.outside, CombatGeometry.flat(npc.position(), home), CombatGeometry.leash(npc) - Engage.BAND, within = false)
        result = result.observe(TacticalFacts.OUTSIDE_AREA, state.outside)
        val mayAdvance = holding && target != null && !state.advanceDone && !npc.holds(NpcActivity.DUG_IN) &&
            CombatPosition.mayAdvance(squad?.order, npc.npcClass, npc.position(), target.position())
        return result.observe(TacticalFacts.MAY_ADVANCE, mayAdvance)
    }

    /**
     * The opening burst at [target] is still to fire. The plan reads only this flag, not the shot
     * count, so it repairs once when the burst or the window ends, not on every round.
     */
    fun openingDue(seen: FireObservation, target: UUID?, now: Long): Boolean {
        val first = seen.firstShootableAt?.takeIf { seen.target == target } ?: return false
        return now - first < Engage.OPENING_WINDOW && seen.shots < if (seen.automatic) Engage.OPENING_ROUNDS_AUTOMATIC else 1
    }

    /**
     * A distance fact with a band: [within] the fact holds at [limit] or nearer and lets go only
     * past [limit] + [Engage.BAND]; otherwise it holds past [limit] + [Engage.BAND] and lets go at
     * [limit] or nearer. Between the two it keeps [previous].
     */
    fun band(previous: Boolean, distance: Double?, limit: Double, within: Boolean): Boolean {
        distance ?: return false
        val near = distance <= limit
        val far = distance > limit + Engage.BAND
        return when {
            near -> within
            far -> !within
            else -> previous
        }
    }
}

/**
 * The executor of one [EngageUnit]: the move the plan chose, run on the man's [EngageState].
 * Aim and fire stay with his gun behaviour; this only moves him.
 */
internal abstract class EngageAction(protected val npc: NpcEntity, protected val state: EngageState, private val sidearm: Boolean) : ActionExecution {
    private val finish = Finish()

    /** One tick of the move against [target]. */
    protected abstract fun step(target: LivingEntity)

    /** The move's unit is done: the plan looks again on the next tick. */
    protected fun done(kind: String) {
        state.finished()
        finish.now(kind, npc.level().gameTime)
        if (DebugFlags.on(LogGroup.ORDER)) DebugFlags.log(LogGroup.ORDER, "[htn] actor={} event=engage_unit_done move={} reason={}", npc.uuid, javaClass.simpleName, kind)
    }

    /** His own tick count, the clock every unit's timing runs on. */
    protected val clock get() = npc.tickCount

    override fun start(now: Long): ActionUpdate {
        state.begin(sidearm)
        return tick(now)
    }

    override fun tick(now: Long): ActionUpdate {
        finish.ready()?.let { return it }
        val target = npc.target?.takeIf { it.isAlive } ?: return ActionUpdate.Running
        step(target)
        return ActionUpdate.Running
    }

    override fun suspend(now: Long, reason: String) = Unit

    override fun resume(now: Long) = Unit

    /** Another move took over: the spot he held is not his any more. */
    override fun cancel(now: Long, reason: String) = state.letGo()

    companion object {
        /** The action for [unit]. */
        fun of(unit: EngageUnit, npc: NpcEntity, state: EngageState): EngageAction =
            when (unit.move) {
                EngageMove.KEEP_AWAY -> KeepAway(npc, state, unit.sidearm)
                EngageMove.RETURN_TO_AREA -> ReturnToArea(npc, state, unit.sidearm)
                EngageMove.SIDESTEP -> Sidestep(npc, state, unit.sidearm)
                EngageMove.RESEARCH_SPOT -> HoldSpot(npc, state, unit.sidearm, research = true)
                EngageMove.OPEN_FIRE -> OpenFire(npc, state, unit.sidearm)
                EngageMove.ADVANCE_FROM_POST -> AdvanceFromPost(npc, state, unit.sidearm)
                EngageMove.CLOSE_IN -> CloseIn(npc, state, unit.sidearm)
                EngageMove.JOIN_FIRE -> JoinFire(npc, state, unit.sidearm)
                EngageMove.HOLD_SPOT -> HoldSpot(npc, state, unit.sidearm, research = false)
            }

        /**
         * A firing pause, whether between bounds or holding a chosen spot, lasts long enough to
         * actually aim and get shots off, not just barely stop and go again: 3-5 s, staggered so a
         * squad doesn't all reposition in lockstep.
         */
        const val HOLD_MIN_TICKS = 60
        const val HOLD_JITTER_TICKS = 40

        /** One rush of a bound. */
        const val BOUND_MOVE_TICKS = 25
    }
}

/**
 * Holds a nearby spot that keeps a clear shot on the target while hiding as much of him as the
 * terrain allows, and fires from it. The spot is looked at again every 3-5 s ([EngageAction.HOLD_MIN_TICKS]
 * and jitter): he really holds a chosen spot and fires, no endless micro-shuffling. Each look ends
 * the unit, so the plan sees the fight again between holds. With [research] the look comes at
 * once: a hull pulled into his line of fire, which no sidestep clears.
 */
private class HoldSpot(npc: NpcEntity, state: EngageState, sidearm: Boolean, private val research: Boolean) : EngageAction(npc, state, sidearm) {
    private var looks = 0

    override fun start(now: Long): ActionUpdate {
        if (research) state.nextCheck = 0
        return super.start(now)
    }

    override fun step(target: LivingEntity) {
        val level = npc.level() as? ServerLevel ?: return
        if (!look(level, target)) return
        looks++
        if (research || looks > 1) done(if (research) "spot_searched" else "held")
    }

    /** One tick of the hold; true when it looked at the spot again and settled on one. */
    private fun look(level: ServerLevel, target: LivingEntity): Boolean {
        // A grenade has come down by the spot being held: pick another now rather than walk
        // into it and get chased back out by GrenadeEvadeBehaviour.
        if (state.spot?.let { GrenadeHazard.threatens(level, it) } == true) state.nextCheck = 0
        // A useful firing post is kept in every order. An enemy moving farther away is no reason
        // to abandon a clear shot in favour of slightly better concealment.
        val held = state.spot
        if (held != null && clock >= state.nextCheck && stillGood(level, target, held)) {
            state.nextCheck = clock + HOLD_MIN_TICKS + npc.random.nextInt(HOLD_JITTER_TICKS)
            return true
        }
        val pos = state.spot
        if (pos != null && clock < state.nextCheck) {
            state.walkTo(target, pos)
            return false
        }
        val shot = FiringSpotSearch.Shot(level, npc, target, state.bounds())
        val found = state.search.find(shot)
        if (found == null && state.search.deferred) {
            state.nextCheck = clock + DEFERRED_RETRY_TICKS
            return false
        }
        state.nextCheck = clock + HOLD_MIN_TICKS + npc.random.nextInt(HOLD_JITTER_TICKS)
        val chosen = found ?: npc.position()
        state.hold(chosen)
        state.search.mark(level, chosen)
        if (DebugFlags.on(LogGroup.ORDER)) {
            DebugFlags.log(
                LogGroup.ORDER,
                "[tactics] {} event=local_position_selected position={} enemyDistance={} concealed={}",
                npc.uuid,
                chosen,
                CombatGeometry.flat(chosen, target.position()),
                state.search.concealmentAt(shot, chosen),
            )
        }
        if (!FiringPostArrival.reached(npc.position(), chosen)) state.navigator.navigate(npc, target, chosen) else npc.navigation.stop()
        return true
    }

    /** Whether the spot he holds still serves: he stands on it, out of any blast, still a shot, not crowded, his lane clear. */
    private fun stillGood(level: ServerLevel, target: LivingEntity, held: Vec3): Boolean {
        if (!FiringPostArrival.reached(npc.position(), held) || GrenadeHazard.threatens(level, held) || !TickBudget.hasRaycasts(level)) return false
        // With the hulls on the line: a spot a vehicle has since pulled in front of is no longer
        // "still has a shot", and keeping it had defenders standing behind a hull, never firing.
        val score = state.search.concealmentAt(FiringSpotSearch.Shot(level, npc, target, state.bounds()), held)
        if (score == null || score <= 0.0) return false
        return !FiringSpots.crowded(held, FiringSpots.nearbyWithBodies(level, npc, FiringSpotSearch.STUCK_SEARCH_RADIUS)) &&
            FriendlyFireGuard.assess(npc, target.eyePosition, npc.spread, target.position(), 0.0).lineClear
    }

    private companion object {
        /** A search the tick's budget cut short resumes this soon. */
        const val DEFERRED_RETRY_TICKS = 2
    }
}

/**
 * A fresh target's opening burst: he stands where he is, his spot claimed, until the burst is
 * fired or its window is over ([Engage.unitOver]).
 */
private class OpenFire(npc: NpcEntity, state: EngageState, sidearm: Boolean) : EngageAction(npc, state, sidearm) {
    override fun step(target: LivingEntity) {
        npc.navigation.stop()
        val here = npc.position()
        if (state.spot?.let { CombatGeometry.flat(it, here) <= ARRIVE_DISTANCE } != true) {
            state.hold(here)
            state.nextCheck = clock + HOLD_MIN_TICKS
        }
    }

    private companion object {
        /** Standing within this of his claimed spot, he is on it. */
        const val ARRIVE_DISTANCE = 1.0
    }
}

/**
 * One bound towards the enemy: a rush of [EngageAction.BOUND_MOVE_TICKS] to his slot in the
 * firing area, then a pause of 3-5 s for covering fire. The rush is jittered too: a whole squad
 * typically spots an enemy the same tick, and without it every member's pause landed at the same
 * moment, the squad visibly freezing in sync before even trading shots.
 */
private class CloseIn(npc: NpcEntity, state: EngageState, sidearm: Boolean) : EngageAction(npc, state, sidearm) {
    private var rushing: Boolean? = null
    private var turnAt = 0

    override fun step(target: LivingEntity) {
        state.stopUnsafePath(target)
        when (rushing) {
            null -> {
                state.letGo()
                rushing = true
                turnAt = clock + BOUND_MOVE_TICKS + npc.random.nextInt(HOLD_JITTER_TICKS)
                CombatGeometry.slotStep(npc, target)?.let { state.navigator.navigate(npc, target, it) }
            }

            true -> if (clock >= turnAt) {
                rushing = false
                turnAt = clock + HOLD_MIN_TICKS + npc.random.nextInt(HOLD_JITTER_TICKS)
                npc.navigation.stop()
            }

            false -> if (clock >= turnAt) done("bound")
        }
    }
}

/**
 * He has no shot from here: one leg on towards his slot in the squad's firing area, without the
 * bound's pause, since a pause is for covering fire he cannot give. Done once the leg ends.
 */
private class JoinFire(npc: NpcEntity, state: EngageState, sidearm: Boolean) : EngageAction(npc, state, sidearm) {
    private var walking = false

    override fun step(target: LivingEntity) {
        state.stopUnsafePath(target)
        if (!npc.navigation.isDone) return
        if (walking) {
            done("leg_over")
            return
        }
        state.letGo()
        walking = true
        val leg = CombatGeometry.slotStep(npc, target)
        if (leg == null || !state.navigator.navigate(npc, target, leg) || npc.navigation.isDone) done("no_leg")
    }
}

/**
 * A defender outside his area walks back into it. The order still owns the destination: an enemy
 * met remains observable and shootable on the way, and arriving is no firing prerequisite. The
 * plan ends the unit once he is back inside ([EngageMove.endsOn]); this only walks.
 */
private class ReturnToArea(npc: NpcEntity, state: EngageState, sidearm: Boolean) : EngageAction(npc, state, sidearm) {
    override fun step(target: LivingEntity) {
        val home = CombatGeometry.defendHome(npc) ?: return done("no_area")
        state.letGo()
        npc.navigation.moveTo(home.x, home.y, home.z, 1.0)
    }
}

/**
 * Holding a post, one bounded approach towards a far enemy: a single endpoint for this approach,
 * since following every movement of a distant target round the boundary made the post wander and
 * kept replacing its path. Done when he got there or the approach no longer holds; he holds from
 * there, and makes no second approach from this post.
 */
private class AdvanceFromPost(npc: NpcEntity, state: EngageState, sidearm: Boolean) : EngageAction(npc, state, sidearm) {
    private var advancing = false

    override fun step(target: LivingEntity) {
        if (!advance(target)) {
            if (advancing) {
                npc.navigation.stop()
                state.hold(npc.position())
                state.nextCheck = clock + HOLD_MIN_TICKS
            }
            state.advanceDone = true
            done("advance_over")
        }
    }

    private fun advance(target: LivingEntity): Boolean {
        val spot = state.advanceGoal ?: run {
            val goal = CombatPosition.advancePoint(npc.npcClass, state.origin ?: npc.position(), target.position()) ?: return false
            val level = npc.level() as? ServerLevel ?: return false
            Terrain.standableOrNull(level, goal.x, npc.y + GROUND_PROBE_RISE, goal.z, GROUND_PROBE_DEPTH) ?: return false
        }
        if (!state.bounds().permits(spot)) return false
        if (FiringPostArrival.reached(npc.position(), spot) || (npc.navigation.isDone && CombatGeometry.flat(npc.position(), spot) <= SETTLE_DISTANCE)) return false
        // If the enemy passed behind him, going on to the old endpoint is no longer an approach.
        if (spot.distanceToSqr(target.position()) >= npc.distanceToSqr(target)) return false
        state.advanceGoal = spot
        state.hold(spot)
        state.nextCheck = clock + HOLD_MIN_TICKS
        advancing = true
        return state.navigator.navigate(npc, target, spot)
    }

    private companion object {
        /** A finished path that stopped this close to the spot counts as having got there. */
        const val SETTLE_DISTANCE = 2.5
        const val GROUND_PROBE_RISE = 4.0
        const val GROUND_PROBE_DEPTH = 12
    }
}

/**
 * A downed pilot backs off from an enemy nearer than [CombatGeometry.KEEP_AWAY_DISTANCE], one step
 * at a time, shooting as he goes. Done once the step ends.
 */
private class KeepAway(npc: NpcEntity, state: EngageState, sidearm: Boolean) : EngageAction(npc, state, sidearm) {
    private var steppedAt: Int? = null

    override fun step(target: LivingEntity) {
        state.letGo()
        val since = steppedAt
        if (since != null) {
            // A step not finished in this long is taken again from where he is now.
            if (npc.navigation.isDone || clock - since >= REPATH_TICKS) done("stepped_back")
            return
        }
        steppedAt = clock
        val away = DefaultRandomPos.getPosAway(npc, FALL_BACK_STEP, FALL_BACK_VERTICAL, target.position())
        if (away == null || CombatGeometry.flat(away, target.position()) <= CombatGeometry.flat(npc.position(), target.position()) ||
            !npc.navigation.moveTo(away.x, away.y, away.z, FALL_BACK_SPEED)
        ) {
            // A failed retreat must not leave an old assault path running toward the enemy.
            npc.navigation.stop()
            done("no_step")
        }
    }

    private companion object {
        const val FALL_BACK_STEP = 16
        const val FALL_BACK_VERTICAL = 7
        const val FALL_BACK_SPEED = 1.4
        const val REPATH_TICKS = 30
    }
}

/**
 * An ally stands in his line of fire: he steps aside, up to [MAX_ATTEMPTS] times 5 ticks apart,
 * then rests [BATCH_REST] ticks before trying again, kept across units so a replanned sidestep
 * does not skip the rest. Done when the lane is clear ([Engage.unitOver]) or the attempts are spent.
 */
private class Sidestep(npc: NpcEntity, state: EngageState, sidearm: Boolean) : EngageAction(npc, state, sidearm) {
    private var attempts = 0
    private var nextTry = 0

    override fun step(target: LivingEntity) {
        val pos = state.spot
        if (pos != null) state.walkTo(target, pos)
        if (clock < state.sidestepRestUntil || clock < nextTry) return
        if (pos != null && !npc.navigation.isDone && !FiringPostArrival.reached(npc.position(), pos)) return
        if (attempts >= MAX_ATTEMPTS) {
            state.sidestepRestUntil = clock + BATCH_REST
            done("attempts_spent")
            return
        }
        nextTry = clock + COOLDOWN
        attempts++
        val bounds = state.bounds()
        FriendlyFireGuard.sidestepAwayFromAllies(npc, target.eyePosition) { bounds.permits(it) }?.let {
            state.hold(it)
            state.nextCheck = clock + HOLD_MIN_TICKS
        }
    }

    private companion object {
        const val COOLDOWN = 5
        const val MAX_ATTEMPTS = 3
        const val BATCH_REST = 40
    }
}
