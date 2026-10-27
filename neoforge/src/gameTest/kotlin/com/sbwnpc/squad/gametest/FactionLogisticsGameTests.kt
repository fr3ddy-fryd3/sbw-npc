package com.sbwnpc.squad.gametest

import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.init.ModAttachments
import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.ServerLifecycle
import com.sbwnpc.squad.block.SupplyBlock
import com.sbwnpc.squad.block.SupplySpawns
import com.sbwnpc.squad.block.entity.BarracksBlockEntity
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.block.entity.SupplyPoints
import com.sbwnpc.squad.config.SquadConfig
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModBlockEntities
import com.sbwnpc.squad.init.ModBlocks
import com.sbwnpc.squad.init.ModItems
import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.network.*
import com.sbwnpc.squad.npc.*
import com.sbwnpc.squad.squad.*
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.portal.DimensionTransition
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.lang.reflect.InvocationTargetException
import java.util.UUID

@PrefixGameTestTemplate(false)
object FactionLogisticsGameTests {
    /**
     * The squad items must be obtainable the way a player looks for them: in SuperbWarfare's *Items*
     * creative tab, which is also what a recipe viewer such as JEI lists. The addon ships no JEI plugin
     * of its own; JEI shows every item the creative tabs contain.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 20, batch = "item-availability")
    fun squadItemsAreRegisteredAndListedInSbwItemsTab(helper: GameTestHelper) {
        val squadItems = mapOf("squad_tool" to ModItems.SQUAD_TOOL.get(), "barracks" to ModItems.BARRACKS.get(), "supply" to ModItems.SUPPLY.get())
        squadItems.forEach { (path, item) ->
            helper.assertTrue(BuiltInRegistries.ITEM.getKey(item) == ResourceLocation.fromNamespaceAndPath("sbwnpc", path), "$path is not registered as sbwnpc:$path")
        }

        CreativeModeTabs.tryRebuildTabContents(helper.level.enabledFeatures(), true, helper.level.registryAccess())
        val tab = requireNotNull(BuiltInRegistries.CREATIVE_MODE_TAB.get(ResourceLocation.fromNamespaceAndPath("superbwarfare", "item"))) { "SuperbWarfare's Items tab is missing" }
        val listed = tab.displayItems.map { it.item }.toSet()
        squadItems.forEach { (path, item) ->
            helper.assertTrue(item in listed, "sbwnpc:$path is missing from SuperbWarfare's Items creative tab")
        }
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 40, batch = "player-backup-pistols")
    fun everyPlayerWeaponIssuesTheMappedPistolAndReserveWithoutDuplication(helper: GameTestHelper) {
        floor(helper)
        val player = player(helper, "player-backup-pistols")
        val mp443 = setOf("ak_47", "ak_12", "qbz_95", "rpk", "svd", "mp_5")
        helper.runAtTickTime(5) {
            Ports.playerSupply.kits.forEachIndexed { kitIndex, kit ->
                kit.options.forEachIndexed { optionIndex, option ->
                    player.inventory.clearContent()
                    val selected = ResourceLocation.fromNamespaceAndPath("superbwarfare", if (option.weapon.path in mp443) "mp_443" else "glock_17")
                    val item = BuiltInRegistries.ITEM.get(selected)
                    val pistol = GunData.from(ItemStack(item))
                    val consumer = pistol.selectedAmmoConsumer().also { if (!it.initialized()) it.init() }
                    val primary = GunData.from(ItemStack(BuiltInRegistries.ITEM.get(option.weapon)))
                    val primaryConsumer = primary.selectedAmmoConsumer().also { if (!it.initialized()) it.init() }
                    val variable = player.getData(ModAttachments.PLAYER_VARIABLE).watch()
                    consumer.playerAmmoType!!.set(variable, 0)
                    player.setData(ModAttachments.PLAYER_VARIABLE, variable)
                    Ports.playerSupply.issueKit(player, kitIndex, optionIndex, null)
                    helper.assertTrue(player.inventory.countItem(item) == 1, "${option.weapon} omitted mapped pistol $selected")
                    val issued = player.inventory.items.first { it.`is`(item) }
                    helper.assertTrue(GunData.from(issued).ammo.get() == 17, "${option.weapon} pistol $selected ammo=${GunData.from(issued).ammo.get()} capacity=${GunData.from(issued).get(GunProp.MAGAZINE)} components=${issued.components}")
                    val issuedPrimary = player.inventory.items.first { BuiltInRegistries.ITEM.getKey(it.item) == option.weapon }
                    val primaryData = GunData.from(issuedPrimary.copy())
                    helper.assertTrue(primaryData.ammo.get() == primaryData.get(GunProp.MAGAZINE), "${option.weapon} lost its loaded magazine when copied")
                    val displayed = option.items.filter { it.first == consumer.playerAmmoType!!.item.let(BuiltInRegistries.ITEM::getKey) }.sumOf { it.second }
                    val expected = if (primaryConsumer.playerAmmoType == consumer.playerAmmoType) displayed else 68
                    helper.assertTrue(expected >= 68 && consumer.playerAmmoType!!.get(player.getData(ModAttachments.PLAYER_VARIABLE)) == expected, "${option.weapon} pistol/shared reserve differs from its kit menu")
                    Ports.playerSupply.issueKit(player, kitIndex, optionIndex, null)
                    helper.assertTrue(player.inventory.countItem(item) == 1, "Repeated kit request duplicated the pistol")
                    helper.assertTrue(consumer.playerAmmoType!!.get(player.getData(ModAttachments.PLAYER_VARIABLE)) == expected, "Repeated kit request accumulated reserve")
                    consumer.playerAmmoType!!.set(variable, 0)
                    player.setData(ModAttachments.PLAYER_VARIABLE, variable)
                    val equipment = player.inventory.items.map { it.copy() }
                    Ports.playerSupply.issueAmmo(player, kitIndex)
                    helper.assertTrue(consumer.playerAmmoType!!.get(player.getData(ModAttachments.PLAYER_VARIABLE)) >= expected, "Ammo-only resupply omitted backup rounds")
                    helper.assertTrue(player.inventory.items.zip(equipment).all { (a, b) -> ItemStack.matches(a, b) }, "Ammo-only resupply altered equipment")
                }
            }
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 40, batch = "class-rations")
    fun everyPlayerClassKitIncludesRefillableRations(helper: GameTestHelper) {
        floor(helper)
        val player = player(helper, "class-rations")
        PlayerFactionRegistry.get(helper.level).set(player.uuid, SquadFaction.PIG)
        val pos = helper.absolutePos(BlockPos(8, 1, 6))
        helper.level.setBlockAndUpdate(pos, ModBlocks.SUPPLY.get().defaultBlockState())
        (helper.level.getBlockEntity(pos) as SupplyBlockEntity).faction = SquadFaction.PIG
        cleanup(helper) { helper.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()) }
        helper.runAtTickTime(5) {
            Ports.playerSupply.kits.forEachIndexed { index, kit ->
                player.inventory.clearContent()
                handle("onSupplyCmd", SupplyCmdPayload(pos, SupplyCmdPayload.TAKE_KIT, index, 0), player)
                helper.assertTrue(player.inventory.countItem(net.minecraft.world.item.Items.BREAD) == 8, "${kit.name} did not issue eight rations")
                helper.assertTrue(player.inventory.countItem(com.atsuishio.superbwarfare.init.ModItems.KNIFE.get()) == 1, "${kit.name} did not issue a knife")
                val pistol = com.sbwnpc.squad.combat.SidearmKit.playerWeapon(kit.options[0].weapon)
                val pistolSlot = player.inventory.items.indexOfFirst { net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it.item) == pistol }
                helper.assertTrue(player.inventory.getItem(pistolSlot + 1).`is`(com.atsuishio.superbwarfare.init.ModItems.KNIFE.get()), "${kit.name} knife is not immediately after the pistol")
                helper.assertTrue(kit.options.all { option -> option.items.any { it.first.path == "knife" && it.second == 1 } }, "${kit.name} menu omitted its knife")
                helper.assertTrue(kit.options.all { option -> option.items.any { it.first.path == "bread" && it.second == 8 } }, "${kit.name} menu omitted rations")
                handle("onSupplyCmd", SupplyCmdPayload(pos, SupplyCmdPayload.TAKE_KIT, index, 0), player)
                helper.assertTrue(player.inventory.countItem(net.minecraft.world.item.Items.BREAD) == 8, "Repeated kit request accumulated rations")
                helper.assertTrue(player.inventory.countItem(com.atsuishio.superbwarfare.init.ModItems.KNIFE.get()) == 1, "Repeated kit request duplicated the knife")
                player.inventory.items.first { it.`is`(net.minecraft.world.item.Items.BREAD) }.shrink(3)
                handle("onSupplyCmd", SupplyCmdPayload(pos, SupplyCmdPayload.RESUPPLY_AMMO, index, 0), player)
                helper.assertTrue(player.inventory.countItem(net.minecraft.world.item.Items.BREAD) == 5, "Ammo-only request refilled food")
                handle("onSupplyCmd", SupplyCmdPayload(pos, SupplyCmdPayload.TAKE_KIT, index, 0), player)
                helper.assertTrue(player.inventory.countItem(net.minecraft.world.item.Items.BREAD) == 8, "${kit.name} did not replace eaten rations")
            }
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 40, batch = "supply-class-ammo")
    fun supplyAmmoUsesSelectedClassWithoutIssuingOrDuplicatingEquipment(helper: GameTestHelper) {
        floor(helper)
        val player = player(helper, "class-ammo")
        PlayerFactionRegistry.get(helper.level).set(player.uuid, SquadFaction.PIG)
        val pos = helper.absolutePos(BlockPos(8, 1, 6))
        helper.level.setBlockAndUpdate(pos, ModBlocks.SUPPLY.get().defaultBlockState())
        val supply = helper.level.getBlockEntity(pos) as SupplyBlockEntity
        supply.faction = SquadFaction.PIG
        cleanup(helper) { helper.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()) }
        helper.runAtTickTime(5) {
            val kit = Ports.playerSupply.kits.indexOfFirst { it.name == "Sniper" }
            val guns = NpcClass.SNIPER.weaponPool.map {
                GunData.from(ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(it)))
            }
            val reserves = player.getData(com.atsuishio.superbwarfare.init.ModAttachments.PLAYER_VARIABLE).watch()
            guns.forEach { gun -> gun.selectedAmmoConsumer().also { if (!it.initialized()) it.init() }.playerAmmoType!!.set(reserves, 0) }
            player.setData(com.atsuishio.superbwarfare.init.ModAttachments.PLAYER_VARIABLE, reserves)
            handle("onSupplyCmd", SupplyCmdPayload(pos, SupplyCmdPayload.RESUPPLY_AMMO, kit, 0), player)
            // SVD and AWM share SBW's SniperAmmo store: use the larger class reserve, not their sum.
            val expectedByType = guns.withIndex().groupBy { it.value.selectedAmmoConsumer().playerAmmoType!! }
                .mapValues { (_, entries) ->
                    entries.maxOf { (index, gun) ->
                        NpcClass.SNIPER.startingRounds(NpcClass.SNIPER.weaponPool[index]) - gun.get(com.atsuishio.superbwarfare.data.gun.GunProp.MAGAZINE)
                    }
                }
            expectedByType.forEach { (type, expected) ->
                val actual = type.get(player.getData(com.atsuishio.superbwarfare.init.ModAttachments.PLAYER_VARIABLE))
                helper.assertTrue(actual == expected, "Selected class reserve incorrect: expected=$expected actual=$actual")
            }
            helper.assertTrue(player.inventory.isEmpty, "Ammo-only request issued weapons, armour or utility gear")
            helper.assertTrue(!Ports.playerSupply.issueAmmo(player, kit), "Repeated resupply accumulated ammunition")
            guns.forEach { gun -> gun.selectedAmmoConsumer().playerAmmoType!!.set(reserves, 0) }
            player.setData(com.atsuishio.superbwarfare.init.ModAttachments.PLAYER_VARIABLE, reserves)
            supply.faction = SquadFaction.COW
            handle("onSupplyCmd", SupplyCmdPayload(pos, SupplyCmdPayload.RESUPPLY_AMMO, kit, 0), player)
            helper.assertTrue(guns.all { it.selectedAmmoConsumer().playerAmmoType!!.get(player.getData(com.atsuishio.superbwarfare.init.ModAttachments.PLAYER_VARIABLE)) == 0 }, "Foreign Supply issued class ammunition in survival")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 1400, batch = "barracks-casualties")
    fun barracksDeploysWholeGarrisonAndOnlyQueuesCasualties(helper: GameTestHelper) {
        for (x in 0 until 40) for (z in 0 until 40) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        // A building occupies part of the preferred formation; recruits must find distinct alternatives.
        for (x in 18..22) for (z in 16..24) for (y in 1..8) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        val player = player(helper, "garrison-life")
        PlayerFactionRegistry.get(helper.level).set(player.uuid, SquadFaction.PIG)
        player.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
        val pos = helper.absolutePos(BlockPos(20, 1, 20))
        helper.level.setBlockAndUpdate(pos, ModBlocks.BARRACKS.get().defaultBlockState())
        val barracks = helper.level.getBlockEntity(pos) as BarracksBlockEntity
        barracks.owner = player.uuid
        val ref = BarracksRef(helper.level.dimension(), pos)
        val manager = SquadManager.get(helper.level)
        val cfg = SquadToolItem.Config(NpcClass.RIFLEMAN, NpcRank.RECRUIT, SquadFaction.COW, SquadPreset.THIRTY_TWO)
        var squad: Squad? = null
        var survivors = emptySet<UUID>()
        var dead = emptySet<UUID>()
        var firstReplacement = -1L
        var killedAt = -1L
        var veteran: NpcEntity? = null
        val interval = SquadConfig.recruitIntervalTicks()
        cleanup(helper) {
            manager.forOwner(player.uuid).toList().forEach { manager.deleteSquad(helper.level, it.id) }
            helper.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())
        }
        helper.runAtTickTime(5) {
            handle("onConfigureBarracks", ConfigureBarracksPayload(pos, SquadToolItem.configTag(cfg)), player)
            squad = manager.squadsAtBarracks(ref).single()
            val group = squad!!
            helper.assertTrue(group.members.size == 32, "Deploy queued or partially released the initial 32 NPCs")
            val deployed = group.members.map { helper.level.getEntity(it) as NpcEntity }
            deployed.forEach { it.isNoAi = true }
            helper.assertTrue(deployed.count { it.npcClass == NpcClass.SHOTGUNNER } == 2 && deployed.count { it.npcClass == NpcClass.MEDIC } == 4, "Barracks did not deploy the mixed company roster")
            helper.assertTrue(deployed.all { helper.level.noCollision(it.boundingBox) }, "Deploy placed a recruit in terrain")
            helper.assertTrue(deployed.withIndex().all { (index, npc) -> deployed.drop(index + 1).none { npc.boundingBox.intersects(it.boundingBox) } }, "Deploy overlapped recruits at fallback positions")
            helper.assertTrue(barracks.recruitmentSnapshot(helper.level).getList("Entries", net.minecraft.nbt.Tag.TAG_COMPOUND.toInt()).isEmpty(), "Initial group entered the replacement queue")
            dead = listOf(deployed.first { it.npcClass == NpcClass.SHOTGUNNER }.uuid, deployed.first { it.npcClass == NpcClass.RIFLEMAN }.uuid).toSet()
            survivors = group.members.toSet() - dead
            veteran = helper.level.getEntity(survivors.first()) as NpcEntity
            veteran!!.gainExperience(37.0, "test_existing_garrison")
            dead.forEach { (helper.level.getEntity(it) as NpcEntity).hurt(helper.level.damageSources().genericKill(), 1000f) }
            killedAt = helper.tick
            val snapshot = barracks.recruitmentSnapshot(helper.level)
            val entries = snapshot.getList("Entries", net.minecraft.nbt.Tag.TAG_COMPOUND.toInt())
            helper.assertTrue(entries.size == 2 && entries.all { (it as CompoundTag).getBoolean("Replacement") }, "Casualties did not create exactly two replacement requests")
            // Pressing Deploy again must preserve living veterans and the full casualty delay.
            handle("onConfigureBarracks", ConfigureBarracksPayload(pos, SquadToolItem.configTag(cfg)), player)
            helper.assertTrue(group.members.toSet() == survivors && barracks.recruitmentSnapshot(helper.level).getInt("RemainingTicks") == interval, "Repeated Deploy bypassed the replacement delay or replaced survivors")
            val saved = barracks.saveWithoutMetadata(helper.level.registryAccess())
            barracks.loadWithComponents(saved, helper.level.registryAccess())
            player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL)
            ServerLifecycle.onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent(player))
        }
        helper.onEachTick {
            val group = squad ?: return@onEachTick
            group.members.forEach { (helper.level.getEntity(it) as? NpcEntity)?.isNoAi = true }
            if (group.members.size > 30 && firstReplacement < 0) {
                firstReplacement = helper.tick
                helper.assertTrue(firstReplacement - killedAt >= interval - 1, "First casualty was replaced before a full interval")
                helper.assertTrue(group.members.size == 31, "Replacement queue released two NPCs together")
            }
            if (group.members.size == 32) {
                helper.assertTrue(helper.tick - firstReplacement >= interval - 1, "Second casualty skipped its interval")
                helper.assertTrue(group.members.containsAll(survivors) && group.members.none { it in dead }, "Replacement resurrected the dead or lost survivors")
                helper.assertTrue(veteran!!.experience == 37.0, "Deploy or replacement reset veteran experience")
                val recruits = group.members.filter { it !in survivors }.map { helper.level.getEntity(it) as NpcEntity }
                helper.assertTrue(recruits.map { it.npcClass }.toSet() == setOf(NpcClass.SHOTGUNNER, NpcClass.RIFLEMAN), "Casualty replacements lost the shotgunner's roster slot")
                helper.assertTrue(recruits.all { it.spawnFaction == SquadFaction.COW && it.experience == 0.0 && it.npcRank == NpcRank.RECRUIT }, "Automatic replacements changed faction or inherited the dead's XP")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 60, batch = "barracks-blocked")
    fun blockedGarrisonWaitsForCompleteSafeDeployment(helper: GameTestHelper) {
        for (x in 0 until 40) for (z in 0 until 40) for (y in 0..8) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        val player = player(helper, "garrison-blocked")
        PlayerFactionRegistry.get(helper.level).set(player.uuid, SquadFaction.PIG)
        val pos = helper.absolutePos(BlockPos(20, 1, 20))
        helper.level.setBlockAndUpdate(pos, ModBlocks.BARRACKS.get().defaultBlockState())
        val barracks = helper.level.getBlockEntity(pos) as BarracksBlockEntity
        barracks.owner = player.uuid
        val manager = SquadManager.get(helper.level)
        val ref = BarracksRef(helper.level.dimension(), pos)
        var squad: Squad? = null
        cleanup(helper) {
            manager.forOwner(player.uuid).toList().forEach { manager.deleteSquad(helper.level, it.id) }
            helper.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())
        }
        helper.runAtTickTime(5) {
            val cfg = SquadToolItem.Config(NpcClass.RIFLEMAN, NpcRank.RECRUIT, SquadFaction.PIG, SquadPreset.SIXTEEN)
            handle("onConfigureBarracks", ConfigureBarracksPayload(pos, SquadToolItem.configTag(cfg)), player)
            squad = manager.squadsAtBarracks(ref).single()
            val snapshot = barracks.recruitmentSnapshot(helper.level)
            helper.assertTrue(squad!!.members.isEmpty() && snapshot.getString("Reason").contains("safe space"), "Blocked Deploy created a partial squad or hid the reason")
            helper.assertTrue(snapshot.getList("Entries", net.minecraft.nbt.Tag.TAG_COMPOUND.toInt()).isEmpty(), "Blocked initial recruits entered the casualty queue")
            // Old saves queued initial slots. Loading them must finish Deploy without that timer.
            val legacyQueue = BarracksRecruitmentQueue()
            legacyQueue.synchronize(squad!!.recruitmentRoster!!.vacancies().map { BarracksRecruitmentQueue.Request(squad!!.id, it.id, false) }, 600)
            val saved = barracks.saveWithoutMetadata(helper.level.registryAccess())
            saved.put("RecruitmentQueue", legacyQueue.save())
            barracks.loadWithComponents(saved, helper.level.registryAccess())
            for (x in 0 until 40) {
                for (z in 0 until 40) {
                    for (y in 1..8) {
                        if (helper.absolutePos(BlockPos(x, y, z)) != pos) helper.setBlock(BlockPos(x, y, z), Blocks.AIR)
                    }
                }
            }
        }
        helper.onEachTick {
            val group = squad ?: return@onEachTick
            group.members.forEach { (helper.level.getEntity(it) as? NpcEntity)?.isNoAi = true }
            if (group.members.isNotEmpty()) {
                helper.assertTrue(group.members.size == 16, "Pending initial deploy released the squad in pieces")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 40, batch = "barracks-support")
    fun immediateGarrisonDeploysSupportOnceWithoutResettingCrew(helper: GameTestHelper) {
        floor(helper)
        val player = player(helper, "garrison-support")
        PlayerFactionRegistry.get(helper.level).set(player.uuid, SquadFaction.PIG)
        val pos = helper.absolutePos(BlockPos(10, 1, 6))
        helper.level.setBlockAndUpdate(pos, ModBlocks.BARRACKS.get().defaultBlockState())
        val barracks = helper.level.getBlockEntity(pos) as BarracksBlockEntity
        barracks.owner = player.uuid
        val manager = SquadManager.get(helper.level)
        val ref = BarracksRef(helper.level.dimension(), pos)
        fun tubes() = helper.level.getEntitiesOfClass(net.minecraft.world.entity.Entity::class.java, AABB(pos).inflate(12.0)).filter { Ports.mortars.isMortar(it) }
        var crew = emptySet<UUID>()
        var tube: UUID? = null
        cleanup(helper) {
            manager.forOwner(player.uuid).toList().forEach { manager.deleteSquad(helper.level, it.id) }
            tubes().forEach { it.discard() }
            helper.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())
        }
        helper.runAtTickTime(5) {
            val cfg = SquadToolItem.Config(NpcClass.MORTAR, NpcRank.RECRUIT, SquadFaction.PIG, SquadPreset.MORTAR_CREW)
            handle("onConfigureBarracks", ConfigureBarracksPayload(pos, SquadToolItem.configTag(cfg)), player)
            crew = manager.squadsAtBarracks(ref).single().members.toSet()
            helper.assertTrue(crew.size == 2 && tubes().size == 1, "Deploy did not release the complete mortar crew and its support immediately")
            crew.forEach { (helper.level.getEntity(it) as NpcEntity).isNoAi = true }
            tube = tubes().single().uuid
            handle("onConfigureBarracks", ConfigureBarracksPayload(pos, SquadToolItem.configTag(cfg)), player)
        }
        helper.runAtTickTime(25) {
            helper.assertTrue(manager.squadsAtBarracks(ref).single().members.toSet() == crew, "Repeated Deploy replaced an existing crew")
            helper.assertTrue(tubes().map { it.uuid } == listOf(tube), "Repeated Deploy or refresh duplicated support")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 40, batch = "supply-respawn")
    fun supplyRespawnFindsNearbySpaceAndHonorsDestroyedOrReplacedPoints(helper: GameTestHelper) {
        floor(helper)
        val player = player(helper, "supply-respawn")
        PlayerFactionRegistry.get(helper.level).set(player.uuid, SquadFaction.PIG)
        val pos = helper.absolutePos(BlockPos(8, 1, 6))
        helper.level.setBlockAndUpdate(pos, ModBlocks.SUPPLY.get().defaultBlockState())
        val supply = helper.level.getBlockEntity(pos) as SupplyBlockEntity
        supply.faction = SquadFaction.PIG
        helper.level.setBlockAndUpdate(pos.above(2), Blocks.STONE.defaultBlockState())
        val spawns = SupplySpawns.get(helper.level.server)
        val original = DimensionTransition(helper.level, helper.absoluteVec(Vec3(2.5, 1.0, 2.5)), Vec3.ZERO, 0f, 0f, DimensionTransition.DO_NOTHING)
        fun respawn(fromEnd: Boolean = false): PlayerRespawnPositionEvent = PlayerRespawnPositionEvent(player, original, fromEnd).also { NeoForge.EVENT_BUS.post(it) }
        cleanup(helper) {
            spawns.clear(player.uuid)
            helper.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())
        }
        helper.runAtTickTime(5) {
            spawns.set(player, helper.level, pos)
            repeat(2) {
                val event = respawn()
                val location = event.dimensionTransition.pos()
                helper.assertTrue(event.dimensionTransition !== original && location.distanceToSqr(pos.center) <= 20.0, "Obstructed Supply top rejected every nearby respawn position")
                helper.assertTrue(helper.level.noCollision(net.minecraft.world.entity.EntityType.PLAYER.dimensions.makeBoundingBox(location)), "Supply respawn placed the player inside terrain")
                helper.assertTrue(spawns.of(player.uuid)?.pos() == pos, "Respawn discarded a usable Supply selection")
            }
            helper.assertTrue(respawn(true).dimensionTransition === original, "Supply stole the End return destination")
            supply.faction = SquadFaction.COW
            helper.assertTrue(respawn().dimensionTransition === original, "Hostile Supply still accepted the player")
            supply.faction = SquadFaction.PIG
            for (x in -4..4) {
                for (z in -4..4) {
                    for (y in -1..4) {
                        if (pos.offset(x, y, z) != pos) helper.level.setBlockAndUpdate(pos.offset(x, y, z), Blocks.STONE.defaultBlockState())
                    }
                }
            }
            helper.assertTrue(respawn().dimensionTransition === original, "Sealed Supply respawned inside a wall")
            helper.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())
            helper.assertTrue(respawn().dimensionTransition === original && spawns.of(player.uuid) == null, "Destroyed Supply retained an invalid respawn selection")
            helper.level.setBlockAndUpdate(pos, ModBlocks.SUPPLY.get().defaultBlockState())
            spawns.set(player, helper.level, pos)
            player.setRespawnPosition(Level.OVERWORLD, helper.absolutePos(BlockPos(2, 1, 2)), 0f, true, false)
            helper.assertTrue(spawns.of(player.uuid) == null, "A later vanilla spawn point did not replace Supply")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 40, batch = "faction-recruitment")
    fun survivalRecruitmentRestrictsDeploymentButKeepsExistingGarrisons(helper: GameTestHelper) {
        floor(helper)
        val player = player(helper, "recruit-policy")
        val registry = PlayerFactionRegistry.get(helper.level)
        registry.set(player.uuid, SquadFaction.PIG)
        val manager = SquadManager.get(helper.level)
        val tool = ItemStack(ModItems.SQUAD_TOOL.get())
        player.setItemInHand(InteractionHand.MAIN_HAND, tool)
        val foreign = SquadToolItem.Config(NpcClass.RIFLEMAN, NpcRank.ELITE, SquadFaction.COW, SquadPreset.SINGLE)
        val pos = helper.absolutePos(BlockPos(16, 1, 6))
        helper.level.setBlockAndUpdate(pos, ModBlocks.BARRACKS.get().defaultBlockState())
        val barracks = helper.level.getBlockEntity(pos) as BarracksBlockEntity
        barracks.owner = player.uuid
        val ref = BarracksRef(helper.level.dimension(), pos)
        var loose: NpcEntity? = null
        cleanup(helper) {
            manager.forOwner(player.uuid).toList().forEach { manager.deleteSquad(helper.level, it.id) }
            loose?.discard()
            helper.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())
        }
        helper.runAtTickTime(5) {
            configureTool(player, foreign)
            helper.assertTrue(SquadToolItem.readConfig(tool)!!.faction == SquadFaction.PIG, "Survival accepted a foreign tool packet")
            // An old creative stack bypasses the configuration handler; useOn must check again.
            SquadToolItem.writeConfig(tool, foreign)
            val ground = helper.absolutePos(BlockPos(6, 0, 6))
            (tool.item as SquadToolItem).useOn(UseOnContext(player, InteractionHand.MAIN_HAND, hit(ground)))
            loose = helper.level.getEntitiesOfClass(NpcEntity::class.java, AABB(ground.above()).inflate(3.0)).single()
            loose!!.isNoAi = true
            helper.assertTrue(loose!!.spawnFaction == SquadFaction.PIG && loose!!.npcRank == NpcRank.RECRUIT, "Old creative tool deployed foreign troops in survival")

            handle("onConfigureBarracks", ConfigureBarracksPayload(pos, SquadToolItem.configTag(foreign)), player)
            val own = manager.squadsAtBarracks(ref).single()
            helper.assertTrue(own.faction == SquadFaction.PIG, "Survival accepted a foreign barracks packet")
            helper.assertTrue(own.members.size == 1, "Deploy did not release the initial garrison immediately")
            (helper.level.getEntity(own.members.single()) as NpcEntity).isNoAi = true

            player.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
            ServerLifecycle.onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent(player))
            configureTool(player, foreign)
            helper.assertTrue(SquadToolItem.readConfig(tool)!!.faction == SquadFaction.COW, "Creative lost free faction choice")
            handle("onConfigureBarracks", ConfigureBarracksPayload(pos, SquadToolItem.configTag(foreign)), player)
            val enemy = manager.squadsAtBarracks(ref).single()
            helper.assertTrue(enemy.faction == SquadFaction.COW, "Creative barracks did not keep the chosen faction")
            helper.assertTrue(enemy.members.size == 1, "Creative foreign Deploy did not release the garrison immediately")
            val soldier = helper.level.getEntity(enemy.members.single()) as NpcEntity
            soldier.isNoAi = true
            soldier.hurt(helper.level.damageSources().genericKill(), 1000f)
            val vacancy = enemy.recruitmentRoster!!.vacancies().single()
            val request = BarracksRecruitmentQueue.Request(enemy.id, vacancy.id, true)

            player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL)
            ServerLifecycle.onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent(player))
            helper.assertTrue(manager.recruitAtBarracks(helper.level, ref, request) == null, "Existing creative garrison stopped replacing casualties after survival logout")
            (helper.level.getEntity(enemy.members.single()) as NpcEntity).isNoAi = true
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 70, batch = "faction-supply")
    fun creativeSupplyChangesOwnerAndResuppliesOnlyServingFactions(helper: GameTestHelper) {
        floor(helper)
        val isolated = IsolatedSupplyPoints(helper.level)
        val player = player(helper, "supply-policy")
        val stack = ItemStack(ModItems.SUPPLY.get(), 2)
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        val ground = helper.absolutePos(BlockPos(8, 0, 6))
        val pos = ground.above()
        val secondGround = helper.absolutePos(BlockPos(17, 0, 6))
        var own: NpcEntity? = null
        var enemy: NpcEntity? = null
        cleanup(helper) {
            listOfNotNull(own, enemy).forEach { it.discard() }
            helper.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())
            helper.level.setBlockAndUpdate(secondGround.above(), Blocks.AIR.defaultBlockState())
            isolated.close()
        }
        helper.runAtTickTime(5) {
            stack.item.useOn(UseOnContext(player, InteractionHand.MAIN_HAND, hit(ground)))
            helper.assertTrue(helper.level.getBlockState(pos).isAir && stack.count == 2, "Factionless placement created a public Supply or consumed the item")
            PlayerFactionRegistry.get(helper.level).set(player.uuid, SquadFaction.PIG)
            stack.item.useOn(UseOnContext(player, InteractionHand.MAIN_HAND, hit(ground)))
            val supply = helper.level.getBlockEntity(pos) as SupplyBlockEntity
            helper.assertTrue(supply.faction == SquadFaction.PIG && stack.count == 1, "Survival placement did not assign its own faction")
            handle("onSupplyCmd", SupplyCmdPayload(pos, SupplyCmdPayload.SET_FACTION, SquadFaction.COW.ordinal, 0), player)
            helper.assertTrue(supply.faction == SquadFaction.PIG, "Survival changed Supply ownership")

            player.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
            val block = ModBlocks.SUPPLY.get() as SupplyBlock
            helper.assertTrue(block.getStateForPlacement(BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit(secondGround))) != null, "Creative placement was rejected")
            stack.item.useOn(UseOnContext(player, InteractionHand.MAIN_HAND, hit(secondGround)))
            helper.assertTrue((helper.level.getBlockEntity(secondGround.above()) as SupplyBlockEntity).faction == SquadFaction.PIG, "Creative placement inherited a foreign faction")
            player.moveTo(pos.x + 12.5, pos.y.toDouble(), pos.z + 0.5, 0f, 0f)
            handle("onSupplyCmd", SupplyCmdPayload(pos, SupplyCmdPayload.SET_FACTION, SquadFaction.COW.ordinal, 0), player)
            helper.assertTrue(supply.faction == SquadFaction.PIG, "Distant creative packet changed Supply ownership")
            player.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 2.5, 0f, 0f)
            handle("onSupplyCmd", SupplyCmdPayload(pos, SupplyCmdPayload.SET_FACTION, 999, 0), player)
            helper.assertTrue(supply.faction == SquadFaction.PIG, "Invalid ordinal changed Supply ownership")
            own = AttackDeploymentGameTests.spawn(helper, Vec3(6.5, 1.0, 6.5), SquadFaction.PIG).apply {
                isNoAi = true
                emptyPrimary(this)
            }
            enemy = AttackDeploymentGameTests.spawn(helper, Vec3(10.5, 1.0, 6.5), SquadFaction.COW).apply {
                isNoAi = true
                emptyPrimary(this)
            }
        }
        helper.runAtTickTime(30) {
            helper.assertTrue(Ports.guns.roundsLeft(own!!.mainHandItem) > 0 && Ports.guns.roundsLeft(enemy!!.mainHandItem) == 0, "Initial Supply did not distinguish serving and enemy NPCs")
            emptyPrimary(own!!)
            handle("onSupplyCmd", SupplyCmdPayload(pos, SupplyCmdPayload.SET_FACTION, SquadFaction.COW.ordinal, 0), player)
            val supply = helper.level.getBlockEntity(pos) as SupplyBlockEntity
            helper.assertTrue(supply.faction == SquadFaction.COW && !supply.serves(SquadFaction.PIG), "Creative ownership change did not update service eligibility")
            val points = SupplyPoints.get(helper.level)
            helper.assertTrue(points.nearestServing(pos.center, SquadFaction.COW, 2.0) == pos && points.nearestServing(pos.center, SquadFaction.PIG, 2.0) == null, "Supply index retained the old faction")
            val saved = supply.saveWithoutMetadata(helper.level.registryAccess())
            val reloaded = ModBlockEntities.SUPPLY.get().create(pos, supply.blockState)!!.apply { loadWithComponents(saved, helper.level.registryAccess()) }
            helper.assertTrue(reloaded.faction == SquadFaction.COW && reloaded.serves(SquadFaction.COW) && !reloaded.serves(SquadFaction.PIG), "Supply ownership did not survive saving")
            helper.assertTrue(SupplyBlock.snapshot(player, helper.level, pos, supply).getBoolean("CanChangeFaction"), "Creative lost access to the foreign Supply screen")
        }
        helper.runAtTickTime(55) {
            helper.assertTrue(Ports.guns.roundsLeft(enemy!!.mainHandItem) > 0 && Ports.guns.roundsLeft(own!!.mainHandItem) == 0, "Renamed Supply served its former side instead of its new owner")
            helper.succeed()
        }
    }

    private fun player(helper: GameTestHelper, name: String): ServerPlayer =
        FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), name)).apply {
            gameMode.changeGameModeForPlayer(GameType.SURVIVAL)
            val at = helper.absoluteVec(Vec3(3.5, 1.0, 6.5))
            moveTo(at.x, at.y, at.z, 0f, 0f)
        }

    private fun configureTool(player: ServerPlayer, cfg: SquadToolItem.Config) =
        handle(
            "onConfigureTool",
            ConfigureToolPayload(cfg.cls.ordinal, cfg.rank.ordinal, cfg.faction.ordinal, cfg.preset.ordinal, cfg.vehicle, cfg.vehicleModel.ordinal, cfg.tankModel.ordinal, cfg.heliModel.ordinal),
            player,
        )

    /** Execute the registered production handler on the test server thread; no replacement policy. */
    private fun handle(name: String, payload: CustomPacketPayload, player: ServerPlayer) {
        val context = object : PayloadContext {
            override fun player() = player
            override fun enqueueWork(task: Runnable) = task.run()
        }
        val method = ServerPayloadHandlers::class.java.getDeclaredMethod(name, payload.javaClass, PayloadContext::class.java).apply { isAccessible = true }
        try {
            method.invoke(ServerPayloadHandlers, payload, context)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    private fun emptyPrimary(npc: NpcEntity) {
        GunData.from(npc.mainHandItem).apply {
            ammo.set(0)
            virtualAmmo.set(0)
            save()
        }
    }
    private fun hit(pos: BlockPos) = BlockHitResult(pos.center.add(0.0, 0.5, 0.0), Direction.UP, pos, false)
    private fun floor(helper: GameTestHelper) {
        for (x in 0 until 24) for (z in 0 until 12) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
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
