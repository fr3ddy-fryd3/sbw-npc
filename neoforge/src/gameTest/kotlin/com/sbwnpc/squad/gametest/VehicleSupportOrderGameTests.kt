package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.VehicleTransportClaims
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.TransportVehicle
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

private fun park(helper: GameTestHelper, at: Vec3): Entity =
    requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_150)).also {
        val point = helper.absoluteVec(at)
        it.moveTo(point.x, point.y, point.z, 0f, 0f)
        helper.level.addFreshEntity(it)
        Ports.vehicles.fuelAndArm(it, TransportVehicle.LAV_150)
    }

private fun assertSupportReleased(helper: GameTestHelper, gun: Entity, man: NpcEntity) {
    // The transport executor may legitimately claim the now-free wheel to carry him away.
    val driving = VehicleTransportClaims.driverOf(gun.uuid) == man.uuid
    helper.assertTrue(man.vehicle !== gun || driving, "The gunner kept the free vehicle for combat support")
    helper.assertTrue(VehicleTransportClaims.vehicleOf(man.uuid) != gun.uuid || driving, "Combat support kept its seat claim")
}

/** A free vehicle's gun must not keep a man from a new movement or withdrawal order. */
@PrefixGameTestTemplate(false)
object VehicleSupportOrderGameTests {
    private fun supportYieldsToOrder(helper: GameTestHelper, order: SquadOrder) {
        val scene = CombatIdleGameTests.Scene(helper, "support-${order.name.lowercase()}")
        val manager = SquadManager.get(helper.level)
        val man = scene.spawn(Vec3(20.5, 1.0, 20.5))
        val gun = park(helper, Vec3(24.5, 1.0, 20.5))
        val enemyHull = park(helper, Vec3(24.5, 1.0, 56.5))
        val enemy = scene.enemy(Vec3(24.5, 1.0, 58.5))
        helper.assertTrue(enemy.startRiding(enemyHull, true), "The enemy crewman could not board")
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(man.uuid))
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.DEFEND, man.blockPosition())
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            gun.discard()
            enemyHull.discard()
        }
        var orderedAt: Long? = null
        helper.onEachTick {
            if (orderedAt == null && man.vehicle === gun) {
                helper.assertTrue(Ports.vehicles.hasWeaponAt(gun, man), "The man boarded an unarmed seat")
                gun.deltaMovement = Vec3(0.4, 0.0, 0.0)
                val destination = helper.absolutePos(BlockPos(140, 1, 20))
                manager.issueOrderAt(helper.level, squad.id, order, destination)
                orderedAt = helper.tick
            }
            val since = orderedAt?.let { helper.tick - it } ?: return@onEachTick
            if (since > 0 && man.vehicle !== gun) {
                helper.assertTrue(gun.deltaMovement.horizontalDistanceSqr() <= 0.0004, "The gunner dismounted before the support vehicle stopped")
            }
            if (since > 20) {
                assertSupportReleased(helper, gun, man)
            }
            // Longer than the ordinary reboarding cooldown: the order itself must keep him out.
            if (since >= 260) {
                helper.assertTrue(enemy.isAlive, "The target died, so the order did not cause the exit")
                scene.result("PASS", "orderedAt=$orderedAt finishedAt=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /** A direct retreat order frees the gun even while the armoured target remains alive. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1200, batch = "support-retreat")
    fun freeVehicleGunnerYieldsToRetreatWithoutResumingSupport(helper: GameTestHelper) = supportYieldsToOrder(helper, SquadOrder.RETREAT)

    /** An explicit MOVE takes the man away from his temporary weapon station. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1200, batch = "support-move")
    fun freeVehicleGunnerYieldsToMoveWithoutResumingSupport(helper: GameTestHelper) = supportYieldsToOrder(helper, SquadOrder.MOVE)

    /** A squad standing on its MOVE point in a fight mans the gun beside it (in-game 03.11 and 04.11). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 600, batch = "support-at-move-point")
    fun squadOnItsMovePointMansTheGunBesideIt(helper: GameTestHelper) = manTheGun(helper, "at-move-point")

    /** An already-manned gun stays occupied when allies leave an unchanged MOVE point. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1000, batch = "support-allies-leave")
    fun gunnerKeepsMovePointStationWhenAlliesLeave(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "support-allies-leave")
        val manager = SquadManager.get(helper.level)
        val man = scene.spawn(Vec3(20.5, 1.0, 20.5)).apply { isInvulnerable = true }
        val allies = (0..2).map { index ->
            scene.spawn(Vec3(30.5 + index * 2, 1.0, 20.5)).apply {
                isNoAi = true
                isInvulnerable = true
            }
        }
        val gun = park(helper, Vec3(20.5, 1.0, 24.5))
        val enemy = scene.enemy(Vec3(60.5, 1.0, 20.5)).apply {
            isNoAi = true
            isInvulnerable = true
        }
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, (allies + man).map { it.uuid })
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.MOVE, man.blockPosition())
        manager.setFocus(squad.id, enemy.uuid)
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            gun.discard()
            enemy.discard()
        }
        var movedAt: Long? = null
        helper.onEachTick {
            if (movedAt == null && man.vehicle === gun) {
                allies.forEachIndexed { index, ally ->
                    val away = helper.absoluteVec(Vec3(120.5 + index * 2, 1.0, 20.5))
                    ally.teleportTo(away.x, away.y, away.z)
                }
                movedAt = helper.tick
            }
            val since = movedAt?.let { helper.tick - it } ?: return@onEachTick
            if (since > 1) helper.assertTrue(man.vehicle === gun, "The gunner left after allies moved away from the unchanged MOVE point")
            if (since >= 80) {
                scene.result("PASS", "movedAt=$movedAt finishedAt=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /** A repeated MOVE is still a new command and releases the old station. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1000, batch = "support-repeat-move")
    fun gunnerYieldsToNewMoveAtSamePoint(helper: GameTestHelper) = supportYieldsToRepeatedMove(helper)

    private fun supportYieldsToRepeatedMove(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "support-change-order")
        val manager = SquadManager.get(helper.level)
        val man = scene.spawn(Vec3(20.5, 1.0, 20.5)).apply { isInvulnerable = true }
        val gun = park(helper, Vec3(20.5, 1.0, 24.5))
        val enemy = scene.enemy(Vec3(60.5, 1.0, 20.5)).apply {
            isNoAi = true
            isInvulnerable = true
        }
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(man.uuid))
        val destination = man.blockPosition()
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.MOVE, destination)
        manager.setFocus(squad.id, enemy.uuid)
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            gun.discard()
            enemy.discard()
        }
        var changedAt: Long? = null
        helper.onEachTick {
            if (changedAt == null && man.vehicle === gun) {
                manager.issueOrderAt(helper.level, squad.id, SquadOrder.MOVE, destination)
                changedAt = helper.tick
            }
            val since = changedAt?.let { helper.tick - it } ?: return@onEachTick
            if (since >= 20) {
                assertSupportReleased(helper, gun, man)
                scene.result("PASS", "changedAt=$changedAt finishedAt=${helper.tick}")
                helper.succeed()
            }
        }
    }

    private fun manTheGun(helper: GameTestHelper, name: String) {
        val scene = CombatIdleGameTests.Scene(helper, "support-$name")
        val manager = SquadManager.get(helper.level)
        val man = scene.spawn(Vec3(20.5, 1.0, 20.5))
        // A lookout on a pillar, out of the gun's reach so the gun is the man's.
        for (y in 1..4) helper.setBlock(BlockPos(18, y, 62), net.minecraft.world.level.block.Blocks.STONE)
        val lookout = scene.spawn(Vec3(18.5, 5.0, 62.5))
        val gun = park(helper, Vec3(20.5, 1.0, 24.5))
        val enemy = scene.enemy(Vec3(60.5, 1.0, 20.5)).apply {
            isNoAi = true
            getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)!!.baseValue = 50000.0
            health = maxHealth
        }
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(man.uuid, lookout.uuid))
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.MOVE, man.blockPosition())
        manager.setFocus(squad.id, enemy.uuid)
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            gun.discard()
            enemy.discard()
        }
        helper.onEachTick {
            if (man.vehicle === gun) {
                scene.result("PASS", "tick=${helper.tick}")
                helper.succeed()
            }
        }
    }
}
