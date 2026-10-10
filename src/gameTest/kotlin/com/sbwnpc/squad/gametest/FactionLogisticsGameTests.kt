package com.sbwnpc.squad.gametest

import com.atsuishio.superbwarfare.data.gun.GunData
import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.ServerLifecycle
import com.sbwnpc.squad.block.SupplyBlock
import com.sbwnpc.squad.block.entity.BarracksBlockEntity
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.block.entity.SupplyPoints
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModBlocks
import com.sbwnpc.squad.init.ModItems
import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.network.*
import com.sbwnpc.squad.npc.*
import com.sbwnpc.squad.squad.*
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.gametest.framework.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier

@PrefixGameTestTemplate(false)
object FactionLogisticsGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 40, batch = "faction-recruitment")
    fun survivalRecruitmentRejectsForeignConfigAndOfflineBarracksMode(helper: GameTestHelper) {
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
            val slot = own.recruitmentRoster!!.slots.single()
            helper.assertTrue(manager.recruitAtBarracks(helper.level, ref, BarracksRecruitmentQueue.Request(own.id, slot.id, false)) == null, "Own-faction recruitment was blocked")
            (helper.level.getEntity(own.members.single()) as NpcEntity).isNoAi = true

            player.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
            ServerLifecycle.onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent(player))
            configureTool(player, foreign)
            helper.assertTrue(SquadToolItem.readConfig(tool)!!.faction == SquadFaction.COW, "Creative lost free faction choice")
            handle("onConfigureBarracks", ConfigureBarracksPayload(pos, SquadToolItem.configTag(foreign)), player)
            val enemy = manager.squadsAtBarracks(ref).single()
            helper.assertTrue(enemy.faction == SquadFaction.COW, "Creative barracks did not keep the chosen faction")
            val vacancy = enemy.recruitmentRoster!!.slots.single()
            val request = BarracksRecruitmentQueue.Request(enemy.id, vacancy.id, false)

            player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL)
            ServerLifecycle.onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent(player))
            helper.assertTrue(manager.recruitAtBarracks(helper.level, ref, request) != null && enemy.members.isEmpty(), "Foreign creative queue kept recruiting after survival logout")
            val saved = registry.save(CompoundTag(), helper.level.registryAccess())
            helper.assertTrue(reloadRegistry(saved, helper).recruitmentProblem(helper.level.server, player.uuid, SquadFaction.COW) != null, "Survival restriction was lost on registry reload")

            player.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
            ServerLifecycle.onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent(player))
            val creativeSave = registry.save(CompoundTag(), helper.level.registryAccess())
            helper.assertTrue(reloadRegistry(creativeSave, helper).recruitmentProblem(helper.level.server, player.uuid, SquadFaction.COW) == null, "Creative offline production was lost on reload")
            helper.assertTrue(manager.recruitAtBarracks(helper.level, ref, request) == null, "Creative foreign recruitment could not resume")
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
            val reloaded = SupplyBlockEntity(pos, supply.blockState).apply { loadWithComponents(saved, helper.level.registryAccess()) }
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
        val context = Proxy.newProxyInstance(IPayloadContext::class.java.classLoader, arrayOf(IPayloadContext::class.java)) { _, method, args ->
            when (method.name) {
                "player" -> player

                "enqueueWork" -> {
                    when (val task = args!!.single()) {
                        is Runnable -> {
                            task.run()
                            CompletableFuture.completedFuture(null)
                        }

                        is Supplier<*> -> CompletableFuture.completedFuture(task.get())

                        else -> error("Unexpected server work: $task")
                    }
                }

                else -> error("Unexpected payload context method: ${method.name}")
            }
        } as IPayloadContext
        val method = ModNetwork::class.java.getDeclaredMethod(name, payload.javaClass, IPayloadContext::class.java).apply { isAccessible = true }
        try {
            method.invoke(ModNetwork, payload, context)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    private fun reloadRegistry(tag: CompoundTag, helper: GameTestHelper): PlayerFactionRegistry {
        val method = PlayerFactionRegistry.Companion::class.java.getDeclaredMethod("load", CompoundTag::class.java, HolderLookup.Provider::class.java).apply { isAccessible = true }
        return method.invoke(PlayerFactionRegistry.Companion, tag, helper.level.registryAccess()) as PlayerFactionRegistry
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
