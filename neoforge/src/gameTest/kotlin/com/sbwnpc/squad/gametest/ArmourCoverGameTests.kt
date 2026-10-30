package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.AntiArmourKit
import com.sbwnpc.squad.combat.ArmourCover
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

    /** Cover near: no rifleman holds the crew, each stays out of its line but for a peek, and they fire at the infantry. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 600, batch = "armour-cover-wall")
    fun riflemenTakeCoverFromArmourAndFireAtItsInfantry(helper: GameTestHelper) {
        val front = Front(helper, "armour-cover-wall", wall = true, roles = listOf(NpcClass.RIFLEMAN, NpcClass.RIFLEMAN, NpcClass.RIFLEMAN, NpcClass.MEDIC))
        val riflemen = front.men.filter { it.npcClass == NpcClass.RIFLEMAN }
        val medic = front.men.single { it.npcClass == NpcClass.MEDIC }
        val rounds = riflemen.associateWith { Ports.guns.roundsLeft(it.primaryGun()) }
        helper.onEachTick {
            front.men.forEach { helper.assertFalse(it.target?.uuid == front.crew.uuid, "${it.npcClass} took the armour's crew for a target at tick ${helper.tick}") }
            helper.assertTrue(medic.armourPin == null, "The medic was pinned by armour")
            if (helper.tick >= 300L) {
                riflemen.forEach {
                    // A peek out of cover to fire at the infantry may cross the armour's line; standing in it may not.
                    val sanctioned = it.holdsAny(NpcActivity.COVER, NpcActivity.PEEK) || it.firedRecently(60)
                    helper.assertTrue(sanctioned || !front.exposed(it), "A rifleman stood in the armour's line of fire at tick ${helper.tick}: ${NpcActivity.entries.filter(it::holds)} pin=${it.armourPin} suppressed=${it.isSuppressed()}")
                }
            }
            if (helper.tick % 100L == 0L) front.scene.result("RUNNING", "tick=${helper.tick} pinned=${riflemen.count { it.armourPin != null }} cover=${riflemen.count { it.holds(NpcActivity.COVER) }} exposed=${riflemen.count(front::exposed)}")
            if (helper.tick >= 500L) {
                helper.assertTrue(riflemen.any { it.armourPin != null || it.holds(NpcActivity.COVER) }, "Nobody was pinned by the armour")
                helper.assertTrue(riflemen.any { Ports.guns.roundsLeft(it.primaryGun()) < rounds.getValue(it) }, "Nobody fired at the infantry")
                front.scene.result("PASS", "tick=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /** No cover on open ground: they move away out of its line instead of standing in it. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 600, batch = "armour-cover-open")
    fun riflemenWithNoCoverMoveOutOfTheArmoursLine(helper: GameTestHelper) {
        val front = Front(helper, "armour-cover-open", wall = false, roles = List(3) { NpcClass.RIFLEMAN })
        val start = front.men.map { it.position().distanceTo(front.lav.position()) }
        helper.onEachTick {
            front.men.forEach { helper.assertFalse(it.target?.uuid == front.crew.uuid, "A rifleman took the armour's crew for a target at tick ${helper.tick}") }
            if (helper.tick % 100L == 0L) front.scene.result("RUNNING", "tick=${helper.tick} distances=${front.men.map { it.position().distanceTo(front.lav.position()).toInt() }}")
            if (helper.tick >= 500L) {
                val moved = front.men.indices.count { front.men[it].position().distanceTo(front.lav.position()) - start[it] >= 10.0 || !front.exposed(front.men[it]) }
                helper.assertTrue(moved == front.men.size, "Only $moved of ${front.men.size} riflemen left the armour's line")
                front.scene.result("PASS", "tick=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /** A machine gunner with a loaded launcher sent at the crew still fires a rocket at it. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 600, batch = "armour-cover-launcher")
    fun launcherStillAnswersArmour(helper: GameTestHelper) {
        val front = Front(helper, "armour-cover-launcher", wall = false, roles = listOf(NpcClass.MACHINE_GUNNER), infantry = false)
        val gunner = front.men.single()
        helper.assertTrue(AntiArmourKit.loaded(gunner), "The machine gunner has no loaded launcher")
        // Sent at the crew: on DEFEND a gunner looks round as a sentry and noticing alone decided when he saw it.
        front.manager.setOrder(front.squad.id, SquadOrder.ATTACK)
        front.manager.setObjective(helper.level, front.squad.id, front.lav.blockPosition())
        front.manager.setFocus(front.squad.id, front.crew.uuid)
        val launcher = { if (AntiArmourKit.isLauncher(gunner.mainHandItem)) gunner.mainHandItem else gunner.antiArmourWeapon }
        val rockets = Ports.guns.roundsLeft(launcher())
        helper.onEachTick {
            helper.assertTrue(gunner.armourPin == null, "A man with a loaded launcher was pinned by the armour")
            if (Ports.guns.roundsLeft(launcher()) < rockets) {
                front.scene.result("PASS", "tick=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /**
     * A pin does not outlast the line for long: walled in where the armour cannot see him while his
     * medic still does, a rifleman is let go [ArmourCover.HOLD_TICKS] plus [ArmourCover.PIN_TICKS] after.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 900, batch = "armour-cover-release")
    fun pinLapsesOnceTheArmourHasLostHim(helper: GameTestHelper) {
        val front = Front(helper, "armour-cover-release", wall = false, roles = listOf(NpcClass.RIFLEMAN, NpcClass.MEDIC), infantry = false)
        val rifleman = front.men.first()
        var walledAt: Long? = null
        helper.onEachTick {
            if (walledAt == null && rifleman.armourPin != null) {
                walledAt = helper.tick
                val at = BlockPos.containing(helper.relativeVec(rifleman.position()))
                for (dx in -1..1) for (dz in -1..1) for (y in 0..2) if (dx != 0 || dz != 0) helper.setBlock(at.offset(dx, y, dz), Blocks.STONE)
                helper.setBlock(at.offset(0, 2, 0), Blocks.STONE)
            }
            val since = walledAt?.let { helper.tick - it } ?: return@onEachTick
            if (since >= ArmourCover.HOLD_TICKS + ArmourCover.PIN_TICKS + 40) {
                helper.assertTrue(rifleman.armourPin == null, "A rifleman the armour cannot see stayed pinned $since ticks")
                front.scene.result("PASS", "tick=${helper.tick}")
                helper.succeed()
            }
        }
    }
}
