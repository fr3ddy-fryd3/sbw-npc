package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.TickBudget
import com.sbwnpc.squad.combat.tactics.*
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
            val result = TacticalPositions.find(npc, task, view)
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
        val goal = VehicleAwareNavigation.walkingNode(helper.absoluteVec(Vec3(45.5, 1.0, 100.5)))
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
    @GameTest(templateNamespace = "sbwnpc", template = "battle_arena", timeoutTicks = 800, batch = "battle-192", skyAccess = true)
    fun ninetySixAgainstNinetySixKeepCommittedNavigationAndExchangeFire(helper: GameTestHelper) {
        val scene = Scene(helper, "battle-192", depth = 608)
        // A shallow ridge and scattered one-block cover interrupt some direct firing lanes.
        for (x in 68..82) {
            for (z in 0..607) {
                helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
                if (z % 30 < 12 && x in 73..77) helper.setBlock(BlockPos(x, 2, z), Blocks.STONE)
            }
        }
        for (z in 12..595 step 12) helper.setBlock(BlockPos(117, 1, z), Blocks.STONE)
        val roles = listOf(NpcClass.RIFLEMAN, NpcClass.RIFLEMAN, NpcClass.RIFLEMAN, NpcClass.RIFLEMAN, NpcClass.MACHINE_GUNNER, NpcClass.SNIPER, NpcClass.SHOTGUNNER, NpcClass.MEDIC)
        val sides = listOf(SquadFaction.PIG, SquadFaction.COW).mapIndexed { side, faction ->
            (0..2).map { group ->
                (0..31).map { index ->
                    val x = if (side == 0) 25.5 + index / 8 * 4 else 142.5 - index / 8 * 4
                    // 28 combat slots at six blocks need 162 blocks of frontage per squad.
                    // Separate fronts exercise shared budgets without assigning overlapping lanes.
                    scene.spawn(Vec3(x, 1.0, 90.5 + group * 190 + index % 8 * 6), roles[index % roles.size], faction).apply {
                        getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 5000.0
                        health = maxHealth
                    }
                }
            }
        }
        val squads = sides.mapIndexed { side, groups ->
            groups.mapIndexed { group, members ->
                scene.squad(members, contact = sides[1 - side][group].first(), order = if (side == 0) SquadOrder.ATTACK else SquadOrder.DEFEND)
            }
        }
        val starting = sides.flatten().flatten().associate { it.uuid to it.position() }
        val fired = sides.map { mutableSetOf<java.util.UUID>() }
        var directionReversals = 0
        val previousVelocity = mutableMapOf<java.util.UUID, Vec3>()
        helper.onEachTick {
            squads.flatten().forEach { scene.tick(it) }
            sides.forEachIndexed { side, groups ->
                groups.flatten().forEach { npc ->
                    val nav = npc.navigation
                    if (!nav.isDone) helper.assertTrue(nav.targetPos == nav.path!!.target, "Active goal disagrees with committed path for ${npc.uuid}")
                    if (npc.lastShotTick >= 0) fired[side] += npc.uuid
                    val movement = npc.deltaMovement.multiply(1.0, 0.0, 1.0)
                    val previous = previousVelocity.put(npc.uuid, movement)
                    if (previous != null && movement.horizontalDistance() > 0.06 && previous.horizontalDistance() > 0.06 && movement.normalize().dot(previous.normalize()) < -0.5) directionReversals++
                }
            }
            val advanced = sides[0].flatten().count { it.position().distanceTo(starting.getValue(it.uuid)) >= 12.0 }
            val recent = sides.map { groups -> groups.flatten().count { it.firedRecently(40) } }
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "fired=${fired.map { it.size }} recent=$recent advanced=$advanced reversals=$directionReversals tick=${helper.tick}")
            if (helper.tick == 720L) {
                helper.assertTrue(sides.all { groups -> groups.flatten().count { it.isAlive } == 96 }, "Fixture lost fighters before completing load window")
                // This is a route-ownership stress check, not a win-rate or firing-participation
                // guarantee. Keep enough live gunfire to exercise the workload; record the
                // separate 50% participation target even when the tactical result misses it.
                helper.assertTrue(fired.all { it.size >= 16 } && recent.all { it >= 8 }, "Load window lacked sustained two-sided gunfire: fired=${fired.map { it.size }} recent=$recent")
                helper.assertTrue(advanced >= 24, "Attack did not physically advance: $advanced/96")
                scene.result("PASS", "active=96+96 fired=${fired.map { it.size }} recent=$recent participationTargetMet=${fired.all { it.size >= 48 }} advanced=$advanced reversals=$directionReversals tick=${helper.tick}")
                helper.succeed()
            }
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
            val active = nav.createPositionPath(VehicleAwareNavigation.walkingNode(first))!!
            helper.assertTrue(nav.moveTo(active, 1.0), "Initial route was not started")
            val probe = nav.createPositionPath(VehicleAwareNavigation.walkingNode(second))!!
            helper.assertTrue(probe.target == VehicleAwareNavigation.walkingNode(second), "First probe did not reach its own target")
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
            helper.assertTrue(nav.path?.target == VehicleAwareNavigation.walkingNode(second), "New post did not own its route")
            nav.createPositionPath(VehicleAwareNavigation.walkingNode(rejected))
            helper.assertTrue(nav.targetPos == nav.path?.target, "Rejected post redirected the active navigation target")
            repeat(2) {
                val ordinaryProbe = nav.createPath(VehicleAwareNavigation.walkingNode(rejected), 1)!!
                helper.assertTrue(ordinaryProbe.target == VehicleAwareNavigation.walkingNode(rejected), "March cache returned an unrelated tactical route")
                helper.assertTrue(nav.targetPos == nav.path?.target, "Ordinary probe redirected the active post")
            }
        }
        helper.runAtTickTime(45) {
            npc.setOnGround(true)
            nav.recomputePath()
            helper.assertTrue(nav.path?.target == VehicleAwareNavigation.walkingNode(second), "Terrain recomputation followed an unaccepted post")
            scene.result("PASS", "repeatedProbeTargetsOwnPost=true rejectedProbeKeepsActiveGoal=true recomputeKeepsCommittedPost=true")
            helper.succeed()
        }
    }

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
            members to scene.squad(members)
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
