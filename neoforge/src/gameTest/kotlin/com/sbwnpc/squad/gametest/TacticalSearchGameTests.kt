package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.TickBudget
import com.sbwnpc.squad.combat.tactics.*
import com.sbwnpc.squad.combat.tactics.PositionPaths
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import com.sbwnpc.squad.gametest.CombatIdleGameTests.Scene
import com.sbwnpc.squad.mixin.PathNavigationAccessor
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate

/** Real pathfinder and world queries; only the prepared candidate list is fixed by the fixture. */
@PrefixGameTestTemplate(false)
object TacticalSearchGameTests {
    /** Isolates snow geometry; this is not the multi-squad combat participation acceptance test. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 600, batch = "snow-firing-posts")
    fun snowLayersAllowNativeFiringPostsAndGunfire(helper: GameTestHelper) {
        val scene = Scene(helper, "snow-firing-posts")
        val actors = (0..8).map { layers ->
            val z = 16 + layers * 22
            if (layers > 0) {
                val snow = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers)
                for (x in 12..100) for (row in z - 4..z + 4) helper.setBlock(BlockPos(x, 1, row), snow)
            }
            val floor = 1.0 + (layers - 1).coerceAtLeast(0) / 8.0
            scene.spawn(Vec3(20.5, floor, z + 0.5)).apply { isNoAi = true } to
                scene.enemy(Vec3(80.5, floor, z + 0.5))
        }
        val tasks = actors.map { (npc, enemy) ->
            npc.target = enemy
            TacticalTask(TacticalJob.COVER, npc.position().add(10.0, 0.0, 0.0), enemy.position(), 0)
        }
        val selected = mutableSetOf<Int>()
        var squad: com.sbwnpc.squad.squad.Squad? = null
        helper.onEachTick {
            if (helper.tick < 20) return@onEachTick
            if (squad == null) {
                for ((layers, pair) in actors.withIndex()) {
                    if (layers in selected) continue
                    val (npc, enemy) = pair
                    npc.setOnGround(true)
                    val expected = helper.absoluteVec(Vec3(20.5, 1.0 + (layers - 1).coerceAtLeast(0) / 8.0, 16.5 + layers * 22))
                    val feet = Terrain.feetAt(helper.level, expected)!!
                    helper.assertTrue(kotlin.math.abs(feet.y - expected.y) < 0.001, "Wrong snow collider floor: layers=$layers feet=$feet expected=$expected")
                    helper.assertTrue(DetectionSightline.canSee(npc, enemy), "Snow alone blocked detection: layers=$layers")
                    helper.assertTrue(!Sightline.blocked(helper.level, npc.eyePosition, enemy.eyePosition, npc), "Snow alone blocked shooting ray: layers=$layers")
                    val now = helper.level.gameTime
                    val view = TacticalSnapshot(
                        now,
                        SquadOrder.ATTACK,
                        0,
                        npc.position(),
                        enemy.position(),
                        listOf(TacticalMember(npc.uuid, npc.position())),
                        listOf(TacticalContact(enemy.uuid, enemy.position(), now)),
                    )
                    val result = TacticalPositions.find(npc, tasks[layers], view)
                    if (result.outcome == TacticalPositions.Outcome.DEFERRED) continue
                    helper.assertTrue(result.outcome == TacticalPositions.Outcome.FOUND, "Snow firing post search failed: layers=$layers rejections=${result.rejections}")
                    selected += layers
                }
                if (selected.size == actors.size) {
                    actors.forEach { (npc, _) -> npc.isNoAi = false }
                    squad = scene.squad(actors.map { it.first }, contact = actors.first().second, order = SquadOrder.PATROL)
                }
            } else {
                scene.tick(squad!!)
                val fired = actors.mapIndexedNotNull { layers, (npc, _) -> layers.takeIf { npc.lastShotTick >= 0 } }
                scene.result("RUNNING", "selectedLayers=$selected firedLayers=$fired tick=${helper.tick}")
                if (fired.size == actors.size) {
                    scene.result("PASS", "selectedLayers=$selected firedLayers=$fired tick=${helper.tick}; isolated snow geometry and initial gunfire only")
                    helper.succeed()
                }
            }
        }
        helper.runAtTickTime(590) {
            val silent = actors.mapIndexedNotNull { layers, (npc, _) -> layers.takeIf { npc.lastShotTick < 0 } }
            scene.result("FAIL", "selectedLayers=$selected silentLayers=$silent")
            helper.fail("Snow geometry or gunfire remained blocked; see snow-firing-posts.csv")
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 80, batch = "operation-post-detour")
    fun exhaustedPostSearchCanFollowABoundedDetourToANearbySlot(helper: GameTestHelper) {
        val scene = Scene(helper, "operation-post-detour")
        for (x in 0..175) for (y in 1..5) helper.setBlock(BlockPos(x, y, 72), Blocks.STONE)
        for (x in 4..68) for (y in 1..5) helper.setBlock(BlockPos(x, y, 72), Blocks.AIR)
        val actor = scene.spawn(Vec3(94.5, 1.0, 64.5)).apply { isNoAi = true }
        val destination = helper.absolutePos(BlockPos(94, 1, 78))
        helper.runAfterDelay(20) {
            // NoAI keeps this path-only actor in place, including its initial ground
            // flag. The fixture's solid floor supplies the grounded navigation start.
            helper.assertTrue(helper.getBlockState(BlockPos(94, 0, 64)).`is`(Blocks.STONE), "Fixture lost its supporting floor")
            actor.setOnGround(true)
            val navigation = actor.navigation as VehicleAwareNavigation
            val ordinary = navigation.createPositionPath(setOf(destination))
            val detour = navigation.createPositionPath(setOf(destination), allowDetour = true)
            helper.assertTrue(ordinary?.canReach() != true, "Fixture did not require a longer detour than the ordinary local search")
            helper.assertTrue(detour?.canReach() == true && detour.target == destination, "Bounded recovery could not reach the nearby post around the wall")
            scene.result("PASS", "ordinaryReached=${ordinary?.canReach()} detourNodes=${detour!!.nodeCount}")
            helper.succeed()
        }
    }

    /** Isolated search contract: real colliders/A*, with an explicit incoming-fire observation. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 160, batch = "protected-bound-post")
    fun advancingUnderFireWaitsForAProtectedNextPost(helper: GameTestHelper) {
        val scene = Scene(helper, "protected-bound-post")
        val npc = scene.spawn(Vec3(20.5, 1.0, 100.5)).apply { isNoAi = true }
        val support = scene.spawn(Vec3(20.5, 1.0, 112.5)).apply { isNoAi = true }
        val enemy = scene.enemy(Vec3(90.5, 1.0, 100.5))
        val squad = scene.squad(listOf(npc, support))
        npc.target = enemy
        val goal = helper.absoluteVec(Vec3(32.5, 1.0, 100.5))
        var task = TacticalTask(TacticalJob.ADVANCE, goal, enemy.position(), 0)
        var shelterAdded = false
        helper.onEachTick {
            if (helper.tick < 10 || helper.tick % 2L != 0L) return@onEachTick
            npc.setOnGround(true)
            val now = helper.level.gameTime
            val view = TacticalSnapshot(
                now,
                SquadOrder.ATTACK,
                squad.orderStamp,
                npc.position(),
                enemy.position(),
                listOf(TacticalMember(npc.uuid, npc.position()), TacticalMember(support.uuid, support.position(), canFire = true)),
                listOf(TacticalContact(enemy.uuid, enemy.position(), now)),
                incoming = listOf(enemy.position()),
            )
            // The HTN executor requires shelter for a move under incoming fire (PositionIntent.requireShelter).
            val result = TacticalPositions.find(npc, task, view, TacticalPositions.Query(ShelteredAdvance))
            if (result.outcome == TacticalPositions.Outcome.DEFERRED) return@onEachTick
            if (!shelterAdded) {
                helper.assertTrue(result.outcome == TacticalPositions.Outcome.UNREACHABLE, "An exposed forward post was accepted under incoming fire")
                helper.assertTrue((result.rejections["exposed_advance_under_fire"] ?: 0) > 0, "Fixture did not exercise the protection gate")
                for (z in 98..102) helper.setBlock(BlockPos(35, 1, z), Blocks.STONE)
                task = TacticalTask(TacticalJob.ADVANCE, goal, enemy.position(), 0)
                shelterAdded = true
            } else {
                helper.assertTrue(result.outcome == TacticalPositions.Outcome.FOUND, "A reachable protected bound remained blocked")
                val point = result.position!!
                helper.assertTrue(point.x - npc.x >= 1.0, "The new post made no forward progress")
                val relative = helper.relativeVec(point)
                helper.assertTrue(relative.x in 32.0..35.0 && relative.z in 98.0..103.0, "Selected post is outside the added wall's shelter: $relative")
                scene.result("PASS", "exposedRejected=true shelteredPost=$relative")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 60, batch = "post-exact-arrival")
    fun exactPostCannotReuseAMarchPathEndingShortOfItsTarget(helper: GameTestHelper) {
        val scene = Scene(helper, "post-exact-arrival")
        val npc = scene.spawn(Vec3(20.5, 1.0, 100.5)).apply { isNoAi = true }
        val nav = npc.navigation as VehicleAwareNavigation
        val goal = PositionPaths.walkingNode(helper.absoluteVec(Vec3(45.5, 1.0, 100.5)))
        helper.runAtTickTime(20) {
            npc.setOnGround(true)
            val coarse = nav.createPath(goal, 3)!!
            helper.assertTrue(coarse.canReach() && coarse.endNode!!.asBlockPos() != goal, "March fixture did not stop within its coarse arrival tolerance")
            helper.assertTrue(nav.moveTo(coarse, 1.0), "Coarse march did not start")
            val state = nav as PathNavigationAccessor
            state.sbwnpcSetStuck(true)
            val exact = nav.createPositionPath(goal)!!
            helper.assertTrue(exact.canReach() && exact.endNode!!.asBlockPos() == goal, "Exact post reused the short march path")
            helper.assertTrue(nav.path === coarse && state.sbwnpcIsStuck(), "Probe changed active route or cleared its physical stuck state")
            helper.assertTrue(state.sbwnpcGetReachRange() == 3, "Probe changed active march's arrival tolerance")
            helper.assertTrue(nav.moveTo(exact, 1.0) && state.sbwnpcGetReachRange() == 0, "Accepted exact post did not commit its own arrival tolerance")
            helper.assertTrue(!state.sbwnpcIsStuck(), "Accepted new route kept the old route's stuck state")
            helper.assertTrue(nav.createPositionPath(goal) === nav.path, "Matching exact route was needlessly rebuilt")
            scene.result("PASS", "marchAccuracy=3 exactAccuracy=0 activeStuckPreserved=true nativeCacheStillUsed=true")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 80, batch = "post-path-ownership")
    fun speculativePostSearchCannotReturnOrRedirectTheActiveRoute(helper: GameTestHelper) {
        val scene = Scene(helper, "post-path-ownership")
        val npc = scene.spawn(Vec3(20.5, 1.0, 100.5)).apply { isNoAi = true }
        val nav = npc.navigation as VehicleAwareNavigation
        val first = helper.absoluteVec(Vec3(40.5, 1.0, 100.5))
        val second = helper.absoluteVec(Vec3(40.5, 1.0, 112.5))
        val rejected = helper.absoluteVec(Vec3(40.5, 1.0, 124.5))
        helper.runAtTickTime(20) {
            npc.setOnGround(true)
            val active = nav.createPositionPath(PositionPaths.walkingNode(first))!!
            helper.assertTrue(nav.moveTo(active, 1.0), "Initial route was not started")
            val probe = nav.createPositionPath(PositionPaths.walkingNode(second))!!
            helper.assertTrue(probe.target == PositionPaths.walkingNode(second), "First probe did not reach its own target")
            helper.assertTrue(nav.path === active, "Unaccepted probe replaced the active path")
            // Native createPath normally remembers the unaccepted probe's target. A repeated
            // request then returns the still-active first route as a cached route to second.
            val task = TacticalTask(TacticalJob.SEARCH, second, null, 1).apply {
                search = TacticalPositionSearch(npc.position(), emptyList()).also {
                    it.destinations = listOf(second)
                    it.scored[second] = TacticalPositionScore(false, 0, 1.0)
                }
            }
            val view = TacticalSnapshot(helper.level.gameTime, SquadOrder.ATTACK, 1, npc.position(), second, listOf(TacticalMember(npc.uuid, npc.position())), emptyList())
            val result = TacticalPositions.find(npc, task, view)
            helper.assertTrue(result.outcome == TacticalPositions.Outcome.FOUND && result.position == second, "Search accepted a stale route or lost a reachable post")
            helper.assertTrue(nav.path?.target == PositionPaths.walkingNode(second), "New post did not own its route")
            nav.createPositionPath(PositionPaths.walkingNode(rejected))
            helper.assertTrue(nav.targetPos == nav.path?.target, "Rejected post redirected the active navigation target")
            repeat(2) {
                val ordinaryProbe = nav.createPath(PositionPaths.walkingNode(rejected), 1)!!
                helper.assertTrue(ordinaryProbe.target == PositionPaths.walkingNode(rejected), "March cache returned an unrelated tactical route")
                helper.assertTrue(nav.targetPos == nav.path?.target, "Ordinary probe redirected the active post")
            }
        }
        helper.runAtTickTime(45) {
            npc.setOnGround(true)
            nav.recomputePath()
            helper.assertTrue(nav.path?.target == PositionPaths.walkingNode(second), "Terrain recomputation followed an unaccepted post")
            scene.result("PASS", "repeatedProbeTargetsOwnPost=true rejectedProbeKeepsActiveGoal=true recomputeKeepsCommittedPost=true")
            helper.succeed()
        }
    }

    /**
     * A squad keeps advancing and firing while other men's post searches burn their path turns on
     * unreachable candidates: the shared per-tick budget starves neither side. That a search does
     * not rebuild the same terrain for every unreachable post is post-search-load's to prove.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 600, batch = "shared-post-search")
    fun squadKeepsMovingAndFiringWhileUnreachablePostsConsumeSearchTurns(helper: GameTestHelper) {
        val scene = Scene(helper, "shared-post-search")
        ridge(helper)
        val members = (0 until SHARED_MEN).map { index -> scene.spawn(Vec3(24.5, 1.0, 22.5 + index * 5)) }
        scene.enemy(Vec3(140.5, 1.0, 40.5))
        val squad = scene.squad(members)
        // Stationary clients search alongside the real brains: thirty-two raised posts nobody can
        // reach before one floor, so each eats path turns before it finds anything.
        val loads = (0 until SHARED_MEN).map { index -> searchingClient(helper, scene, index) }
        val started = members.associate { it.uuid to it.position() }
        val finished = mutableSetOf<java.util.UUID>()
        val fired = mutableSetOf<java.util.UUID>()
        val advanced = mutableSetOf<java.util.UUID>()
        helper.onEachTick {
            scene.tick(squad)
            members.forEach { npc ->
                if (npc.lastShotTick >= 0) fired += npc.uuid
                if (npc.x - started.getValue(npc.uuid).x >= 15.0) advanced += npc.uuid
            }
            if (helper.tick >= 10 && helper.tick % 2L == 0L) loads.filter { it.first.uuid !in finished }.forEach { (npc, task) -> if (searched(helper, npc, task)) finished += npc.uuid }
            val active = members.count { it.firedRecently(40) }
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "finished=${finished.size}/$SHARED_MEN fired=${fired.size} active=$active advanced=${advanced.size}")
            val squadFights = fired.size == SHARED_MEN && active >= SHARED_MEN / 2 && advanced.size >= SHARED_MEN * 3 / 4
            if (finished.size == SHARED_MEN && squadFights) {
                scene.result("PASS", "finished=$SHARED_MEN fired=${fired.size} active=$active advanced=${advanced.size} tick=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /** A ridge across the whole arena between the squad and its enemy. */
    private fun ridge(helper: GameTestHelper) {
        for (x in 46..80) {
            for (z in 0..223) {
                val height = minOf(4, (x - 46) / 3 + 1, (80 - x) / 3 + 1)
                for (y in 1..height) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
            }
        }
    }

    /** A still man whose post search ranks thirty-two unreachable raised posts before his floor. */
    private fun searchingClient(helper: GameTestHelper, scene: Scene, index: Int): Pair<NpcEntity, TacticalTask> {
        val npc = scene.spawn(Vec3(10.5 + index % 4 * 4, 1.0, 95.5 + index / 4 * 4)).apply { isNoAi = true }
        val goal = helper.absoluteVec(Vec3(10.5 + index % 4 * 4, 1.0, 124.5 + index / 4 * 4))
        val candidates = (0..31).map { helper.absoluteVec(Vec3(95.5 + it % 8 * 2, 14.0, 110.5 + it / 8 * 2)) } + goal
        val task = TacticalTask(TacticalJob.SEARCH, goal, null, 1).apply {
            search = TacticalPositionSearch(npc.position(), emptyList()).also { search ->
                search.destinations = candidates
                candidates.forEachIndexed { rank, point -> search.scored[point] = TacticalPositionScore(false, 0, rank.toDouble()) }
            }
        }
        return npc to task
    }

    /** One search turn for [npc]; true once it found his floor. */
    private fun searched(helper: GameTestHelper, npc: NpcEntity, task: TacticalTask): Boolean {
        npc.setOnGround(true)
        val view = TacticalSnapshot(helper.level.gameTime, SquadOrder.ATTACK, 1, npc.position(), null, listOf(TacticalMember(npc.uuid, npc.position())), emptyList())
        val result = TacticalPositions.find(npc, task, view)
        helper.assertTrue(result.outcome != TacticalPositions.Outcome.UNREACHABLE, "Queued client lost its reachable floor")
        if (result.outcome != TacticalPositions.Outcome.FOUND) return false
        npc.navigation.stop()
        return true
    }

    /** Men in the shared-budget squad, and as many searching clients beside it. */
    private const val SHARED_MEN = 8

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

    private object ShelteredAdvance : com.sbwnpc.squad.combat.tactics.TacticalPositionPolicy {
        override fun requiresShelter(view: TacticalSnapshot, task: TacticalTask) = view.incoming.isNotEmpty()
    }
}
