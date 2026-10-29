package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/**
 * Men on a DEFEND order in contact each take a post of their own (#236). In a playtest four men
 * of one squad were planned onto the identical point; a squad that gets out of a vehicle starts
 * that way, all on the one spot it dismounts them at.
 */
@PrefixGameTestTemplate(false)
object DefensivePostSpacingGameTests {
    /** Four riflemen standing on one spot spread to posts at least [SPACING] apart once they hold. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 600, batch = "defend-post-spacing")
    fun defendersOnOneSpotSpreadToPostsOfTheirOwn(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "defend-post-spacing")
        val manager = SquadManager.get(helper.level)
        val men = (0 until 4).map { scene.spawn(Vec3(30.5, 1.0, 60.5)) }
        scene.enemy(Vec3(110.5, 1.0, 60.5))
        val squad = manager.create(helper.level, UUID(236, 1), requireNotNull(men.first().spawnFaction), men.map { it.uuid })
        manager.setOrder(squad.id, SquadOrder.DEFEND)
        manager.setObjective(helper.level, squad.id, men.first().blockPosition())
        scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        helper.onEachTick {
            val closest = men.flatMapIndexed { i, a -> men.drop(i + 1).map { b -> a.position().subtract(b.position()).horizontalDistance() } }.min()
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} closest=${"%.2f".format(closest)} at=${men.map { it.blockPosition().toShortString() }}")
            if (helper.tick >= 500) {
                helper.assertTrue(closest >= SPACING, "Two defenders stand ${"%.2f".format(closest)} blocks apart after 25 s")
                scene.result("PASS", "closest=${"%.2f".format(closest)}")
                helper.succeed()
            }
        }
    }

    /** Pushing alone keeps two bodies about a block apart; a post of one's own is farther. */
    private const val SPACING = 2.0
}
