package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.CoverPeekSearch
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
object CoverBudgetGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 100, batch = "cover-peek-resume")
    fun rejectedPeekRouteResumesAtNextCandidateOnTheNextTick(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "cover-peek-resume", recordTrace = false)
        val npc = scene.spawn(Vec3(20.5, 1.0, 12.5)).apply { isNoAi = true }
        val enemy = scene.enemy(Vec3(60.5, 1.0, 12.5))
        // The first supporting surface is a fence top: a clear firing ray, but an
        // unreachable 1.5-block rise. The opposite-side probe remains on ordinary ground.
        helper.setBlock(BlockPos(20, 1, 13), Blocks.OAK_FENCE)
        val search = CoverPeekSearch(npc.blockPosition(), enemy.eyePosition, enemy.uuid, false)
        val searches = mutableMapOf<Long, Int>()
        (npc.navigation as VehicleAwareNavigation).pathWorkObserver = { _, _, native -> if (native) searches.merge(helper.tick, 1, Int::plus) }
        scene.onClose = { (npc.navigation as VehicleAwareNavigation).pathWorkObserver = null }
        var rejectedAt = -1L
        helper.onEachTick {
            if (helper.tick < 20) return@onEachTick
            npc.setOnGround(true)
            val result = search.find(npc, helper.level, enemy.eyePosition, enemy)
            if (rejectedAt < 0) {
                helper.assertTrue(result == null && !search.exhausted && searches[helper.tick] == 1, "First unreachable peek was not deferred with its cursor retained")
                rejectedAt = helper.tick
            } else if (result != null) {
                helper.assertTrue(result.point.y == npc.y && result.path.canReach(), "Continuation reused the rejected fence-top route")
                helper.assertTrue(searches.values.all { it <= 1 } && searches.values.sum() == 2, "Continuation repeated native work: $searches")
                scene.result("PASS", "rejectedAt=$rejectedAt resumedAt=${helper.tick} native=2 point=${helper.relativeVec(result.point)}")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 700, batch = "cover-blind-reply")
    fun distantUnseenHitProducesTwoBurstsAfterPhysicalCoverAndPeek(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "cover-blind-reply")
        val npc = scene.spawn(Vec3(20.5, 1.0, 50.5))
        val enemy = scene.enemy(Vec3(140.5, 1.0, 50.5))
        for (y in 1..2) for (z in 48..52) helper.setBlock(BlockPos(24, y, z), Blocks.STONE)
        var peeked = false
        var beganReply = false
        helper.runAtTickTime(20) {
            Ports.guns.inHand(enemy)!!.inflictHit(npc, 1.0f)
            helper.assertTrue(npc.incomingFire.pending(helper.level.gameTime), "Hostile ranged hit did not create an incoming-fire episode")
        }
        helper.onEachTick {
            peeked = peeked || npc.holds(NpcActivity.PEEK)
            beganReply = beganReply || npc.incomingFire.replying(helper.level.gameTime)
            if (helper.tick % 40L == 0L) scene.result("CHECK", "tick=${helper.tick} at=${helper.relativeVec(npc.position())} peek=${npc.holds(NpcActivity.PEEK)} roundsLeft=${npc.incomingFire.roundsLeft} peeked=$peeked beganReply=$beganReply lastShot=${npc.lastShotTick}")
            if (helper.tick > 20 && peeked && beganReply && npc.incomingFire.roundsLeft == 0) {
                helper.assertTrue(npc.lastShotTick >= 0 && npc.isAlive, "No live physical return-fire shooter")
                scene.result("PASS", "peeked=$peeked beganReply=$beganReply roundsLeft=0 at=${helper.relativeVec(npc.position())} tick=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /**
     * Sixteen men suppressed in one tick, each with a wall beside him: all take cover, and every one
     * fights again, most by a peek along a path he can walk. Their peek searches share the per-tick
     * tactical path budget (its FIFO is [com.sbwnpc.squad.combat.tactics.TacticalPathBudget],
     * unit-tested), so the scene is about the queue starving nobody in a real fight, not about its
     * arithmetic. A man who steps out and fires instead of peeking is not starved.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "cover-peek-budget")
    fun synchronizedCoverPeeksObeyNativeBudgetAndAllReceivePaths(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "cover-peek-budget", recordTrace = false)
        val actors = List(16) { i ->
            val z = 12.5 + i * 12
            val npc = scene.spawn(Vec3(20.5, 1.0, z))
            val enemy = scene.enemy(Vec3(60.5, 1.0, z))
            npc.target = enemy
            for (y in 1..2) for (dz in -2..2) helper.setBlock(BlockPos(24, y, z.toInt() + dz), Blocks.STONE)
            npc to enemy
        }
        val covered = mutableSetOf<NpcEntity>()
        val peeked = mutableSetOf<NpcEntity>()
        // His own clock: lastShotTick counts his ticks, not the test's.
        val suppressedAt = mutableMapOf<NpcEntity, Int>()
        helper.onEachTick {
            if (helper.tick == SUPPRESSED_AT) {
                actors.forEach { (npc, enemy) ->
                    npc.suppress(enemy.eyePosition)
                    suppressedAt[npc] = npc.tickCount
                }
            }
            if (helper.tick < SUPPRESSED_AT) return@onEachTick
            actors.forEach { (npc, _) ->
                if (npc.holds(NpcActivity.COVER)) covered += npc
                // A peek claim with no reachable path is the starved case.
                if (npc.holds(NpcActivity.PEEK) && npc.navigation.path?.canReach() != false) peeked += npc
            }
            val fought = actors.count { (npc, _) -> npc in covered && npc.lastShotTick > suppressedAt.getValue(npc) }
            if (helper.tick % 40L == 0L) scene.result("RUNNING", "tick=${helper.tick} covered=${covered.size}/16 peeked=${peeked.size}/16 foughtAgain=$fought/16")
            if (fought == actors.size && peeked.size >= actors.size / 2) {
                scene.result("PASS", "tick=${helper.tick} covered=${covered.size} peeked=${peeked.size} foughtAgain=$fought")
                helper.succeed()
            }
        }
    }

    /**
     * Sixteen squadless men suppressed in one tick from a shooter none of them can see, on the open
     * ground east of the arena where no cover is in reach (as in `noCoverNoTargetFallsBack`): each
     * plan falls back (#189). Their sixteen fall-back searches queue in the per-tick tactical path
     * budget, so no tick of the admission searches more than it allows, all are admitted within it,
     * and every man gets away. Later native work (a walking man's repath, a peek) is not counted:
     * the budget does not cover it, and with the AI on it is not the fall-backs'.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "cover-fallback-budget")
    fun simultaneousFallBacksObeyNativeBudgetAndAllLeave(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "cover-fallback-budget", recordTrace = false)
        val searches = mutableMapOf<Long, Int>()
        val actors = List(16) { i ->
            val npc = scene.spawn(Vec3(140.5, 1.0, 12.5 + i * 12))
            (npc.navigation as VehicleAwareNavigation).pathWorkObserver = { _, _, native -> if (native) searches.merge(helper.tick, 1, Int::plus) }
            npc to npc.eyePosition.add(-136.0, 0.0, 0.0)
        }
        scene.onClose = { actors.forEach { (npc, _) -> (npc.navigation as VehicleAwareNavigation).pathWorkObserver = null } }
        val start = actors.associate { (npc, threat) -> npc to npc.eyePosition.distanceTo(threat) }
        val left = mutableSetOf<NpcEntity>()
        helper.onEachTick {
            if (helper.tick == SUPPRESSED_AT) actors.forEach { (npc, threat) -> npc.suppress(threat) }
            if (helper.tick < SUPPRESSED_AT) return@onEachTick
            val admission = searches.filterKeys { it >= SUPPRESSED_AT && it < SUPPRESSED_AT + ADMISSION_TICKS }
            val maximum = admission.values.maxOrNull() ?: 0
            helper.assertTrue(maximum <= NATIVE_PATHS_PER_TICK, "Fall-back paths exceeded the native budget: $admission")
            if (helper.tick == SUPPRESSED_AT + ADMISSION_TICKS) helper.assertTrue(admission.values.sum() >= actors.size, "Not every fall-back was admitted within $ADMISSION_TICKS ticks: $admission")
            actors.filter { (npc, threat) -> npc.eyePosition.distanceTo(threat) - start.getValue(npc) >= FALL_BACK_GAIN }.forEach { left += it.first }
            if (helper.tick % 40L == 0L) scene.result("RUNNING", "tick=${helper.tick} left=${left.size}/16 maximumNativePerTick=$maximum")
            if (left.size == actors.size) {
                helper.assertTrue(helper.tick >= SUPPRESSED_AT + ADMISSION_TICKS, "All left before the admission was checked")
                scene.result("PASS", "tick=${helper.tick} maximumNativePerTick=$maximum admitted=${admission.values.sum()}")
                helper.succeed()
            }
        }
    }

    /** The tactical path budget's paths per tick (`TacticalPathBudget`). */
    private const val NATIVE_PATHS_PER_TICK = 4

    /** Sixteen searches at four a tick, with room for the plans' first refresh window. */
    private const val ADMISSION_TICKS = 15L

    /** How far from the fire a man has to get to count as having fallen back, as in the planned-cover scene. */
    private const val FALL_BACK_GAIN = 4.0

    /**
     * A squadless man in the open with his enemy in sight, suppressed: his answer to fire is a root
     * of his own (#189), and once the suppression lapses the same refresh hands his agent the fight.
     * No tick between the two leaves him with a target and no plan; that gap was the throttle window
     * of the refresh. No wall: behind one he loses his hidden target, and with it the fight to hand over.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 400, batch = "cover-lone-handoff")
    fun squadlessManKeepsAPlanWhenHisAnswerToFireEnds(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "cover-lone-handoff", recordTrace = false)
        val npc = scene.spawn(Vec3(20.5, 1.0, 50.5))
        val enemy = scene.enemy(Vec3(60.5, 1.0, 50.5))
        npc.target = enemy
        var rooted = false
        var endedAt = -1L
        helper.onEachTick {
            if (helper.tick == SUPPRESSED_AT) npc.suppress(enemy.eyePosition)
            if (helper.tick <= SUPPRESSED_AT) return@onEachTick
            rooted = rooted || NativeTactics.hasAgent(npc)
            if (!rooted) return@onEachTick
            if (endedAt < 0 && !npc.isSuppressed()) endedAt = helper.tick
            if (endedAt < 0 || npc.target == null) return@onEachTick
            helper.assertTrue(NativeTactics.hasAgent(npc), "No plan at tick ${helper.tick}, ${helper.tick - endedAt} ticks after his suppression ended at $endedAt")
            if (helper.tick - endedAt >= HANDOFF_WATCH_TICKS) {
                scene.result("PASS", "suppressionEnded=$endedAt watched=$HANDOFF_WATCH_TICKS")
                helper.succeed()
            }
        }
    }

    /** How long after his suppression ends the hand-off scene watches him: several refresh windows. */
    private const val HANDOFF_WATCH_TICKS = 60L

    /** The tick every man is suppressed at once, after the scene settles. */
    private const val SUPPRESSED_AT = 20L
}
