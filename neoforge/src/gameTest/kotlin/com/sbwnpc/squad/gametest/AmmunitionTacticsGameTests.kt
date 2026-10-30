package com.sbwnpc.squad.gametest

import com.atsuishio.superbwarfare.data.gun.GunData
import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.SidearmKit
import com.sbwnpc.squad.combat.tactics.DefensiveOverwatch
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.init.ModBlocks
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
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
object AmmunitionTacticsGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 600, batch = "pistol-defence")
    fun pistolDefenderWaitsInCoverThenFiresAtCloseContact(helper: GameTestHelper) = cover(helper, SquadOrder.DEFEND)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 600, batch = "pistol-patrol")
    fun pistolPatrolWaitsInCoverThenFiresAtCloseContact(helper: GameTestHelper) = cover(helper, SquadOrder.PATROL)

    private fun cover(helper: GameTestHelper, order: SquadOrder) {
        val supplyScope = IsolatedSupplyPoints(helper.level)
        floor(helper)
        for (z in 50..70) helper.setBlock(BlockPos(44, 1, z), Blocks.STONE)
        val npc = AttackDeploymentGameTests.spawn(helper, Vec3(40.5, 1.0, 60.5), SquadFaction.PIG)
        ammunition(npc.primaryGun(), 0, 0)
        val enemy = AttackDeploymentGameTests.spawn(helper, Vec3(100.5, 1.0, 60.5), SquadFaction.COW).apply {
            isNoAi = true
            getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 5000.0
            health = maxHealth
        }
        val origin = npc.position()
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(npc.uuid))
        manager.setOrder(squad.id, order)
        manager.setObjective(helper.level, squad.id, npc.blockPosition())
        val witness = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "pistol-cover-observer"))
        witness.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
        witness.setPos(helper.absoluteVec(Vec3(45.5, 5.0, 20.5)))
        witness.setNoGravity(true)
        helper.level.addNewPlayer(witness)
        var rounds = 0
        var tookCover = false
        var sawTarget = false
        val listener = Consumer<EntityJoinLevelEvent> { event ->
            val projectile = event.entity as? Projectile ?: return@Consumer
            if (event.level === helper.level && projectile.owner === npc && Ports.guns.isRound(projectile)) rounds++
        }
        val pacer = CombatTickPacer()
        NeoForge.EVENT_BUS.addListener(listener)
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
        cleanup(helper) {
            supplyScope.close()
            NeoForge.EVENT_BUS.unregister(listener)
            NeoForge.EVENT_BUS.unregister(pacer)
            manager.deleteSquad(helper.level, squad.id)
            npc.discard()
            enemy.discard()
            helper.level.removePlayerImmediately(witness, Entity.RemovalReason.DISCARDED)
            report("pistol-cover-$order.txt", "cover=$tookCover\ncontact=$sawTarget\nrounds=$rounds\ndisplacement=${npc.position().distanceTo(origin)}\n")
        }
        helper.onEachTick {
            // Noticing takes time (#114); this scene is about cover, so he keeps looking at the enemy.
            if (helper.tick <= 250L) npc.yHeadRot = -90f
            if (npc.target === enemy) sawTarget = true
            if (DefensiveOverwatch.nearbyCover(npc.position(), enemy.eyePosition) { from, to -> com.sbwnpc.squad.combat.Sightline.blocked(helper.level, from, to, npc) }) tookCover = true
            helper.assertTrue(npc.position().subtract(origin).horizontalDistance() <= 12.0, "Pistol user advanced toward a distant enemy instead of holding local shelter")
            if (helper.tick <= 250L) helper.assertTrue(rounds == 0, "Pistol fired outside close range")
            if (helper.tick <= 250L) helper.assertTrue(npc.nearestSupply() == null, "Pistol-cover control discovered a foreign Supply")
            if (helper.tick == 250L) {
                helper.assertTrue(tookCover && sawTarget, "No actual sheltered waiting/contact occurred: cover=$tookCover contact=$sawTarget")
                enemy.setPos(helper.absoluteVec(Vec3(65.5, 1.0, 60.5)))
            }
            if (helper.tick > 250 && rounds >= 2) helper.succeed()
        }
    }

    internal fun ammunition(stack: ItemStack, loaded: Int, reserve: Int) {
        val data = GunData.from(stack)
        data.ammo.set(loaded)
        data.virtualAmmo.set(reserve)
        data.save()
    }

    internal fun floor(helper: GameTestHelper) {
        for (x in 0..159) for (z in 0..127) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
    }

    private fun report(name: String, content: String) {
        val file = Path.of(System.getProperty("sbwnpc.gameTestReports"), name)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    internal fun cleanup(helper: GameTestHelper, action: () -> Unit) {
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = action()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = action()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = action()
        })
    }
}
