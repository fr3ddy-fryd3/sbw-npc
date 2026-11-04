package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.AntiArmourKit
import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.ai.diagnostics.DecisionTrace
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.TransportVehicle
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/**
 * Men without a loaded launcher facing an armoured vehicle (#237): they never take its crew for a
 * target, take cover from it, and fight its infantry from there. A launcher still answers it.
 */
@PrefixGameTestTemplate(false)
object ArmourCoverGameTests {
    private const val DAY_TIME = 6000L

    /**
     * An enemy LAV-150 with a gunner at x 60, east of the squad standing in the open at x 20, and an
     * enemy rifleman beside the vehicle unless not [infantry]. With [wall], a wall south of the squad
     * gives cover from the vehicle a few blocks away.
     */
    private class Front(val helper: GameTestHelper, name: String, wall: Boolean, roles: List<NpcClass>, infantry: Boolean = true) {
        val scene = CombatIdleGameTests.Scene(helper, name)
        val manager = SquadManager.get(helper.level)
        private val oldTime = helper.level.dayTime
        val lav = requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_150)).also {
            val point = helper.absoluteVec(Vec3(60.5, 1.0, 60.5))
            it.moveTo(point.x, point.y, point.z, 90f, 0f)
            helper.level.addFreshEntity(it)
            Ports.vehicles.fuelAndArm(it, TransportVehicle.LAV_150)
        }
        val crew: NpcEntity = scene.enemy(Vec3(58.5, 1.0, 60.5)).also { helper.assertTrue(it.startRiding(lav, true), "The enemy crewman could not board") }
        val rifleman: NpcEntity? = if (infantry) scene.enemy(Vec3(56.5, 1.0, 64.5)) else null
        val men = roles.mapIndexed { i, role -> scene.spawn(Vec3(20.5, 1.0, 56.5 + i * 3), role) }
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })

        private val oldGroups = com.sbwnpc.squad.combat.DebugFlags.enabledGroups

        init {
            com.sbwnpc.squad.combat.DebugFlags.enabledGroups = oldGroups + setOf(com.sbwnpc.squad.combat.LogGroup.DIG, com.sbwnpc.squad.combat.LogGroup.ORDER)
            helper.level.setDayTime(DAY_TIME)
            if (wall) for (z in 44..54) for (y in 1..2) helper.setBlock(BlockPos(24, y, z), Blocks.STONE)
            manager.setOrder(squad.id, SquadOrder.DEFEND)
            manager.setObjective(helper.level, squad.id, men.first().blockPosition())
            scene.onClose = {
                manager.deleteSquad(helper.level, squad.id)
                crew.stopRiding()
                lav.discard()
                helper.level.setDayTime(oldTime)
                com.sbwnpc.squad.combat.DebugFlags.enabledGroups = oldGroups
            }
        }

        /** Whether the crewman has a line to [man]'s eye, the line the vehicle's guns fire along. */
        fun exposed(man: NpcEntity) = DetectionSightline.visible(helper.level, crew.eyePosition, man.eyePosition, man)
    }

    /** A lone vehicle provokes planned cover even without any bullet suppression. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "armour-planned-cover")
    fun loneArmourMakesRiflemanTakePlannedCover(helper: GameTestHelper) {
        val front = Front(helper, "armour-planned-cover", wall = true, roles = listOf(NpcClass.RIFLEMAN), infantry = false)
        val man = front.men.single()
        var hidden = 0
        var answered = false
        helper.onEachTick {
            // Supply a real sighting: this scene checks the response, not spotting latency.
            if (helper.tick == 10L) com.sbwnpc.squad.combat.tactics.SquadTactics.observe(man, front.crew)
            answered = answered || DecisionTrace.decisionsOf(man.uuid).any { it.event == "plan" && "*answer_armour" in it.detail }
            if (helper.tick > 100L && !front.exposed(man)) hidden++
            helper.assertTrue(NativeTactics.movable(man), "Armour suspended his plan")
            helper.assertFalse(man.isSuppressed(), "Seeing armour was turned into bullet suppression")
            if (helper.tick >= 300L) {
                helper.assertTrue(answered, "His plan never answered armour")
                helper.assertTrue(hidden >= 40, "He stayed in the armour's line: hidden=$hidden")
                front.scene.result("PASS", "hidden=$hidden")
                helper.succeed()
            }
        }
    }

    /** With no wall, unarmed infantry retreat even when infantry targets remain available. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "armour-planned-retreat")
    fun armourWithoutCoverMakesRiflemanRetreat(helper: GameTestHelper) {
        val front = Front(helper, "armour-planned-retreat", wall = false, roles = listOf(NpcClass.RIFLEMAN))
        val man = front.men.single()
        val start = man.position().distanceTo(front.lav.position())
        helper.onEachTick {
            if (helper.tick % 20L == 10L) com.sbwnpc.squad.combat.tactics.SquadTactics.observe(man, front.crew)
            if (helper.tick >= 300L) {
                val gained = man.position().distanceTo(front.lav.position()) - start
                helper.assertTrue(gained >= 6.0 || !front.exposed(man), "He neither escaped nor retreated: gained=$gained")
                front.scene.result("PASS", "gained=$gained")
                helper.succeed()
            }
        }
    }
}
