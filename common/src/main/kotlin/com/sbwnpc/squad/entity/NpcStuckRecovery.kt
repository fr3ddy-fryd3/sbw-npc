package com.sbwnpc.squad.entity

import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import com.sbwnpc.squad.entity.ai.WalkingClearance
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.Vec3

/**
 * Stuck with somewhere to go — either walking a path without getting anywhere, or getting paths
 * that end where it stands — gets a fresh path with a bounded, health-aware descent allowance.
 * Soft colliders touching his body, legs or jumping headroom can be cleared, one at a time.
 * Nothing harder than [SOFT_BLOCK_HARDNESS], nothing with contents, and no block breaking where
 * mob griefing is off. Navigation recovery works independently of block breaking.
 */
class NpcStuckRecovery(private val npc: NpcEntity) {
    private var stuckCheckPos: Vec3? = null
    private var stuckSinceTick = -1

    /** Where it last asked to go and got a path that ends where it stands, and when — see
     *  [notePathGoesNowhere]. */
    private var nowhereToward: BlockPos? = null
    private var nowhereSinceTick = -1
    private var nowhereLastTick = -1
    private var recoveryAttempts = 0
    private var lastWarningTick = -600
    private var wasInPowderSnow = false

    /**
     * Called by the navigation for every ground search: [goesNowhere] means no path, or a local
     * path ending where the NPC already stands while [toward] is still far away. A man boxed in has a path that is
     * finished before it starts — the navigation reports itself done, so from outside he looks
     * like someone who has arrived, standing still with nowhere to go.
     */
    fun notePathGoesNowhere(toward: BlockPos, goesNowhere: Boolean) {
        if (!goesNowhere) {
            nowhereSinceTick = -1
            return
        }
        if (nowhereSinceTick < 0) nowhereSinceTick = npc.tickCount
        nowhereLastTick = npc.tickCount
        nowhereToward = toward
    }

    fun tick() {
        val tick = npc.tickCount
        if (tick % STUCK_CHECK_TICKS != 0 || npc.isPassenger) return
        if (npc.isInPowderSnow != wasInPowderSnow) {
            wasInPowderSnow = npc.isInPowderSnow
            DebugFlags.log(
                LogGroup.STUCK,
                "{} powder snow {} at {} pathDone={} frozenTicks={}",
                npc.uuid,
                if (wasInPowderSnow) "entered" else "exited",
                npc.blockPosition(),
                npc.navigation.isDone,
                npc.ticksFrozen,
            )
        }
        val here = npc.position()
        val path = npc.navigation.path
        // Bouncing vertically against a ledge is not travel progress. A completed upward step is.
        val moved = stuckCheckPos?.let {
            here.subtract(it).horizontalDistanceSqr() > STUCK_MOVE_SQR || (npc.onGround() && here.y > it.y + 0.5) ||
                (npc.onClimbable() && kotlin.math.abs(here.y - it.y) > 0.5)
        } ?: true
        stuckCheckPos = here
        if (moved) recoveryAttempts = 0
        val walkingInPlace = path != null && !npc.navigation.isDone && !moved
        if (walkingInPlace) {
            if (stuckSinceTick < 0) stuckSinceTick = tick
        } else {
            stuckSinceTick = -1
        }
        val boxedIn = nowhereSinceTick >= 0 && tick - nowhereLastTick <= NOWHERE_FRESH_TICKS && !moved
        val toward: Vec3 = when {
            walkingInPlace && tick - stuckSinceTick >= STUCK_BREAK_TICKS -> Vec3.atCenterOf(path!!.nextNodePos)
            boxedIn && tick - nowhereSinceTick >= STUCK_BREAK_TICKS -> Vec3.atCenterOf(nowhereToward ?: return)
            else -> return
        }
        val level = npc.level() as? ServerLevel ?: return
        val navigation = npc.navigation as? VehicleAwareNavigation
        // Recovery must keep a jump's momentum and must continue to the original goal, not its blocked step.
        if (navigation?.canPlan == false) return
        val destination = path?.target ?: nowhereToward ?: BlockPos.containing(toward)
        val dx = toward.x - npc.x
        val dz = toward.z - npc.z
        val len = kotlin.math.sqrt(dx * dx + dz * dz)
        val direction = if (len < 1.0e-3) Vec3.ZERO else Vec3(dx / len, 0.0, dz / len)
        val candidates = WalkingClearance.clearingCandidates(level, npc, npc.boundingBox, direction) +
            listOfNotNull(WalkingClearance.lowerCrownLayer(level, npc, npc.boundingBox))
        val supports = WalkingClearance.supports(level, npc, npc.boundingBox)
        // Only a step at arm's length is cleared. Breaking towards a goal or a path node far off
        // dug a tunnel block by block: a medic broke into a cave that way and dug on through it
        // towards his patient 150 blocks away, and the next medic followed him in (in-game 24.10).
        val stepAhead = len <= MAX_BREAK_REACH
        val crown = WalkingClearance.lowerCrownLayer(level, npc, npc.boundingBox)
        val blocking = candidates.filter { it !in supports || it == crown }
        // The whole way through is cleared at once or not at all. One block a try left a hole at
        // his feet with the block at head height still in it once the replanned path turned away
        // (in-game 24.10), and a hard block anywhere in the way makes the rest useless to break.
        val cleared = if (stepAhead && Ports.loader.canGrief(level, npc) && blocking.all { clearable(level, it) }) blocking else emptyList()
        // A new search must not reuse the path that has just failed to move him.
        stuckSinceTick = -1
        nowhereSinceTick = -1
        if (cleared.isNotEmpty()) npc.swing(InteractionHand.MAIN_HAND)
        cleared.forEach { pos ->
            val state = level.getBlockState(pos)
            level.destroyBlock(pos, true, npc)
            DebugFlags.log(LogGroup.STUCK, "{} broke {} at {} to get unstuck", npc.uuid, state.block.descriptionId, pos)
        }
        if (cleared.isEmpty()) navigation?.rejectStuckStep()
        // Relax descent only to get off a crown. A blocked upward step is no reason to drop
        // several blocks below the route, and clearing dirt underfoot excavated deeper traps.
        if (supports.any { WalkingClearance.leaves(level.getBlockState(it)) }) npc.allowRecoveryDescent()
        npc.navigation.stop()
        npc.navigation.moveTo(npc.navigation.createPath(destination, 0), 1.0)
        DebugFlags.log(
            LogGroup.STUCK,
            "{} replanned from {} toward {} after blocked step {} with fall limit {}",
            npc.uuid,
            npc.blockPosition(),
            destination,
            toward,
            npc.maxFallDistance,
        )
        recoveryAttempts++
        if (recoveryAttempts >= 6 && tick - lastWarningTick >= 600) {
            lastWarningTick = tick
            ModIdentity.LOGGER.warn(
                "NPC {} ({}) still stuck at {} toward {} after {} recoveries; fall limit {}, colliders {}",
                npc.uuid,
                npc.npcClass,
                npc.blockPosition(),
                toward,
                recoveryAttempts,
                npc.maxFallDistance,
                candidates.take(4).map { "${BuiltInRegistries.BLOCK.getKey(level.getBlockState(it).block)} at $it" },
            )
        }
    }

    private fun clearable(level: ServerLevel, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        val hardness = state.getDestroySpeed(level, pos)
        return state.fluidState.isEmpty && level.getBlockEntity(pos) == null &&
            hardness >= 0f && hardness <= SOFT_BLOCK_HARDNESS && !state.requiresCorrectToolForDrops()
    }

    private companion object {
        const val STUCK_CHECK_TICKS = 10
        const val STUCK_MOVE_SQR = 0.15 * 0.15

        /** Stood still this long with a path before it clears the way. */
        const val STUCK_BREAK_TICKS = 30

        /** A path that goes nowhere counts only while they keep coming. */
        const val NOWHERE_FRESH_TICKS = 40

        /** Anything a hand clears quickly: leaves 0.2, glass 0.3, sand and dirt 0.5, gravel and grass
         *  0.6. Stone (1.5) and planks (2) stay. */
        const val SOFT_BLOCK_HARDNESS = 0.6f

        /** How far off (blocks) the blocked step may be for the way to it to be cleared. */
        const val MAX_BREAK_REACH = 2.0
    }
}
