package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.entity.ai.SquadMarch
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@PrefixGameTestTemplate(false)
object MarchRouteGameTests {
    /**
     * A squad of 32 sent 170 blocks walks one long route there (#145). Keyed by each man's own place
     * round the objective, it ran a long search for every cluster of places, 6 to 8 a squad, and in
     * a multiplayer game stood waiting 30 s for them before its first step.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 3000, batch = "march-one-route")
    fun squadOfThirtyTwoMarchesOnOneLongRoute(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "march-one-route")
        val manager = SquadManager.get(helper.level)
        val men = (0 until 32).map { scene.spawn(Vec3(74.5 + it % 8 * 3, 1.0, 12.5 + it / 8 * 3)) }
        val squad = manager.create(helper.level, UUID(90, 40), requireNotNull(men.first().spawnFaction), men.map { it.uuid })
        scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        val objective = helper.absolutePos(BlockPos(88, 1, 190))
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.MOVE, objective)
        val center = Vec3.atBottomCenterOf(objective)
        helper.onEachTick {
            val searches = SquadMarch.searchesStarted(squad.id)
            val near = men.count { it.isAlive && it.position().distanceTo(center) <= 32.0 }
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} searches=$searches near=$near/${men.size}")
            helper.assertTrue(searches <= 2, "The squad ran $searches long-route searches by tick ${helper.tick}")
            if (near >= men.size - 2) {
                scene.result("PASS", "tick=${helper.tick} searches=$searches near=$near")
                helper.succeed()
            }
        }
    }
}
