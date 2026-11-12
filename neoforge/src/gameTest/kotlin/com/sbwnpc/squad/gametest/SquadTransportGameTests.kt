package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.combat.HostileMobs
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.SquadTransit
import com.sbwnpc.squad.entity.ai.VehicleTransportClaims
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.TransportVehicle
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import net.tslat.smartbrainlib.util.BrainUtils
import java.util.UUID

/** A distant ATTACK travels as a squad before the operation hands out position work. */
@PrefixGameTestTemplate(false)
object SquadTransportGameTests {
    /** How near the DEFEND point the replaced order's men count as there: a five-man squad's spread round it. */
    private const val DEFEND_REACHED = 12.0

    /** By when a travelling squad's men all know whether they ride or walk: its plan's first pass and a margin. */
    private const val WALK_TOLD_TICKS = 20L

    /** Observes physical march while far; after transit the reinforcement makes for its march point ([entry]). */
    internal fun reinforcementMarch(helper: GameTestHelper, group: List<NpcEntity>, squad: Squad, entry: Point3, elapsed: Long): Boolean {
        val transit = SquadTransit.distant(squad, helper.level)
        val astray = group.filter { npc ->
            NativeTactics.assignment(npc).let { task ->
                if (transit) task != null else task == null || task.anchor.distanceTo(Vec3(entry.x, entry.y, entry.z)) > 1.0
            }
        }
        helper.assertTrue(astray.isEmpty(), "Distant reinforcement is not marching to the entry: ${astray.map { helper.relativeVec(it.position()) to NativeTactics.assignment(it) }}")
        val moved = group.any { helper.relativeVec(it.position()).x > 7.5 }
        if (elapsed > 120) helper.assertTrue(moved, "Distant reinforcement did not physically start its ordinary march")
        return moved
    }

    private fun park(helper: GameTestHelper, at: Vec3): Entity =
        requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_25)).also {
            val point = helper.absoluteVec(at)
            it.moveTo(point.x, point.y, point.z, 0f, 0f)
            helper.level.addFreshEntity(it)
            Ports.vehicles.fuelAndArm(it, TransportVehicle.LAV_25)
        }

    private fun keepContact(men: List<NpcEntity>, enemy: NpcEntity?) {
        if (enemy == null) return
        men.forEach { BrainUtils.setTargetOfEntity(it, enemy) }
    }

    private fun assertTransportDriver(helper: GameTestHelper, lav: Entity) {
        val driver = VehicleTransportClaims.driverOf(lav.uuid)
        helper.assertTrue(driver != null && Ports.vehicles.seating(lav).firstOrNull()?.uuid == driver, "The boarded squad has no transport driver at the wheel")
    }

    /** A reported target beyond engagement distance does not switch off the common ride. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 700, batch = "remote-contact-transport")
    fun allFiveAttackersBoardWhileKeepingARemoteContact(helper: GameTestHelper) = wholeSquadBoards(helper, remoteContact = true)

    private fun wholeSquadBoards(helper: GameTestHelper, remoteContact: Boolean = false) {
        val scene = CombatIdleGameTests.Scene(helper, if (remoteContact) "remote-contact-transport" else "whole-squad-transport")
        val manager = SquadManager.get(helper.level)
        val men = listOf(32.5, 35.5, 38.5, 41.5, 68.5).map { scene.spawn(Vec3(it, 1.0, 20.5)) }
        val lav = park(helper, Vec3(45.5, 1.0, 20.5))
        val parkedAt = lav.position()
        val enemy = if (remoteContact) scene.enemy(Vec3(150.5, 1.0, 20.5)) else null
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
        }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(BlockPos(150, 1, 20)))
        keepContact(men, enemy)
        var retainedContactTicks = 0
        helper.onEachTick {
            if (enemy != null && men.any { it.target === enemy }) retainedContactTicks++
            keepContact(men, enemy)
            if (helper.tick == 40L) helper.assertTrue(men.none(SquadTactics::hasTask), "A distant attacker was given a position duty instead of the squad's ride")
            if (men.any { it.vehicle === lav } && men.any { it.vehicle !== lav }) {
                helper.assertTrue(lav.position().distanceTo(parkedAt) <= 4.0, "The driver left before the nearby men with remote contacts boarded")
            }
            if (men.all { it.vehicle === lav }) {
                assertTransportDriver(helper, lav)
                helper.assertTrue(!remoteContact || retainedContactTicks >= 5, "The remote contact was cleared instead of retained during boarding")
                scene.result("PASS", "tick=${helper.tick} aboard=${men.size} retainedContactTicks=$retainedContactTicks")
                helper.succeed()
            }
        }
    }

    /**
     * A zombie going for the squad from beyond [HostileMobs.CLOSE_RANGE] does not hold it back: the
     * squad boards and leaves, and whoever sees the zombie fires from where he is (owner 28.10, #231).
     * The zombie cannot walk, so it stays out there however long the boarding takes, and wears a
     * helmet so the daylight does not burn it.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 700, batch = "transport-far-mob")
    fun squadLeavesPastAZombieBeyondHalfItsEngageRange(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "transport-far-mob")
        val manager = SquadManager.get(helper.level)
        val men = listOf(32.5, 35.5, 38.5, 41.5).map { scene.spawn(Vec3(it, 1.0, 20.5)) }
        val lav = park(helper, Vec3(45.5, 1.0, 20.5))
        val zombie = requireNotNull(EntityType.ZOMBIE.create(helper.level)).also {
            val at = helper.absoluteVec(Vec3(32.5 - HostileMobs.CLOSE_RANGE - 4.0, 1.0, 20.5))
            it.moveTo(at.x, at.y, at.z, -90f, 0f)
            it.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.IRON_HELMET))
            it.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = 0.0
            helper.level.addFreshEntity(it)
            it.target = men.first()
        }
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
            zombie.discard()
        }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(BlockPos(150, 1, 20)))
        var sighted = false
        helper.onEachTick {
            // Seen from the start: noticing a zombie behind them took longer than boarding.
            if (zombie.isAlive) men.filter { it.vehicle == null }.forEach { BrainUtils.setTargetOfEntity(it, zombie) }
            if (men.any { it.target === zombie }) sighted = true
            helper.assertTrue(men.none { it.distanceTo(zombie) <= HostileMobs.CLOSE_RANGE }, "The zombie came within half its engage range: the scene no longer tests a far mob")
            if (men.all { it.vehicle === lav }) {
                helper.assertTrue(sighted, "Nobody had the zombie as a target, so it was no contact for the journey")
                assertTransportDriver(helper, lav)
                scene.result("PASS", "tick=${helper.tick} zombieAlive=${zombie.isAlive}")
                helper.succeed()
            }
        }
    }

    /**
     * A new order replaces the old one on the ride too: a squad that boarded for an Attack far to the
     * east and is then told to defend a spot behind it must not be driven on to the old objective
     * (in game 29.10 the ride went to the old target, dropped the men there, and they walked back).
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1200, batch = "transport-new-order")
    fun rideDoesNotCarryOnToTheObjectiveOfAReplacedOrder(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "transport-new-order")
        val manager = SquadManager.get(helper.level)
        val men = listOf(32.5, 35.5, 38.5, 41.5, 44.5).map { scene.spawn(Vec3(it, 1.0, 20.5)) }
        val lav = park(helper, Vec3(45.5, 1.0, 24.5))
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
        }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(BlockPos(160, 1, 20)))
        var replacedAt = -1L
        var boardedX = 0.0
        helper.onEachTick {
            if (replacedAt < 0 && men.all { it.vehicle === lav }) {
                replacedAt = helper.tick
                boardedX = helper.relativeVec(lav.position()).x
                manager.issueOrderAt(helper.level, squad.id, SquadOrder.DEFEND, helper.absolutePos(BlockPos(8, 1, 20)))
            }
            if (replacedAt >= 0) {
                val x = helper.relativeVec(lav.position()).x
                helper.assertTrue(x < boardedX + 15.0, "The ride drove on towards the replaced order's objective: x=$x, boarded at $boardedX, tick ${helper.tick}")
                // And the new order is carried out: the men end at the DEFEND point, not idle in a parked ride.
                val there = men.all { it.vehicle == null && helper.relativeVec(it.position()).subtract(8.5, 1.0, 20.5).horizontalDistance() <= DEFEND_REACHED }
                if (there) {
                    scene.result("PASS", "boardedAt=$replacedAt boardedX=$boardedX x=$x arrived=${helper.tick}")
                    helper.succeed()
                }
            }
        }
    }

    /** A man outside the vehicle search radius walks instead of making his own trip to the ride. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "transport-straggler")
    fun memberBeyondVehicleSearchRadiusWalksWhileTheOthersBoard(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "transport-straggler")
        val manager = SquadManager.get(helper.level)
        val near = (0 until 4).map { scene.spawn(Vec3(20.5 + it * 2, 1.0, 20.5)) }
        val straggler = scene.spawn(Vec3(100.5, 1.0, 20.5))
        val start = straggler.position()
        val lav = park(helper, Vec3(28.5, 1.0, 20.5))
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, (near + straggler).map { it.uuid })
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
        }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(BlockPos(200, 1, 20)))
        helper.onEachTick {
            helper.assertTrue(straggler.vehicle == null && VehicleTransportClaims.vehicleOf(straggler.uuid) == null, "The distant straggler was sent back to the vehicle")
            if (helper.tick >= 180) {
                helper.assertTrue(near.all { it.vehicle === lav }, "The nearby squad members did not all board")
                helper.assertTrue(straggler.x > start.x + 8.0, "The straggler did not continue towards the objective on foot")
                scene.result("PASS", "tick=${helper.tick} aboard=${near.size} walked=${straggler.x - start.x}")
                helper.succeed()
            }
        }
    }

    /**
     * A hidden recon squad goes on foot (owner 10.11) and is told so at once: its plan gave out no
     * seats, so its men looked for a vehicle for 10 s before they set off (#312). A free LAV beside
     * them stays empty.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 200, batch = "transport-recon")
    fun reconSquadWalksAtOnceAndLeavesTheVehicleBesideIt(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "transport-recon")
        val manager = SquadManager.get(helper.level)
        val men = (0 until 3).map { scene.spawn(Vec3(20.5 + it * 2, 1.0, 20.5), role = NpcClass.SCOUT) }
        val lav = park(helper, Vec3(28.5, 1.0, 24.5))
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid }, List(3) { NpcClass.SCOUT })
        // As with the owner beside them: at a quarter rate the men may miss the window the test is about.
        NpcEntity.fullRateBrains = true
        scene.onClose = {
            NpcEntity.fullRateBrains = false
            manager.deleteSquad(helper.level, squad.id)
            lav.discard()
        }
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, helper.absolutePos(BlockPos(200, 1, 20)))
        helper.onEachTick {
            helper.assertTrue(men.none { it.vehicle != null }, "A scout got into the vehicle")
            if (helper.tick >= WALK_TOLD_TICKS) {
                val seeking = men.filter { it.holds(NpcActivity.VEHICLE) }
                helper.assertTrue(seeking.isEmpty(), "${seeking.size} scouts still look for a vehicle at tick ${helper.tick}")
                helper.assertTrue(men.all(NativeTactics::walks), "The recon squad's men were not told to walk")
            }
            if (helper.tick >= WALK_TOLD_TICKS + 60) {
                helper.assertTrue(men.all { it.x > helper.absoluteVec(Vec3(30.5, 1.0, 20.5)).x }, "The scouts did not set off on foot")
                scene.result("PASS", "tick=${helper.tick} x=${men.map { it.x.toInt() }}")
                helper.succeed()
            }
        }
    }
}
