package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.domain.port.Guns
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import com.sbwnpc.squad.gametest.CombatIdleGameTests.Scene
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate

/** Real-world measurements; timings are evidence, deterministic work counts are assertions. */
@PrefixGameTestTemplate(false)
object CombatPerformanceGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 400, batch = "failed-combat-path")
    fun failedBlindApproachBacksOffAndResumesAfterTerrainOpens(helper: GameTestHelper) {
        val scene = Scene(helper, "failed-combat-path")
        for (x in 40..41) for (z in 0..223) for (y in 1..8) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        val shooter = scene.spawn(Vec3(30.5, 1.0, 100.5))
        scene.enemy(Vec3(150.5, 1.0, 100.5))
        val squad = scene.squad(listOf(shooter))
        val nav = shooter.navigation as VehicleAwareNavigation
        var blockedSearches = 0
        var blockedNanos = 0L
        nav.positionPathObserver = { _, nanos, _ ->
            if (helper.tick < 80) {
                blockedSearches++
                blockedNanos += nanos
            }
        }
        scene.onClose = { nav.positionPathObserver = null }
        helper.runAtTickTime(80) {
            scene.result("MEASURED", "blockedSearches=$blockedSearches blockedSearchMillis=${blockedNanos / 1_000_000.0}")
            // An advance by legs (#172) walks to the foot of the wall without a post search; what
            // matters is that a failed approach does not keep rebuilding (a single run took 12).
            helper.assertTrue(blockedSearches <= 16, "Unchanged failed approach rebuilt $blockedSearches paths in 80 ticks")
            for (x in 40..41) for (z in 0..223) for (y in 1..8) helper.setBlock(BlockPos(x, y, z), Blocks.AIR)
        }
        helper.onEachTick {
            scene.tick(squad)
            if (helper.tick > 80 && helper.relativeVec(shooter.position()).x > 50.0 && shooter.lastShotTick >= 0) {
                scene.result("PASS", "blockedSearches=$blockedSearches blockedSearchMillis=${blockedNanos / 1_000_000.0} resumed=${helper.tick} at=${helper.relativeVec(shooter.position())}")
                helper.succeed()
            }
        }
    }

    /**
     * The friendly-fire guard finds an ally in the lane, in the blast and in his own motion, and
     * once both answers are known it stops looking: with twenty more allies about, a blocked shot
     * takes no more than a few of their safety boxes.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 100, batch = "friendly-fire-work")
    fun friendlyFireStopsCompletedAssessmentAndKeepsBlastAndMotionSafety(helper: GameTestHelper) {
        val scene = Scene(helper, "friendly-fire-work")
        val shooter = scene.spawn(Vec3(10.5, 1.0, 100.5)).apply { isNoAi = true }
        val blocker = scene.spawn(Vec3(20.5, 1.0, 100.5)).apply { isNoAi = true }
        val impactAlly = scene.spawn(Vec3(150.5, 1.0, 120.5)).apply { isNoAi = true }
        repeat(FRIENDLY_CROWD) { index -> scene.spawn(Vec3(20.5 + index % 10 * 12, 1.0, 40.5 + index / 10 * 12)).isNoAi = true }
        val impact = helper.absoluteVec(Vec3(150.5, 1.0, 100.5))
        val aim = impact.add(0.0, shooter.eyeHeight.toDouble(), 0.0)
        val actualGuns = Ports.guns
        var bounds = 0
        val counting = object : Guns by actualGuns {
            override fun firingSafetyBounds(entity: LivingEntity, flightTicks: Double): AABB {
                bounds++
                return actualGuns.firingSafetyBounds(entity, flightTicks)
            }
        }
        fun place(npc: NpcEntity, at: Vec3) {
            npc.setPos(helper.absoluteVec(at))
            npc.xOld = npc.x
            npc.yOld = npc.y
            npc.zOld = npc.z
            npc.deltaMovement = Vec3.ZERO
        }
        fun check(variant: String, blast: Double, line: Boolean, blastClear: Boolean) {
            val actual = FriendlyFireGuard.assess(shooter, aim, 0.0, impact, blast)
            helper.assertTrue(actual.lineClear == line && actual.blastClear == blastClear, "$variant: line=${actual.lineClear} blast=${actual.blastClear}, expected line=$line blast=$blastClear")
        }
        helper.runAtTickTime(10) {
            Ports.guns = counting
            try {
                check("ally in the lane", 0.0, line = false, blastClear = true)
                place(impactAlly, Vec3(150.5, 1.0, 104.5))
                check("ally in the lane and the blast", 6.0, line = false, blastClear = false)
                place(blocker, Vec3(20.5, 1.0, 120.5))
                check("ally in the blast only", 6.0, line = true, blastClear = false)
                place(impactAlly, Vec3(150.5, 1.0, 120.5))
                check("nobody near", 6.0, line = true, blastClear = true)
                // Crouched off the lane, running into it: where he will be when the round arrives counts.
                place(blocker, Vec3(20.5, 1.0, 110.5))
                blocker.pose = Pose.CROUCHING
                blocker.deltaMovement = Vec3(0.0, 0.0, -2.0)
                check("ally moving into the lane", 0.0, line = false, blastClear = true)
                place(blocker, Vec3(20.5, 1.0, 100.5))
                blocker.pose = Pose.STANDING
                bounds = 0
                FriendlyFireGuard.assess(shooter, aim, 0.0, impact, 0.0)
                helper.assertTrue(bounds <= EARLY_EXIT_BOUNDS, "A decided line check still went through the crowd: $bounds safety boxes")
                scene.result("PASS", "boundsForABlockedShot=$bounds crowd=$FRIENDLY_CROWD")
                helper.succeed()
            } finally {
                Ports.guns = actualGuns
            }
        }
    }

    /** Allies about the field that a decided check must not go through. */
    private const val FRIENDLY_CROWD = 20

    /** Safety boxes a decided check may take: the blocker's, and a few its search happened to meet first. */
    private const val EARLY_EXIT_BOUNDS = 4

    /**
     * Thirteen squads of 24 attack one area, as in the Fabric playtest of 2026-11-07 where 306 of
     * 307 men stood with their post search deferred (#301). Records every 20 ticks how many men
     * moved, how many position searches finished, and why the rest were deferred.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1700, batch = "position-queue")
    fun positionSearchQueueUnderAThreeHundredManAttack(helper: GameTestHelper) {
        val scene = Scene(helper, "position-queue")
        val manager = com.sbwnpc.squad.squad.SquadManager.get(helper.level)
        val groups = (0 until 13).map { squad ->
            (0 until 24).map { index -> scene.spawn(Vec3(8.5 + squad % 4 * 10 + index % 4 * 2, 1.0, 20.5 + squad / 4 * 45 + index / 4 * 2)) }
        }
        val members = groups.flatten()
        val ids = members.mapTo(HashSet()) { it.uuid }
        val origins = members.associate { it.uuid to it.position() }
        for (index in 0 until 16) scene.enemy(Vec3(145.5 + index / 4 * 3, 1.0, 100.5 + index % 4 * 12))
        val squads = groups.map { group -> manager.create(helper.level, java.util.UUID(90, 301), requireNotNull(group.first().spawnFaction), group.map { it.uuid }) }
        val tally = SearchTally(ids)
        scene.onClose = {
            tally.close()
            squads.forEach { manager.deleteSquad(helper.level, it.id) }
        }
        val report = java.nio.file.Path.of(System.getProperty("sbwnpc.gameTestReports")).resolve("position-queue.csv")
        java.nio.file.Files.writeString(report, "tick,men,moved,foundTotal,menFound,freshSearches,msptAvg,restarts,deferrals\n")
        val objective = helper.absolutePos(BlockPos(148, 1, 118))
        com.sbwnpc.squad.squad.GroupOrders.apply(helper.level, manager, squads.map { it.id }, com.sbwnpc.squad.squad.SquadOrder.ATTACK, objective.x, objective.z)
        var lastNanos = System.nanoTime()
        helper.onEachTick {
            if (helper.tick % 20L != 0L) return@onEachTick
            val now = System.nanoTime()
            val mspt = (now - lastNanos) / 20 / 1_000_000.0
            lastNanos = now
            val moved = members.count { it.isAlive && it.position().distanceTo(origins.getValue(it.uuid)) >= 8.0 }
            java.nio.file.Files.writeString(report, "${helper.tick},${members.count { it.isAlive }},$moved,${tally.line("%.1f".format(mspt))}\n", java.nio.file.StandardOpenOption.APPEND)
            if (helper.tick >= 1600) {
                scene.result("MEASURED", "tick=${helper.tick} moved=$moved menFound=${tally.menFound} of ${members.size}")
                helper.succeed()
            }
        }
    }

    /** What the position searches of [ids] did between two report lines: results, deferral reasons and restarts. */
    private class SearchTally(private val ids: Set<java.util.UUID>) {
        private val reasons = HashMap<String, Int>()
        private val restarts = HashMap<String, Int>()
        private val found = HashSet<java.util.UUID>()
        private var finished = 0
        private var fresh = 0
        val menFound get() = found.size

        init {
            com.sbwnpc.squad.combat.tactics.TacticalPositions.restartObserver = { npc, why -> if (npc.uuid in ids && why != "kept") restarts.merge(why, 1, Int::plus) }
            com.sbwnpc.squad.combat.tactics.TacticalPositions.searchWorkObserver = { npc, cursor -> if (cursor && npc.uuid in ids) fresh++ }
            com.sbwnpc.squad.combat.tactics.TacticalPositions.resultObserver = { npc, result -> if (npc.uuid in ids) count(npc.uuid, result) }
        }

        private fun count(id: java.util.UUID, result: com.sbwnpc.squad.combat.tactics.TacticalPositions.Result) {
            when (result.outcome) {
                com.sbwnpc.squad.combat.tactics.TacticalPositions.Outcome.DEFERRED -> reasons.merge(result.deferral ?: "unknown", 1, Int::plus)

                com.sbwnpc.squad.combat.tactics.TacticalPositions.Outcome.FOUND -> {
                    finished++
                    found += id
                }

                com.sbwnpc.squad.combat.tactics.TacticalPositions.Outcome.UNREACHABLE -> reasons.merge("unreachable", 1, Int::plus)
            }
        }

        /** The CSV columns after tick, men and moved; the per-line counters start again. */
        fun line(mspt: String): String {
            val text = "$finished,${found.size},$fresh,$mspt,${restarts.entries.joinToString(" ") { "${it.key}=${it.value}" }},${reasons.entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key}=${it.value}" }}"
            reasons.clear()
            restarts.clear()
            return text
        }

        fun close() {
            com.sbwnpc.squad.combat.tactics.TacticalPositions.resultObserver = null
            com.sbwnpc.squad.combat.tactics.TacticalPositions.searchWorkObserver = null
            com.sbwnpc.squad.combat.tactics.TacticalPositions.restartObserver = null
        }
    }
}
