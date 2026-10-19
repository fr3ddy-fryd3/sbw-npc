package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@PrefixGameTestTemplate(false)
object ObserverLookGameTests {
    /**
     * Support men defending with nobody about watch the field level. With no focus point their
     * look used to be taken from their own gaze and raised every tick, so after a while they all
     * stared about 40 degrees up (#120).
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1000, batch = "observer-look")
    fun observersWithNoFocusWatchLevel(helper: GameTestHelper) {
        DebugFlags.enabledGroups = setOf(LogGroup.ORDER)
        for (x in 0..175) for (z in 0..95) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        // Cover to watch from, as in the tower scenes: open floor is no observation post.
        for ((x, z) in listOf(48 to 48, 48 to 76, 78 to 78, 78 to 50)) {
            for (offset in -2..2) {
                helper.setBlock(BlockPos(x + 2, 1, z + offset), Blocks.STONE)
                helper.setBlock(BlockPos(x + offset, 1, z - 2), Blocks.STONE)
            }
        }
        val npcs = listOf(Vec3(42.5, 1.0, 58.5), Vec3(48.5, 1.0, 58.5), Vec3(42.5, 1.0, 64.5), Vec3(48.5, 1.0, 64.5)).mapIndexed { i, at ->
            AttackDeploymentGameTests.spawn(helper, at, SquadFaction.PIG, randomSeed = 200L + i, role = if (i % 2 == 0) NpcClass.MACHINE_GUNNER else NpcClass.SNIPER)
        }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, npcs.map { it.uuid })
        manager.setOrder(squad.id, SquadOrder.DEFEND)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(64, 1, 64)))
        var steepest = 0f
        var watched = false
        helper.onEachTick {
            if (helper.tick < 300) return@onEachTick
            // Negative pitch is up. Only men with nothing to aim at: a target may well be up a tower.
            npcs.filter { it.isAlive && it.target == null }.forEach {
                steepest = minOf(steepest, it.xRot)
                if (SquadTactics.ownsLook(it)) watched = true
            }
            if (helper.tick >= 900) {
                helper.assertTrue(watched, "Nobody reached a post to watch from, so the look was never tested")
                helper.assertTrue(steepest >= -15f, "An observer with nothing to watch looked ${-steepest.toInt()} degrees up")
                helper.succeed()
            }
        }
    }
}
