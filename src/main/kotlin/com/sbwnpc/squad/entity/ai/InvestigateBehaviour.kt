package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.DeathSites
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import com.sbwnpc.squad.init.ModMemories
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import net.tslat.smartbrainlib.util.BrainUtils

/**
 * In the Idle activity (`NpcEntity.getIdleTasks()`): response to an [com.sbwnpc.squad.combat.Alarm]
 * (heard nearby gunfire, or an ally went down without a resolvable killer) — NOT combat, just "go
 * look". If a real target turns up along the way, `ATTACK_TARGET` becomes non-null and the Fight
 * activity outranks Idle automatically, so this stops on its own.
 *
 * Reads [ModMemories.ALERT_POSITION] — expires on its own via TTL; `NpcEntity.clearAlert()` clears
 * it early on arrival.
 *
 * Beside [SquadOrderBehaviour] in Idle. `!entity.combatLockedByCover()` is checked explicitly: Idle
 * behaviours aren't mutually exclusive with `SeekCoverBehaviour`, which lives in Core.
 */
class InvestigateBehaviour : ExtendedBehaviour<NpcEntity>() {

    // ExtendedBehaviour defaults to a 60-tick runtime cap (confirmed via javap — see
    // SeekCoverBehaviour's doc comment for the full story); the walk to the alert position can
    // easily take longer than that, so without this it would get force-stopped and immediately
    // restarted mid-walk regardless of ALERT_POSITION still being present.
    init {
        noTimeout()
    }

    companion object {
        private const val ARRIVE_DISTANCE = 3.0
        /** A path that ended at least this much nearer than the last one began is worth another. */
        private const val PROGRESS = 2.0
        /** The spot moved farther than this: the way to the old one is no way to the new. */
        private const val SPOT_SHIFT = 16.0
        /** A moved spot is asked the way to at most this often: in a big fight the shots move it
         *  every second, and each ask is a long path search for every man sent to look. */
        private const val REPLAN_TICKS = 60
        /** The look at a death site is a raycast — twice a second is plenty. */
        private const val DEATH_SITE_CHECK_INTERVAL = 10

        private val MEMORIES: List<Pair<MemoryModuleType<*>, MemoryStatus>> =
            listOf(Pair.of(ModMemories.ALERT_POSITION.get(), MemoryStatus.VALUE_PRESENT))
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = MEMORIES

    // !entity.diggedIn too (not just !combatLockedByCover()): a dug-in mob deliberately clears
    // COVER_HOLD for its whole holding duration (see SeekCoverBehaviour.enterDugInHolding) so
    // GunAttackBehaviour can fire from the hole — but that means combatLockedByCover() alone no
    // longer implies "don't touch this mob's movement" once dug in. Without this, a dug-in mob whose
    // target happened to die/break LOS (leaving `target == null` for even a moment) would satisfy
    // this eligibility and get walked off toward some alert position, out of its own hole — reported
    // in-game as digging in not actually preventing the mob from running off once shot at again.
    // Not while falling back either: investigating gunfire means walking toward it.
    private fun eligible(entity: NpcEntity) =
        entity.target == null && !entity.combatLockedByCover() && !entity.diggedIn && !entity.busyWithRole() &&
            entity.retreatPoint() == null && !entity.resupplying && !com.sbwnpc.squad.combat.tactics.SquadTactics.hasTask(entity)

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean = eligible(entity)
    override fun shouldKeepRunning(entity: NpcEntity): Boolean = eligible(entity) && entity.isAlert()

    /** How far off the spot was when the path now walked was asked for. */
    private var askedFrom = Double.MAX_VALUE

    /** The spot the path being walked was asked for. */
    private var spot: Vec3? = null

    /** When that path was asked for. */
    private var askedAt = Int.MIN_VALUE / 2

    /** A moved spot is waiting out [REPLAN_TICKS] — said once in the log. */
    private var deferring = false

    override fun start(entity: NpcEntity) {
        BrainUtils.getMemory(entity, ModMemories.ALERT_POSITION.get())?.let { setOut(entity, it, "goes to look at") }
    }

    /** Asks for the way to [pos], or — heard in mid-jump — for it once he lands. */
    private fun setOut(entity: NpcEntity, pos: Vec3, what: String) {
        spot = pos
        askedAt = entity.tickCount
        deferring = false
        askedFrom = Math.sqrt(entity.distanceToSqr(pos))
        val going = entity.navigation.moveTo(pos.x, pos.y, pos.z, 1.0)
        // No path can be asked for till he lands — the first tick on the ground asks for it. The
        // path he was on goes nowhere near the noise.
        val inAir = !going && !canPlan(entity)
        if (inAir) {
            askedFrom = Double.MAX_VALUE
            entity.navigation.stop()
        }
        log(entity, "$what ${BlockPos.containing(pos)} (${Math.sqrt(entity.distanceToSqr(pos)).toInt()} blocks)" +
            when {
                going -> ", ${pathTrace(entity, pos)}"
                inAir -> ", in the air: asks once he lands"
                else -> ", no path"
            })
    }

    override fun tick(entity: NpcEntity) {
        val pos = BrainUtils.getMemory(entity, ModMemories.ALERT_POSITION.get()) ?: return
        // Newer shots move the spot, in a fight every second or so. Gone somewhere else, the path
        // to the old one leads the wrong way: a new one now. A few blocks over, the old one still
        // gets him there — but a stretch's progress is counted to the spot as it is now, or a
        // farther noise made the stretch look like no progress and he gave up on the spot.
        spot?.takeIf { pos.distanceToSqr(it) > 1.0 }?.let { old ->
            val end = entity.navigation.path?.takeUnless { entity.navigation.isDone }?.endNode?.asBlockPos()
            if (pos.distanceToSqr(old) > SPOT_SHIFT * SPOT_SHIFT &&
                (end == null || end.distToCenterSqr(pos) > SPOT_SHIFT * SPOT_SHIFT)
            ) {
                // Still on his way: the new spot waits a little, so a run of shots is one new
                // path rather than one each. The old spot stays the one to count from meanwhile.
                if (end != null && entity.tickCount - askedAt < REPLAN_TICKS) {
                    if (!deferring) log(entity, "spot moved ${Math.sqrt(pos.distanceToSqr(old)).toInt()} blocks, " +
                        "path ends ${Math.sqrt(end.distToCenterSqr(pos)).toInt()} from it: asks in ${REPLAN_TICKS - (entity.tickCount - askedAt)} ticks")
                    deferring = true
                    return
                }
                setOut(entity, pos, "spot moved ${Math.sqrt(pos.distanceToSqr(old)).toInt()} blocks, goes to look at")
                return
            }
            if (askedFrom != Double.MAX_VALUE) askedFrom = Math.sqrt(entity.distanceToSqr(pos))
            spot = pos
        }
        // A far spot is got to a stretch at a time — a path goes a hundred blocks at most, and
        // counts as there some blocks short: on again while each stretch gets him nearer.
        if (!entity.position().closerThan(pos, ARRIVE_DISTANCE) && entity.navigation.isDone) {
            // Nothing can be planned mid-jump, and an empty answer then says nothing about the way.
            if (!canPlan(entity)) return
            val now = Math.sqrt(entity.distanceToSqr(pos))
            if (now < askedFrom - PROGRESS) {
                val before = askedFrom
                askedFrom = now
                val going = entity.navigation.moveTo(pos.x, pos.y, pos.z, 1.0)
                log(entity, "stretch ended ${now.toInt()} blocks off (asked from ${if (before == Double.MAX_VALUE) "the air" else before.toInt()}), asks again: " +
                    if (going) pathTrace(entity, pos) else "no path")
                if (going) return
            } else {
                log(entity, "stretch ended ${now.toInt()} blocks off, no nearer than the ${askedFrom.toInt()} it was asked from")
            }
        }
        if (entity.position().closerThan(pos, ARRIVE_DISTANCE) || entity.navigation.isDone) {
            log(entity, if (entity.position().closerThan(pos, ARRIVE_DISTANCE)) "got there, nothing seen"
                else "path ended ${Math.sqrt(entity.distanceToSqr(pos)).toInt()} blocks short, gives up")
            entity.clearAlert()
            return
        }
        if (entity.tickCount % DEATH_SITE_CHECK_INTERVAL == 0 && deathSiteSettled(entity, pos)) {
            log(entity, "death site already checked, stops")
            entity.clearAlert()
            entity.navigation.stop()
        }
    }

    override fun stop(entity: NpcEntity) {
        // Stopped with the alert still up: something else took over.
        if (entity.isAlert()) {
            log(entity, when {
                entity.target != null -> "found a target"
                entity.combatLockedByCover() -> "taken for cover"
                entity.diggedIn -> "dug in"
                entity.busyWithRole() -> "busy with a role"
                entity.retreatPoint() != null -> "falling back"
                entity.resupplying -> "off to resupply"
                else -> "stopped"
            })
        }
    }

    private fun canPlan(entity: NpcEntity) = (entity.navigation as? VehicleAwareNavigation)?.canPlan != false

    private fun pathTrace(entity: NpcEntity, pos: Vec3): String = entity.navigation.path?.let {
        "path of ${it.nodeCount} nodes ends ${it.endNode?.asBlockPos()}, " +
            "${it.endNode?.let { end -> Math.sqrt(end.asBlockPos().distToCenterSqr(pos)).toInt() }} blocks from the spot, reach=${it.canReach()}"
    } ?: "no path kept"

    private fun log(entity: NpcEntity, what: String) {
        DebugFlags.log(LogGroup.HEARING, "{} investigating: {}", entity.uuid.toString().take(8), what)
    }

    /** Headed for a fallen ally's body: done if someone has already looked and found nothing,
     *  or if this NPC can now see the spot itself — see [DeathSites]. */
    private fun deathSiteSettled(entity: NpcEntity, pos: Vec3): Boolean {
        val level = entity.level() as? ServerLevel ?: return false
        val faction = SquadTeams.factionOf(entity) ?: return false
        val site = DeathSites.at(faction, pos, level.gameTime) ?: return false
        if (!site.checked && DeathSites.canConfirm(entity, pos)) site.checked = true
        return site.checked
    }
}
