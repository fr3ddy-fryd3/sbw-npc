package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.domain.port.Ports
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.BlockCollisions
import net.minecraft.world.level.PathNavigationRegion
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ScaffoldingBlock
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.level.pathfinder.PathfindingContext
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator

/*
 * The node evaluator on Minecraft 1.21 (NeoForge and Fabric). Forge on 1.20.1 has its own
 * version of this file and these names, written against that version's evaluator; the rules
 * they apply are the same.
 */

/** The kind of cell a path node stands in: vanilla's `PathType` on 1.21. */
typealias NodePathType = PathType

/** The cell kinds the shared code names. */
object NodeTypes {
    val BLOCKED: NodePathType = PathType.BLOCKED
    val WATER: NodePathType = PathType.WATER
    val WATER_BORDER: NodePathType = PathType.WATER_BORDER
}

internal open class VehicleAwareNodeEvaluator(
    private val permitsStep: (BlockPos, BlockPos) -> Boolean = { _, _ -> true },
    private val onPrepare: () -> Unit = {},
) : WalkNodeEvaluator() {
    /** Lets a subclass change what a cell counts as, after every rule here has run. */
    protected open fun filterType(type: NodePathType): NodePathType = type

    override fun getStart(): Node = if (mob.onClimbable()) getStartNode(mob.blockPosition()) else super.getStart()

    override fun getNeighbors(output: Array<Node>, node: Node): Int {
        val count = super.getNeighbors(output, node)
        var accepted = 0
        for (index in 0 until count) {
            val next = output[index]
            // WalkNodeEvaluator may cut sideways to ground several blocks below a rung.
            // Keep descending the shaft instead: a mob can be almost a block above that
            // integer node when it detaches, making the nominal safe drop cause fall damage.
            if (climbable(node.asBlockPos()) && !climbable(next.asBlockPos()) && next.y < node.y - dropLimit(node.asBlockPos())) continue
            if (permitsStep(node.asBlockPos(), next.asBlockPos())) output[accepted++] = next
        }
        val from = node.asBlockPos()
        if (climbable(from) || climbable(from.below())) {
            for (next in listOf(from.above(), from.below())) {
                if (!ladderEdge(from, next) || !permitsStep(from, next)) continue
                val type = getPathTypeOfMob(currentContext, next.x, next.y, next.z, mob)
                if (type != PathType.WALKABLE || mob.getPathfindingMalus(type) < 0f) continue
                val neighbor = getNode(next)
                if (neighbor.closed) continue
                neighbor.type = type
                neighbor.costMalus = mob.getPathfindingMalus(type)
                if ((0 until accepted).none { output[it] === neighbor }) output[accepted++] = neighbor
            }
        }
        return accepted
    }

    private fun shaft(pos: BlockPos): Boolean = Ports.loader.isLadder(currentContext.getBlockState(pos), mob.level(), pos, mob)

    /** The lid of a shaft: a wooden trapdoor right above a ladder or scaffolding cell, see [Hatches]. */
    private fun hatch(pos: BlockPos): Boolean = Hatches.isTrapdoor(currentContext.getBlockState(pos)) && shaft(pos.below())

    /** Whether [pos] is the lid, the cell under it or the cell above it: the cells a hatch changes the type of. */
    private fun nearHatch(pos: BlockPos): Boolean = hatch(pos) || hatch(pos.above()) || hatch(pos.below())

    private fun climbable(pos: BlockPos): Boolean = shaft(pos) || hatch(pos)
    /**
     * How far below a climbable node the route may leave the shaft sideways. A ladder allows one block.
     * A scaffolding shaft allows none: the follower cuts the corner to a node one block lower while the
     * mob is still 1-1.5 blocks above it, he walks sideways into the next column, and the collision on a
     * climbable block makes him climb back up to the top. Scaffolding always stands on the ground, so the
     * shaft has a bottom node to step out from level.
     */
    private fun dropLimit(pos: BlockPos): Int = if (currentContext.getBlockState(pos).`is`(Blocks.SCAFFOLDING)) 0 else 1

    private fun climbable(pos: BlockPos): Boolean = Ports.loader.isLadder(currentContext.getBlockState(pos), mob.level(), pos, mob)

    /** A platform can open onto the air cell immediately above the last ladder rung. */
    private fun ladderEdge(from: BlockPos, to: BlockPos): Boolean {
        if (climbable(from) && climbable(to)) return true
        val lower = if (from.y < to.y) from else to
        val upper = lower.above()
        // Only the top exit is open air. This cannot connect across a missing rung in the shaft.
        return climbable(lower) && currentContext.getBlockState(upper).getCollisionShape(currentContext.level(), upper).isEmpty
    }

    // Vanilla caches the aggregate type of a mob-sized node, but neighboring nodes repeatedly
    // evaluate overlapping cells and their 24 surrounding hazards. Keep the final cell type
    // for this search only; raw block types in PathfindingContext do not cache those hazards.
    private val cellTypes = Long2ObjectOpenHashMap<PathType>()

    private val hulls = VehicleHulls()
    private var start: BlockPos? = null
    private var escapingCanopy = false

    /**
     * One entity query for the whole path computation. [done] drops it again, and the evaluator's
     * own per-position cache is cleared there too, so a vehicle that drives off doesn't leave
     * phantom walls behind.
     */
    override fun prepare(level: PathNavigationRegion, mob: Mob) {
        onPrepare()
        cellTypes.clear()
        super.prepare(level, mob)
        hulls.refresh(mob)
        start = mob.blockPosition()
        // A man already on a crown needs a way off it, but ground routes must not climb crowns.
        escapingCanopy = (-1..0).any { dy ->
            WalkingClearance.leaves(level.getBlockState(mob.blockPosition().offset(0, dy, 0)))
        }
    }

    override fun done() {
        cellTypes.clear()
        hulls.clear()
        start = null
        escapingCanopy = false
        super.done()
    }

    /**
     * Blocks that fill the upper part of their space without being full blocks — an azalea bush
     * is its top half and a thin stem — are open ground to vanilla (only a full collision box
     * counts as in the way), so paths ran straight through them and men walked into the bush with
     * no jump to get over it. Anything a man can't step over is solid here, and stood on like it.
     */
    override fun getPathType(context: PathfindingContext, x: Int, y: Int, z: Int): PathType {
        // The public evaluator API can also be queried outside a prepared path search. Those
        // independent queries must see current terrain rather than retain a cached answer.
        if (context !== currentContext) return calculatePathType(context, x, y, z)
        val key = BlockPos.asLong(x, y, z)
        cellTypes.get(key)?.let { return it }
        return calculatePathType(context, x, y, z).also { cellTypes.put(key, it) }
    }

    private fun calculatePathType(context: PathfindingContext, x: Int, y: Int, z: Int): PathType {
        val pos = BlockPos(x, y, z)
        val state = context.getBlockState(pos)
        val ladder = if (mob != null) Ports.loader.isLadder(state, mob.level(), pos, mob) else state.`is`(BlockTags.CLIMBABLE)
        if (ladder || (mob != null && hatch(pos))) return PathType.WALKABLE
        if (bodyHigh(context, x, y, z)) return PathType.BLOCKED
        val type = super.getPathType(context, x, y, z)
        if (type == PathType.OPEN) {
            val below = BlockPos(x, y - 1, z)
            val state = context.getBlockState(below)
            // Partial roots need a raised walking node even when they are only half a block high.
            if (!state.isAir && !state.getCollisionShape(context.level(), below).isEmpty) return PathType.WALKABLE
        }
        return type
    }

    private fun bodyHigh(context: PathfindingContext, x: Int, y: Int, z: Int): Boolean {
        // Wherever the mob already is stays passable, or a man stuck half inside one has no start.
        start?.let { if (it.x == x && it.y == y && it.z == z) return false }
        if (context.getPathTypeFromState(x, y, z) != PathType.OPEN) return false
        val pos = BlockPos(x, y, z)
        val state = context.getBlockState(pos)
        if (state.isAir) return false
        val shape = state.getCollisionShape(context.level(), pos)
        return !shape.isEmpty && shape.max(net.minecraft.core.Direction.Axis.Y) > NodeRules.STEP_OVER
    }

    override fun getPathTypeOfMob(context: PathfindingContext, x: Int, y: Int, z: Int, mob: Mob): PathType =
        filterType(vehicleAwareType(context, x, y, z, mob))

    private fun vehicleAwareType(context: PathfindingContext, x: Int, y: Int, z: Int, mob: Mob): PathType {
        if (hulls.blocks(x, y, z)) return PathType.BLOCKED
        val pos = BlockPos(x, y, z)
        val raw = super.getPathTypeOfMob(context, x, y, z, mob)
        // A lid in the shaft is walked through, not a trapdoor hazard to avoid.
        val type = if ((raw == PathType.TRAPDOOR || raw == PathType.DANGER_TRAPDOOR) && nearHatch(pos)) PathType.WALKABLE else raw
        if (mob.getPathfindingMalus(type) < 0f) return type
        val support = context.getBlockState(pos.below())
        if (WalkingClearance.leaves(support) && (!escapingCanopy || y > (start?.y ?: y) + 1)) return PathType.BLOCKED
        // Vanilla normally checks collisions only for fences/doors and jumps. A modded branch
        // can occupy an otherwise empty neighboring node, and roots can catch the legs there.
        // Keep the start usable for an NPC who was already pushed into an obstacle.
        val origin = start
        if (origin != null && origin.x == x && origin.z == z && kotlin.math.abs(origin.y - y) <= 1) return type
        val floor = if (type == PathType.OPEN || type == PathType.WATER || climbable(pos)) {
            y.toDouble()
        } else {
            getFloorLevel(pos)
        }
        val box = WalkingClearance.body(x, floor, z, mob.bbWidth, mob.bbHeight)
        if (hatch(pos) || hatch(pos.above())) {
            // The lid opens as he reaches it, so its closed shape is no obstacle to the route.
            val collisions = BlockCollisions(context.level(), mob, box, false) { at, _ ->
                val state = context.getBlockState(at)
                !hatch(at) && (!state.`is`(Blocks.SCAFFOLDING) || state.getValue(ScaffoldingBlock.DISTANCE) != 0)
            }
            return if (collisions.asSequence().any { it }) PathType.BLOCKED else type
        }
        if (context.getBlockState(pos).`is`(Blocks.SCAFFOLDING)) {
            // The real entity is still above the candidate: its collision context would
            // falsely turn every lower scaffold into a solid floor. Supported shaft decks
            // can be passed in either direction; all other colliders remain obstacles.
            val collisions = BlockCollisions(context.level(), mob, box, false) { at, _ ->
                val state = context.getBlockState(at)
                !state.`is`(Blocks.SCAFFOLDING) || state.getValue(ScaffoldingBlock.DISTANCE) != 0
            }
            return if (collisions.asSequence().any { it }) PathType.BLOCKED else type
        }
        return if (context.level().noBlockCollision(mob, box)) type else PathType.BLOCKED
    }
}
