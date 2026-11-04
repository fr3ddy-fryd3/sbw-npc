package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.planning.*
import com.sbwnpc.squad.ai.tactics.*
import com.sbwnpc.squad.combat.*
import com.sbwnpc.squad.combat.tactics.*
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.CoverPeekSearch
import com.sbwnpc.squad.entity.ai.CoverSearch
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

/** One man's observed armour source and results returned by his plan's execution units. */
internal class ArmourState {
    val reservation = java.util.UUID.randomUUID()
    var running: ArmourStep? = null
    var runningOwner: Any? = null
    private var orderStamp: Int? = null
    var contact: ArmourContact? = null
    var source: Vec3 = Vec3.ZERO
    var eye: Vec3 = Vec3.ZERO
    private var seenAt = Long.MIN_VALUE
    private var exposedAt = Long.MIN_VALUE
    private var checkedAt = Long.MIN_VALUE
    private var failedAt = Long.MIN_VALUE
    var sortieLauncher = false
    private var hiddenNow = false
    var cover: BlockPos? = null
    var sortie: CoverPeekSearch.Result? = null
    var step: ArmourStep? = null
    var cycle = 0
    var coverFailed = false
    var escapeFailed = false
    var sheltered = false

    /** Facts contain stable decisions inputs; a contact timestamp is only an expiry clock. */
    fun observe(facts: FactSnapshot, npc: NpcEntity, view: TacticalSnapshot?): FactSnapshot {
        val now = facts.now
        val order = npc.currentSquad()?.orderStamp
        if (orderStamp != order) {
            clear(npc)
            orderStamp = order
        }
        if (checkedAt == Long.MIN_VALUE || now - checkedAt >= OBSERVE_TICKS) {
            checkedAt = now
            refresh(npc, view, now)
            hiddenNow = contact != null && hidden(npc, npc.position())
            sheltered = cover?.let { CoverState.on(npc, it) && hidden(npc, npc.position()) } == true
        }
        val loaded = AntiArmourKit.loaded(npc)
        if (failedAt != Long.MIN_VALUE && now - failedAt >= RETRY_TICKS) {
            coverFailed = false
            escapeFailed = false
            failedAt = Long.MIN_VALUE
        }
        var result = facts.observe(ArmourFacts.LOADED, loaded)
            .observe(ArmourFacts.SORTIE_READY, sortie != null).observe(ArmourFacts.SORTIE_LAUNCHER, sortieLauncher).observe(ArmourFacts.HIDDEN, hiddenNow)
            .observe(ArmourFacts.CYCLE, cycle).observe(ArmourFacts.COVER_FAILED, coverFailed)
            .observe(ArmourFacts.ESCAPE_FAILED, escapeFailed).observe(ArmourFacts.SHELTERED, sheltered)
        running?.let { result = result.observe(ArmourFacts.RUNNING, it) }
        step?.let { result = result.observe(ArmourFacts.STEP, it) }
        contact?.let { result = result.observe(ArmourFacts.CONTACT, it) }
        return result
    }

    private fun refresh(npc: NpcEntity, view: TacticalSnapshot?, now: Long) {
        val level = npc.level() as ServerLevel
        val current = contact?.let { level.getEntity(it.id) as? LivingEntity }
        if (current != null && !valid(npc, current)) clear(npc)
        val known = view?.contacts.orEmpty().filter { it.armoured && !it.airborne && (level.getEntity(it.id) as? LivingEntity)?.let { live -> valid(npc, live) } == true && SquadKnowledge.known(it.seenAt, it.firstSeenAt, now, npc.uuid in it.seenBy) }
        val observed = known.filter { now - it.seenAt < RECENT_TICKS }
            .sortedBy { if (it.id == contact?.id) -1.0 else it.position.distanceToSqr(npc.position()) }
            .firstOrNull { DetectionSightline.visible(level, it.eye, npc.eyePosition, npc) }
        if (observed != null) {
            if (observed.id != contact?.id) {
                clear(npc)
                source = observed.position
                contact = ArmourContact(observed.id, FireSector.of(source.x, source.z))
            }
            if (observed.seenAt >= seenAt) {
                seenAt = observed.seenAt
                source = observed.position
                eye = observed.eye
            }
            exposedAt = maxOf(exposedAt, observed.seenAt)
        }
        if (contact != null && now - exposedAt >= ArmourResponse.MEMORY_TICKS) clear(npc)
    }

    private fun valid(npc: NpcEntity, target: LivingEntity) = target.isAlive && npc.isEnemy(target) && VehicleTargeting.isGroundArmour(target)

    /** Vehicle hulls are not allowed to hide their own gun's source ray. */
    fun hidden(npc: NpcEntity, feet: Vec3): Boolean = !DetectionSightline.visible(npc.level() as ServerLevel, eye, feet.add(0.0, npc.eyeHeight.toDouble(), 0.0), npc)

    fun failed(now: Long, escape: Boolean) {
        if (escape) escapeFailed = true else coverFailed = true
        failedAt = now
    }

    fun complete(completed: ArmourStep) {
        running = null
        step = completed
        cycle++
    }

    private fun clear(npc: NpcEntity) {
        FiringSpots.release(npc.uuid, reservation)
        running = null
        runningOwner = null
        contact = null
        cover = null
        sortie = null
        step = null
        coverFailed = false
        escapeFailed = false
        sheltered = false
        seenAt = Long.MIN_VALUE
        exposedAt = Long.MIN_VALUE
        failedAt = Long.MIN_VALUE
    }

    private companion object {
        const val OBSERVE_TICKS = 10L
        const val RECENT_TICKS = 40L
        const val RETRY_TICKS = 200L
    }
}

/** A physical unit of the HTN's armour manoeuvre: search/walk, aim/fire, return or bounded hold. */
internal class NativeArmour(private val unit: ArmourUnit, npc: NpcEntity, private val state: ArmourState) : CoverAction(npc) {
    private var completed = false
    private var target: Vec3? = null
    private var started = 0
    private var lastShot = -1
    private var peek: CoverPeekSearch? = null
    private val origin = npc.position()
    private val walking = ArmourWalk(npc, state, ::done, ::timedOut)

    override fun start(now: Long): ActionUpdate {
        state.runningOwner = this
        state.running = unit.step
        started = npc.tickCount
        lastShot = npc.lastShotTick
        if (unit.step == ArmourStep.SORTIE) {
            state.sortieLauncher = unit.launcher
            state.sortie = null
        }
        restoreClaims()
        return tick(now)
    }

    private fun restoreClaims() {
        claim.take(NpcActivity.COVER)
        if (unit.step == ArmourStep.FIRE || unit.step == ArmourStep.HOLD) {
            claim.drop(NpcActivity.COVER)
            claim.take(NpcActivity.PEEK)
        }
    }

    override fun resume(now: Long) {
        state.runningOwner = this
        state.running = unit.step
        state.cover?.let { FiringSpots.claim(npc.uuid, Vec3.atBottomCenterOf(it), state.reservation) }
        restoreClaims()
    }

    override fun tick(now: Long): ActionUpdate {
        finished()?.let { return it }
        if (state.contact != unit.contact) return ActionUpdate.Failed("armour_contact_changed")
        if (npc.tickCount - started >= UNIT_TICKS) return timedOut(now)
        return when (unit.step) {
            ArmourStep.COVER -> walking.cover(now)
            ArmourStep.SORTIE -> sortie(now)
            ArmourStep.FIRE -> fire(now)
            ArmourStep.RETURN -> walking.returning(now)
            ArmourStep.ESCAPE -> walking.escape(now)
            ArmourStep.HOLD -> if (npc.tickCount - started >= HOLD_TICKS) done(now) else ActionUpdate.Running
        }
    }

    private fun sortie(now: Long): ActionUpdate {
        target?.let { return walking.walk(it, now, requireHidden = !unit.launcher) }
        val enemy = targetEnemy() ?: return done(now)
        if (unit.launcher) {
            npc.target = enemy.first
            AntiArmourKit.wield(npc, launcher = true)
        }
        val base = state.cover ?: BlockPos.containing(origin)
        val search = peek ?: CoverPeekSearch(base, enemy.second, enemy.first.uuid, blind = false, backwards = true) { point ->
            ArmourResponse.lateralOrBack(origin.point(), state.source.point(), point.point()) && (unit.launcher || state.hidden(npc, point))
        }.also { peek = it }
        val found = search.find(npc, npc.level() as ServerLevel, enemy.second, enemy.first)
        if (found == null) return if (search.exhausted) done(now) else ActionUpdate.Running
        state.sortie = found
        target = found.point
        npc.navigation.moveTo(found.path, 1.0)
        return ActionUpdate.Running
    }

    private fun targetEnemy(): Pair<LivingEntity, Vec3>? {
        val level = npc.level() as ServerLevel
        val enemy = if (unit.launcher) level.getEntity(unit.contact.id) as? LivingEntity else npc.target
        enemy ?: return null
        if (!enemy.isAlive || !npc.isEnemy(enemy)) return null
        if (!unit.launcher && VehicleTargeting.isGroundArmour(enemy)) return null
        if (DetectionSightline.canSee(npc, enemy)) {
            npc.rememberVisible(enemy)
            return enemy to enemy.eyePosition
        }
        // A launcher searches from the last observed vehicle source, never a hidden live seat.
        if (unit.launcher) return enemy to state.eye
        return npc.rememberedEye(enemy)?.let { enemy to it }
    }

    private fun fire(now: Long): ActionUpdate {
        npc.navigation.stop()
        if (!unit.launcher && !state.hidden(npc, npc.position())) {
            claim.take(NpcActivity.COVER)
            claim.drop(NpcActivity.PEEK)
            return done(now)
        }
        val enemy = targetEnemy() ?: return done(now)
        npc.target = enemy.first
        if (unit.launcher) AntiArmourKit.wield(npc, launcher = true)
        val firedLauncher = unit.launcher && (npc.lastShotTick != lastShot || !AntiArmourKit.loaded(npc))
        if (firedLauncher || npc.tickCount - started >= FIRE_TICKS) return done(now)
        return ActionUpdate.Running
    }

    private fun timedOut(now: Long): ActionUpdate {
        when (unit.step) {
            ArmourStep.COVER -> state.failed(now, escape = false)

            ArmourStep.ESCAPE -> state.failed(now, escape = true)

            ArmourStep.RETURN -> {
                state.cover = null
                state.sheltered = false
            }

            ArmourStep.SORTIE -> state.sortie = null

            else -> Unit
        }
        return done(now)
    }

    private fun done(now: Long): ActionUpdate {
        npc.navigation.stop()
        completed = true
        state.complete(unit.step)
        return finish.now("armour_${unit.step.name.lowercase()}", now)
    }

    override fun release(paused: Boolean) {
        npc.navigation.stop()
        if (state.runningOwner === this) {
            state.running = null
            state.runningOwner = null
        }
        if (!completed || paused) FiringSpots.release(npc.uuid, state.reservation)
    }

    private companion object {
        const val UNIT_TICKS = 200
        const val HOLD_TICKS = 40
        const val FIRE_TICKS = 50
    }
}

/** Cover, return and retreat path searches selected by the armour HTN, sharing its path budget. */
private class ArmourWalk(private val npc: NpcEntity, private val state: ArmourState, private val done: (Long) -> ActionUpdate, private val timedOut: (Long) -> ActionUpdate) {
    private var target: Vec3? = null
    private val failed = HashSet<BlockPos>()
    private var escapeCursor = 0
    private var bestEscape: Pair<Vec3, net.minecraft.world.level.pathfinder.Path>? = null
    private val origin = npc.position()
    fun cover(now: Long): ActionUpdate {
        val level = npc.level() as ServerLevel
        target?.let { return walk(it, now, covering = true, requireHidden = true) }
        if (!canSearch()) return ActionUpdate.Running
        val found = (TickBudget.attempt(npc, TickBudget.Work.COVER) { CoverSearch.find(npc, level, state.source, failed) { state.hidden(npc, it) } } ?: return ActionUpdate.Running).value
        if (found == null || failed.size >= MAX_FAILED) {
            state.failed(now, escape = false)
            return done(now)
        }
        if (!TacticalBudget.path(npc)) return ActionUpdate.Running
        val point = Vec3.atBottomCenterOf(found)
        val path = TacticalPositions.pathToPosition(npc, point)
        if (!state.hidden(npc, point) || !TacticalPositions.reaches(path, point)) {
            failed += found
            return ActionUpdate.Running
        }
        target = point
        FiringSpots.claim(npc.uuid, point, state.reservation)
        npc.navigation.moveTo(path, 1.0)
        return ActionUpdate.Running
    }

    fun returning(now: Long): ActionUpdate {
        val home = state.cover ?: return done(now)
        val point = Vec3.atBottomCenterOf(home)
        if (CoverState.on(npc, home) && state.hidden(npc, npc.position())) return done(now)
        if (target == null) {
            if (!TacticalBudget.path(npc)) return ActionUpdate.Running
            val path = TacticalPositions.pathToPosition(npc, point)
            if (!TacticalPositions.reaches(path, point)) {
                state.cover = null
                state.sheltered = false
                return done(now)
            }
            target = point
            npc.navigation.moveTo(path, 1.0)
        }
        return walk(point, now, requireHidden = true)
    }

    /** A bounded set of away/side-away destinations: prefer a broken line, else report a real retreat. */
    fun escape(now: Long): ActionUpdate {
        target?.let { return walk(it, now, covering = true) }
        if (!canSearch()) return ActionUpdate.Running
        if (escapeCursor >= ESCAPE_PROBES.size) {
            val best = bestEscape
            if (best == null) {
                state.failed(now, escape = true)
                return done(now)
            }
            target = best.first
            npc.navigation.moveTo(best.second, 1.0)
            return ActionUpdate.Running
        }
        val away = origin.subtract(state.source).multiply(1.0, 0.0, 1.0).normalize()
        val side = Vec3(-away.z, 0.0, away.x)
        val offset = ESCAPE_PROBES[escapeCursor]
        val raw = origin.add(away.scale(offset.first)).add(side.scale(offset.second))
        val level = npc.level() as ServerLevel
        val point = Terrain.feetAt(level, raw) { level.noCollision(npc, npc.getDimensions(npc.pose).makeBoundingBox(it)) }
        if (point == null || GrenadeHazard.threatens(level, point) || !ArmourResponse.lateralOrBack(origin.point(), state.source.point(), point.point())) {
            escapeCursor++
            return ActionUpdate.Running
        }
        if (!TacticalBudget.path(npc)) return ActionUpdate.Running
        escapeCursor++
        val path = TacticalPositions.pathToPosition(npc, point)
        if (TacticalPositions.reaches(path, point)) {
            bestEscape = point to path!!
            if (state.hidden(npc, point)) escapeCursor = ESCAPE_PROBES.size
        }
        return ActionUpdate.Running
    }

    fun walk(point: Vec3, now: Long, covering: Boolean = false, requireHidden: Boolean = false): ActionUpdate {
        if (CoverState.on(npc, BlockPos.containing(point))) {
            if (requireHidden && !state.hidden(npc, npc.position())) {
                if (!state.hidden(npc, point)) return timedOut(now)
                npc.moveControl.setWantedPosition(point.x, point.y, point.z, 1.0)
                return ActionUpdate.Running
            }
            if (covering) {
                state.sheltered = state.hidden(npc, npc.position())
                state.cover = BlockPos.containing(point).takeIf { state.sheltered }
            }
            return done(now)
        }
        if (npc.navigation.isDone) {
            if (npc.position().distanceToSqr(point) < LAST_STEP_SQR) {
                npc.moveControl.setWantedPosition(point.x, point.y, point.z, 1.0)
            } else {
                if (!canSearch() || !TacticalBudget.path(npc)) return ActionUpdate.Running
                val path = TacticalPositions.pathToPosition(npc, point)
                if (!TacticalPositions.reaches(path, point)) return timedOut(now)
                npc.navigation.moveTo(path, 1.0)
            }
        }
        return ActionUpdate.Running
    }

    private fun canSearch() = (npc.navigation as? VehicleAwareNavigation)?.canPlan != false
    private companion object {
        const val MAX_FAILED = 4
        const val LAST_STEP_SQR = 6.25
        val ESCAPE_PROBES = listOf(6.0 to 0.0, 12.0 to -6.0, 12.0 to 6.0, 18.0 to 0.0)
    }
}
