package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.experience.ExperienceAwards
import com.sbwnpc.squad.experience.ExperienceRules
import com.sbwnpc.squad.init.ModEntities
import com.sbwnpc.squad.npc.HelicopterModel
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.gametest.framework.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID
import java.util.function.Consumer
import kotlin.math.abs

@PrefixGameTestTemplate(false)
object ExperienceGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 40, batch = "class-experience")
    fun classMultipliersApplyOnceToKillsDeferredRewardsAndSavedExperience(helper: GameTestHelper) {
        val roles = listOf(NpcClass.RIFLEMAN, NpcClass.SNIPER, NpcClass.MACHINE_GUNNER)
        val shooters = roles.mapIndexed { i, role -> npc(helper, 3.5 + i * 3, SquadFaction.PIG, role) }
        val enemies = roles.mapIndexed { i, _ -> npc(helper, 15.5 + i * 3, SquadFaction.COW) }
        var restored: NpcEntity? = null
        cleanup(helper) { (shooters + enemies + listOfNotNull(restored)).forEach { it.discard() } }
        helper.runAtTickTime(5) {
            shooters.zip(enemies).forEach { (shooter, enemy) ->
                Ports.guns.inHand(shooter)!!.inflictHit(enemy, enemy.health * 2)
                val multiplier = com.sbwnpc.squad.config.SquadConfig.experienceMultiplier(shooter.npcClass)
                near(helper, shooter.experience, 100.0 * multiplier, "Actual kill did not apply the class coefficient once")
            }
            helper.assertTrue(shooters[1].experience < shooters[0].experience && shooters[2].experience < shooters[0].experience, "High-power roles received the rifleman's progression rate")
            val sniper = shooters[1]
            val previous = sniper.experience
            val saved = CompoundTag().also { sniper.saveWithoutId(it) }
            sniper.discard()
            val manager = SquadManager.get(helper.level)
            manager.creditExperience(helper.level.server, sniper.uuid, 80.0, "assist")
            restored = ModEntities.NPC.get().create(helper.level)!!.apply {
                load(saved)
                isNoAi = true
            }
            near(helper, restored!!.experience, previous, "Loading multiplied saved XP again")
            helper.level.addFreshEntity(restored!!)
            manager.deliverExperience(restored!!)
            near(helper, restored!!.experience, previous + 80.0 * com.sbwnpc.squad.config.SquadConfig.experienceMultiplier(NpcClass.SNIPER), "Deferred XP lost or doubled its class coefficient")
            manager.deliverExperience(restored!!)
            near(helper, restored!!.experience, previous + 80.0 * com.sbwnpc.squad.config.SquadConfig.experienceMultiplier(NpcClass.SNIPER), "Deferred reward was delivered twice")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 40, batch = "experience-contribution")
    fun playerContributionsAndTargetPromotionKeepTheFiniteLifeBudget(helper: GameTestHelper) {
        val shooter = npc(helper, 3.5, SquadFaction.PIG)
        val enemy = npc(helper, 15.5, SquadFaction.COW)
        val promoted = npc(helper, 20.5, SquadFaction.COW)
        val player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.level, com.mojang.authlib.GameProfile(UUID.randomUUID(), "xp-assist-helper"))
        player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL)
        player.setPos(helper.absoluteVec(Vec3(7.5, 1.0, 5.5)))
        helper.level.addNewPlayer(player)
        SquadTeams.assign(player, SquadFaction.PIG)
        cleanup(helper) {
            SquadTeams.clear(player)
            helper.level.removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED)
            listOf(shooter, enemy, promoted).forEach { it.discard() }
        }
        helper.runAtTickTime(5) {
            val initialHealth = enemy.health
            enemy.hurt(helper.level.damageSources().playerAttack(player), enemy.health * 0.75f)
            helper.assertTrue(enemy.health in 0f..initialHealth && enemy.health != initialHealth, "Player control did no actual damage")
            val npcFraction = enemy.health / initialHealth
            enemy.invulnerableTime = 0
            Ports.guns.inHand(shooter)!!.inflictHit(enemy, enemy.health * 2)
            near(helper, shooter.experience, npcFraction * 100.0, "Player contribution must reduce the NPC assist share by actual damage")
            val before = shooter.experience
            Ports.guns.inHand(shooter)!!.inflictHit(promoted, promoted.health * 0.25f)
            val hp = promoted.health
            promoted.gainExperience(ExperienceRules.threshold(NpcRank.REGULAR), "test_enemy_promotion")
            helper.assertTrue(promoted.health == hp, "Target promotion unexpectedly healed")
            promoted.invulnerableTime = 0
            Ports.guns.inHand(shooter)!!.inflictHit(promoted, promoted.health * 2)
            near(helper, shooter.experience - before, 100.0, "Target's new maximum health rewrote its life budget")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 40, batch = "experience-damage")
    fun experienceUsesActualHostileDamageAndSharedKills(helper: GameTestHelper) {
        val first = npc(helper, 3.5, SquadFaction.PIG)
        val second = npc(helper, 6.5, SquadFaction.PIG)
        val enemy = npc(helper, 15.5, SquadFaction.COW)
        val friend = npc(helper, 20.5, SquadFaction.PIG)
        val all = listOf(first, second, enemy, friend)
        cleanup(helper) { all.forEach { it.discard() } }
        helper.runAtTickTime(5) {
            val hp = enemy.health
            Ports.guns.inHand(first)!!.inflictHit(enemy, hp / 4)
            val lost = hp - enemy.health
            helper.assertTrue(lost > 0, "Actual SBW bullet did no damage")
            near(helper, first.experience, lost / hp * 70.0, "Damage XP uses actual lost health")
            val beforeFriend = first.experience
            Ports.guns.inHand(first)!!.inflictHit(friend, 2f)
            near(helper, first.experience, beforeFriend, "Friendly hits must give no XP")
            enemy.invulnerableTime = 0
            Ports.guns.inHand(second)!!.inflictHit(enemy, enemy.health * 2)
            helper.assertTrue(!enemy.isAlive, "Enemy survived the real fatal bullet")
            near(helper, first.experience + second.experience, 100.0, "One life must pay exactly its budget")
            near(helper, first.experience, lost / hp * 100.0, "Assist reward must follow contribution")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 40, batch = "experience-medical")
    fun combatTreatmentPromotionAndIdentityPreserveTheKit(helper: GameTestHelper) {
        val medic = npc(helper, 3.5, SquadFaction.PIG, NpcClass.MEDIC)
        val patient = npc(helper, 7.5, SquadFaction.PIG)
        val enemy = npc(helper, 15.5, SquadFaction.COW)
        cleanup(helper) { listOf(medic, patient, enemy).forEach { it.discard() } }
        helper.runAtTickTime(5) {
            Ports.guns.inHand(enemy)!!.inflictHit(patient, 5f)
            val wounds = ExperienceAwards.wounds(patient)
            val hp = patient.health
            Ports.gear.treat(patient)
            ExperienceAwards.treated(medic, patient, hp, wounds)
            near(helper, medic.experience, minOf(wounds, (patient.health - hp).toDouble()) * 2, "Medic XP must use actual combat treatment")
            helper.assertTrue(medic.experience > 0, "Medic received no XP for treating a hostile wound")
            // Direct healing clears the eligibility; later fall injuries are not combat XP.
            patient.health = patient.maxHealth
            near(helper, ExperienceAwards.wounds(patient), 0.0, "Direct healing left stale wounds")
            patient.health = patient.maxHealth - 4
            val beforeXp = medic.experience
            val beforeHp = patient.health
            val beforeWounds = ExperienceAwards.wounds(patient)
            Ports.gear.treat(patient)
            ExperienceAwards.treated(medic, patient, beforeHp, beforeWounds)
            near(helper, medic.experience, beforeXp, "Noncombat treatment awarded XP")
            repeat(7) {
                patient.invulnerableTime = 0
                Ports.guns.inHand(enemy)!!.inflictHit(patient, 8f)
                val beforeTreatment = patient.health
                val eligible = ExperienceAwards.wounds(patient)
                Ports.gear.treat(patient)
                ExperienceAwards.treated(medic, patient, beforeTreatment, eligible)
            }
            near(helper, medic.experience, 50.0, "Repeated treatment exceeded the per-patient battle cap")
            val gun = medic.mainHandItem
            val pistol = medic.sidearmWeapon
            Ports.guns.inHand(medic)!!.spendShot()
            val rounds = Ports.guns.roundsLeft(gun)
            medic.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)!!.baseValue = 100.0
            medic.health = 70f
            val oldMaximum = medic.maxHealth
            val damagedHp = medic.health
            medic.gainExperience(ExperienceRules.threshold(NpcRank.VETERAN) - medic.experience, "test_promotion")
            helper.assertTrue(medic.npcRank == NpcRank.VETERAN && medic.health == damagedHp, "Promotion healed the soldier or lost rank")
            helper.assertTrue(medic.mainHandItem === gun && medic.sidearmWeapon === pistol && Ports.guns.roundsLeft(gun) == rounds, "Promotion replaced or refilled the kit")
            near(helper, medic.maxHealth.toDouble(), oldMaximum * NpcRank.VETERAN.healthMultiplier / NpcRank.RECRUIT.healthMultiplier, "Promotion discarded a custom health attribute")
            val tag = CompoundTag()
            medic.saveWithoutId(tag)
            val restored = ModEntities.NPC.get().create(helper.level)!!
            restored.load(tag)
            helper.assertTrue(restored.name.string == medic.name.string && restored.npcRank == medic.npcRank, "Name/rank lost during load")
            near(helper, restored.experience, medic.experience, "XP lost during load")
            near(helper, restored.maxHealth.toDouble(), medic.maxHealth.toDouble(), "Load discarded a custom health attribute")
            helper.assertTrue(Ports.guns.roundsLeft(restored.mainHandItem) == rounds, "Loading XP reset ammunition")
            tag.remove("Experience")
            val legacy = ModEntities.NPC.get().create(helper.level)!!
            legacy.load(tag)
            near(helper, legacy.experience, ExperienceRules.threshold(NpcRank.VETERAN), "Legacy rank was not preserved")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 40, batch = "experience-vehicle")
    fun vehicleDamageCreditsShooterAndDriverWithoutFriendlyExperience(helper: GameTestHelper) {
        val driver = npc(helper, 3.5, SquadFaction.PIG)
        val shooter = npc(helper, 6.5, SquadFaction.PIG)
        val ride = Ports.vehicles.create(helper.level, HelicopterModel.AH_6)!!
        val enemy = Ports.vehicles.create(helper.level, HelicopterModel.AH_6)!!
        ride.setPos(helper.absoluteVec(Vec3(4.5, 2.0, 7.5)))
        enemy.setPos(helper.absoluteVec(Vec3(23.5, 2.0, 7.5)))
        helper.level.addFreshEntity(ride)
        helper.level.addFreshEntity(enemy)
        SquadTeams.assign(ride, SquadFaction.PIG)
        SquadTeams.assign(enemy, SquadFaction.COW)
        helper.assertTrue(driver.startRiding(ride, true) && shooter.startRiding(ride, true), "Crew did not board")
        cleanup(helper) { listOf(driver, shooter, ride, enemy).forEach { it.discard() } }
        helper.runAtTickTime(5) {
            val before = Ports.vehicles.healthFraction(enemy)
            enemy.hurt(helper.level.damageSources().explosion(shooter, shooter), 30f)
            val fraction = before - Ports.vehicles.healthFraction(enemy)
            helper.assertTrue(fraction > 0, "Vehicle damage fixture did no damage")
            near(helper, shooter.experience + driver.experience, fraction * 210.0, "Vehicle XP did not use actual SBW health")
            near(helper, shooter.experience, driver.experience * 3, "Crew reward must be 75/25")
            val xp = shooter.experience + driver.experience
            ride.hurt(helper.level.damageSources().explosion(shooter, shooter), 30f)
            near(helper, shooter.experience + driver.experience, xp, "Friendly vehicle damage awarded XP")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 300, batch = "experience-projectile")
    fun realBulletsAwardExperienceAndEmptyObjectivesDoNot(helper: GameTestHelper) {
        val shooter = npc(helper, 3.5, SquadFaction.PIG)
        val enemy = npc(helper, 23.5, SquadFaction.COW)
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(shooter.uuid))
        cleanup(helper) {
            manager.disband(helper.level, squad.id)
            listOf(shooter, enemy).forEach { it.discard() }
        }
        ExperienceAwards.objective(helper.level.server, squad)
        near(helper, shooter.experience, 0.0, "Empty objective gave XP")
        manager.issueOrder(squad.id, com.sbwnpc.squad.squad.SquadOrder.ATTACK)
        val ammo = Ports.guns.roundsLeft(shooter.mainHandItem)
        helper.onEachTick {
            val gun = Ports.guns.inHand(shooter)!!
            gun.operate()
            if (gun.canShoot()) gun.shootAt(0.0, enemy.eyePosition)
            if (!enemy.isAlive) {
                near(helper, shooter.experience, 100.0, "Actual projectiles did not award the life budget")
                helper.assertTrue(Ports.guns.roundsLeft(shooter.mainHandItem) < ammo, "No physical bullet spent ammunition")
                ExperienceAwards.objective(helper.level.server, squad)
                near(helper, shooter.experience, 150.0, "Contested objective/participation bonus missing")
                ExperienceAwards.objective(helper.level.server, squad)
                near(helper, shooter.experience, 150.0, "Objective reward duplicated")
                helper.succeed()
            }
        }
    }

    private fun npc(helper: GameTestHelper, x: Double, faction: SquadFaction, role: NpcClass = NpcClass.RIFLEMAN): NpcEntity {
        for (fx in 0 until 24) for (fz in 0 until 12) helper.setBlock(net.minecraft.core.BlockPos(fx, 0, fz), Blocks.STONE)
        return AttackDeploymentGameTests.spawn(helper, Vec3(x, 1.0, 5.5), faction, role = role).apply {
            isNoAi = true
            npcRank = NpcRank.RECRUIT
            finalizeSpawn(helper.level, helper.level.getCurrentDifficultyAt(blockPosition()), MobSpawnType.COMMAND, null)
            for (slot in EquipmentSlot.entries.filter { it.type == EquipmentSlot.Type.HUMANOID_ARMOR }) setItemSlot(slot, ItemStack.EMPTY)
        }
    }

    private fun near(helper: GameTestHelper, actual: Double, expected: Double, message: String) = helper.assertTrue(abs(actual - expected) < 1e-4, "$message: $actual != $expected")

    private fun cleanup(helper: GameTestHelper, action: () -> Unit) {
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = action()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = action()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = action()
        })
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 100, batch = "experience-mortar")
    fun mortarRewardSnapshotsTheWorkingCrewAndExcludesTheirRifleFire(helper: GameTestHelper) {
        val gunner = npc(helper, 10.5, SquadFaction.PIG, NpcClass.MORTAR)
        val loader = npc(helper, 12.5, SquadFaction.PIG, NpcClass.MORTAR)
        val enemy = npc(helper, 20.5, SquadFaction.COW)
        val second = npc(helper, 22.5, SquadFaction.COW)
        val mortar = Ports.mortars.create(helper.level, 0f)
        mortar.setPos(helper.absoluteVec(Vec3(12.5, 1.0, 7.5)))
        helper.level.addFreshEntity(mortar)
        com.sbwnpc.squad.entity.ai.MortarClaims.assign(mortar, gunner)
        com.sbwnpc.squad.entity.ai.MortarClaims.assign(mortar, loader)
        var shell: net.minecraft.world.entity.projectile.Projectile? = null
        val listener = Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> {
            val projectile = it.entity as? net.minecraft.world.entity.projectile.Projectile
            if (it.level === helper.level && Ports.mortars.isShell(projectile) && projectile?.owner === gunner) shell = projectile
        }
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener)
        cleanup(helper) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener)
            com.sbwnpc.squad.entity.ai.MortarClaims.releaseMortar(mortar.uuid)
            listOf(gunner, loader, enemy, second, mortar).forEach { it.discard() }
            shell?.discard()
        }
        Ports.mortars.loadShell(mortar)
        helper.assertTrue(Ports.mortars.fire(mortar, gunner), "Real mortar firing cycle rejected")
        helper.onEachTick {
            val projectile = shell ?: return@onEachTick
            helper.assertTrue(projectile.persistentData.getCompound("sbwnpcExperienceCrew").allKeys.size == 2, "Shell did not snapshot both working crew")
            // The loader can leave after launch without rewriting ownership of that shot.
            loader.moveTo(helper.absoluteVec(Vec3(3.5, 1.0, 5.5)).x, loader.y, loader.z)
            helper.assertTrue(enemy.health == enemy.maxHealth, "Mortar target lost environmental health before the controlled hit: hp=${enemy.health}/${enemy.maxHealth} ledger=${enemy.persistentData}")
            enemy.hurt(helper.level.damageSources().explosion(projectile, gunner), enemy.health * 2)
            val multiplier = com.sbwnpc.squad.config.SquadConfig.experienceMultiplier(NpcClass.MORTAR)
            near(helper, gunner.experience, 50.0 * multiplier, "Gunner's shell share with class coefficient")
            near(helper, loader.experience, 50.0 * multiplier, "Loader's captured shell share with class coefficient")
            projectile.discard()
            Ports.guns.inHand(gunner)!!.inflictHit(second, second.health * 2)
            near(helper, gunner.experience, 150.0 * multiplier, "Personal firearm must belong entirely to its shooter")
            near(helper, loader.experience, 50.0 * multiplier, "Loader received XP for somebody else's personal weapon")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 160, batch = "experience-medic-ai")
    fun actualMedicBehaviourAwardsExperienceForItsTreatment(helper: GameTestHelper) {
        val medic = npc(helper, 3.5, SquadFaction.PIG, NpcClass.MEDIC)
        val patient = npc(helper, 5.5, SquadFaction.PIG)
        val enemy = npc(helper, 21.5, SquadFaction.COW)
        Ports.guns.inHand(enemy)!!.inflictHit(patient, patient.health * 0.75f)
        val damaged = patient.health
        enemy.discard()
        medic.isNoAi = false
        cleanup(helper) { listOf(medic, patient, enemy).forEach { it.discard() } }
        helper.onEachTick {
            if (patient.health > damaged && medic.experience > 0) {
                helper.assertTrue(medic.experience <= (patient.maxHealth - damaged) * 2 + 0.01, "Medic earned more than healed combat health")
                helper.assertTrue(Ports.guns.isGun(medic.mainHandItem), "Healing displaced the medic weapon")
                helper.succeed()
            }
        }
    }
}
