package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.planning.ActionEvidence
import com.sbwnpc.squad.ai.planning.ActionExecution
import com.sbwnpc.squad.ai.planning.ActionUpdate
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FiringPostArrival
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.GrenadeHazard
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.SquadAreas
import com.sbwnpc.squad.combat.TickBudget
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.AmmoRetreat
import com.sbwnpc.squad.entity.ai.CoverSearch
import com.sbwnpc.squad.entity.ai.MandatoryRule
import com.sbwnpc.squad.entity.ai.MandatoryRuleOwner
import com.sbwnpc.squad.entity.ai.MandatoryRules
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.util.DefaultRandomPos
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3

/**
 * An empty man with no trip in a fight (#189; OutOfAmmoBehaviour, moved under the plan as it was):
 * ordered back, he runs to his rally slot; else cover from his enemy, or a bounded withdrawal away
 * from him ([AmmoRetreat]), then he holds. A cover action of his own (COVER, PEEK) has him first, and
 * a higher mandatory rule pauses him. He keeps [NpcActivity.RESUPPLY] while he runs, as before.
 */
internal class AmmoWithdraw(private val level: ServerLevel, private val npc: NpcEntity) :
    ActionExecution,
    PlanExecutor,
    MandatoryRuleOwner {
    override val rule = MandatoryRule.OUT_OF_AMMO

    private val feet = Footing()
    private var nextStep = 0L
    private var lastMode: String? = null

    override fun start(now: Long): ActionUpdate {
        FiringSpots.release(npc.uuid)
        DebugFlags.log(LogGroup.AMMO, "{} ({}) out of ammo, running from {}", npc.uuid, npc.npcClass, npc.target?.name?.string)
        begin(now)
        return tick(now)
    }

    override fun tick(now: Long): ActionUpdate {
        finished(now)?.let { return it }
        val target = npc.target ?: return ActionUpdate.Running
        begin(now)
        if (npc.holdsAny(NpcActivity.COVER, NpcActivity.PEEK) || !MandatoryRules.may(MandatoryRule.OUT_OF_AMMO, npc)) return ActionUpdate.Running
        npc.claim(NpcActivity.RESUPPLY, this)
        if (rally(now)) return ActionUpdate.Running
        if (feet.retreat.exhausted(npc.position(), now)) {
            trace(now, feet.hold(npc, "limit_reached"), npc.position())
            return ActionUpdate.Running
        }
        if (feet.underWay(npc)?.let { trace(now, it, npc.position()) } != null || feet.moving(npc)) return ActionUpdate.Running
        if (now < nextStep) return ActionUpdate.Running
        nextStep = now + REPATH_TICKS
        feet.step(npc, level, target)?.let { (mode, point) -> trace(now, mode, point) } ?: run { nextStep = now + 2 }
        return ActionUpdate.Running
    }

    // Done once his enemy is gone or his gun has rounds again.
    private fun finished(now: Long): ActionUpdate? {
        val target = npc.target?.takeIf { it.isAlive }
        if (target != null && Ports.guns.inHand(npc)?.hasAmmo() == false) return null
        stop()
        return ActionUpdate.Completed(listOf(ActionEvidence(if (target == null) "no_enemy" else "rounds_again", now, "")))
    }

    // Ordered back with nothing to fire he gives no cover to his squad's bounds (#253): straight to his slot.
    private fun rally(now: Long): Boolean {
        val rally = npc.retreatPoint() ?: return false
        val point = SquadAreas.rallySlot(npc, rally)
        trace(now, "ordered_retreat", point)
        if (now >= nextStep || npc.navigation.isDone) {
            nextStep = now + REPATH_TICKS
            npc.navigateTo(point, SPEED)
            feet.own(npc)
        }
        return true
    }

    private fun begin(now: Long) {
        val squad = npc.currentSquad()
        if (!feet.retreat.begin(npc.position(), now, squad?.id, squad?.orderStamp)) return
        feet.reset(npc)
        lastMode = null
        nextStep = 0
        trace(now, "started", npc.position())
    }

    private fun trace(now: Long, mode: String, point: Vec3) {
        if (lastMode == mode) return
        lastMode = mode
        DebugFlags.log(
            LogGroup.ORDER, "[tactics] {} squad={} tick={} event=ammo_retreat mode={} origin={} position={} destination={} target={}",
            npc.uuid, npc.currentSquad()?.id, now, mode, feet.retreat.origin, npc.position(), point, npc.target?.uuid,
        )
    }

    private fun stop() {
        feet.stopOwnedPath(npc)
        npc.release(NpcActivity.RESUPPLY, this)
    }

    override fun suspend(now: Long, reason: String) = stop()

    override fun resume(now: Long) = Unit

    override fun cancel(now: Long, reason: String) = stop()

    /** Where his withdrawal is going and the path it set, so only that path is ever stopped. */
    private class Footing {
        val retreat = AmmoRetreat()
        private val failedCover = HashSet<BlockPos>()
        private var destination: Vec3? = null
        private var hiding = false
        private var ownedPath: Path? = null

        fun own(npc: NpcEntity) {
            ownedPath = npc.navigation.path
        }

        fun reset(npc: NpcEntity) {
            stopOwnedPath(npc)
            destination = null
            failedCover.clear()
        }

        /** The mode reached at his destination, if he has just arrived in cover; null otherwise. */
        fun underWay(npc: NpcEntity): String? {
            val post = destination ?: return null
            val arrived = if (hiding) FiringPostArrival.reached(npc.position(), post) else npc.position().distanceTo(post) <= 2.0
            if (arrived && hiding) return hold(npc, "cover_reached")
            return null
        }

        /** Whether he is still on his way to his destination; a failed cover spot is marked off. */
        fun moving(npc: NpcEntity): Boolean {
            val post = destination ?: return false
            val arrived = if (hiding) FiringPostArrival.reached(npc.position(), post) else npc.position().distanceTo(post) <= 2.0
            if (!arrived && hiding && FiringPostArrival.steer(npc, post, SPEED)) return true
            if (!arrived && !npc.navigation.isDone) return true
            if (!arrived && hiding) failedCover += BlockPos.containing(post)
            destination = null
            return false
        }

        /** His next move from [target]: cover, else a step away; the mode and point, or null while the search waits its turn. */
        fun step(npc: NpcEntity, level: ServerLevel, target: LivingEntity): Pair<String, Vec3>? {
            val search = TickBudget.attempt(npc, TickBudget.Work.COVER) { CoverSearch.find(npc, level, target.position(), failedCover, CoverSearch.Filter(permitted = retreat::permits)) } ?: return null
            val cover = search.value
            if (cover != null && move(npc, Vec3.atBottomCenterOf(cover), hidden = true)) return "seek_cover" to Vec3.atBottomCenterOf(cover)
            if (cover != null) failedCover += cover
            val away = (1..AWAY_TRIES).mapNotNull { DefaultRandomPos.getPosAway(npc, STEP, VERTICAL, target.position()) }
                .filter { retreat.permits(it) && !GrenadeHazard.threatens(level, it) }
                .filter { it.subtract(target.position()).horizontalDistance() > npc.position().subtract(target.position()).horizontalDistance() }
                .maxByOrNull { it.subtract(target.position()).horizontalDistance() }
            if (away != null && move(npc, away, hidden = false)) return "withdraw" to away
            return hold(npc, "no_safe_step") to npc.position()
        }

        fun hold(npc: NpcEntity, reason: String): String {
            retreat.hold()
            stopOwnedPath(npc)
            return reason
        }

        fun stopOwnedPath(npc: NpcEntity) {
            if (ownedPath != null && npc.navigation.path === ownedPath) npc.navigation.stop()
            ownedPath = null
        }

        private fun move(npc: NpcEntity, point: Vec3, hidden: Boolean): Boolean {
            if (!npc.navigation.moveTo(point.x, point.y, point.z, SPEED)) return false
            ownedPath = npc.navigation.path
            destination = point
            hiding = hidden
            return true
        }
    }

    private companion object {
        const val STEP = 16
        const val VERTICAL = 7
        const val AWAY_TRIES = 6
        const val REPATH_TICKS = 30L
        const val SPEED = 1.4
    }
}
