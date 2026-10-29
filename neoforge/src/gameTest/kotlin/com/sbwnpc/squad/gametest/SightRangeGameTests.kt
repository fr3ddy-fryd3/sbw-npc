package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate

/**
 * Nobody takes a target from beyond detection range just because a squadmate has it, and nobody
 * sees anybody past 256 blocks (#232). The field report: a whole Defend squad kept shooting at men
 * 280 blocks away for as long as one squadmate refreshed the contact.
 */
@PrefixGameTestTemplate(false)
object SightRangeGameTests {
    private const val DAY_TIME = 6000L
    private const val FLOOR = 300

    /**
     * The front man is 100 blocks from the enemy, the rear man [rear] blocks behind him. The front
     * man starts with a reported contact, as after he has noticed the enemy himself.
     */
    private fun twoMen(helper: GameTestHelper, name: String, rear: Double, body: (front: NpcEntity, back: NpcEntity, scene: CombatIdleGameTests.Scene) -> Unit) {
        // The far men stand outside the chunks a test structure keeps ticking: force the column they stand in.
        val chunks = (0..FLOOR step 16).map { ChunkPos(helper.absolutePos(BlockPos(90, 1, it))) }.distinct()
        chunks.forEach { helper.level.setChunkForced(it.x, it.z, true) }
        val scene = CombatIdleGameTests.Scene(helper, name, depth = FLOOR)
        val oldTime = helper.level.dayTime
        helper.level.setDayTime(DAY_TIME)
        val front = scene.spawn(Vec3(90.5, 1.0, 180.5))
        val back = scene.spawn(Vec3(90.5, 1.0, 180.5 - rear))
        scene.enemy(Vec3(90.5, 1.0, 280.5))
        val squad = scene.squad(listOf(front, back), contact = null, order = SquadOrder.DEFEND)
        scene.onClose = {
            chunks.forEach { helper.level.setChunkForced(it.x, it.z, false) }
            helper.level.setDayTime(oldTime)
        }
        helper.onEachTick {
            // Facing the enemy (south): the cone of sight is what the sensor checks first.
            front.yHeadRot = 0f
            back.yHeadRot = 0f
            scene.tick(squad)
        }
        body(front, back, scene)
    }

    /** Control: the front man, 100 blocks from the enemy, takes the target; the scenes below mean nothing if he does not. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 800, batch = "sight-range-control")
    fun frontManTakesAnEnemyInsideDetectionRange(helper: GameTestHelper) =
        twoMen(helper, "sight-range-control", 30.0) { front, _, scene ->
            helper.onEachTick {
                if (front.target != null) {
                    scene.result("PASS", "tick=${helper.tick}")
                    helper.succeed()
                }
            }
        }

    /** The rear man is 160 blocks off: the front man's contact does not hand him the target. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 800, batch = "sight-range-160")
    fun rearManDoesNotTakeASharedContactBeyondDetectionRange(helper: GameTestHelper) =
        twoMen(helper, "sight-range-160", 60.0) { front, back, scene ->
            var frontHadIt = false
            helper.onEachTick {
                if (front.target != null) frontHadIt = true
                helper.assertTrue(back.target == null, "The man 160 blocks off took the shared target at tick ${helper.tick}")
                if (helper.tick >= 700) {
                    helper.assertTrue(frontHadIt, "The control man never took the target, so the scene proves nothing")
                    scene.result("PASS", "rear man never took it")
                    helper.succeed()
                }
            }
        }

    /** The rear man is 270 blocks off: past 256 nobody sees anybody, however fresh the contact. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 800, batch = "sight-range-270")
    fun rearManNeverTakesAnEnemyPastTheSightLimit(helper: GameTestHelper) =
        twoMen(helper, "sight-range-270", 170.0) { front, back, scene ->
            var frontHadIt = false
            helper.onEachTick {
                if (front.target != null) frontHadIt = true
                helper.assertTrue(back.target == null, "The man 270 blocks off took the target at tick ${helper.tick}")
                if (helper.tick >= 700) {
                    helper.assertTrue(frontHadIt, "The control man never took the target, so the scene proves nothing")
                    scene.result("PASS", "rear man never took it")
                    helper.succeed()
                }
            }
        }
}
