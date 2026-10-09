package com.sbwnpc.squad.gametest

import com.atsuishio.superbwarfare.data.gun.GunData
import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.combat.AntiArmourKit
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.SidearmKit
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.domain.port.TriggerMode
import com.sbwnpc.squad.entity.ai.AmmoRetreat
import com.sbwnpc.squad.init.ModBlocks
import com.sbwnpc.squad.init.ModEntities
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.function.Consumer

@PrefixGameTestTemplate(false)
object SidearmGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 40, batch = "sidearm-storage")
    fun sidearmsSurviveSaveSupplyAndRoleOwnership(helper: GameTestHelper) {
        floor(helper, 24, 12)
        val npcs = NpcClass.entries.mapIndexed { index, role ->
            AttackDeploymentGameTests.spawn(helper, Vec3(2.5 + index, 1.0, 5.5), SquadFaction.PIG, role = role).apply { isNoAi = true }
        }
        helper.runAtTickTime(10) {
            for (npc in npcs) {
                helper.assertTrue(Ports.guns.magazineSize(npc.sidearmWeapon) == 17 && Ports.guns.reserve(npc.sidearmWeapon) == 68, "Missing loaded MP443 + four reserve magazines for ${npc.npcClass}")
                val primary = npc.mainHandItem
                GunData.from(primary).ammo.set(0)
                GunData.from(primary).virtualAmmo.set(12)
                Ports.guns.persist(primary)
                SidearmKit.update(npc)
                helper.assertTrue(!npc.usingSidearm && npc.mainHandItem === primary, "Ordinary reload was mistaken for exhaustion")
                GunData.from(primary).virtualAmmo.set(0)
                Ports.guns.persist(primary)
                SidearmKit.update(npc)
                helper.assertTrue(npc.usingSidearm && npc.primaryGun() === primary && npc.ammoFraction() == 0.0, "Pistol replaced primary ammo accounting")
                helper.assertTrue(Ports.guns.inHand(npc)?.triggerMode == TriggerMode.SEMI, "Backup pistol is automatic")
                GunData.from(npc.mainHandItem).ammo.set(1)
                GunData.from(npc.mainHandItem).virtualAmmo.set(2)
                if (npc.npcClass == NpcClass.MACHINE_GUNNER) {
                    helper.assertTrue(AntiArmourKit.wield(npc, true) && !npc.usingSidearm, "Launcher did not restore primary ownership")
                    helper.assertTrue(AntiArmourKit.isLauncher(npc.mainHandItem) && npc.antiArmourWeapon === primary, "Launcher exchange lost the rifle")
                    AntiArmourKit.wield(npc, false)
                    SidearmKit.update(npc)
                }
                if (npc.npcClass == NpcClass.DRONE_OPERATOR) {
                    Ports.guns.persist(npc.mainHandItem)
                    npc.stowedWeapon = npc.mainHandItem.copy()
                    npc.setItemInHand(InteractionHand.MAIN_HAND, Ports.drones.monitor())
                    npc.operatingDrone = true
                    SidearmKit.update(npc)
                    helper.assertTrue(Ports.drones.isMonitor(npc.mainHandItem), "Pistol displaced the drone monitor")
                }
                val tag = CompoundTag()
                npc.saveWithoutId(tag)
                val loaded = ModEntities.NPC.get().create(helper.level)!!
                loaded.load(tag)
                helper.assertTrue(loaded.usingSidearm && loaded.mainHandItem === loaded.sidearmWeapon && Ports.guns.roundsLeft(loaded.sidearmWeapon) == 3 && loaded.ammoFraction() == 0.0, "Save/load duplicated or reset ammo/weapon ownership for ${npc.npcClass}")
                helper.assertTrue(loaded.resupply(), "Supply did not refill the kit")
                SidearmKit.update(loaded)
                helper.assertTrue(!loaded.usingSidearm && Ports.guns.roundsLeft(loaded.primaryGun()) > 0 && Ports.guns.reserve(loaded.sidearmWeapon) == 68, "Supply failed to restore primary or mis-sized pistol reserve")
                val oldTag = CompoundTag()
                loaded.saveWithoutId(oldTag)
                oldTag.remove("Sidearm")
                oldTag.remove("SidearmPrimary")
                val legacy = ModEntities.NPC.get().create(helper.level)!!
                legacy.load(oldTag)
                helper.assertTrue(Ports.guns.roundsLeft(legacy.sidearmWeapon) == 85, "Existing NPC did not receive backup pistol")
            }
            npcs.forEach { it.discard() }
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 400, batch = "sidearm-fire")
    fun backupPistolsFireWithoutAdvancingOrRefillingPrimary(helper: GameTestHelper) {
        floor(helper, 160, 128)
        val names = listOf("mp_443", "glock_17", "m_1911")
        val shooters = names.mapIndexed { index, name ->
            AttackDeploymentGameTests.spawn(helper, Vec3(20.5, 1.0, 40.5 + index * 16), SquadFaction.PIG).apply {
                diggedIn = true
                sidearmWeapon = Ports.guns.issue(ResourceLocation.fromNamespaceAndPath("superbwarfare", name), this, if (name == "m_1911") 70 else 85)
                GunData.from(mainHandItem).ammo.set(0)
                GunData.from(mainHandItem).virtualAmmo.set(0)
                Ports.guns.persist(mainHandItem)
            }
        }
        val origins = shooters.map { it.position() }
        val enemies = shooters.mapIndexed { index, _ ->
            AttackDeploymentGameTests.spawn(helper, Vec3(80.5, 1.0, 40.5 + index * 16), SquadFaction.COW).apply {
                isNoAi = true
                getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 5000.0
                health = maxHealth
            }
        }
        val witness = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "sidearm-observer"))
        witness.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
        val at = helper.absoluteVec(Vec3(45.5, 5.0, 5.5))
        witness.moveTo(at.x, at.y, at.z, 0f, 0f)
        witness.setNoGravity(true)
        helper.level.addNewPlayer(witness)
        val rounds = IntArray(shooters.size)
        val listener = Consumer<EntityJoinLevelEvent> { event ->
            val projectile = event.entity as? Projectile ?: return@Consumer
            val index = shooters.indexOf(projectile.owner)
            if (event.level === helper.level && index >= 0 && Ports.guns.isRound(projectile)) rounds[index]++
        }
        val pacer = CombatTickPacer()
        NeoForge.EVENT_BUS.addListener(listener)
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
        cleanup(helper) {
            NeoForge.EVENT_BUS.unregister(listener)
            NeoForge.EVENT_BUS.unregister(pacer)
            (shooters + enemies).forEach { it.discard() }
            helper.level.removePlayerImmediately(witness, Entity.RemovalReason.DISCARDED)
            val report = Path.of(System.getProperty("sbwnpc.gameTestReports"), "sidearm-fire.txt")
            Files.createDirectories(report.parent)
            Files.writeString(report, names.zip(rounds.toList()).joinToString("\n") { "${it.first}=${it.second}" })
        }
        helper.onEachTick {
            shooters.forEachIndexed { index, npc ->
                if (helper.tick > 5) helper.assertTrue(npc.usingSidearm && npc.ammoFraction() == 0.0, "Pistol made empty primary appear replenished")
                helper.assertTrue(npc.position().distanceTo(origins[index]) < 1.0, "Pistol forced support to leave its held position")
            }
            if (rounds.all { it >= 2 }) {
                shooters.forEach { npc ->
                    helper.assertTrue(Ports.guns.inHand(npc)?.triggerMode == TriggerMode.SEMI, "Automatic backup pistol")
                    helper.assertTrue(Ports.guns.reserve(npc.sidearmWeapon) == SidearmKit.reserveRounds(npc.sidearmWeapon), "Wrong spare-magazine budget")
                }
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 900, batch = "sidearm-supply")
    fun emptyPrimaryWithPistolStillWalksToActualSupply(helper: GameTestHelper) {
        floor(helper, 160, 128)
        val supply = BlockPos(5, 1, 64)
        helper.setBlock(supply, ModBlocks.SUPPLY.get())
        (helper.getBlockEntity(supply) as SupplyBlockEntity).faction = SquadFaction.PIG
        val npc = AttackDeploymentGameTests.spawn(helper, Vec3(25.5, 1.0, 64.5), SquadFaction.PIG)
        val origin = npc.position()
        GunData.from(npc.mainHandItem).ammo.set(0)
        GunData.from(npc.mainHandItem).virtualAmmo.set(0)
        Ports.guns.persist(npc.mainHandItem)
        val enemy = AttackDeploymentGameTests.spawn(helper, Vec3(80.5, 1.0, 64.5), SquadFaction.COW).apply { isNoAi = true }
        var drewPistol = false
        cleanup(helper) {
            npc.discard()
            enemy.discard()
            helper.setBlock(supply, Blocks.AIR)
        }
        helper.onEachTick {
            if (npc.usingSidearm) {
                drewPistol = true
                if (npc.ammoFraction() == 0.0) helper.assertTrue(npc.wantsResupply(), "Pistol canceled primary Supply request")
            }
            if (drewPistol && !npc.usingSidearm && npc.ammoFraction() >= 0.8) {
                helper.assertTrue(npc.position().distanceTo(origin) >= 10.0, "No physical Supply trip occurred")
                helper.assertTrue(npc.position().distanceTo(helper.absolutePos(supply).center) <= SupplyBlockEntity.RADIUS, "Refilled outside Supply reach")
                helper.succeed()
            }
        }
    }

    private fun floor(helper: GameTestHelper, width: Int, depth: Int) {
        for (x in 0 until width) for (z in 0 until depth) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 40, batch = "posture-shot-safety")
    fun crouchedAllyCannotOpenUnsafeStandingHeadLane(helper: GameTestHelper) {
        floor(helper, 24, 12)
        val shooter = AttackDeploymentGameTests.spawn(helper, Vec3(4.5, 1.0, 4.5), SquadFaction.PIG).apply { isNoAi = true }
        val ally = AttackDeploymentGameTests.spawn(helper, Vec3(4.59, 1.0, 5.29), SquadFaction.PIG).apply { isNoAi = true }
        cleanup(helper) {
            shooter.discard()
            ally.discard()
        }
        helper.runAtTickTime(10) {
            shooter.pose = net.minecraft.world.entity.Pose.STANDING
            ally.pose = net.minecraft.world.entity.Pose.CROUCHING
            val from = shooter.eyePosition
            val aim = from.add(58.0, 0.0, 30.0)
            helper.assertTrue(ally.boundingBox.clip(from, aim).isEmpty, "Control ray should pass above the current crouched body")
            helper.assertTrue(!FriendlyFireGuard.hasClearLineOfFire(shooter, aim, shooter.spread), "Guard authorized a shot through an ally's next standing head position")
            val crouchedHeight = ally.boundingBox.ysize
            ally.pose = net.minecraft.world.entity.Pose.STANDING
            helper.assertTrue(ally.boundingBox.ysize > crouchedHeight, "Control ally did not change its physical posture")
            helper.assertTrue(!FriendlyFireGuard.hasClearLineOfFire(shooter, aim, shooter.spread), "Standing posture opened an unsafe spread envelope")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 600, batch = "sidearm-empty")
    fun emptyKitHoldsWithoutSupplyOrEndlessWithdrawal(helper: GameTestHelper) {
        floor(helper, 160, 128)
        val npc = AttackDeploymentGameTests.spawn(helper, Vec3(65.5, 1.0, 64.5), SquadFaction.PIG)
        val origin = npc.position()
        for (stack in listOf(npc.mainHandItem, npc.sidearmWeapon)) {
            GunData.from(stack).ammo.set(0)
            GunData.from(stack).virtualAmmo.set(0)
            Ports.guns.persist(stack)
        }
        val enemy = AttackDeploymentGameTests.spawn(helper, Vec3(110.5, 1.0, 64.5), SquadFaction.COW).apply { isNoAi = true }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(npc.uuid))
        manager.setObjective(helper.level, squad.id, npc.blockPosition())
        DebugFlags.enabledGroups = setOf(LogGroup.ORDER, LogGroup.AMMO)
        val trace = StringBuilder("tick,distance,target,done,speed,cover,hasAmmo\n")
        var stoppedSince: Long? = null
        cleanup(helper) {
            manager.deleteSquad(helper.level, squad.id)
            npc.discard()
            enemy.discard()
            val report = Path.of(System.getProperty("sbwnpc.gameTestReports"), "empty-kit.csv")
            Files.createDirectories(report.parent)
            Files.writeString(report, trace)
        }
        helper.onEachTick {
            trace.append("${helper.tick},${npc.position().distanceTo(origin)},${npc.target != null},${npc.navigation.isDone},${npc.deltaMovement.horizontalDistance()},${npc.movementLockedByCover()},${Ports.guns.inHand(npc)?.hasAmmo()}\n")
            helper.assertTrue(npc.nearestSupply() == null, "Empty-kit control unexpectedly has Supply")
            helper.assertTrue(Ports.guns.inHand(npc)?.hasAmmo() == false, "Empty-kit control can still fire")
            helper.assertTrue(!SidearmKit.hasSmallArmsAmmo(npc), "Empty kit regenerated ammunition: primary=${Ports.guns.roundsLeft(npc.primaryGun())} pistol=${Ports.guns.roundsLeft(npc.sidearmWeapon)} supply=${npc.nearestSupply()}")
            helper.assertTrue(npc.position().subtract(origin).horizontalDistance() <= AmmoRetreat.RADIUS + 0.75, "Empty NPC withdrew without a distance limit")
            if (helper.tick >= 300 && npc.target != null && npc.onGround() && npc.navigation.isDone && npc.deltaMovement.horizontalDistance() < 0.01) {
                if (stoppedSince == null) stoppedSince = helper.tick
                if (helper.tick - stoppedSince!! >= 100) helper.succeed()
            } else {
                stoppedSince = null
            }
        }
    }

    private fun cleanup(helper: GameTestHelper, action: () -> Unit) {
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = action()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = action()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = action()
        })
    }
}
