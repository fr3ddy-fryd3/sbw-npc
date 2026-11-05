package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.diagnostics.DecisionTrace
import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.combat.AntiArmourKit
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
            if (helper.tick % 50L == 0L) front.scene.result("RUNNING", "tick=${helper.tick} at=${helper.relativeVec(man.position())} hidden=$hidden reports=${NativeTactics.reports(man).values.map { "${it.ticket.identity}:${it.status}:${it.reason}" }}")
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

    /** A tank-focused ATTACK does not occupy the rifleman's aim while infantry can be fought from shelter. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 800, batch = "armour-infantry")
    fun shelteredRiflemanFiresAtInfantryBesideArmour(helper: GameTestHelper) {
        val front = Front(helper, "armour-infantry", wall = true, roles = listOf(NpcClass.RIFLEMAN))
        val man = front.men.single()
        val infantry = requireNotNull(front.rifleman)
        val point = helper.absoluteVec(Vec3(42.5, 1.0, 80.5))
        infantry.moveTo(point.x, point.y, point.z)
        front.manager.setOrder(front.squad.id, SquadOrder.ATTACK)
        front.manager.setObjective(helper.level, front.squad.id, front.lav.blockPosition())
        front.manager.setFocus(front.squad.id, front.crew.uuid)
        var safeShots = 0
        var shotsAtCrew = 0
        var lastShot = man.lastShotTick
        helper.onEachTick {
            if (helper.tick == 10L) {
                com.sbwnpc.squad.combat.tactics.SquadTactics.observe(man, front.crew)
                com.sbwnpc.squad.combat.tactics.SquadTactics.observe(man, infantry)
            }
            if (man.lastShotTick != lastShot) {
                if (man.target === front.crew) shotsAtCrew++
                if (man.target === infantry && !front.exposed(man)) safeShots++
                lastShot = man.lastShotTick
            }
            if (helper.tick >= 600L) {
                helper.assertTrue(safeShots > 0, "He never fired at infantry while sheltered from armour: at=${helper.relativeVec(man.position())} exposed=${front.exposed(man)} target=${man.target?.uuid} reports=${NativeTactics.reports(man).values} contacts=${front.squad.tactics.contacts.values}")
                helper.assertTrue(shotsAtCrew == 0, "He fired $shotsAtCrew rounds at armour")
                front.scene.result("PASS", "safeShots=$safeShots")
                helper.succeed()
            }
        }
    }

    /** A loaded launcher sorties without advancing along the threat bearing, fires, and returns. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 900, batch = "armour-sortie")
    fun launcherSortiesFiresAndReturnsFromArmourCover(helper: GameTestHelper) {
        val front = Front(helper, "armour-sortie", wall = true, roles = listOf(NpcClass.MACHINE_GUNNER), infantry = false)
        front.lav.isInvulnerable = true
        val man = front.men.single()
        front.manager.setOrder(front.squad.id, SquadOrder.ATTACK)
        front.manager.setObjective(helper.level, front.squad.id, front.lav.blockPosition())
        front.manager.setFocus(front.squad.id, front.crew.uuid)
        val launcher = { if (AntiArmourKit.isLauncher(man.mainHandItem)) man.mainHandItem else man.antiArmourWeapon }
        val rockets = Ports.guns.roundsLeft(launcher())
        var fired = false
        var returned = false
        var sawReturn = false
        var sortieOrigin: Vec3? = null
        helper.onEachTick {
            if (helper.tick == 10L) com.sbwnpc.squad.combat.tactics.SquadTactics.observe(man, front.crew)
            val sortie = NativeTactics.reports(man).values.any { ":SORTIE:" in it.ticket.identity || ":FIRE:" in it.ticket.identity }
            if (sortie && sortieOrigin == null) sortieOrigin = man.position()
            if (sortie) {
                sortieOrigin?.let { origin ->
                    val toward = front.lav.position().subtract(origin).multiply(1.0, 0.0, 1.0).normalize()
                    helper.assertTrue(man.position().subtract(origin).dot(toward) <= 0.75, "The launcher sortie moved forward toward armour")
                }
            }
            fired = fired || Ports.guns.roundsLeft(launcher()) < rockets
            if (fired) {
                sawReturn = sawReturn || NativeTactics.reports(man).values.any { ":RETURN:" in it.ticket.identity }
                returned = returned || (sawReturn && !front.exposed(man))
            }
            if (returned) {
                front.scene.result("PASS", "fired=$fired returned=$returned")
                helper.succeed()
            }
        }
    }

    /** Grounded operators launch only after reaching armour shelter, including a walk out from under a roof. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 700, batch = "armour-drone")
    fun operatorLaunchesFromShelterFromArmour(helper: GameTestHelper) {
        val front = Front(helper, "armour-drone", wall = true, roles = listOf(NpcClass.DRONE_OPERATOR), infantry = false)
        val man = front.men.single()
        man.dronesLeft = 0
        front.manager.setOrder(front.squad.id, SquadOrder.ATTACK)
        front.manager.setObjective(helper.level, front.squad.id, front.lav.blockPosition())
        front.manager.setFocus(front.squad.id, front.crew.uuid)
        for (x in 18..23) for (z in 48..57) helper.setBlock(BlockPos(x, 4, z), Blocks.STONE)
        helper.onEachTick {
            if (helper.tick == 10L) com.sbwnpc.squad.combat.tactics.SquadTactics.observe(man, front.crew)
            if (helper.tick == 80L) man.dronesLeft = 1
            if (helper.tick >= 80L && man.holds(NpcActivity.DRONE)) helper.assertFalse(front.exposed(man), "He prepared his drone while exposed to armour: tick=${helper.tick} at=${helper.relativeVec(man.position())} reports=${NativeTactics.reports(man).values} contacts=${front.squad.tactics.contacts.values}")
            if (helper.tick > 80L && man.dronesLeft == 0) {
                helper.assertFalse(front.exposed(man), "He launched while exposed to armour")
                front.scene.result("PASS", "at=${helper.relativeVec(man.position())}")
                helper.succeed()
            }
        }
    }

    /** A threat memory expires after its last observed line, without tracking a hidden vehicle. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 700, batch = "armour-expiry")
    fun armourResponseExpiresAfterLosingItsLine(helper: GameTestHelper) {
        val front = Front(helper, "armour-expiry", wall = true, roles = listOf(NpcClass.RIFLEMAN), infantry = false)
        val man = front.men.single()
        var answered = false
        var hidden = false
        helper.onEachTick {
            if (helper.tick == 10L) com.sbwnpc.squad.combat.tactics.SquadTactics.observe(man, front.crew)
            answered = answered || NativeTactics.reports(man).values.any { it.ticket.identity.startsWith("armour:") }
            if (helper.tick == 100L) {
                // Two blocks east keeps his own cell clear; stone against his feet suffocates him.
                val at = BlockPos.containing(helper.relativeVec(man.position()))
                for (x in 2..3) for (z in -8..8) for (y in 0..3) helper.setBlock(at.offset(x, y, z), Blocks.STONE)
            }
            if (helper.tick > 100L) hidden = hidden || !front.exposed(man)
            if (helper.tick >= 550L) {
                helper.assertTrue(answered, "He never answered armour")
                helper.assertTrue(hidden, "The wall never broke the armour's line: at=${helper.relativeVec(man.position())}")
                helper.assertFalse(NativeTactics.reports(man).values.any { it.ticket.identity.startsWith("armour:") }, "The hidden armour kept his response indefinitely: at=${helper.relativeVec(man.position())} exposed=${front.exposed(man)} reports=${NativeTactics.reports(man).values} known=${front.squad.tactics.contacts.values.map { it.id to (helper.level.gameTime - it.seenAt) }}")
                front.scene.result("PASS", "expired=true")
                helper.succeed()
            }
        }
    }

    /** Known armour constrains a launch column even if it has never had a line to the operator. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "armour-known-launch")
    fun hiddenOperatorDoesNotWalkIntoKnownArmourToLaunch(helper: GameTestHelper) {
        val front = Front(helper, "armour-known-launch", wall = true, roles = listOf(NpcClass.DRONE_OPERATOR, NpcClass.RIFLEMAN), infantry = false)
        val man = front.men.first()
        val observer = front.men.last()
        val home = helper.absoluteVec(Vec3(20.5, 1.0, 50.5))
        man.moveTo(home.x, home.y, home.z)
        man.dronesLeft = 0
        val speed = requireNotNull(man.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED))
        val normalSpeed = speed.baseValue
        speed.baseValue = 0.0
        front.manager.setObjective(helper.level, front.squad.id, man.blockPosition())
        for (x in 6..26) for (z in 34..68) helper.setBlock(BlockPos(x, 4, z), Blocks.STONE)
        var launched = false
        helper.onEachTick {
            if (helper.tick % 20L == 10L) com.sbwnpc.squad.combat.tactics.SquadTactics.observe(observer, front.crew)
            if (helper.tick == 80L) {
                helper.assertFalse(front.exposed(man), "The operator fixture did not start sheltered")
                val exposedColumn = helper.absoluteVec(Vec3(26.5, 1.0, 60.5))
                helper.assertFalse(NativeTactics.armourSheltered(man, exposedColumn), "A known armour source allowed an exposed launch column before any pin")
                speed.baseValue = normalSpeed
                man.dronesLeft = 1
            }
            if (helper.tick >= 80L && man.holds(NpcActivity.DRONE)) helper.assertFalse(front.exposed(man), "The known armour launch walk exposed its operator")
            if (helper.tick >= 80L) launched = launched || man.dronesLeft == 0
            if (helper.tick >= 350L) {
                helper.assertFalse(launched, "He left shelter to launch into known armour's line: at=${helper.relativeVec(man.position())} exposed=${front.exposed(man)} contacts=${front.squad.tactics.contacts.values} reports=${NativeTactics.reports(man).values}")
                helper.assertTrue(man.dronesLeft == 1, "A refused launch spent its drone")
                front.scene.result("PASS", "launch refused without spending drone")
                helper.succeed()
            }
        }
    }

    /** A fresh sighting of a tank well to the side invalidates the old source and its physical unit. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 600, batch = "armour-moved-source")
    fun movedArmourReplansCoverFromItsNewObservedSource(helper: GameTestHelper) {
        val front = Front(helper, "armour-moved-source", wall = true, roles = listOf(NpcClass.RIFLEMAN), infantry = false)
        val man = front.men.single()
        var hiddenAfterMove = 0
        helper.onEachTick {
            if (helper.tick == 10L) com.sbwnpc.squad.combat.tactics.SquadTactics.observe(man, front.crew)
            if (helper.tick == 100L) {
                val point = helper.absoluteVec(Vec3(60.5, 1.0, 90.5))
                front.lav.moveTo(point.x, point.y, point.z)
            }
            if (helper.tick == 110L) com.sbwnpc.squad.combat.tactics.SquadTactics.observe(man, front.crew)
            if (helper.tick > 150L && !front.exposed(man)) hiddenAfterMove++
            if (helper.tick >= 400L) {
                helper.assertTrue(hiddenAfterMove >= 40, "He stayed exposed to the moved armour: hidden=$hiddenAfterMove")
                front.scene.result("PASS", "source invalidated and cover replanned")
                helper.succeed()
            }
        }
    }
}
