package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.SmokeClouds
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.SmokeCloudRegistry
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/** A squad falling back under fire: its smoke screen (#115). */
@PrefixGameTestTemplate(false)
object WithdrawalGameTests {
    /**
     * A squad of ten that starts falling back under fire throws two smoke grenades, a quarter of it
     * rounded down, towards the enemy from the half that holds; they come down side by side, not
     * in one spot, and the cloud hides the enemy from it (#115).
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
            val out = SmokeCloudRegistry.all(helper.level).filter { Ports.grenades.smokeIsOut(it) }
            val clouds = out.size
            if (clouds > 0 && men.any { SmokeClouds.blocks(helper.level, it.eyePosition, enemy.eyePosition) && !DetectionSightline.canSee(it, enemy) }) hidden = true
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

    /**
     * Strung out over a hillside, a squad of twelve throws three grenades as it falls back, and
     * three more when it is sent back a second time to the same point; each time they come down
     * apart, not in one spot.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1600, batch = "withdrawal-smoke-hill")
    fun squadOnAHillsideThrowsSmokeOnEachWithdrawal(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "withdrawal-smoke-hill")
        val manager = SquadManager.get(helper.level)
        // Steps rising towards the enemy: a block every four, up to five.
        val steps = (30..60).flatMap { x -> (30..110).flatMap { z -> (1..minOf(5, (x - 30) / 4)).map { y -> BlockPos(x, y, z) } } }
        val ground = steps.associateWith { helper.getBlockState(it) }
        steps.forEach { helper.setBlock(it, Blocks.STONE) }
        val men = (0 until 12).map { index ->
            val x = 32 + index % 3 * 12
            scene.spawn(Vec3(x + 0.5, 1.0 + minOf(5, (x - 30) / 4), 40.5 + index / 3 * 14))
        }
        val enemy = scene.enemy(Vec3(100.5, 1.0, 64.5))
        val squad = manager.create(helper.level, UUID(90, 13), requireNotNull(men.first().spawnFaction), men.map { it.uuid })
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            ground.forEach { (pos, state) -> helper.setBlock(pos, state) }
        }
        val rally = helper.absolutePos(BlockPos(10, 1, 64))
        val wanted = 3
        var withdrawals = 0
        var issuedAt = 0L
        val screens = Screens(helper)
        helper.onEachTick {
            screens.track(withdrawals)
            val thrown = men.count { it.smokeLeft == 0 }
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} withdrawals=$withdrawals thrown=$thrown screens=${screens.of(1).size},${screens.of(2).size} order=${squad.order}")
            when {
                withdrawals == 0 && men.any { it.target?.uuid == enemy.uuid } -> {
                    manager.issueOrderAt(helper.level, squad.id, SquadOrder.RETREAT, rally)
                    withdrawals = 1
                    issuedAt = helper.tick
                }

                // Called off and sent back again: the second order must screen again.
                withdrawals == 1 && thrown >= wanted && helper.tick - issuedAt >= 40 -> {
                    manager.issueOrderAt(helper.level, squad.id, SquadOrder.DEFEND, helper.absolutePos(BlockPos(44, 1, 64)))
                    manager.issueOrderAt(helper.level, squad.id, SquadOrder.RETREAT, rally)
                    withdrawals = 2
                }

                withdrawals == 2 && thrown >= wanted * 2 && screens.of(1).size >= wanted && screens.of(2).size >= wanted -> {
                    screens.assertSpread(1)
                    screens.assertSpread(2)
                    scene.result("PASS", "tick=${helper.tick} thrown=$thrown")
                    helper.succeed()
                }
            }
        }
    }

    /** Each grenade by the withdrawal it was thrown in, and where it came down once smoking. */
    private class Screens(private val helper: GameTestHelper) {
        private val thrownIn = HashMap<UUID, Int>()
        private val landed = HashMap<UUID, Vec3>()

        fun track(withdrawal: Int) =
            SmokeCloudRegistry.all(helper.level).forEach { grenade ->
                thrownIn.putIfAbsent(grenade.uuid, withdrawal)
                if (Ports.grenades.smokeIsOut(grenade)) landed.putIfAbsent(grenade.uuid, grenade.position())
            }

        fun of(withdrawal: Int) = landed.filterKeys { thrownIn[it] == withdrawal }.values.toList()

        fun assertSpread(withdrawal: Int) {
            val points = of(withdrawal)
            val closest = points.indices.flatMap { a -> (0 until a).map { b -> points[a].distanceTo(points[b]) } }.min()
            helper.assertTrue(closest >= 4.0, "Withdrawal $withdrawal: two grenades came down ${"%.1f".format(closest)} blocks apart")
        }
    }

    /**
     * Smoke still hides a man after SBW has discarded the grenade (200 ticks) for as long as its
     * particles last, and stops once they are gone. Nobody looks through it while the grenade
     * lives, so the cloud is kept from the grenade's end, not from the last look.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1200, batch = "smoke-linger")
    fun smokeHidesAManUntilItsParticlesAreGone(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "smoke-linger")
        val watcher = scene.spawn(Vec3(40.5, 1.0, 64.5))
        val enemy = scene.enemy(Vec3(60.5, 1.0, 64.5))
        watcher.isNoAi = true
        Ports.grenades.throwSmoke(watcher, helper.level, helper.absoluteVec(Vec3(50.5, 1.0, 64.5)))
        helper.onEachTick {
            when (helper.tick) {
                320L -> helper.assertTrue(!DetectionSightline.canSee(watcher, enemy) && SmokeCloudRegistry.all(helper.level).isEmpty(), "Smoke stopped hiding him once the grenade was gone")

                1000L -> {
                    helper.assertTrue(DetectionSightline.canSee(watcher, enemy), "Smoke still hid him after its particles were gone")
                    scene.result("PASS", "tick=${helper.tick}")
                    helper.succeed()
                }
            }
        }
    }
}
