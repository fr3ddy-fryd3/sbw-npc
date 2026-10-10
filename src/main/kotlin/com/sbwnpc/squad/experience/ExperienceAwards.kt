package com.sbwnpc.squad.experience

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.MortarClaims
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import java.util.UUID

object ExperienceAwards {
    private const val LEDGER = "sbwnpcExperienceTarget"
    private const val BATTLE = "sbwnpcExperienceBattle"
    private const val WOUNDS = "sbwnpcExperienceWounds"
    private const val MEDICAL = "sbwnpcExperienceMedical"
    private const val QUIET_TICKS = 600L
    private const val PARTICIPATION_XP = 20.0
    private const val OBJECTIVE_XP = 30.0
    private const val MEDICAL_XP_PER_HP = 2.0
    private const val MEDICAL_PATIENT_CAP = 50.0

    fun shares(npc: NpcEntity, projectile: Entity? = null): Map<UUID, Double> {
        val level = npc.level() as? ServerLevel ?: return mapOf(npc.uuid to 1.0)
        npc.assignedMortarId?.takeIf { Ports.mortars.isShell(projectile) }?.let { mortar ->
            val loader = MortarClaims.loader(mortar)?.let { level.getEntity(it) as? NpcEntity }
            val tube = level.getEntity(mortar)
            if (loader != null && loader !== npc && tube != null && MortarClaims.atPost(loader, tube)) {
                return mapOf(npc.uuid to 0.5, loader.uuid to 0.5)
            }
        }
        npc.vehicle?.takeIf { Ports.vehicles.isVehicle(it) }?.let { vehicle ->
            val driver = Ports.vehicles.seating(vehicle).firstOrNull() as? NpcEntity
            if (driver != null && driver !== npc && driver.isAlive) return mapOf(npc.uuid to 0.75, driver.uuid to 0.25)
        }
        return mapOf(npc.uuid to 1.0)
    }

    fun target(entity: Entity): Boolean =
        entity is NpcEntity ||
            (entity is Player && !entity.isCreative && !entity.isSpectator) || Ports.vehicles.isVehicle(entity)

    fun damage(victim: Entity, source: DamageSource, fraction: Double, dead: Boolean) {
        val level = victim.level() as? ServerLevel ?: return
        if (!target(victim) || fraction <= 0 || !fraction.isFinite()) return
        val attacker = source.entity ?: return
        if (!SquadTeams.isHostile(victim, attacker)) return
        recordWound(victim, fraction, level.gameTime)
        val budget = targetBudget(victim)
        val tag = victim.persistentData.getCompound(LEDGER)
        val ledger = ExperienceLedger(tag)
        val npc = attacker as? NpcEntity
        val recipients = if (npc != null) contributors(npc, source.directEntity) else mapOf(attacker.uuid to 1.0)
        val eligible = tag.getCompound("Npcs")
        if (npc != null) recipients.keys.forEach { eligible.putBoolean(it.toString(), true) }
        tag.put("Npcs", eligible)
        // A promotion can increase max health without healing. Keep the life's initial denominator.
        var rewardFraction = fraction
        if (victim is LivingEntity) {
            val initialHealth = tag.getDouble("LifeMaxHealth").takeIf { it > 0 } ?: victim.maxHealth.toDouble()
            tag.putDouble("LifeMaxHealth", initialHealth)
            rewardFraction = fraction * victim.maxHealth / initialHealth
        }
        ledger.damage(rewardFraction, budget, recipients).filterKeys { eligible.getBoolean(it.toString()) }.forEach { (id, xp) ->
            SquadManager.get(level).creditExperience(level.server, id, xp, "damage")
            (SquadManager.findEntity(level.server, id) as? NpcEntity)?.let(::confirm)
        }
        if (dead) {
            ledger.finish(budget, killMultiplier = if (victim is Player) 3.0 else 1.0).filterKeys { eligible.getBoolean(it.toString()) }.forEach { (id, xp) ->
                SquadManager.get(level).creditExperience(level.server, id, xp, "assist")
            }
        }
        victim.persistentData.put(LEDGER, tag)
    }

    private fun recordWound(victim: Entity, fraction: Double, now: Long) {
        if (victim !is LivingEntity) return
        if (now - victim.persistentData.getLong("sbwnpcExperienceLastWound") > QUIET_TICKS) {
            victim.persistentData.putDouble(MEDICAL, 0.0)
        }
        victim.persistentData.putLong("sbwnpcExperienceLastWound", now)
        victim.persistentData.putDouble(WOUNDS, wounds(victim) + fraction * victim.maxHealth)
        if (victim is NpcEntity) confirm(victim)
    }

    private fun targetBudget(victim: Entity): Double =
        when {
            Ports.drones.isDrone(victim) -> 30.0
            Ports.vehicles.isVehicle(victim) -> 300.0
            else -> 100.0
        }

    private fun contributors(npc: NpcEntity, projectile: Entity?): Map<UUID, Double> {
        val captured = projectile?.persistentData?.getCompound("sbwnpcExperienceCrew")
        if (captured == null || captured.isEmpty) return shares(npc)
        return captured.allKeys.mapNotNull { key ->
            runCatching { UUID.fromString(key) }.getOrNull()?.let { it to captured.getDouble(key) }
        }.toMap()
    }

    @JvmStatic
    fun vehicleDamage(victim: Entity, source: DamageSource, before: Float) {
        val after = Ports.vehicles.healthFraction(victim).coerceAtLeast(0f)
        damage(victim, source, (before - after).toDouble(), after <= 0f)
    }

    fun wounds(patient: LivingEntity): Double = patient.persistentData.getDouble(WOUNDS)

    fun healed(patient: LivingEntity, amount: Double) {
        if (amount > 0) patient.persistentData.putDouble(WOUNDS, (wounds(patient) - amount).coerceAtLeast(0.0))
    }

    fun treated(medic: NpcEntity, patient: LivingEntity, before: Float, injury: Double) {
        val healed = minOf((patient.health - before).toDouble(), injury).coerceAtLeast(0.0)
        patient.persistentData.putDouble(WOUNDS, (injury - healed).coerceAtLeast(0.0))
        val paid = patient.persistentData.getDouble(MEDICAL)
        val xp = minOf(healed * MEDICAL_XP_PER_HP, (MEDICAL_PATIENT_CAP - paid).coerceAtLeast(0.0))
        if (xp <= 0 || !SquadTeams.sameSide(medic, patient)) return
        patient.persistentData.putDouble(MEDICAL, paid + xp)
        medic.gainExperience(xp, "medical")
        confirm(medic)
    }

    private fun markParticipant(npc: NpcEntity, now: Long) {
        npc.persistentData.putLong(BATTLE, now)
        npc.persistentData.putBoolean("sbwnpcExperienceConfirmed", true)
        npc.currentSquad()?.takeIf { it.order == SquadOrder.ATTACK }?.let {
            npc.persistentData.putString("sbwnpcExperienceAttack", "${it.id}:${it.orderStamp}")
        }
    }

    private fun confirm(npc: NpcEntity) {
        val now = npc.level().gameTime
        markParticipant(npc, now)
        val squad = npc.currentSquad() ?: return
        squad.members.forEach { id ->
            val member = (npc.level() as ServerLevel).getEntity(id) as? NpcEntity ?: return@forEach
            val firing = member.lastShotTick >= 0 && member.tickCount - member.lastShotTick < 60
            val maneuvering = squad.tactics.plan?.tasks?.containsKey(id) == true &&
                !member.navigation.isDone && member.deltaMovement.horizontalDistanceSqr() > 0.0025
            if (member.isAlive && (firing || maneuvering)) markParticipant(member, now)
        }
    }

    fun tick(npc: NpcEntity) {
        if (npc.tickCount % 20 != 0 || !npc.persistentData.getBoolean("sbwnpcExperienceConfirmed")) return
        val fighting = npc.target?.let { it.isAlive && npc.isEnemy(it) } == true &&
            npc.lastShotTick >= 0 && npc.tickCount - npc.lastShotTick < 40
        if (fighting) npc.persistentData.putLong(BATTLE, npc.level().gameTime)
        if (npc.level().gameTime - npc.persistentData.getLong(BATTLE) < QUIET_TICKS) return
        npc.persistentData.putBoolean("sbwnpcExperienceConfirmed", false)
        npc.gainExperience(PARTICIPATION_XP, "battle_participation")
    }

    fun objective(server: MinecraftServer, squad: Squad) {
        val receipt = "${squad.id}:${squad.orderStamp}"
        squad.members.mapNotNull { SquadManager.findEntity(server, it) as? NpcEntity }.forEach { npc ->
            if (npc.persistentData.getString("sbwnpcExperienceAttack") != receipt) return@forEach
            npc.persistentData.remove("sbwnpcExperienceAttack")
            val participation = if (npc.persistentData.getBoolean("sbwnpcExperienceConfirmed")) PARTICIPATION_XP else 0.0
            npc.persistentData.putBoolean("sbwnpcExperienceConfirmed", false)
            npc.gainExperience(OBJECTIVE_XP + participation, "combat_objective")
        }
    }
}
