package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/** A squad falling back under fire: its smoke screen (#115). */
@PrefixGameTestTemplate(false)
object WithdrawalGameTests {
    /**
     * A squad of ten that starts falling back under fire throws two smoke grenades, a quarter of it
     * rounded down, towards the enemy from the half that holds; they come down side by side, not
     * in one spot (in-game 20.10), and the cloud hides the enemy from it (#115).
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 900, batch = "withdrawal-smoke")
    fun fallingBackSquadThrowsAWideSmokeScreenThatHidesTheEnemy(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "withdrawal-smoke")
        val manager = SquadManager.get(helper.level)
        val men = (0 until 10).map { scene.spawn(Vec3(40.5 + it % 2 * 3, 1.0, 54.5 + it / 2 * 4)) }
        val enemy = scene.enemy(Vec3(95.5, 1.0, 64.5))
        val squad = manager.create(helper.level, UUID(90, 11), requireNotNull(men.first().spawnFaction), men.map { it.uuid })
        scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.RETREAT, helper.absolutePos(BlockPos(10, 1, 64)))
        var hidden = false
        helper.onEachTick {
            val out = com.sbwnpc.squad.entity.SmokeCloudRegistry.all(helper.level).filter { Ports.grenades.smokeIsOut(it) }
            val clouds = out.size
            if (clouds > 0 && men.any { com.sbwnpc.squad.combat.SmokeClouds.blocks(helper.level, it.eyePosition, enemy.eyePosition) && !com.sbwnpc.squad.combat.DetectionSightline.canSee(it, enemy) }) hidden = true
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} smokeLeft=${men.map { it.smokeLeft }} cloudsOut=$clouds hidden=$hidden")
            val thrown = men.count { it.smokeLeft == 0 }
            helper.assertTrue(thrown <= 2, "More than a quarter of the squad threw smoke: $thrown")
            if (hidden && thrown == 2 && clouds == 2) {
                val apart = out[0].position().distanceTo(out[1].position())
                helper.assertTrue(apart >= 4.0, "The two grenades came down in one spot, ${"%.1f".format(apart)} blocks apart")
                scene.result("PASS", "tick=${helper.tick} cloudsOut=$clouds apart=${"%.1f".format(apart)}")
                helper.succeed()
            }
        }
    }
}
