package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/**
 * Own-eyes scans outside the squad's sensor go through the same noticing rule (#114): light,
 * weather and time to notice, not a bare line of sight.
 */
@PrefixGameTestTemplate(false)
object NoticingGameTests {
    private const val DAY_TIME = 6000L
    private const val NIGHT_TIME = 18000L

    /** A veteran operator reaches 126 blocks: an enemy 100 off by day is noticed in about a second, and the drone goes up. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "notice-operator-day")
    fun droneOperatorLaunchesAtAnEnemyHeNoticesByDay(helper: GameTestHelper) = operator(helper, "notice-operator-day", DAY_TIME, expectLaunch = true)

    /** At night the dark reaches 79 blocks for him: the same enemy is never noticed, and no drone goes up on him. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 600, batch = "notice-operator-night")
    fun droneOperatorDoesNotLaunchAtAnEnemyTooFarForTheDark(helper: GameTestHelper) = operator(helper, "notice-operator-night", NIGHT_TIME, expectLaunch = false)

    private fun operator(helper: GameTestHelper, name: String, time: Long, expectLaunch: Boolean) {
        val scene = CombatIdleGameTests.Scene(helper, name)
        val manager = SquadManager.get(helper.level)
        val oldTime = helper.level.dayTime
        helper.level.setDayTime(time)
        com.sbwnpc.squad.combat.DebugFlags.enabledGroups += setOf(LogGroup.FIRE_SUPPORT, LogGroup.SPOTTING)
        val operator = scene.spawn(Vec3(20.5, 1.0, 60.5), role = NpcClass.DRONE_OPERATOR)
        operator.npcRank = NpcRank.VETERAN
        val enemy = scene.enemy(Vec3(120.5, 1.0, 60.5))
        val squad = manager.create(helper.level, UUID(90, 30), requireNotNull(operator.spawnFaction), listOf(operator.uuid))
        manager.setOrder(squad.id, SquadOrder.DEFEND)
        manager.setObjective(helper.level, squad.id, operator.blockPosition())
        val drones = operator.dronesLeft
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            helper.level.setDayTime(oldTime)
        }
        helper.onEachTick {
            operator.yHeadRot = -90f
            val launched = operator.dronesLeft < drones
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} launched=$launched light=${helper.level.getMaxLocalRawBrightness(enemy.blockPosition())}")
            if (expectLaunch) {
                if (launched) {
                    scene.result("PASS", "tick=${helper.tick}")
                    helper.succeed()
                }
            } else {
                helper.assertFalse(launched, "Launched at an enemy 100 blocks off in the dark at tick ${helper.tick}")
                if (helper.tick >= 500) {
                    scene.result("PASS", "no launch in 500 ticks")
                    helper.succeed()
                }
            }
        }
    }

    /**
     * Rain shortens the dark's reach to 72 × 0.7 = 50 blocks (snow, if the biome snows, to 43): a man
     * 60 blocks off at night, noticed in clear weather after a few seconds, is never noticed in it.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 600, batch = "notice-rain-night")
    fun enemyAtSixtyBlocksIsNotNoticedInRainAtNight(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "notice-rain-night")
        val manager = SquadManager.get(helper.level)
        val oldTime = helper.level.dayTime
        helper.level.setDayTime(NIGHT_TIME)
        helper.level.setWeatherParameters(0, 6000, true, false)
        val shooter = scene.spawn(Vec3(20.5, 1.0, 60.5))
        val enemy = scene.enemy(Vec3(80.5, 1.0, 60.5))
        val squad = manager.create(helper.level, UUID(90, 31), requireNotNull(shooter.spawnFaction), listOf(shooter.uuid))
        manager.setOrder(squad.id, SquadOrder.DEFEND)
        manager.setObjective(helper.level, squad.id, shooter.blockPosition())
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            helper.level.setDayTime(oldTime)
            helper.level.setWeatherParameters(6000, 0, false, false)
            // Rain fades out over a few seconds otherwise, into whichever batch runs next.
            helper.level.setRainLevel(0f)
            helper.level.setThunderLevel(0f)
        }
        helper.onEachTick {
            shooter.yHeadRot = -90f
            val weather = com.sbwnpc.squad.combat.Noticing.weatherAt(helper.level, enemy.blockPosition())
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} weather=$weather target=${shooter.target?.uuid}")
            helper.assertFalse(shooter.target?.uuid == enemy.uuid, "Noticed an enemy 60 blocks off at night in $weather at tick ${helper.tick}")
            if (helper.tick >= 500) {
                helper.assertTrue(weather != com.sbwnpc.squad.combat.SpottingTime.Weather.CLEAR, "The scene never had rain")
                scene.result("PASS", "weather=$weather")
                helper.succeed()
            }
        }
    }
}
