package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.planning.ActionEvidence
import com.sbwnpc.squad.ai.planning.ActionExecution
import com.sbwnpc.squad.ai.planning.ActionUpdate
import com.sbwnpc.squad.ai.planning.FactSnapshot
import com.sbwnpc.squad.ai.tactics.GrenadeFacts
import com.sbwnpc.squad.ai.tactics.GrenadeReason
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.GrenadeThrower
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.Noticing
import com.sbwnpc.squad.combat.VehicleTargeting
import com.sbwnpc.squad.combat.Vision
import com.sbwnpc.squad.domain.port.GrenadeKind
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Who of a level's men may throw a grenade now and at what (#263): the conditions of the old
 * grenade behaviours, observed for the plan as [GrenadeFacts], and the throw it chose.
 *
 * Any fighter throws his reserve grenade at a bunch of enemies, at a target hidden long enough that
 * shooting at it goes nowhere, or at the fire pinning him; a grenadier throws by hand at the target
 * he sees, beside his launcher. A squad throws one grenade per [SQUAD_COOLDOWN_TICKS], so an ambush
 * draws an answer, not a volley; the grenadier's are not counted against it.
 *
 * Every man of a squad whose ration is free and who would throw is told so ([GrenadeFacts.DUE]);
 * the first whose plan throws spends the ration and the others' facts go back. A turn given to one
 * man ahead of his plan went to men under fire or in their hole, whose plans refuse a flush, and
 * kept the man who could throw waiting (diff review, #263).
 */
internal class GrenadeChoice(private val level: ServerLevel) {
    private val aims = GrenadeAims(level)

    private val hands = HashMap<UUID, Hand>()
    private val lastSquadThrow = HashMap<UUID, Long>()

    /** [npc]'s grenade facts at [now]: why he throws, if his squad lets him, and how many he has thrown. */
    fun observe(facts: FactSnapshot, npc: NpcEntity, now: Long): FactSnapshot {
        val hand = hands.getOrPut(npc.uuid) { Hand(npc) }
        val aim = aims.aim(npc, hand, now)
        hand.aim = aim
        val reason = aim?.takeIf { rationFree(npc, now) }?.reason ?: GrenadeReason.NONE
        return facts.observe(GrenadeFacts.DUE, reason).observe(GrenadeFacts.THROWN, hand.thrown)
    }

    /** The throw [npc]'s plan chose, carried out on the aim observed for it. */
    fun action(npc: NpcEntity): ActionExecution = Throw(npc)

    /** Keeps only the men [planned] still holds an agent for. */
    fun keep(planned: (UUID) -> Boolean) {
        hands.keys.removeIf { !planned(it) }
    }

    /** [squad] is gone. */
    fun forgetSquad(squad: UUID) {
        lastSquadThrow.remove(squad)
    }

    fun clear() {
        hands.clear()
        lastSquadThrow.clear()
    }

    /** Whether [npc]'s squad's ration lets him throw at [now]; a grenadier's own throws are not counted against it. */
    private fun rationFree(npc: NpcEntity, now: Long): Boolean {
        if (npc.npcClass == NpcClass.GRENADIER) return true
        val squad = npc.squadId ?: return true
        return lastSquadThrow[squad]?.let { now - it >= SQUAD_COOLDOWN_TICKS } != false
    }

    /** One throw: on the aim observed for it, a grenadier's led from where his target is now. */
    private inner class Throw(private val npc: NpcEntity) : ActionExecution {
        override fun start(now: Long): ActionUpdate {
            val hand = hands[npc.uuid]
            val aim = hand?.aim?.takeIf { now - it.tick <= AIM_TICKS } ?: return refused("no_aim")
            val target = npc.target?.takeIf { it.isAlive }
            val point = if (aim.reason == GrenadeReason.GRENADIER && target != null) target.boundingBox.center else aim.point
            val velocity = if (aim.reason == GrenadeReason.GRENADIER && target != null) target.deltaMovement else aim.velocity
            npc.navigation.stop()
            if (!GrenadeThrower.throwAt(npc, level, point, aim.kind, velocity)) return refused("throw_blocked")
            hand.thrown++
            hand.aim = null
            npc.grenadeReadyTick = npc.tickCount + if (aim.reason == GrenadeReason.GRENADIER) GRENADIER_COOLDOWN_TICKS + npc.random.nextInt(GRENADIER_COOLDOWN_JITTER) else SELF_COOLDOWN_TICKS
            if (aim.reason != GrenadeReason.GRENADIER) npc.squadId?.let { lastSquadThrow[it] = now }
            DebugFlags.log(LogGroup.GRENADE, "[htn] actor={} event=grenade_thrown reason={} kind={}", npc.uuid, aim.reason, aim.kind)
            return ActionUpdate.Completed(listOf(ActionEvidence("grenade_thrown", now, "${npc.uuid}:${hand.thrown}")))
        }

        /**
         * A throw that could not be made waits out [RETRY_TICKS] before he is told to throw again:
         * his facts then change, and his plan falls back to his duty or engage. Kept due, it
         * stood on a failed throw with nothing to replan it.
         */
        private fun refused(reason: String): ActionUpdate {
            hands[npc.uuid]?.aim = null
            npc.grenadeReadyTick = npc.tickCount + RETRY_TICKS
            DebugFlags.log(LogGroup.GRENADE, "[htn] actor={} event=grenade_refused reason={}", npc.uuid, reason)
            return ActionUpdate.Failed(reason)
        }

        override fun tick(now: Long): ActionUpdate = ActionUpdate.Failed("throw_over")

        override fun suspend(now: Long, reason: String) = Unit

        override fun resume(now: Long) = Unit

        override fun cancel(now: Long, reason: String) = Unit
    }

    private companion object {
        const val SELF_COOLDOWN_TICKS = 200
        const val GRENADIER_COOLDOWN_TICKS = 100
        const val GRENADIER_COOLDOWN_JITTER = 60

        /** One grenade per squad per this, 6 s. */
        const val SQUAD_COOLDOWN_TICKS = 120L

        /** After a throw that could not be made, 1 s. */
        const val RETRY_TICKS = 20

        /** An aim this old is still thrown at: planning the throw takes a tick or three. */
        const val AIM_TICKS = 10L
    }
}

/** What he would throw, at what, and why, as observed on [tick]. */
private class Aim(val point: Vec3, val kind: GrenadeKind, val velocity: Vec3, val reason: GrenadeReason, val tick: Long)

/** One man's clocks and his last aim. */
private class Hand(npc: NpcEntity) {
    var nextClusterScanTick = 0
    var thrown = 0
    var aim: Aim? = null
    val noticing = Noticing("grenade:${npc.uuid}", LogGroup.GRENADE)
}

/**
 * The throw conditions of the old grenade behaviours, carried over unchanged (#263): what one man
 * would throw at and why, before his squad's ration and turn.
 */
private class GrenadeAims(private val level: ServerLevel) {
    /** What [npc] would throw at [now], and why, or null while he may not or need not. */
    fun aim(npc: NpcEntity, hand: Hand, now: Long): Aim? {
        val ready = npc.isAlive && npc.hasGrenade
        if (!ready || npc.busyWithRole() || npc.tickCount < npc.grenadeReadyTick) return null
        return if (npc.npcClass == NpcClass.GRENADIER) grenadierAim(npc, now) else reserveAim(npc, hand, now)
    }

    /** The grenadier's hand throw at the target he sees, leading it. */
    private fun grenadierAim(npc: NpcEntity, now: Long): Aim? {
        if (npc.holdsAny(NpcActivity.COVER, NpcActivity.EVADE)) return null
        val target = npc.target?.takeIf { it.isAlive && !VehicleTargeting.isAircrew(it) } ?: return null
        if (npc.distanceTo(target) !in GrenadeThrower.MIN_RANGE..GrenadeThrower.MAX_RANGE) return null
        if (!DetectionSightline.canSee(npc, target) || !npc.sensing.hasLineOfSight(target)) return null
        val point = target.boundingBox.center
        val kind = GrenadeThrower.pick(npc, level, point, target.deltaMovement) ?: return null
        return Aim(point, kind, target.deltaMovement, GrenadeReason.GRENADIER, now)
    }

    /** Any other fighter's reserve grenade: a bunch, a hidden target, or the fire pinning him. */
    private fun reserveAim(npc: NpcEntity, hand: Hand, now: Long): Aim? {
        val (point, reason) = clusterPoint(npc, hand)?.let { it to GrenadeReason.BUNCH }
            ?: flushPoint(npc)?.let { it to GrenadeReason.FLUSH }
            ?: suppressionPoint(npc)?.let { it to GrenadeReason.SUPPRESSION }
            ?: return null
        if (!GrenadeThrower.inRange(npc.position(), point)) return null
        val kind = GrenadeThrower.pick(npc, level, point) ?: return null
        return Aim(point, kind, Vec3.ZERO, reason, now)
    }

    /**
     * A target that is known but not shootable. [NpcEntity.blockedSightSince] is stamped by the
     * gun every tick it has a target it cannot see, so this asks the shooter what it already knows
     * rather than raycasting again.
     */
    private fun flushPoint(npc: NpcEntity): Vec3? {
        // Out of sight up in a helicopter is not something a grenade flushes out.
        val target = npc.target?.takeIf { it.isAlive && !VehicleTargeting.isAircrew(it) } ?: return null
        val since = npc.blockedSightSince ?: return null
        if (npc.tickCount - since < BLOCKED_TICKS) return null
        return knownPosition(npc, target)
    }

    /** Pinned down, and the memory of where the fire is coming from is still live. */
    private fun suppressionPoint(npc: NpcEntity): Vec3? {
        if (!npc.isSuppressed()) return null
        // Pinned down from the air: the fire comes from somewhere a grenade can't go.
        val target = npc.target?.takeIf { !VehicleTargeting.isAircrew(it) } ?: return null
        val from = knownPosition(npc, target) ?: return null
        return from.takeIf { it.y - npc.y <= AIR_THREAT_HEIGHT }
    }

    /** Where his squad saw [target] lately, else where it is if he sees it himself. */
    private fun knownPosition(npc: NpcEntity, target: LivingEntity): Vec3? {
        val known = npc.currentSquad()?.tactics?.contacts?.get(target.uuid)
        if (known != null && level.gameTime - known.seenAt <= CONTACT_TICKS) return known.position
        return target.position().takeIf { DetectionSightline.canSee(npc, target) }
    }

    /**
     * The middle of [CLUSTER_SIZE] or more enemies within [CLUSTER_RADIUS] of one another, one of
     * them in sight and within throwing range: the biggest such bunch. Looked for once every
     * [CLUSTER_SCAN_TICKS]: it is an area query and some line-of-sight checks, per man.
     */
    private fun clusterPoint(npc: NpcEntity, hand: Hand): Vec3? {
        if (npc.tickCount < hand.nextClusterScanTick) return hand.aim?.takeIf { it.reason == GrenadeReason.BUNCH }?.point?.takeIf { stillBunched(npc, it) }
        hand.nextClusterScanTick = npc.tickCount + CLUSTER_SCAN_TICKS
        val reach = GrenadeThrower.MAX_RANGE + CLUSTER_RADIUS
        val enemies = level.getEntitiesOfClass(LivingEntity::class.java, npc.boundingBox.inflate(reach, BUNCH_HEIGHT, reach)) {
            it.isAlive && it !== npc && npc.isEnemy(it) && !VehicleTargeting.isAircrew(it) && noticed(npc, hand, it)
        }
        hand.noticing.endScan(level.gameTime)
        if (enemies.size < CLUSTER_SIZE) return null
        val radiusSqr = CLUSTER_RADIUS * CLUSTER_RADIUS
        return enemies.asSequence()
            .map { centre -> enemies.filter { it.distanceToSqr(centre) <= radiusSqr } }
            .filter { it.size >= CLUSTER_SIZE }
            .map { bunch -> bunch.size to Vec3(bunch.sumOf { it.x } / bunch.size, bunch.sumOf { it.y } / bunch.size, bunch.sumOf { it.z } / bunch.size) }
            .filter { (_, middle) -> GrenadeThrower.inRange(npc.position(), middle) }
            .maxByOrNull { it.first }?.second
    }

    /**
     * Whether a bunch found by the last scan is still at [point]: kept between scans so the fact
     * does not come and go with the scan clock, but never thrown where the bunch has died or moved.
     */
    private fun stillBunched(npc: NpcEntity, point: Vec3): Boolean =
        level.getEntitiesOfClass(LivingEntity::class.java, AABB.ofSize(point, CLUSTER_RADIUS * 2, BUNCH_HEIGHT, CLUSTER_RADIUS * 2)) {
            it.isAlive && npc.isEnemy(it) && it.distanceToSqr(point) <= CLUSTER_RADIUS * CLUSTER_RADIUS
        }.size >= CLUSTER_SIZE

    /** Seen, not merely known of: in view and noticed, by his own spotting time or by his squad's contact. */
    private fun noticed(npc: NpcEntity, hand: Hand, enemy: LivingEntity): Boolean {
        val sighting = DetectionSightline.sight(npc, enemy)
        if (sighting.visibility != DetectionSightline.Visibility.VISIBLE) return false
        val offAxis = Vision.offAxis(npc.position(), npc.yHeadRot, enemy.position())
        return hand.noticing.noticed(npc, level, enemy, offAxis, sighting.grassBlocks)
    }

    private companion object {
        /** How long a target has to stay out of sight before it counts as dug in rather than momentarily behind a tree. */
        const val BLOCKED_TICKS = 60

        /** Enemies that make a bunch worth a grenade, and how close together they have to be. */
        const val CLUSTER_SIZE = 3
        const val CLUSTER_RADIUS = 3.5
        const val CLUSTER_SCAN_TICKS = 20
        const val BUNCH_HEIGHT = 6.0

        /** A squad's sighting of a target is used this long, 5 s. */
        const val CONTACT_TICKS = 100L

        /** Fire from this far overhead is coming from the air. */
        const val AIR_THREAT_HEIGHT = 8.0
    }
}
