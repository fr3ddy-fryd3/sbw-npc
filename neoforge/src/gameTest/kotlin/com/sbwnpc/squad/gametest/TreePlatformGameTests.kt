package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.combat.FiringPostArrival
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.ScaffoldingBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID
import kotlin.math.abs

/** Sentries take a player's platform round a tree, reached by a column of scaffolding. */
@PrefixGameTestTemplate(false)
object TreePlatformGameTests {
    /**
     * A defending squad's machine gunner or sniper climbs the scaffolding onto a platform built
     * round a tree in a wood, and holds a post on it.
     *
     * Laid out after the owner's tower in game (24.10): a 3x3 trunk, a 5x5 floor of top slabs
     * eight blocks up with a parapet of upside-down stairs, leaves round it and a crown above, and
     * one column of scaffolding through the leaves. Before leaves stopped counting as floors, the
     * treetops round it cost the sentries 24 s of search before they took it.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 2400, batch = "tree-platform")
    fun sentryClimbsScaffoldingOntoTreePlatform(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "tree-platform")
        val trunk = BlockPos(60, 1, 60)
        buildTower(helper, trunk)
        WOOD.forEach { plantSpruce(helper, it) }
        val roles = listOf(NpcClass.MACHINE_GUNNER, NpcClass.SNIPER, NpcClass.RIFLEMAN, NpcClass.RIFLEMAN, NpcClass.RIFLEMAN)
        val men = roles.mapIndexed { index, role -> scene.spawn(Vec3(52.5 + index * 2, 1.0, 52.5), role) }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID(91, 3), SquadFaction.PIG, men.map { it.uuid })
        scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        manager.setOrder(squad.id, SquadOrder.DEFEND)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(trunk.offset(0, 0, 4)))
        val sentries = men.take(2)
        helper.onEachTick {
            val up = sentries.firstOrNull { holdsPlatform(helper, it) }
            if (helper.tick % 100L == 0L) {
                val where = sentries.joinToString(" ") { npc -> "${npc.npcClass}=${helper.relativeVec(npc.position()).let { "%.0f,%.0f,%.0f".format(it.x, it.y, it.z) }}" }
                scene.result("RUNNING", "tick=${helper.tick} $where")
            }
            if (up != null) {
                scene.result("PASS", "tick=${helper.tick} sentry=${up.npcClass}")
                helper.succeed()
            }
        }
    }

    private val leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true)

    /** A wood round the tower: every crown is a raised floor the post search sees. */
    private val WOOD = listOf(BlockPos(44, 1, 50), BlockPos(48, 1, 72), BlockPos(74, 1, 46), BlockPos(76, 1, 70), BlockPos(60, 1, 80), BlockPos(40, 1, 64))

    private fun holdsPlatform(helper: GameTestHelper, npc: NpcEntity): Boolean {
        val task = NativeTactics.assignment(npc) ?: return false
        val post = task.position ?: return false
        return task.job == TacticalJob.OVERWATCH && helper.relativeVec(post).y >= PLATFORM_FLOOR &&
            FiringPostArrival.reached(npc.position(), post) && npc.onGround()
    }

    /** Just under the feet of a man standing on the platform's slabs. */
    private const val PLATFORM_FLOOR = 9.5

    private fun square(radius: Int, edgeOnly: Boolean = false, place: (Int, Int) -> Unit) {
        for (x in -radius..radius) {
            for (z in -radius..radius) {
                if (!edgeOnly || maxOf(abs(x), abs(z)) == radius) place(x, z)
            }
        }
    }

    private fun buildTower(helper: GameTestHelper, trunk: BlockPos) {
        fun set(x: Int, y: Int, z: Int, state: BlockState) = helper.setBlock(trunk.offset(x, y - 1, z), state)
        for (y in 1..8) square(1) { x, z -> set(x, y, z, Blocks.OAK_LOG.defaultBlockState()) }
        square(2) { x, z -> set(x, 9, z, Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)) }
        for (y in 8..9) square(3, edgeOnly = true) { x, z -> set(x, y, z, leaves) }
        square(3, edgeOnly = true) { x, z ->
            val facing = when {
                z == -3 -> Direction.SOUTH
                z == 3 -> Direction.NORTH
                x == -3 -> Direction.EAST
                else -> Direction.WEST
            }
            set(x, 10, z, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.HALF, Half.TOP).setValue(StairBlock.FACING, facing))
        }
        square(4, edgeOnly = true) { x, z -> set(x, 10, z, leaves) }
        // Only the corners at eye height: the owner's tower left the sentries a view over the parapet.
        square(3, edgeOnly = true) { x, z -> if (abs(x) + abs(z) >= 5) set(x, 11, z, leaves) }
        for (y in 12..14) square(3) { x, z -> if (abs(x) + abs(z) <= 28 - 2 * y) set(x, y, z, leaves) }
        for (y in 1..9) {
            set(0, y, 2, Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.DISTANCE, 0).setValue(ScaffoldingBlock.BOTTOM, y == 1))
            if (y <= 7) {
                set(-1, y, 2, leaves)
                set(1, y, 2, leaves)
            }
        }
    }

    private fun plantSpruce(helper: GameTestHelper, root: BlockPos) {
        for (y in 0..9) helper.setBlock(root.above(y), Blocks.SPRUCE_LOG)
        for (y in 4..12) {
            val radius = (12 - y) / 3 + 1
            square(radius) { x, z ->
                val besideTrunk = x != 0 || z != 0 || y > 9
                if (besideTrunk && abs(x) + abs(z) <= radius + 1) helper.setBlock(root.offset(x, y, z), leaves)
            }
        }
    }
}
