package com.sbwnpc.squad.gametest

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
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
                helper.assertTrue(count(vehicle, ModItems.RIFLE_AMMO.get()) == 512, "${vehicle.type} missing rifle cargo")
                helper.assertTrue(count(vehicle, ModItems.HANDGUN_AMMO.get()) == 128, "${vehicle.type} missing handgun cargo")
                helper.assertTrue(count(vehicle, ModItems.SNIPER_AMMO.get()) == 128, "${vehicle.type} missing sniper cargo")
                helper.assertTrue(count(vehicle, ModItems.SHOTGUN_AMMO.get()) == 64, "${vehicle.type} missing shotgun cargo")
            }
            val cargo = vehicles.last()
            player.inventory.clearContent()
            val menu = cargo.createMenu(1, player.inventory, player)!!
            val slot = menu.slots.indexOfFirst { it.item.item == ModItems.RIFLE_AMMO.get() }
            helper.assertTrue(slot >= 0, "Native vehicle menu cannot see cargo")
            menu.quickMoveStack(player, slot)
            helper.assertTrue(player.inventory.countItem(ModItems.RIFLE_AMMO.get()) == 64, "Player cannot retrieve native cargo")
            helper.assertTrue(count(cargo, ModItems.RIFLE_AMMO.get()) == 448, "Player retrieval did not consume cargo")
            for (index in 0 until cargo.getContainerSize()) {
                if (cargo.getItem(index).item == ModItems.RIFLE_AMMO.get()) cargo.setItem(index, ItemStack.EMPTY)
            }
            val empty = (0 until cargo.getContainerSize()).first { cargo.getItem(it).isEmpty }
            cargo.setItem(empty, ItemStack(ModItems.RIFLE_AMMO.get(), 7))
            val gun = Ports.guns.issue(ResourceLocation.fromNamespaceAndPath("superbwarfare", "ak_47"), npc, 0)
            npc.setItemInHand(InteractionHand.MAIN_HAND, gun)
            npc.moveTo(cargo.x, cargo.y, cargo.z + 3.0)
            SquadTeams.assign(cargo, SquadFaction.COW)
            helper.assertTrue(VehicleAmmoSupply.replenish(npc) == 0, "NPC stole hostile cargo")
            helper.assertTrue(count(cargo, ModItems.RIFLE_AMMO.get()) == 7, "Hostile cargo changed")
            SquadTeams.assign(cargo, SquadFaction.PIG)
            helper.assertTrue(VehicleAmmoSupply.replenish(npc) == 7, "NPC did not take the finite remainder")
            helper.assertTrue(Ports.guns.reserve(npc.primaryGun().copy()) == 7, "NPC rounds were not persisted")
            helper.assertTrue(count(cargo, ModItems.RIFLE_AMMO.get()) == 0, "NPC supply did not consume cargo")
            helper.assertTrue(VehicleAmmoSupply.replenish(npc) == 0, "Empty vehicle created ammunition")
            npc.setItemInHand(InteractionHand.MAIN_HAND, Ports.guns.issue(ResourceLocation.fromNamespaceAndPath("superbwarfare", "ak_47"), npc, 0))
            SidearmKit.update(npc)
            helper.assertTrue(npc.usingSidearm, "Empty primary did not draw the backup")
            cargo.setItem(empty, ItemStack(ModItems.RIFLE_AMMO.get(), 64))
            helper.assertTrue(npc.startRiding(cargo, false), "NPC cannot board its supply vehicle")
            helper.assertTrue(VehicleAmmoSupply.replenish(npc) == 60, "Passenger did not receive two rifle magazines")
            helper.assertTrue(!npc.usingSidearm && Ports.guns.reserve(npc.primaryGun()) == 60, "Vehicle supply did not restore the primary")
            helper.assertTrue(count(cargo, ModItems.RIFLE_AMMO.get()) == 4, "Passenger supply spent the wrong amount")
            helper.assertTrue(count(cargo, ModItems.SMALL_SHELL_HE.get()) == 256, "Infantry consumed the helicopter's cannon shells")
            helper.succeed()
        }
    }

    private fun count(vehicle: VehicleEntity, item: Item) = (0 until vehicle.getContainerSize()).sumOf { slot -> vehicle.getItem(slot).let { if (it.item == item) it.count else 0 } }
}
