package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.combat.T90WeaponSelection
import com.sbwnpc.squad.combat.VehicleTargeting
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModSensors
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.sensing.SensorType
import net.tslat.smartbrainlib.api.core.sensor.ExtendedSensor
import net.tslat.smartbrainlib.util.BrainUtils

/**
 * SmartBrain migration step 4 — replaces FOUR separate Goal classes (SquadFocusTargetGoal,
 * SquadAwarenessTargetGoal, plus vanilla HurtByTargetGoal/NearestAttackableTargetGoal) with ONE
 * sensor evaluating the exact same priority chain each scan, all in one place instead of spread
 * across four independently-prioritized targetSelector entries that had to be kept mentally in sync
 * — that fragmentation is what caused the mortar-confusing-its-own-target class of bug earlier in
 * this project.
 *
 * Priority (highest first), unchanged from the old goals:
 * 1. Squad focus (ATTACK: hunt it; DEFEND: whoever last hurt the guarded focus) — skipped entirely
 *    for mortar crew, who have their own solver (see MortarOperatorBehaviour).
 * 2. Whoever last hurt this NPC (vanilla HurtByTargetGoal equivalent).
 * 3. Faction-wide relayed contact via [TeamAwareness] — skipped for mortar crew too (same reason).
 * 4. Nearest directly-visible hostile within follow range (vanilla NearestAttackableTargetGoal
 *    equivalent).
 *
 * Uses [BrainUtils.setTargetOfEntity] rather than setting the ATTACK_TARGET memory directly — that
 * call ALSO sets the legacy `mob.target` field, which every not-yet-migrated Goal (melee, grenade,
 * mortar, cover, investigate, squad-order) still reads. This is a deliberate, temporary bridge for
 * the migration window (see SMARTBRAIN_MIGRATION_PLAN.md task 4) — those goals are not touched here.
 */
class SquadTargetSensor : ExtendedSensor<NpcEntity>() {

    // ExtendedSensor's default is a flat 20 ticks with NO initial offset (nextTickTime starts at 0
    // — confirmed via javap), so every NPC deployed in the same tick (a whole preset squad, a
    // barracks reinforcement wave) scans on the very same tick forever after: N entity queries +
    // N raycasts in one tick, nothing on the other 19. Randomising the interval per scan
    // decorrelates them within a few cycles. Average is still ~20 ticks.
    init {
        setScanRate { entity -> SCAN_RATE_MIN + entity.random.nextInt(SCAN_RATE_JITTER) }
    }

    override fun type(): SensorType<out ExtendedSensor<*>> = ModSensors.SQUAD_TARGET.get()

    override fun memoriesUsed(): List<MemoryModuleType<*>> = MEMORIES

    override fun doTick(level: ServerLevel, entity: NpcEntity) {
        val target = computeDesired(entity, level)
        BrainUtils.setTargetOfEntity(entity, target)
        if (target != null) T90WeaponSelection.update(entity, target)
    }

    private fun computeDesired(mob: NpcEntity, level: ServerLevel): LivingEntity? {
        mob.vehicleAttacker()?.let { return it }
        val isMortarCrew = mob.npcClass == NpcClass.MORTAR_OPERATOR || mob.npcClass == NpcClass.MORTAR_LOADER

        // SBW aims at the vehicle when its passenger is the gunner's target. Give armed vehicle
        // crews that passenger first, so armour is engaged before nearby dismounted infantry.
        if (mob.vehicle is VehicleEntity && (mob.vehicle as VehicleEntity).getGunData(mob) != null) {
            VehicleTargeting.closestVisibleHostileVehicleOccupant(mob, level, NpcEntity.DETECTION_RANGE)?.let { return it }
        }

        if (!isMortarCrew) {
            squadFocusTarget(mob, level)?.let { return it }
        }

        mob.lastHurtByMob?.takeIf { it.isAlive && SquadTeams.isHostile(mob, it) }?.let { return it }

        if (!isMortarCrew) {
            relayedTarget(mob, level)?.let { return it }
        }

        return nearestDirectTarget(mob, level)
    }

    private fun squadFocusTarget(mob: NpcEntity, level: ServerLevel): LivingEntity? {
        val squad = mob.currentSquad() ?: return null
        val fid = squad.focusEntity ?: return null
        val focus = level.getEntity(fid) as? LivingEntity ?: return null
        if (!focus.isAlive) return null
        return when (squad.order) {
            SquadOrder.ATTACK -> focus
            SquadOrder.DEFEND -> focus.lastHurtByMob?.takeIf {
                it.isAlive && it !== mob && SquadTeams.isHostile(mob, it)
            }
            else -> null
        }
    }

    private fun relayedTarget(mob: NpcEntity, level: ServerLevel): LivingEntity? {
        val faction = SquadTeams.factionOf(mob) ?: return null
        val followRangeSqr = NpcEntity.DETECTION_RANGE * NpcEntity.DETECTION_RANGE
        return TeamAwareness.relayedContacts(faction, level.gameTime)
            .asSequence()
            .mapNotNull { level.getEntity(it) as? LivingEntity }
            .filter { it.isAlive && it !== mob && SquadTeams.isHostile(mob, it) && mob.distanceToSqr(it) <= followRangeSqr }
            .minByOrNull { mob.distanceToSqr(it) }
    }

    // Two cost fixes versus the naive "filter by LOS, then take the nearest" version:
    //  - the box is DETECTION_RANGE wide but only DETECTION_HEIGHT tall — infantry lives on the
    //    ground, and a full 144-block-tall column meant walking ~10x the chunk sections for nothing;
    //  - hostiles are sorted by distance FIRST and raycast in that order, stopping at the first one
    //    visible — the result is identical (nearest visible hostile) but it's typically 1-3
    //    raycasts instead of one per hostile in range (dozens, in a big fight, per NPC per scan).
    private fun nearestDirectTarget(mob: NpcEntity, level: ServerLevel): LivingEntity? {
        val box = mob.boundingBox.inflate(NpcEntity.DETECTION_RANGE, DETECTION_HEIGHT, NpcEntity.DETECTION_RANGE)
        val hostiles = level.getEntitiesOfClass(LivingEntity::class.java, box) { candidate ->
            candidate !== mob && candidate.isAlive && SquadTeams.isHostile(mob, candidate)
        }
        if (hostiles.isEmpty()) return null
        val rangeSqr = NpcEntity.DETECTION_RANGE * NpcEntity.DETECTION_RANGE
        hostiles.sortBy { mob.distanceToSqr(it) }
        for (candidate in hostiles) {
            if (mob.distanceToSqr(candidate) > rangeSqr) break // box corners reach past the sphere
            if (mob.sensing.hasLineOfSight(candidate)) return candidate
        }
        return null
    }

    companion object {
        private const val SCAN_RATE_MIN = 15
        private const val SCAN_RATE_JITTER = 11 // 15..25 ticks, mean 20 — same average as before
        private const val DETECTION_HEIGHT = 24.0

        private val MEMORIES: List<MemoryModuleType<*>> = listOf(MemoryModuleType.ATTACK_TARGET)
    }
}
