package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.npc.HelicopterModel
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.StructureUtils
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/**
 * A helicopter takes time to notice like anyone else (#246): by day a hovering enemy aircraft
 * about 125 blocks off is noticed within a few seconds; at night, beyond 64 blocks, never. Before
 * #246 a clear line of sight to aircrew made it a target the same tick, at any hour.
 */
@PrefixGameTestTemplate(false)
object HelicopterSpottingGameTests {
    private const val DAY_TIME = 6000L
    private const val NIGHT_TIME = 18000L
    private const val NEVER_TICKS = 200L

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 300, batch = "heli-spot-day")
    fun riflemanNoticesAHoveringHelicopterByDay(helper: GameTestHelper) = spot(helper, "heli-spot-day", DAY_TIME, expectNoticed = true)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 300, batch = "heli-spot-night")
    fun riflemanDoesNotNoticeAHelicopterBeyondTheNightRange(helper: GameTestHelper) = spot(helper, "heli-spot-night", NIGHT_TIME, expectNoticed = false)

    private fun spot(helper: GameTestHelper, name: String, time: Long, expectNoticed: Boolean) {
        // The template's barrier roof would sit between the aircraft and the sky, and darken it.
        StructureUtils.removeBarriers(helper.bounds, helper.level)
        val scene = CombatIdleGameTests.Scene(helper, name)
        val manager = SquadManager.get(helper.level)
        val oldTime = helper.level.dayTime
        helper.level.setDayTime(time)
        DebugFlags.enabledGroups += setOf(LogGroup.SPOTTING)
        val shooter = scene.spawn(Vec3(20.5, 1.0, 60.5))
        val heli = Ports.vehicles.create(helper.level, HelicopterModel.AH_6)!!
        val at = helper.absoluteVec(Vec3(140.5, 40.0, 60.5))
        heli.moveTo(at.x, at.y, at.z, 90f, 0f)
        heli.setNoGravity(true)
        helper.level.addFreshEntity(heli)
        SquadTeams.assign(heli, SquadFaction.COW)
        val pilot = scene.enemy(Vec3(140.5, 40.0, 60.5))
        helper.assertTrue(pilot.startRiding(heli, true), "The enemy pilot did not take his seat")
        val squad = manager.create(helper.level, UUID(90, 246), requireNotNull(shooter.spawnFaction), listOf(shooter.uuid))
        manager.setOrder(squad.id, SquadOrder.DEFEND)
        manager.setObjective(helper.level, squad.id, shooter.blockPosition())
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            heli.discard()
            helper.level.setDayTime(oldTime)
        }
        helper.onEachTick {
            heli.deltaMovement = Vec3.ZERO
            // Facing it, as its rotor would turn him in the world: the cone is not what this scene tests.
            shooter.yHeadRot = -90f
            val noticed = shooter.target?.uuid == pilot.uuid
            if (helper.tick % 50L == 0L) scene.result("RUNNING", "tick=${helper.tick} noticed=$noticed distance=${shooter.distanceTo(heli)} light=${helper.level.getMaxLocalRawBrightness(pilot.blockPosition())}")
            if (expectNoticed) {
                if (noticed) {
                    scene.result("PASS", "tick=${helper.tick}")
                    helper.succeed()
                }
            } else {
                helper.assertFalse(noticed, "Took a helicopter ${shooter.distanceTo(heli).toInt()} blocks off as a target at night at tick ${helper.tick}")
                if (helper.tick >= NEVER_TICKS) {
                    scene.result("PASS", "not noticed in $NEVER_TICKS ticks")
                    helper.succeed()
                }
            }
        }
    }

    /**
     * From a gunship's gun at night a man 80 blocks off, in the open, is past what the dark lets the
     * gunner see (#245): he is not taken as a target at once, nor in ten seconds.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 300, batch = "heli-gunner-night")
    fun gunshipGunnerDoesNotTakeAManInTheDarkAtOnce(helper: GameTestHelper) {
        StructureUtils.removeBarriers(helper.bounds, helper.level)
        val scene = CombatIdleGameTests.Scene(helper, "heli-gunner-night")
        val manager = SquadManager.get(helper.level)
        val oldTime = helper.level.dayTime
        helper.level.setDayTime(NIGHT_TIME)
        DebugFlags.enabledGroups += setOf(LogGroup.SPOTTING)
        val heli = Ports.vehicles.create(helper.level, HelicopterModel.MI_28)!!
        val at = helper.absoluteVec(Vec3(20.5, 30.0, 60.5))
        heli.moveTo(at.x, at.y, at.z, -90f, 0f)
        heli.setNoGravity(true)
        helper.level.addFreshEntity(heli)
        Ports.vehicles.fuelAndArm(heli, HelicopterModel.MI_28)
        SquadTeams.assign(heli, SquadFaction.PIG)
        val pilot = scene.spawn(Vec3(20.5, 1.0, 62.5), role = NpcClass.HELICOPTER_PILOT).also { it.assignedVehicleId = heli.uuid }
        val gunner = scene.spawn(Vec3(21.5, 1.0, 62.5), role = NpcClass.HELICOPTER_GUNNER).also { it.assignedVehicleId = heli.uuid }
        helper.assertTrue(pilot.startRiding(heli, true) && gunner.startRiding(heli, true), "The crew did not take their seats")
        helper.assertTrue(Ports.vehicles.hasWeaponAt(heli, gunner), "The gunner is not at a gun")
        val enemy = scene.enemy(Vec3(100.5, 1.0, 60.5))
        val squad = manager.create(helper.level, UUID(90, 2461), SquadFaction.PIG, listOf(pilot.uuid, gunner.uuid))
        manager.setOrder(squad.id, SquadOrder.DEFEND)
        manager.setObjective(helper.level, squad.id, pilot.blockPosition())
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            heli.discard()
            helper.level.setDayTime(oldTime)
        }
        helper.onEachTick {
            heli.setPos(at.x, at.y, at.z)
            heli.deltaMovement = Vec3.ZERO
            val taken = gunner.target?.uuid == enemy.uuid
            if (helper.tick % 50L == 0L) scene.result("RUNNING", "tick=${helper.tick} taken=$taken distance=${gunner.distanceTo(enemy).toInt()}")
            helper.assertFalse(taken, "The gunner took a man ${gunner.distanceTo(enemy).toInt()} blocks off in the dark at tick ${helper.tick}")
            if (helper.tick >= NEVER_TICKS) {
                scene.result("PASS", "not taken in $NEVER_TICKS ticks")
                helper.succeed()
            }
        }
    }
}
