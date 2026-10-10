package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.TickBudget
import com.sbwnpc.squad.combat.tactics.*
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import com.sbwnpc.squad.gametest.CombatIdleGameTests.Scene
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate

/** Real pathfinder and world queries; only the prepared candidate list is fixed by the fixture. */
@PrefixGameTestTemplate(false)
object TacticalSearchGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 900, batch = "shared-post-search")
    fun twoSquadsKeepMovingAndFiringWhileUnreachablePostsConsumeSearchTurns(helper: GameTestHelper) {
        val scene = Scene(helper, "shared-post-search")
        for (x in 46..80) {
            for (z in 0..223) {
                val height = minOf(4, (x - 46) / 3 + 1, (80 - x) / 3 + 1)
                for (y in 1..height) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
            }
        }
        val groups = (0..1).map { group ->
            val members = (0..15).map { index -> scene.spawn(Vec3(24.5 + index / 8 * 5, 1.0, 22.5 + group * 120 + index % 8 * 5)) }
            scene.enemy(Vec3(140.5, 1.0, 40.5 + group * 120))
            members to scene.squad(members, TacticalPattern.BOUND, isolated = false)
        }
        // Additional stationary clients exercise the same native A*/budget alongside real brains.
        // Their fixed prepared posts isolate queue pressure rather than terrain scoring.
        val loads = (0..31).map { index ->
            val npc = scene.spawn(Vec3(10.5 + index % 8 * 4, 1.0, 75.5 + index / 8 * 4)).apply { isNoAi = true }
            val goal = helper.absoluteVec(Vec3(10.5 + index % 8 * 4, 1.0, 104.5 + index / 8 * 4))
            val candidates = (0..31).map { helper.absoluteVec(Vec3(95.5 + it % 8 * 2, 14.0, 90.5 + it / 8 * 2)) } + goal
            val task = TacticalTask(TacticalJob.SEARCH, goal, null, 1).apply {
                search = TacticalPositionSearch(npc.position(), emptyList()).also { search ->
                    search.destinations = candidates
                    candidates.forEachIndexed { rank, point -> search.scored[point] = TacticalPositionScore(false, 0, rank.toDouble()) }
                }
            }
            npc to task
        }
        val started = groups.flatMap { it.first }.associate { it.uuid to it.position() }
        val granted = mutableMapOf<java.util.UUID, Int>()
        val finished = mutableSetOf<java.util.UUID>()
        var lastLoadFinished = 0L
        loads.forEach { (npc, _) -> (npc.navigation as VehicleAwareNavigation).positionPathObserver = { _, _, _ -> granted.merge(npc.uuid, 1, Int::plus) } }
        scene.onClose = { loads.forEach { (npc, _) -> (npc.navigation as VehicleAwareNavigation).positionPathObserver = null } }
        val fired = groups.map { mutableSetOf<java.util.UUID>() }
        val advanced = groups.map { mutableSetOf<java.util.UUID>() }
        fun view(npc: NpcEntity) = TacticalSnapshot(helper.level.gameTime, SquadOrder.ATTACK, 1, npc.position(), null, listOf(TacticalMember(npc.uuid, npc.position())), emptyList())
        helper.onEachTick {
            groups.forEachIndexed { index, (members, squad) ->
                scene.tick(squad)
                members.forEach { npc ->
                    if (npc.lastShotTick >= 0) fired[index] += npc.uuid
                    if (npc.x - started.getValue(npc.uuid).x >= 15.0) advanced[index] += npc.uuid
                }
            }
            if (helper.tick >= 10 && helper.tick % 2L == 0L) {
                for ((npc, task) in loads) {
                    if (npc.uuid in finished) continue
                    npc.setOnGround(true)
                    val result = TacticalPositions.find(npc, task, view(npc))
                    helper.assertTrue(result.outcome != TacticalPositions.Outcome.UNREACHABLE, "Queued client lost its reachable floor")
                    if (result.outcome == TacticalPositions.Outcome.FOUND) {
                        finished += npc.uuid
                        lastLoadFinished = helper.tick
                        npc.navigation.stop()
                    }
                }
            }
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "finished=${finished.size}/32 loadSearches=${granted.values.sum()} fired=${fired.map { it.size }} advanced=${advanced.map { it.size }}")
            val activeShooters = groups.map { (members, _) -> members.count { it.firedRecently(40) } }
            if (helper.tick >= 360 && finished.size == 32 && fired.all { it.size == 16 } && activeShooters.all { it >= 8 } && advanced.all { it.size >= 12 }) {
                helper.assertTrue(granted.values.all { it <= 5 }, "An unreachable client's search repeatedly rebuilt its region: $granted")
                scene.result("PASS", "finished=32 lastLoadFinished=$lastLoadFinished loadSearches=${granted.values.sum()} fired=${fired.map { it.size }} activeShooters=$activeShooters advanced=${advanced.map { it.size }} tick=${helper.tick}")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 160, batch = "post-search-load")
    fun unreachableCandidatesDoNotRebuildTheSameTerrainForEveryPost(helper: GameTestHelper) {
        val scene = Scene(helper, "post-search-load")
        val npc = scene.spawn(Vec3(20.5, 1.0, 100.5)).apply { isNoAi = true }
        val goal = helper.absoluteVec(Vec3(45.5, 1.0, 100.5))
        // Prepared outputs of terrain scoring: inaccessible raised posts before a usable floor.
        // This isolates real A* work; the end-to-end tower scenes verify discovery of those posts.
        val candidates = (0..31).map { helper.absoluteVec(Vec3(60.5 + it % 8 * 2, 12.0, 80.5 + it / 8 * 2)) } + goal
        val task = TacticalTask(TacticalJob.SEARCH, goal, null, 1).apply {
            search = TacticalPositionSearch(npc.position(), emptyList()).also {
                it.destinations = candidates
                candidates.forEachIndexed { index, point -> it.scored[point] = TacticalPositionScore(false, 0, index.toDouble()) }
            }
        }
        val nav = npc.navigation as VehicleAwareNavigation
        var searches = 0
        var nanos = 0L
        nav.positionPathObserver = { _, elapsed, _ ->
            searches++
            nanos += elapsed
        }
        scene.onClose = { nav.positionPathObserver = null }
        helper.onEachTick {
            if (helper.tick < 10 || helper.tick % 2L != 0L) return@onEachTick
            npc.setOnGround(true)
            val view = TacticalSnapshot(helper.level.gameTime, SquadOrder.ATTACK, 1, npc.position(), goal, listOf(TacticalMember(npc.uuid, npc.position())), emptyList())
            val result = TacticalPositions.find(npc, task, view)
            helper.assertTrue(result.outcome != TacticalPositions.Outcome.UNREACHABLE, "An inaccessible post hid the later reachable floor")
            if (result.outcome == TacticalPositions.Outcome.FOUND) {
                scene.result("MEASURED", "searches=$searches searchMillis=${nanos / 1_000_000.0} reached=${result.position == goal} tick=${helper.tick}")
                helper.assertTrue(result.position == goal, "A partial path was accepted as a usable post")
                helper.assertTrue(searches <= 5, "One terrain region was rebuilt $searches times for 33 candidate posts")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 100, batch = "route-resume")
    fun deferredRouteResumesWithoutRebuildingAndReviewsNewThreat(helper: GameTestHelper) {
        val scene = Scene(helper, "route-resume")
        val npc = scene.spawn(Vec3(20.5, 1.0, 100.5)).apply { isNoAi = true }
        val goal = helper.absoluteVec(Vec3(44.5, 1.0, 100.5))
        val nav = npc.navigation as VehicleAwareNavigation
        var searches = 0
        nav.positionPathObserver = { _, _, _ ->
            searches++
            // Exhaust the ray budget after A* succeeds, before route safety starts.
            repeat(500) { TickBudget.chargeRaycast(helper.level) }
        }
        scene.onClose = { nav.positionPathObserver = null }
        fun task() =
            TacticalTask(TacticalJob.FLANK, goal, null, 1).apply {
                search = TacticalPositionSearch(npc.position(), emptyList()).also {
                    it.destinations = listOf(goal)
                    it.scored[goal] = TacticalPositionScore(false, 0, 1.0)
                }
            }
        fun view() = TacticalSnapshot(helper.level.gameTime, SquadOrder.ATTACK, 1, npc.position(), goal, listOf(TacticalMember(npc.uuid, npc.position())), emptyList())
        val first = task()
        val threatened = task()
        helper.runAtTickTime(20) {
            npc.setOnGround(true)
            helper.assertTrue(TacticalPositions.find(npc, first, view()).outcome == TacticalPositions.Outcome.DEFERRED, "Ray budget did not suspend route validation")
            helper.assertTrue(first.search?.routeValidation != null && searches == 1, "Built route was not retained")
        }
        helper.runAtTickTime(22) {
            npc.setOnGround(true)
            helper.assertTrue(TacticalPositions.find(npc, first, view()).outcome == TacticalPositions.Outcome.FOUND, "Retained route did not resume")
            helper.assertTrue(searches == 1, "Ray deferral rebuilt the same A*")
            nav.stop()
        }
        helper.runAtTickTime(30) {
            npc.setOnGround(true)
            helper.assertTrue(TacticalPositions.find(npc, threatened, view()).outcome == TacticalPositions.Outcome.DEFERRED, "Second route was not suspended")
        }
        helper.runAtTickTime(32) {
            npc.setOnGround(true)
            val danger = view().copy(contacts = listOf(TacticalContact(java.util.UUID(1, 1), goal, helper.level.gameTime)))
            helper.assertTrue(TacticalPositions.find(npc, threatened, danger).outcome == TacticalPositions.Outcome.UNREACHABLE, "New enemy clearance was ignored while resuming")
            helper.assertTrue(nav.isDone && searches == 2, "Unsafe resumed path was used or rebuilt")
            scene.result("PASS", "twoRoutes=$searches resumedWithoutRebuild=true newThreatRejected=true")
            helper.succeed()
        }
    }
}
