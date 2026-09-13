package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModSensors
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
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

    override fun type(): SensorType<out ExtendedSensor<*>> = ModSensors.SQUAD_TARGET.get()

    override fun memoriesUsed(): List<MemoryModuleType<*>> = MEMORIES

    override fun doTick(level: ServerLevel, entity: NpcEntity) {
        BrainUtils.setTargetOfEntity(entity, computeDesired(entity, level))
    }

    private fun computeDesired(mob: NpcEntity, level: ServerLevel): LivingEntity? {
        val isMortarCrew = mob.npcClass == NpcClass.MORTAR_OPERATOR || mob.npcClass == NpcClass.MORTAR_LOADER

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
        val followRangeSqr = (mob.getAttribute(Attributes.FOLLOW_RANGE)?.value ?: 48.0).let { it * it }
        return TeamAwareness.relayedContacts(faction, mob.tickCount.toLong())
            .asSequence()
            .mapNotNull { level.getEntity(it) as? LivingEntity }
            .filter { it.isAlive && it !== mob && SquadTeams.isHostile(mob, it) && mob.distanceToSqr(it) <= followRangeSqr }
            .minByOrNull { mob.distanceToSqr(it) }
    }

    private fun nearestDirectTarget(mob: NpcEntity, level: ServerLevel): LivingEntity? {
        val followRange = mob.getAttribute(Attributes.FOLLOW_RANGE)?.value ?: 48.0
        val box = mob.boundingBox.inflate(followRange)
        return level.getEntitiesOfClass(LivingEntity::class.java, box) { candidate ->
            candidate !== mob && mob.isEnemy(candidate) && mob.sensing.hasLineOfSight(candidate)
        }.minByOrNull { mob.distanceToSqr(it) }
    }

    companion object {
        private val MEMORIES: List<MemoryModuleType<*>> = listOf(MemoryModuleType.ATTACK_TARGET)
    }
}
