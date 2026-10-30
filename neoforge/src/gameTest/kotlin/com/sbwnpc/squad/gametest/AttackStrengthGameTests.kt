package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.SidearmKit
import com.sbwnpc.squad.init.ModBlocks
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/** An attack judged by its combat strength (#238): measured at its start, held, called off, or sent in as a remnant. */
@PrefixGameTestTemplate(false)
object AttackStrengthGameTests {
    /** Four riflemen and an attack squad on them, at x 120; the objective is east at x 155. */
    private class AttackScene(val helper: GameTestHelper, men: Int = 4, composition: List<NpcClass>? = null) {
        val supplyScope = IsolatedSupplyPoints(helper.level).also { AmmunitionTacticsGameTests.floor(helper) }
        val manager = SquadManager.get(helper.level)
        val members = (0 until men).map { AttackDeploymentGameTests.spawn(helper, Vec3(120.5, 1.0, 55.5 + it * 4), SquadFaction.PIG) }
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, members.map { it.uuid }, composition)
        val objective: BlockPos = helper.absolutePos(BlockPos(155, 1, 61))
        private val supplies = ArrayList<BlockPos>()
        private val extra = ArrayList<Entity>()
        private val oldGroups = DebugFlags.enabledGroups

        init {
            DebugFlags.enabledGroups = oldGroups + setOf(LogGroup.ORDER, LogGroup.SUPPLY, LogGroup.AMMO)
            AmmunitionTacticsGameTests.cleanup(helper) {
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

        fun dry(vararg index: Int) = index.forEach { AmmunitionTacticsGameTests.ammunition(members[it].primaryGun(), 0, 0) }

        fun attack() {
            manager.issueOrder(squad.id, SquadOrder.ATTACK)
            manager.setObjective(helper.level, squad.id, objective)
        }
    }

    /** Half the squad dry on a fresh attack, a Supply behind it: it refills first, then attacks the same point by itself (#238). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 1600, batch = "strength-refill")
    fun freshAttackHalfDryRefillsAtTheSupplyFirst(helper: GameTestHelper) {
        val scene = AttackScene(helper)
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
        val scene = AttackScene(helper)
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
        val scene = AttackScene(helper)
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
        val scene = AttackScene(helper)
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
        val scene = AttackScene(helper, men = 3, composition = List(12) { NpcClass.RIFLEMAN })
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
}
