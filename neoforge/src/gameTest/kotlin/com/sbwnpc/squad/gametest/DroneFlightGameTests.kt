package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.StructureUtils
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.abs

@PrefixGameTestTemplate(false)
object DroneFlightGameTests {
    /**
     * A strike drone sent over a hill comes back down to its cruise height on the far side. It used
     * to hold the hill's height all the way, because its wanted height only came down 0.5 a tick,
     * inside the controller's deadband (#121).
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "aviation_arena", timeoutTicks = 900, batch = "drone-ridge")
    fun strikeDroneComesDownAfterARidge(helper: GameTestHelper) {
        StructureUtils.removeBarriers(helper.bounds, helper.level)
        DebugFlags.enabledGroups = setOf(LogGroup.FIRE_SUPPORT)
        for (x in 0..191) for (z in 0..191) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        // The same hill as the helicopter ridge scenes: 30 high at x 80, gone by x 115.
        for (x in 45..115) {
            val top = (30.0 * (1.0 - abs(x - 80) / 35.0)).toInt()
            for (z in 70..130) for (y in 1..top) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        }
        val operator = AttackDeploymentGameTests.spawn(helper, Vec3(20.5, 1.0, 100.5), SquadFaction.PIG, role = NpcClass.DRONE_OPERATOR)
        // An elite operator reaches 160, so the objective 130 away can be struck.
        operator.npcRank = NpcRank.ELITE
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(operator.uuid))
        manager.setOrder(squad.id, SquadOrder.ATTACK)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(150, 1, 100)))
        val heights = mutableListOf<Double>()
        val trace = StringBuilder("tick,x,height,vy\n")
        helper.onEachTick {
            // The operator's is the only drone in the arena.
            val drone = helper.level.getEntities(operator, operator.boundingBox.inflate(200.0)) { Ports.drones.isDrone(it) }.firstOrNull()
                ?: return@onEachTick
            val x = helper.relativeVec(drone.position()).x
            val height = drone.y - helper.level.getHeight(Heightmap.Types.MOTION_BLOCKING, drone.blockX, drone.blockZ)
            trace.append("${helper.tick},${"%.1f".format(x)},${"%.2f".format(height)},${"%.3f".format(drone.deltaMovement.y)}\n")
            // Past the hill and before the attack dive, which starts within 15 of the objective.
            if (x in 122.0..132.0) heights += height
            if (x > 132.0 && heights.isNotEmpty()) {
                Path.of(System.getProperty("sbwnpc.gameTestReports"), "${helper.testInfo.testName}.csv").also { Files.createDirectories(it.parent) }.let { Files.writeString(it, trace) }
                helper.assertTrue(heights.max() <= 24.0, "Drone crossed the flat past the hill ${heights.max().toInt()} blocks up")
                // Down to its cruise height, not bobbing or skimming the ground on the way.
                helper.assertTrue(heights.min() >= 6.0 && heights.max() - heights.min() <= 6.0, "Drone height past the hill ran ${heights.min().toInt()}..${heights.max().toInt()}")
                helper.succeed()
            }
        }
    }
}
