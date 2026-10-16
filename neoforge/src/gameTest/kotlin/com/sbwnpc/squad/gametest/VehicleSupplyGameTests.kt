package com.sbwnpc.squad.gametest

import com.atsuishio.superbwarfare.data.gun.Ammo
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.tools.InventoryTool
import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.combat.SidearmKit
import com.sbwnpc.squad.combat.VehicleAmmoSupply
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.npc.HelicopterModel
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.TankModel
import com.sbwnpc.squad.npc.TransportVehicle
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@PrefixGameTestTemplate(false)
object VehicleSupplyGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 40, batch = "vehicle-cargo")
    fun vehicleCargoIsAccessibleFiniteAndFactionBound(helper: GameTestHelper) {
        for (x in 0..159) for (z in 0..127) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val vehicles = (TankModel.entries + TransportVehicle.entries + HelicopterModel.entries).mapIndexed { index, model ->
            (Ports.vehicles.create(helper.level, model) as VehicleEntity).also {
                val point = helper.absoluteVec(Vec3(10.5 + index * 16.0, 1.0, 64.5))
                it.moveTo(point.x, point.y, point.z)
                helper.level.addFreshEntity(it)
                Ports.vehicles.fuelAndArm(it, model)
                SquadTeams.assign(it, SquadFaction.PIG)
            }
        }
        val npc = AttackDeploymentGameTests.spawn(helper, Vec3(10.5, 1.0, 60.5), SquadFaction.PIG).apply { isNoAi = true }
        val player = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "vehicle-cargo-player"))
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                npc.discard()
                vehicles.forEach(Entity::discard)
                player.inventory.clearContent()
            }
        })
        helper.runAtTickTime(5) {
            for (vehicle in vehicles) {
                helper.assertTrue(count(vehicle, ModItems.AMMO_BOX.get()) == 0, "${vehicle.type} contains universal ammo box")
                helper.assertTrue((0 until vehicle.getContainerSize()).none { vehicle.getItem(it).item in setOf(ModItems.RIFLE_AMMO.get(), ModItems.HANDGUN_AMMO.get(), ModItems.SNIPER_AMMO.get(), ModItems.SHOTGUN_AMMO.get()) }, "${vehicle.type} contains loose small arms ammunition")
                helper.assertTrue(count(vehicle, ModItems.RIFLE_AMMO_BOX.get()) == 35 && rounds(vehicle, Ammo.RIFLE) == 1050, "${vehicle.type} wrong rifle box load")
                helper.assertTrue(count(vehicle, ModItems.HANDGUN_AMMO_BOX.get()) == 9 && rounds(vehicle, Ammo.HANDGUN) == 270, "${vehicle.type} wrong handgun box load")
                helper.assertTrue(count(vehicle, ModItems.SNIPER_AMMO_BOX.get()) == 22 && rounds(vehicle, Ammo.SNIPER) == 264, "${vehicle.type} wrong sniper box load")
                helper.assertTrue(count(vehicle, ModItems.SHOTGUN_AMMO_BOX.get()) == 11 && rounds(vehicle, Ammo.SHOTGUN) == 132, "${vehicle.type} wrong shotgun box load")
            }
            val cargo = vehicles.last()
            player.inventory.clearContent()
            val menu = cargo.createMenu(1, player.inventory, player)!!
            val slot = menu.slots.indexOfFirst { it.item.item == ModItems.RIFLE_AMMO_BOX.get() }
            helper.assertTrue(slot >= 0, "Native vehicle menu cannot see rifle boxes")
            menu.quickMoveStack(player, slot)
            helper.assertTrue(player.inventory.countItem(ModItems.RIFLE_AMMO_BOX.get()) == 35, "Player cannot retrieve rifle boxes")
            helper.assertTrue(rounds(cargo, Ammo.RIFLE) == 0, "Player retrieval duplicated ammunition")
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(ModItems.RIFLE_AMMO_BOX.get()))
            val before = Ammo.RIFLE.get(player)
            val handgunBefore = Ammo.HANDGUN.get(player)
            ModItems.RIFLE_AMMO_BOX.get().use(helper.level, player, InteractionHand.MAIN_HAND)
            helper.assertTrue(Ammo.RIFLE.get(player) - before == 30, "One box did not give the player exactly 30 rifle rounds")
            helper.assertTrue(Ammo.HANDGUN.get(player) == handgunBefore, "Rifle box supplied unrelated ammunition")
            for (index in 0 until cargo.getContainerSize()) {
                if (cargo.getItem(index).`is`(ModItems.HANDGUN_AMMO_BOX.get())) cargo.setItem(index, ItemStack.EMPTY)
            }
            val empty = (0 until cargo.getContainerSize()).first { cargo.getItem(it).isEmpty }
            cargo.setItem(empty, ItemStack(ModItems.RIFLE_AMMO_BOX.get(), 2))
            val gun = Ports.guns.issue(ResourceLocation.fromNamespaceAndPath("superbwarfare", "ak_47"), npc, 0)
            npc.setItemInHand(InteractionHand.MAIN_HAND, gun)
            npc.moveTo(cargo.x, cargo.y, cargo.z + 3.0)
            SquadTeams.assign(cargo, SquadFaction.COW)
            helper.assertTrue(VehicleAmmoSupply.replenish(npc) == 0, "NPC stole hostile cargo")
            helper.assertTrue(rounds(cargo, Ammo.RIFLE) == 60, "Hostile cargo changed")
            SquadTeams.assign(cargo, SquadFaction.PIG)
            helper.assertTrue(VehicleAmmoSupply.replenish(npc) == 60, "NPC did not take the finite boxes")
            helper.assertTrue(Ports.guns.reserve(npc.primaryGun().copy()) == 60, "NPC rounds were not persisted")
            helper.assertTrue(rounds(cargo, Ammo.RIFLE) == 0, "NPC supply did not consume cargo")
            helper.assertTrue(VehicleAmmoSupply.replenish(npc) == 0, "Empty vehicle created ammunition")
            npc.setItemInHand(InteractionHand.MAIN_HAND, Ports.guns.issue(ResourceLocation.fromNamespaceAndPath("superbwarfare", "ak_47"), npc, 0))
            SidearmKit.update(npc)
            helper.assertTrue(npc.usingSidearm, "Empty primary did not draw the backup")
            cargo.setItem(empty, ItemStack(ModItems.RIFLE_AMMO_BOX.get(), 3))
            helper.assertTrue(npc.startRiding(cargo, false), "NPC cannot board its supply vehicle")
            helper.assertTrue(VehicleAmmoSupply.replenish(npc) == 60, "Passenger did not receive two rifle magazines")
            helper.assertTrue(!npc.usingSidearm && Ports.guns.reserve(npc.primaryGun()) == 60, "Vehicle supply did not restore the primary")
            helper.assertTrue(rounds(cargo, Ammo.RIFLE) == 30, "Passenger supply spent the wrong amount")
            helper.assertTrue(count(cargo, ModItems.SMALL_SHELL_HE.get()) == 256, "Infantry consumed the helicopter's cannon shells")
            helper.assertTrue(InventoryTool.consumeAmmoItem(cargo, Ammo.RIFLE, 3) == 30, "Native vehicle cannot consume boxed rounds")
            helper.assertTrue(rounds(cargo, Ammo.RIFLE) == 0, "Native vehicle consumption was not finite")
            val types = listOf(Ammo.RIFLE to "ak_47", Ammo.HANDGUN to "glock_17", Ammo.SNIPER to "awm", Ammo.SHOTGUN to "m_870")
            for ((type, weapon) in types) {
                val source = vehicles.first()
                val beforeRounds = rounds(source, type)
                val testGun = Ports.guns.issue(ResourceLocation.fromNamespaceAndPath("superbwarfare", weapon), npc, 0)
                val expected = if (type == Ammo.RIFLE || type == Ammo.HANDGUN) 30 else 12
                val transferred = Ports.vehicles.takeSmallArmsAmmo(source, testGun, 1)
                helper.assertTrue(transferred == expected && beforeRounds - rounds(source, type) == transferred, "$type box lost its remainder or transferred the wrong number of rounds")
            }
            helper.assertTrue(rounds(vehicles.first(), Ammo.HEAVY) == 512, "NPC changed doubled heavy ammunition")
            val legacy = ItemStack(ModItems.AMMO_BOX.get())
            Ammo.RIFLE.set(legacy, 7)
            cargo.setItem(empty, legacy)
            helper.assertTrue(Ports.vehicles.takeSmallArmsAmmo(cargo, gun, 7) == 7, "Existing universal box no longer works")
            cargo.setItem(empty, ItemStack(ModItems.RIFLE_AMMO.get(), 5))
            helper.assertTrue(Ports.vehicles.takeSmallArmsAmmo(cargo, gun, 5) == 5, "Existing loose cargo no longer works")
            helper.succeed()
        }
    }

    private fun rounds(vehicle: VehicleEntity, type: Ammo) = InventoryTool.countAmmoItem(vehicle, type)

    private fun count(vehicle: VehicleEntity, item: Item) = (0 until vehicle.getContainerSize()).sumOf { slot -> vehicle.getItem(slot).let { if (it.item == item) it.count else 0 } }
}
