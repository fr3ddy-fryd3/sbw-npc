package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DeathSites
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.world.phys.Vec3
import com.sbwnpc.squad.init.ModMemories
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import net.tslat.smartbrainlib.util.BrainUtils

/**
 * SmartBrain migration step 7 — direct port of the old `InvestigateGoal` onto `ExtendedBehaviour`,
 * placed in `NpcEntity.getIdleTasks()` (see there): response to an [com.sbwnpc.squad.combat.Alarm]
 * (heard nearby gunfire, or an ally went down without a resolvable killer) — NOT combat, just "go
 * look". If a real target turns up along the way, `ATTACK_TARGET` becomes non-null and the Fight
 * activity outranks Idle automatically, so this stops on its own.
 *
 * Reads [ModMemories.ALERT_POSITION] (see that memory's own doc comment) instead of
 * `NpcEntity.alertUntilTick`/`alertPos` — expires on its own via TTL, `NpcEntity.clearAlert()`
 * clears it early on arrival, same as before.
 *
 * Placed in Idle rather than a dedicated `Activity`, alongside [SquadOrderBehaviour] (migration step
 * 8) — `!entity.combatLockedByCover()` is still checked explicitly here (Idle behaviours aren't
 * mutually exclusive with `SeekCoverBehaviour`, which lives in Core — see that class's own doc
 * comment for why), same manual bridge the old goal already used.
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
    private fun eligible(entity: NpcEntity) =
        entity.target == null && !entity.combatLockedByCover() && !entity.diggedIn && !entity.vehicleTransport && !entity.operatingDrone && !entity.servingMortar && !entity.antiDroneEngaged

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean = eligible(entity)
    override fun shouldKeepRunning(entity: NpcEntity): Boolean = eligible(entity) && entity.isAlert()

    override fun start(entity: NpcEntity) {
        BrainUtils.getMemory(entity, ModMemories.ALERT_POSITION.get())?.let {
            entity.navigation.moveTo(it.x, it.y, it.z, 1.0)
        }
    }

    override fun tick(entity: NpcEntity) {
        val pos = BrainUtils.getMemory(entity, ModMemories.ALERT_POSITION.get()) ?: return
        if (entity.position().closerThan(pos, ARRIVE_DISTANCE) || entity.navigation.isDone) {
            entity.clearAlert()
            return
        }
        if (entity.tickCount % DEATH_SITE_CHECK_INTERVAL == 0 && deathSiteSettled(entity, pos)) {
            entity.clearAlert()
            entity.navigation.stop()
        }
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
