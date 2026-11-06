package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.SpottingTime
import com.sbwnpc.squad.combat.Vision
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.SquadTargetSensor
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.TransportVehicle
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

    /**
     * How long a negative scene watches. The slowest notice the table allows for any of these
     * enemies, were it in reach, is about seven seconds (100 blocks in the black band), so ten
     * prove "never" as well as the old twenty-five did.
     */
    private const val NEVER_TICKS = 200L

    /** A veteran operator launches out to 133 blocks and sees 144 by day: an enemy 100 off is noticed in about a second and a half, and the drone goes up. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "notice-operator-day")
    fun droneOperatorLaunchesAtAnEnemyHeNoticesByDay(helper: GameTestHelper) = operator(helper, "notice-operator-day", DAY_TIME, expectLaunch = true)

    /** At night the dark reaches 51 blocks for him: the same enemy is never noticed, and no drone goes up on him. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 300, batch = "notice-operator-night")
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
                if (helper.tick >= NEVER_TICKS) {
                    scene.result("PASS", "no launch in $NEVER_TICKS ticks")
                    helper.succeed()
                }
            }
        }
    }

    /**
     * Rain shortens a regular's reach in the dark from 36 blocks to 28.8 (snow, if the biome snows,
     * to 25): a man 32 blocks off at night, noticed in clear weather in under two seconds, is never
     * noticed in it.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 300, batch = "notice-rain-night")
    fun enemyJustInsideTheDarkRangeIsNotNoticedInRainAtNight(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "notice-rain-night")
        val manager = SquadManager.get(helper.level)
        val oldTime = helper.level.dayTime
        helper.level.setDayTime(NIGHT_TIME)
        helper.level.setWeatherParameters(0, 6000, true, false)
        val shooter = scene.spawn(Vec3(20.5, 1.0, 60.5))
        val enemy = scene.enemy(Vec3(52.5, 1.0, 60.5))
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
            helper.assertFalse(shooter.target?.uuid == enemy.uuid, "Noticed an enemy 32 blocks off at night in $weather at tick ${helper.tick}")
            if (helper.tick >= NEVER_TICKS) {
                helper.assertTrue(weather != com.sbwnpc.squad.combat.SpottingTime.Weather.CLEAR, "The scene never had rain")
                // The control: in clear weather the same man, in the same light, is noticed. So it is
                // the rain the man in the world obeyed, not a light or distance that hides him anyway.
                val eyes = SpottingTime.Observer.of(shooter.npcRank, shooter.npcClass)
                val light = helper.level.getMaxLocalRawBrightness(enemy.blockPosition())
                val distance = shooter.position().distanceTo(enemy.position())
                helper.assertTrue(SpottingTime.seconds(eyes, SpottingTime.Target(distance, light)) != null, "Even in clear weather he would not be noticed at light $light")
                helper.assertTrue(SpottingTime.seconds(eyes, SpottingTime.Target(distance, light, weather = weather)) == null, "In $weather the table notices him too, so the scene proves nothing")
                scene.result("PASS", "weather=$weather")
                helper.succeed()
            }
        }
    }

    /**
     * A regular's reach in the dark is the same in a vehicle as on foot (#245). From a parked LAV's
     * gun and its passenger seat alike, an enemy 60 blocks off is never noticed at night; then one
     * 24 blocks off is, by both men.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "notice-crew-night")
    fun crewNoticeOnlyAnEnemyInsideTheDarkRange(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "notice-crew-night")
        val manager = SquadManager.get(helper.level)
        val oldTime = helper.level.dayTime
        helper.level.setDayTime(NIGHT_TIME)
        DebugFlags.enabledGroups += setOf(LogGroup.SPOTTING, LogGroup.VEHICLE)
        val lav = requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_150)).also {
            val point = helper.absoluteVec(Vec3(20.5, 1.0, 60.5))
            it.moveTo(point.x, point.y, point.z, -90f, 0f)
            helper.level.addFreshEntity(it)
            Ports.vehicles.fuelAndArm(it, TransportVehicle.LAV_150)
        }
        // LAV-150 lays its gun from seat zero; the first man in takes it, the second a bare seat.
        val gunner = scene.spawn(Vec3(18.5, 1.0, 58.5))
        val passenger = scene.spawn(Vec3(18.5, 1.0, 62.5))
        helper.assertTrue(gunner.startRiding(lav, true) && Ports.vehicles.hasWeaponAt(lav, gunner), "The gunner could not take the gun")
        helper.assertTrue(passenger.startRiding(lav, true) && !Ports.vehicles.hasWeaponAt(lav, passenger), "The passenger needs an unarmed seat")
        val crew = listOf(gunner, passenger)
        // Only their sensors run: with the brain on, the transport behaviour got them out on arrival
        // or for the fight before the test could watch; and one man's contact would be the other's
        // at once (SquadKnowledge), so each sensor runs on its own and the squad shares nothing new.
        crew.forEach {
            it.isNoAi = true
            it.npcRank = NpcRank.REGULAR
        }
        // A sensor per man: one instance keeps one scan clock and one noticing state, so the
        // second man's scan was skipped and his progress mixed with the first's.
        val sensors = crew.associateWith { SquadTargetSensor() }
        val far = scene.enemy(Vec3(80.5, 1.0, 60.5))
        var near: NpcEntity? = null
        val squad = manager.create(helper.level, UUID(90, 32), requireNotNull(gunner.spawnFaction), crew.map { it.uuid })
        manager.setOrder(squad.id, SquadOrder.DEFEND)
        manager.setObjective(helper.level, squad.id, gunner.blockPosition())
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            crew.forEach { it.stopRiding() }
            lav.discard()
            helper.level.setDayTime(oldTime)
        }
        helper.onEachTick {
            crew.forEach {
                it.yHeadRot = -90f
                sensors.getValue(it).tick(helper.level, it)
                helper.assertTrue(it.vehicle === lav, "A crewman left the vehicle at tick ${helper.tick}")
            }
            val enemy = near
            if (enemy == null) {
                crew.forEach { man ->
                    assertInView(helper, man, far, man === gunner)
                    helper.assertFalse(man.target?.uuid == far.uuid, "Noticed an enemy 60 blocks off at night from the vehicle at tick ${helper.tick}")
                }
                if (helper.tick >= NEVER_TICKS) {
                    far.discard()
                    near = scene.enemy(Vec3(44.5, 1.0, 60.5))
                }
                return@onEachTick
            }
            crew.forEach { assertInView(helper, it, enemy, it === gunner) }
            if (helper.tick % 20L == 0L) scene.result("RUNNING", "tick=${helper.tick} gunner=${gunner.target?.uuid == enemy.uuid}/${gunner.target?.uuid} passenger=${passenger.target?.uuid == enemy.uuid}/${passenger.target?.uuid} far=${far.uuid}")
            if (crew.all { it.target?.uuid == enemy.uuid }) {
                scene.result("PASS", "nearNoticedAt=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /** Not passing for the wrong reason: the enemy is in view, and in the dark band the scene is about. */
    private fun assertInView(helper: GameTestHelper, crewman: NpcEntity, enemy: NpcEntity, gun: Boolean) {
        helper.assertTrue(gun || Vision.inCone(crewman.position(), crewman.yHeadRot, enemy.position()), "The enemy is outside the passenger's cone")
        helper.assertTrue(DetectionSightline.canSee(crewman, enemy), "The crewman has no line to the enemy")
        // The sky darkens a tick after the clock is set.
        val light = helper.level.getMaxLocalRawBrightness(enemy.blockPosition())
        helper.assertTrue(helper.tick < 2 || SpottingTime.Light.of(light) == SpottingTime.Light.DARK, "The scene is not in the DARK band: light $light")
    }
}
