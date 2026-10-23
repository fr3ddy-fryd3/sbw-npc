package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.domain.port.Ports
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

/** A visible head above cover is not a usable target for the vehicle's gun. */
@PrefixGameTestTemplate(false)
object VehicleSupportSightlineGameTests {
    /** A live target whose body can be hidden after the man takes the free vehicle's gun. */
    private class ConcealedTarget(val helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "support-sightline")
        val man = scene.spawn(Vec3(20.5, 1.0, 20.5))
        val enemy = scene.enemy(Vec3(24.5, 1.0, 58.5))
        val gun = requireNotNull(Ports.vehicles.create(helper.level, TransportVehicle.LAV_150))
        private val manager = SquadManager.get(helper.level)
        private val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(man.uuid))

        init {
            val at = helper.absoluteVec(Vec3(24.5, 1.0, 20.5))
            gun.moveTo(at.x, at.y, at.z, 0f, 0f)
            helper.level.addFreshEntity(gun)
            Ports.vehicles.fuelAndArm(gun, TransportVehicle.LAV_150)
            manager.issueOrderAt(helper.level, squad.id, SquadOrder.DEFEND, man.blockPosition())
            scene.onClose = {
                manager.deleteSquad(helper.level, squad.id)
                gun.discard()
            }
        }

        /** Covers the target's chest while leaving its eyes above the slabs. */
        fun hideBody() {
            for (x in 23..25) {
                helper.setBlock(BlockPos(x, 1, 57), Blocks.BEDROCK)
                helper.setBlock(BlockPos(x, 2, 57), Blocks.STONE_SLAB)
            }
        }

        fun assertOnlyHeadVisible() {
            helper.assertTrue(man.sensing.hasLineOfSight(enemy), "The fixture must keep vanilla eye-to-eye sight")
            helper.assertTrue(Sightline.blocked(helper.level, man.eyePosition, enemy.boundingBox.center, man), "The fixture must hide the target's body")
        }
    }

    /** The gunner gives up an eye-only target and does not keep boarding for it. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1400, batch = "support-sightline")
    fun freeVehicleGunnerDropsTargetWhoseBodyIsCovered(helper: GameTestHelper) {
        val fixture = ConcealedTarget(helper)
        var hiddenAt: Long? = null
        helper.onEachTick {
            if (hiddenAt == null && fixture.man.vehicle === fixture.gun) hiddenAt = helper.tick
            val since = hiddenAt?.let { helper.tick - it } ?: return@onEachTick
            // Keep the cover intact if an already-fired cannon round hits the slab.
            fixture.hideBody()
            if (since == 10L) fixture.assertOnlyHeadVisible()
            if (since >= 340) helper.assertTrue(fixture.man.vehicle !== fixture.gun, "The gunner kept an eye-only target behind cover")
            if (since >= 560) {
                helper.assertTrue(fixture.enemy.isAlive, "The target died instead of being dropped")
                fixture.scene.result("PASS", "hiddenAt=$hiddenAt finishedAt=${helper.tick}")
                helper.succeed()
            }
        }
    }
}
