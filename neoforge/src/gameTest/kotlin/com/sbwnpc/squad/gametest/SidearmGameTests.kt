package com.sbwnpc.squad.gametest

import com.atsuishio.superbwarfare.data.gun.GunData
import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.combat.AntiArmourKit
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.SidearmKit
import com.sbwnpc.squad.combat.WeaponReach
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.domain.port.TriggerMode
import com.sbwnpc.squad.entity.ai.AmmoRetreat
import com.sbwnpc.squad.init.ModBlocks
import com.sbwnpc.squad.init.ModEntities
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.ItemStack
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
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 600, batch = "primary-gun-variety")
    fun rifleVariantsAndShotgunFireReloadAndKeepTheirSavedWeapon(helper: GameTestHelper) {
        floor(helper, 160, 128)
        val weapons = NpcClass.RIFLEMAN.weaponPool + NpcClass.SHOTGUNNER.weaponPool
        val shooters = weapons.mapIndexed { index, weapon ->
            val role = if (weapon in NpcClass.SHOTGUNNER.weaponPool) NpcClass.SHOTGUNNER else NpcClass.RIFLEMAN
            AttackDeploymentGameTests.spawn(helper, Vec3(20.5, 1.0, 12.5 + index * 16), SquadFaction.PIG, role = role).apply {
                diggedIn = true
                setItemInHand(InteractionHand.MAIN_HAND, Ports.guns.issue(weapon, this, role.startingRounds(weapon)))
                helper.assertTrue(!mainHandItem.isEmpty, "Unregistered weapon: $weapon")
                ammunition(mainHandItem, 1, role.startingRounds(weapon) - 1)
                val saved = CompoundTag().also { saveWithoutId(it) }
                val restored = ModEntities.NPC.get().create(helper.level)!!.apply { load(saved) }
                helper.assertTrue(restored.npcClass == role && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(restored.primaryGun().item) == weapon, "Save/load replaced $weapon")
                helper.assertTrue(Ports.guns.roundsLeft(restored.primaryGun()) == role.startingRounds(weapon), "Save/load changed ammo for $weapon")
            }
        }
        val enemies = shooters.mapIndexed { index, _ ->
            AttackDeploymentGameTests.spawn(helper, Vec3(40.5, 1.0, 12.5 + index * 16), SquadFaction.COW).apply {
                isNoAi = true
                getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 50000.0
                health = maxHealth
            }
        }
        val witness = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "primary-observer")).apply {
            gameMode.changeGameModeForPlayer(GameType.CREATIVE)
            setPos(helper.absoluteVec(Vec3(30.5, 5.0, 5.5)))
            setNoGravity(true)
            helper.level.addNewPlayer(this)
        }
        val rounds = IntArray(shooters.size)
        val reserve = shooters.map { Ports.guns.reserve(it.primaryGun()) }
        val pellets = shooters.map { Ports.guns.inHand(it)!!.pellets }
        helper.assertTrue(pellets.last() == 12, "M870 must use SBW's twelve pellets")
        val listener = Consumer<EntityJoinLevelEvent> { event ->
            val round = event.entity as? Projectile ?: return@Consumer
            val index = shooters.indexOf(round.owner)
            if (event.level === helper.level && index >= 0 && Ports.guns.isRound(round)) rounds[index]++
        }
        val pacer = CombatTickPacer()
        NeoForge.EVENT_BUS.addListener(listener)
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
        cleanup(helper) {
            NeoForge.EVENT_BUS.unregister(listener)
            NeoForge.EVENT_BUS.unregister(pacer)
            (shooters + enemies).forEach { it.discard() }
            helper.level.removePlayerImmediately(witness, Entity.RemovalReason.DISCARDED)
            val report = Path.of(System.getProperty("sbwnpc.gameTestReports"), "primary-variants.txt")
            Files.createDirectories(report.parent)
            Files.writeString(report, weapons.indices.joinToString("\n") { "${weapons[it]} pellets=${pellets[it]} rounds=${rounds[it]} reserve=${reserve[it]}->${Ports.guns.reserve(shooters[it].primaryGun())}" })
        }
        helper.onEachTick {
            if (weapons.indices.all { rounds[it] >= 3 * pellets[it] }) {
                shooters.indices.forEach { index ->
                    helper.assertTrue(Ports.guns.reserve(shooters[index].primaryGun()) < reserve[index], "${weapons[index]} fired without reloading the single loaded round")
                    helper.assertTrue(!shooters[index].usingSidearm, "${weapons[index]} switched instead of reloading")
                }
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 800, batch = "shotgun-contact-approach")
    fun shotgunWaitsOutsideRangeThenAttacksInsideRifleClearance(helper: GameTestHelper) {
        floor(helper, 160, 128)
        val shooter = AttackDeploymentGameTests.spawn(helper, Vec3(20.5, 1.0, 60.5), SquadFaction.PIG, role = NpcClass.SHOTGUNNER)
        val enemy = AttackDeploymentGameTests.spawn(helper, Vec3(100.5, 1.0, 60.5), SquadFaction.COW).apply {
            isNoAi = true
            getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 50000.0
            health = maxHealth
        }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(shooter.uuid))
        manager.setOrder(squad.id, SquadOrder.DEFEND)
        manager.setObjective(helper.level, squad.id, shooter.blockPosition())
        manager.setFocus(squad.id, enemy.uuid)
        val witness = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "shotgun-observer")).apply {
            gameMode.changeGameModeForPlayer(GameType.CREATIVE)
            setPos(helper.absoluteVec(Vec3(30.5, 5.0, 5.5)))
            setNoGravity(true)
            helper.level.addNewPlayer(this)
        }
        var rounds = 0
        var attack = false
        var closeShots = 0
        val listener = Consumer<EntityJoinLevelEvent> { event ->
            val round = event.entity as? Projectile ?: return@Consumer
            if (event.level === helper.level && round.owner === shooter && Ports.guns.isRound(round)) {
                helper.assertTrue(WeaponReach.inRange(shooter, shooter.eyePosition, enemy.eyePosition), "Shotgun fired beyond effective range")
                rounds++
                if (shooter.distanceTo(enemy) < 42.0) closeShots++
            }
        }
        val pacer = CombatTickPacer()
        NeoForge.EVENT_BUS.addListener(listener)
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
        cleanup(helper) {
            NeoForge.EVENT_BUS.unregister(listener)
            NeoForge.EVENT_BUS.unregister(pacer)
            manager.deleteSquad(helper.level, squad.id)
            listOf(shooter, enemy).forEach { it.discard() }
            helper.level.removePlayerImmediately(witness, Entity.RemovalReason.DISCARDED)
            val report = Path.of(System.getProperty("sbwnpc.gameTestReports"), "shotgun-contact.txt")
            Files.createDirectories(report.parent)
            Files.writeString(report, "rounds=$rounds closePellets=$closeShots distance=${shooter.distanceTo(enemy)} attack=$attack")
        }
        helper.runAtTickTime(120) {
            helper.assertTrue(rounds == 0 && shooter.distanceTo(enemy) > 48.0, "Defending shotgunner chased or shot the remote contact")
            manager.setOrder(squad.id, SquadOrder.ATTACK)
            manager.setObjective(helper.level, squad.id, enemy.blockPosition())
            attack = true
        }
        helper.onEachTick {
            if (attack && closeShots >= 24) {
                helper.assertTrue(shooter.distanceTo(enemy) >= 15.0, "Shotgunner ran into its enemy")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 500, batch = "sniper-cadence")
    fun sniperPrimaryFiresAtHalfRateWithoutSlowingBackupPistol(helper: GameTestHelper) {
        floor(helper, 160, 128)
        val oldGroups = DebugFlags.enabledGroups
        DebugFlags.enabledGroups = setOf(LogGroup.ORDER, LogGroup.FIRE)
        val roles = listOf(NpcClass.RIFLEMAN, NpcClass.SNIPER, NpcClass.RIFLEMAN, NpcClass.SNIPER, NpcClass.RIFLEMAN, NpcClass.SNIPER, NpcClass.RIFLEMAN, NpcClass.SHOTGUNNER)
        val weapons = roles.indices.map {
            if (it >= 6) {
                "m_870"
            } else if (it >= 4) {
                "awm"
            } else {
                "svd"
            }
        }
        val shooters = roles.mapIndexed { index, role ->
            AttackDeploymentGameTests.spawn(helper, Vec3(20.5, 1.0, 16.5 + index * 12), SquadFaction.PIG, role = role).apply {
                diggedIn = true
                setItemInHand(InteractionHand.MAIN_HAND, Ports.guns.issue(ResourceLocation.fromNamespaceAndPath("superbwarfare", weapons[index]), this, 100))
                if (index in 2..3) ammunition(mainHandItem, 0, 0)
            }
        }
        val enemies = shooters.mapIndexed { index, _ ->
            AttackDeploymentGameTests.spawn(helper, Vec3(40.5, 1.0, 16.5 + index * 12), SquadFaction.COW).apply {
                isNoAi = true
                getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 50000.0
                health = maxHealth
            }
        }
        val witness = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "cadence-observer"))
        witness.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
        val at = helper.absoluteVec(Vec3(30.5, 5.0, 80.5))
        witness.moveTo(at.x, at.y, at.z, 0f, 0f)
        witness.setNoGravity(true)
        helper.level.addNewPlayer(witness)
        val shots = List(shooters.size) { mutableListOf<Long>() }
        val lastVolley = LongArray(shooters.size) { -1 }
        val listener = Consumer<EntityJoinLevelEvent> { event ->
            val projectile = event.entity as? Projectile ?: return@Consumer
            val index = shooters.indexOf(projectile.owner)
            if (event.level === helper.level && index >= 0 && Ports.guns.isRound(projectile) && lastVolley[index] != helper.tick) {
                lastVolley[index] = helper.tick // All twelve shotgun pellets are one shot.
                shots[index].add(System.nanoTime())
            }
        }
        val pacer = CombatTickPacer()
        NeoForge.EVENT_BUS.addListener(listener)
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
        cleanup(helper) {
            DebugFlags.enabledGroups = oldGroups
            NeoForge.EVENT_BUS.unregister(listener)
            NeoForge.EVENT_BUS.unregister(pacer)
            (shooters + enemies).forEach { it.discard() }
            helper.level.removePlayerImmediately(witness, Entity.RemovalReason.DISCARDED)
            val report = Path.of(System.getProperty("sbwnpc.gameTestReports"), "sniper-cadence.txt")
            Files.createDirectories(report.parent)
            Files.writeString(
                report,
                shots.mapIndexed { index, times ->
                    "${roles[index]} weapon=${weapons[index]} sidearm=${index in 2..3} npcTicks=${shooters[index].tickCount} target=${shooters[index].target?.uuid} intervals_ms=${times.zipWithNext { a, b -> (b - a) / 1_000_000.0 }}"
                }.joinToString("\n"),
            )
        }
        helper.onEachTick {
            if (shots.all { it.size >= 5 }) {
                val intervals = shots.map { times -> times.zipWithNext { a, b -> (b - a) / 1_000_000.0 }.sorted().let { it[it.size / 2] } }
                helper.assertTrue(intervals[1] / intervals[0] in 1.8..2.2, "Sniper primary cadence did not halve: $intervals")
                helper.assertTrue(intervals[3] / intervals[2] in 0.85..1.15, "Sniper class slowed the same backup pistol: $intervals")
                helper.assertTrue(intervals[5] / intervals[4] in 1.8..2.2, "Sniper bolt-action cadence did not halve: $intervals")
                helper.assertTrue(intervals[7] / intervals[6] in 1.8..2.2, "Shotgun pump cadence did not halve: $intervals")
                helper.assertTrue(shooters[2].usingSidearm && shooters[3].usingSidearm, "Pistol comparison was not using backup weapons")
                helper.succeed()
            }
        }
    }

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
                ammunition(primary, 0, 12)
                SidearmKit.update(npc)
                helper.assertTrue(!npc.usingSidearm && npc.mainHandItem === primary, "Ordinary reload was mistaken for exhaustion")
                ammunition(primary, 0, 0)
                SidearmKit.update(npc)
                helper.assertTrue(npc.usingSidearm && npc.primaryGun() === primary && npc.ammoFraction() == 0.0, "Pistol replaced primary ammo accounting")
                helper.assertTrue(Ports.guns.inHand(npc)?.triggerMode == TriggerMode.SEMI, "Backup pistol is automatic")
                ammunition(npc.mainHandItem, 1, 2)
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
    fun backupPistolsWaitForCloseTargetsWithoutAdvancingOrRefillingPrimary(helper: GameTestHelper) {
        floor(helper, 160, 128)
        val names = listOf("mp_443", "glock_17", "m_1911")
        val shooters = names.mapIndexed { index, name ->
            AttackDeploymentGameTests.spawn(helper, Vec3(20.5, 1.0, 40.5 + index * 16), SquadFaction.PIG).apply {
                diggedIn = true
                sidearmWeapon = Ports.guns.issue(ResourceLocation.fromNamespaceAndPath("superbwarfare", name), this, if (name == "m_1911") 70 else 85)
                ammunition(mainHandItem, 0, 0)
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
            if (helper.tick == 120L) {
                helper.assertTrue(rounds.all { it == 0 }, "Pistol fired at a target 60 blocks away: ${rounds.toList()}")
                enemies.forEachIndexed { index, enemy ->
                    val close = helper.absoluteVec(Vec3(40.5, 1.0, 40.5 + index * 16))
                    enemy.setPos(close)
                }
            }
            shooters.forEachIndexed { index, npc ->
                if (helper.tick > 5) helper.assertTrue(npc.usingSidearm && npc.ammoFraction() == 0.0, "Pistol made empty primary appear replenished")
                helper.assertTrue(npc.position().distanceTo(origins[index]) < 1.0, "Pistol forced support to leave its held position")
            }
            if (helper.tick > 120 && rounds.all { it >= 2 }) {
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
        ammunition(npc.mainHandItem, 0, 0)
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

    /** SBW uses weak GunData cache values; write fixture ammunition as one persisted transaction. */
    private fun ammunition(stack: ItemStack, loaded: Int, reserve: Int) {
        val data = GunData.from(stack)
        data.ammo.set(loaded)
        data.virtualAmmo.set(reserve)
        data.save()
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
            ammunition(stack, 0, 0)
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
