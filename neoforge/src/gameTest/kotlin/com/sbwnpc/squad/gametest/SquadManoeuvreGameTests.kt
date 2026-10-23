package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.ai.tactics.CombatStrategy
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID
import kotlin.math.hypot

/** The squad manoeuvres chosen by the squad HTN: bounding by halves and regrouping (#172). */
@PrefixGameTestTemplate(false)
object SquadManoeuvreGameTests {
    /**
     * Twelve riflemen attacking across open ground under fire from four riflemen take up bounding
     * by halves, swap halves at least twice and close in on the enemy while doing it.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1600, batch = "manoeuvre-bound")
    fun attackUnderFireBoundsByHalves(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "manoeuvre-bound")
        val manager = SquadManager.get(helper.level)
        val men = (0 until 12).map { scene.spawn(Vec3(20.5 + it / 4 * 3, 1.0, 56.5 + it % 4 * 4)).also(::sturdy) }
        val enemies = (0 until 4).map { scene.spawn(Vec3(110.5, 1.0, 56.5 + it * 5), faction = SquadFaction.COW).also(::sturdy) }
        val squad = manager.create(helper.level, UUID(91, 1), SquadFaction.PIG, men.map { it.uuid })
        scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(BlockPos(110, 1, 64)))
        val target = helper.absoluteVec(Vec3(110.5, 1.0, 64.5))
        fun distance() = men.map { hypot(it.x - target.x, it.z - target.z) }.average()
        val start = distance()
        var bounded = false
        var turns = 0
        helper.onEachTick {
            val strategy = NativeTactics.strategy(helper.level, squad.id)
            if (strategy == CombatStrategy.BOUND_OVERWATCH) bounded = true
            NativeTactics.boundTurn(helper.level, squad.id)?.let { turns = maxOf(turns, it) }
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} strategy=$strategy turn=$turns distance=${"%.1f".format(distance())} start=${"%.1f".format(start)} enemiesFired=${enemies.count { it.lastShotTick >= 0 }}")
            if (bounded && turns >= 2 && start - distance() >= 12.0) {
                scene.result("PASS", "tick=${helper.tick} turns=$turns closed=${"%.1f".format(start - distance())}")
                helper.succeed()
            }
        }
    }

    /**
     * Nine riflemen ordered to attack an enemy in sight while strung out in three groups 60 blocks
     * apart regroup at a rally point first, gather within the squad's perimeter and then go on.
     *
     * Out of contact each man marches by the order, and the squad gathers on the march by itself.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1600, batch = "manoeuvre-regroup")
    fun scatteredAttackRegroupsFirst(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "manoeuvre-regroup")
        val manager = SquadManager.get(helper.level)
        val men = (0 until 9).map { scene.spawn(Vec3(20.5 + it % 3 * 3, 1.0, 14.5 + it / 3 * 60)).also(::sturdy) }
        repeat(3) { scene.spawn(Vec3(110.5, 1.0, 64.5 + it * 5), faction = SquadFaction.COW).also(::sturdy) }
        val squad = manager.create(helper.level, UUID(91, 2), SquadFaction.PIG, men.map { it.uuid })
        scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(BlockPos(110, 1, 69)))
        var regrouped = false
        helper.onEachTick {
            val strategy = NativeTactics.strategy(helper.level, squad.id)
            if (strategy == CombatStrategy.REGROUP) regrouped = true
            val centre = Vec3(men.map { it.x }.average(), 0.0, men.map { it.z }.average())
            val spread = men.maxOf { hypot(it.x - centre.x, it.z - centre.z) }
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} strategy=$strategy spread=${"%.1f".format(spread)}")
            if (regrouped && strategy != CombatStrategy.REGROUP && spread <= 30.0) {
                scene.result("PASS", "tick=${helper.tick} strategy=$strategy spread=${"%.1f".format(spread)}")
                helper.succeed()
            }
        }
    }

    // Nobody falls in these scenes, so the squads keep their strength and the manoeuvre its men.
    private fun sturdy(npc: NpcEntity) {
        npc.getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 5000.0
        npc.health = npc.maxHealth
    }
}
