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
    /** Four riflemen and an attack squad on them, at x 120; the objective is east at x 155. */
    private class Attack(val helper: GameTestHelper, men: Int = 4, composition: List<NpcClass>? = null) {
        val supplyScope = IsolatedSupplyPoints(helper.level).also { floor(helper) }
        val manager = SquadManager.get(helper.level)
        val members = (0 until men).map { AttackDeploymentGameTests.spawn(helper, Vec3(120.5, 1.0, 55.5 + it * 4), SquadFaction.PIG) }
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, members.map { it.uuid }, composition)
        val objective: BlockPos = helper.absolutePos(BlockPos(155, 1, 61))
        private val supplies = ArrayList<BlockPos>()
        private val extra = ArrayList<Entity>()
        private val oldGroups = DebugFlags.enabledGroups

        init {
            DebugFlags.enabledGroups = oldGroups + setOf(LogGroup.ORDER, LogGroup.SUPPLY, LogGroup.AMMO)
            cleanup(helper) {
                DebugFlags.enabledGroups = oldGroups
                manager.deleteSquad(helper.level, squad.id)
                (members + extra).forEach { it.discard() }
                supplies.forEach { helper.level.setBlockAndUpdate(it, Blocks.AIR.defaultBlockState()) }
                supplyScope.close()
            }
        }

        fun enemy() =
            AttackDeploymentGameTests.spawn(helper, Vec3(155.5, 1.0, 61.5), SquadFaction.COW).apply {
                isNoAi = true
                getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 10000.0
                health = maxHealth
                extra += this
            }

        /** A friendly Supply at the absolute [pos]. */
        fun supply(pos: BlockPos): BlockPos {
            helper.level.setBlockAndUpdate(pos, ModBlocks.SUPPLY.get().defaultBlockState())
            (helper.level.getBlockEntity(pos) as SupplyBlockEntity).faction = SquadFaction.PIG
            supplies += pos
            return pos
        }

        fun dry(vararg index: Int) = index.forEach { ammunition(members[it].primaryGun(), 0, 0) }

        fun attack() {
            manager.issueOrder(squad.id, SquadOrder.ATTACK)
            manager.setObjective(helper.level, squad.id, objective)
        }
    }

    /** Half the squad dry on a fresh attack, a Supply behind it: it refills first, then attacks the same point by itself (#238). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 1600, batch = "strength-refill")
    fun freshAttackHalfDryRefillsAtTheSupplyFirst(helper: GameTestHelper) {
        val scene = Attack(helper)
        val supply = scene.supply(helper.absolutePos(BlockPos(100, 1, 61)))
        scene.dry(0, 1)
        scene.attack()
        var detoured = false
        helper.onEachTick {
            if (helper.tick == 60L) {
                helper.assertTrue(scene.squad.order == SquadOrder.MOVE && scene.squad.objective == supply && scene.squad.resupplyAttack != null, "The dry squad did not go to the Supply first: ${scene.squad.order} ${scene.squad.objective}")
                detoured = true
            }
            if (detoured && scene.squad.order == SquadOrder.ATTACK) {
                helper.assertTrue(scene.squad.objective == scene.objective, "The attack resumed on ${scene.squad.objective}, not its own point")
                helper.assertTrue(scene.members.all(SidearmKit::hasPrimaryAmmo), "The attack resumed before the men refilled")
                helper.assertTrue(scene.squad.attackResupplied && scene.squad.resupplyAttack == null, "The refill was not recorded as done")
                helper.succeed()
            }
        }
    }

    /** The same with no Supply anywhere: it attacks at once, measured at what it has (#238). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 400, batch = "strength-no-supply")
    fun freshAttackHalfDryWithoutSupplyGoesInAtWhatItHas(helper: GameTestHelper) {
        val scene = Attack(helper)
        scene.dry(0, 1)
        scene.attack()
        helper.onEachTick {
            helper.assertTrue(scene.squad.order == SquadOrder.ATTACK, "A dry squad with nowhere to refill turned back at tick ${helper.tick}")
            if (helper.tick >= 300L) {
                helper.assertTrue(scene.squad.attackBase == 2, "The attack was measured at ${scene.squad.attackBase}, not its 2 armed men")
                helper.succeed()
            }
        }
    }

    /**
     * An attack measured at 4 holds at half and is called off at a quarter, toward the Supply behind
     * it (#238): the dead count as lost, the way the dry do.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 800, batch = "strength-losses")
    fun attackLosingItsMenHoldsThenFallsBackToTheSupplyBehind(helper: GameTestHelper) {
        val scene = Attack(helper)
        scene.enemy()
        scene.attack()
        var supply: BlockPos? = null
        helper.onEachTick {
            when (helper.tick) {
                40L -> {
                    helper.assertTrue(scene.squad.attackBase == 4, "The attack was measured at ${scene.squad.attackBase}, not 4")
                    // Placed only now: had it been known at the order, nobody dry would count as lost here.
                    supply = scene.supply(helper.absolutePos(BlockPos(40, 1, 61)))
                    scene.members[0].kill()
                    scene.members[1].kill()
                }

                200L -> {
                    helper.assertTrue(scene.squad.order == SquadOrder.ATTACK, "Half the base lost called the attack off instead of holding it")
                    scene.members[2].kill()
                }

                else -> Unit
            }
            if (helper.tick > 200L && scene.squad.order == SquadOrder.RETREAT) {
                helper.assertTrue(scene.squad.objective == supply, "The called-off attack fell back to ${scene.squad.objective}, not the Supply behind it")
                helper.succeed()
            }
        }
    }

    /** Run dry with nowhere to refill, the attack falls back 100 m and does not start again by itself. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 1200, batch = "strength-dry")
    fun attackRunningDryFallsBackAndDoesNotRestart(helper: GameTestHelper) {
        val scene = Attack(helper)
        scene.enemy()
        scene.attack()
        var abortedAt: Long? = null
        helper.onEachTick {
            if (helper.tick == 40L) {
                helper.assertTrue(scene.squad.attackBase == 4, "The attack was measured at ${scene.squad.attackBase}, not 4")
                scene.dry(0, 1, 2)
            }
            if (abortedAt == null && scene.squad.order == SquadOrder.RETREAT) {
                abortedAt = helper.tick
                val depth = scene.members.sumOf { it.x } / scene.members.size - scene.squad.objective!!.bottomCenter.x
                helper.assertTrue(depth in 90.0..110.0, "The dry attack fell back $depth blocks, not 100")
                scene.supply(scene.squad.objective!!)
            }
            if (abortedAt != null) {
                helper.assertTrue(scene.squad.order != SquadOrder.ATTACK, "The attack restarted after the Supply without a player order")
                if (helper.tick - abortedAt!! >= 400L) helper.succeed()
            }
        }
    }

    /** Three men left of twelve sent in: a remnant, it attacks to the end however dry it runs (#238). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 600, batch = "strength-remnant")
    fun remnantAttacksToTheEnd(helper: GameTestHelper) {
        val scene = Attack(helper, men = 3, composition = List(12) { NpcClass.RIFLEMAN })
        scene.enemy()
        scene.attack()
        helper.onEachTick {
            if (helper.tick == 40L) {
                helper.assertTrue(scene.squad.attackRemnant, "Three of twelve were not taken for a remnant")
                scene.dry(0, 1, 2)
            }
            helper.assertTrue(scene.squad.order == SquadOrder.ATTACK, "The remnant turned back at tick ${helper.tick}")
            if (helper.tick >= 400L) helper.succeed()
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
