package com.sbwnpc.squad.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import kotlin.math.abs

/**
 * Wariness (#186): it starts when a contact is over, and a wary man can be seen to be wary, since
 * he sweeps his eyes over the sector the threat came from instead of looking about at random.
 */
@PrefixGameTestTemplate(false)
object WaryGameTests {
    /** Half a minute after the last target the man grows wary of the place he last saw an enemy. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1000, batch = "wary-after-contact")
    fun manGrowsWaryHalfAMinuteAfterHisLastTarget(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "wary-after-contact")
        val man = scene.spawn(Vec3(60.5, 1.0, 60.5))
        val enemy = scene.enemy(Vec3(100.5, 1.0, 60.5))
        val seenAt = enemy.position()
        helper.onEachTick {
            if (helper.tick < 20) {
                man.target = enemy
            } else if (helper.tick == 20L) {
                man.target = null
                enemy.discard()
            }
            val wary = man.wary()
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} wary=$wary toward=${man.waryToward()}")
            if (helper.tick < 560) helper.assertFalse(wary, "Wary before the contact was half a minute over, tick ${helper.tick}")
            if (helper.tick >= 700) {
                helper.assertTrue(wary, "Not wary 35 s after the last target")
                val toward = man.waryToward()
                helper.assertTrue(toward != null && toward.distanceTo(seenAt) < 2.0, "Wary of $toward, not of where the enemy was seen ($seenAt)")
                scene.result("PASS", "wary toward $toward")
                helper.succeed()
            }
        }
    }

    /** A wary man with nothing to fight is seen sweeping his eyes: his head turns through a wide arc within ten seconds. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 600, batch = "wary-sweep")
    fun aWaryManSweepsHisEyesOverTheSector(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "wary-sweep")
        val man = scene.spawn(Vec3(60.5, 1.0, 60.5))
        man.becomeWary(helper.absoluteVec(Vec3(100.5, 1.0, 60.5)))
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        helper.onEachTick {
            if (helper.tick >= 20) {
                low = minOf(low, man.yHeadRot)
                high = maxOf(high, man.yHeadRot)
            }
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} wary=${man.wary()} headRange=${high - low}")
            if (helper.tick >= 400) {
                helper.assertTrue(man.wary(), "Not wary any more")
                helper.assertTrue(abs(high - low) >= 40f, "The wary man's head swept only ${abs(high - low)} degrees in 380 ticks")
                scene.result("PASS", "headRange=${high - low}")
                helper.succeed()
            }
        }
    }
}
