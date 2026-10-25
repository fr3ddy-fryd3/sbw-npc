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

/** A free vehicle's gun must not keep a man from a new movement or withdrawal order. */
@PrefixGameTestTemplate(false)
object VehicleSupportOrderGameTests {
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

    private fun supportYieldsToOrder(helper: GameTestHelper, order: SquadOrder, withdrawal: Boolean = false) {
        val scene = CombatIdleGameTests.Scene(helper, "support-${if (withdrawal) "withdrawal" else order.name.lowercase()}")
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
                if (withdrawal) {
                    manager.setObjective(helper.level, squad.id, destination)
                    manager.withdraw(squad.id, "gametest_losses")
                } else {
                    manager.issueOrderAt(helper.level, squad.id, order, destination)
                }
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

    /** Loss-driven withdrawal uses the same order contract as an explicit retreat. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1200, batch = "support-withdrawal")
    fun freeVehicleGunnerLeavesWhenSquadWithdraws(helper: GameTestHelper) = supportYieldsToOrder(helper, SquadOrder.RETREAT, withdrawal = true)

    /** An explicit MOVE takes the man away from his temporary weapon station. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1200, batch = "support-move")
    fun freeVehicleGunnerYieldsToMoveWithoutResumingSupport(helper: GameTestHelper) = supportYieldsToOrder(helper, SquadOrder.MOVE)
}
