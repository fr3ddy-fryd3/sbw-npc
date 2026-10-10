package com.sbwnpc.squad.gametest

import com.atsuishio.superbwarfare.data.gun.GunData
import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.tactics.DefensiveOverwatch
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.init.ModBlocks
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
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 1600, batch = "ammo-withdrawal")
    fun halfEmptyAttackWithdrawsAndDoesNotRestartAfterSupply(helper: GameTestHelper) {
        floor(helper)
        val members = (0..3).map {
            AttackDeploymentGameTests.spawn(helper, Vec3(120.5, 1.0, 55.5 + it * 4), SquadFaction.PIG)
        }
        ammunition(members[0].primaryGun(), 0, 0)
        ammunition(members[0].sidearmWeapon, 0, 0)
        val enemy = AttackDeploymentGameTests.spawn(helper, Vec3(155.5, 1.0, 61.5), SquadFaction.COW).apply {
            isNoAi = true
            getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 10000.0
            health = maxHealth
        }
        val oldGroups = DebugFlags.enabledGroups
        DebugFlags.enabledGroups = oldGroups + setOf(LogGroup.ORDER, LogGroup.RETREAT, LogGroup.SUPPLY, LogGroup.AMMO)
        // Attractive Supply beside the fighting position must not override the collective retreat.
        val forwardSupply = BlockPos(126, 1, 86)
        helper.setBlock(forwardSupply, ModBlocks.SUPPLY.get())
        (helper.getBlockEntity(forwardSupply) as SupplyBlockEntity).faction = SquadFaction.PIG
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, members.map { it.uuid })
        manager.setOrder(squad.id, SquadOrder.ATTACK)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(155, 1, 61)))
        val origins = members.map { it.position() }
        var abortedAt: Long? = null
        var rally: BlockPos? = null
        var heldSince: Long? = null
        val trace = StringBuilder("tick,order,member,x,y,z,primary,sidearm,resupplying\n")
        cleanup(helper) {
            DebugFlags.enabledGroups = oldGroups
            manager.deleteSquad(helper.level, squad.id)
            (members + enemy).forEach { it.discard() }
            helper.setBlock(forwardSupply, Blocks.AIR)
            rally?.let { helper.level.setBlockAndUpdate(it, Blocks.AIR.defaultBlockState()) }
            report("ammo-withdrawal.csv", trace.toString())
        }
        helper.onEachTick {
            if (helper.tick == 20L) {
                helper.assertTrue(squad.order == SquadOrder.ATTACK, "One empty primary out of four aborted the attack")
                ammunition(members[1].primaryGun(), 0, 0)
            }
            if (helper.tick >= 50L) helper.assertTrue(squad.order != SquadOrder.ATTACK, "Half-empty attack did not abort, or restarted without a player order")
            if (squad.order == SquadOrder.RETREAT && abortedAt == null) {
                abortedAt = helper.tick
                rally = squad.objective!!
                val point = rally!!.bottomCenter
                val center = Vec3(members.sumOf { it.x } / 4, members.sumOf { it.y } / 4, members.sumOf { it.z } / 4)
                val depth = center.x - point.x
                helper.assertTrue(depth in 95.0..105.0, "Ammo retreat objective has wrong depth: $depth")
                helper.assertTrue(squad.focusEntity == null, "Retreat retained an attack entity focus")
                helper.level.setBlockAndUpdate(rally!!, ModBlocks.SUPPLY.get().defaultBlockState())
                (helper.level.getBlockEntity(rally!!) as SupplyBlockEntity).faction = SquadFaction.PIG
            }
            if (rally != null) helper.assertTrue(squad.objective == rally, "The retreat point drifted with the moving squad")
            members.forEachIndexed { index, npc ->
                if (helper.tick % 10L == 0L) trace.append("${helper.tick},${squad.order},$index,${npc.x},${npc.y},${npc.z},${npc.ammoFraction()},${npc.usingSidearm},${npc.resupplying}\n")
            }
            val arrived = squad.order == SquadOrder.DEFEND && members.all { it.ammoFraction() > 0.8 && it.position().subtract(origins[members.indexOf(it)]).horizontalDistance() >= 70.0 }
            if (arrived) {
                if (heldSince == null) heldSince = helper.tick
                if (helper.tick - heldSince!! >= 100) {
                    report("ammo-withdrawal-result.txt", "abort=$abortedAt\nheld=$heldSince\nfinished=${helper.tick}\norder=${squad.order}\nrally=$rally\n")
                    helper.succeed()
                }
            } else {
                heldSince = null
            }
        }
    }

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

    private fun ammunition(stack: ItemStack, loaded: Int, reserve: Int) {
        val data = GunData.from(stack)
        data.ammo.set(loaded)
        data.virtualAmmo.set(reserve)
        data.save()
    }

    private fun floor(helper: GameTestHelper) {
        for (x in 0..159) for (z in 0..127) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
    }

    private fun report(name: String, content: String) {
        val file = Path.of(System.getProperty("sbwnpc.gameTestReports"), name)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
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
