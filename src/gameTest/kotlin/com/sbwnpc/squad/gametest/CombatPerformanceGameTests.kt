package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.FiringCone
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.tactics.TacticalPattern
import com.sbwnpc.squad.domain.port.Guns
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.entity.ai.PathBlockCache
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import com.sbwnpc.squad.entity.ai.VehicleAwareNodeEvaluator
import com.sbwnpc.squad.entity.ai.WalkingClearance
import com.sbwnpc.squad.gametest.CombatIdleGameTests.Scene
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockCollisions
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.CollisionGetter
import net.minecraft.world.level.PathNavigationRegion
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.piston.PistonHeadBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.pathfinder.PathFinder
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.level.pathfinder.PathfindingContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.VoxelShape
import net.neoforged.fml.ModList
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.lang.management.ManagementFactory

/** Real-world measurements; timings are evidence, deterministic work counts are assertions. */
@PrefixGameTestTemplate(false)
object CombatPerformanceGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 100, batch = "path-block-reads")
    fun pathBlockCacheReducesReadsWithoutChangingRoutes(helper: GameTestHelper) {
        val scene = Scene(helper, "path-block-reads")
        val npc = scene.spawn(Vec3(30.5, 1.0, 100.5)).apply { isNoAi = true }
        for (x in 45..46) for (z in 60..140) for (y in 1..5) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        for (x in 35..39) for (z in 104..108) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
        helper.setBlock(BlockPos(34, 1, 96), Blocks.OAK_LEAVES)
        helper.setBlock(BlockPos(35, 1, 96), Blocks.MANGROVE_ROOTS)
        helper.setBlock(BlockPos(36, 1, 96), Blocks.OAK_SLAB)
        helper.setBlock(BlockPos(37, 0, 96), Blocks.POWDER_SNOW)
        // These are beyond the unreachable wall, so they cannot alter the benchmark routes.
        helper.setBlock(BlockPos(60, 2, 101), Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, Direction.EAST))
        helper.setBlock(BlockPos(60, 2, 105), Blocks.OAK_SLAB)
        val dynamicBlocks = mutableListOf<String>()
        if (ModList.get().isLoaded("dynamictrees")) {
            val entries = BuiltInRegistries.BLOCK.keySet().filter { it.namespace == "dynamictrees" }
            for ((index, type) in listOf("BasicBranchBlock", "SurfaceRootBlock").withIndex()) {
                val id = entries.firstOrNull { BuiltInRegistries.BLOCK.get(it).javaClass.simpleName == type } ?: error("Dynamic Trees has no $type")
                val block = BuiltInRegistries.BLOCK.get(id)
                val radius = block.stateDefinition.getProperty("radius") as? IntegerProperty ?: error("No radius on $id")
                helper.setBlock(BlockPos(34 + index, 1, 96), block.defaultBlockState().setValue(radius, radius.possibleValues.max().coerceAtMost(8)))
                dynamicBlocks += id.toString()
            }
        }
        helper.runAtTickTime(10) {
            npc.setOnGround(true)
            val origin = npc.blockPosition()
            val region = PathNavigationRegion(helper.level, origin.offset(-56, -56, -56), origin.offset(56, 56, 56))
            val collisionCache = PathBlockCache(region)
            for (probe in listOf(BlockPos(59, 1, 101), BlockPos(60, 1, 105))) {
                val at = helper.absolutePos(probe)
                val box = WalkingClearance.body(at.x, at.y.toDouble(), at.z, npc.bbWidth, npc.bbHeight)
                helper.assertTrue(!region.noBlockCollision(npc, box), "Protruding/partial collider fixture does not intersect at $probe")
                helper.assertTrue(!collisionCache.noBlockCollision(npc, box), "Deferred body shape missed protruding/partial collider at $probe")
            }
            val goals = listOf(BlockPos(60, 1, 100), BlockPos(42, 1, 110), BlockPos(38, 2, 105)).map(helper::absolutePos)
            val cpuClock = ManagementFactory.getThreadMXBean()
            fun signature(path: net.minecraft.world.level.pathfinder.Path?): String = "${path?.canReach()}:${path?.let { p -> (0 until p.nodeCount).map { p.getNode(it).let { n -> "${n.asBlockPos()}/${n.type}/${n.costMalus}" } } }}"
            fun measure(cached: Boolean, countReads: Boolean = true): ReadMeasurement {
                val evaluator = CountingEvaluator(cached, countReads).apply { setCanPassDoors(true) }
                val finder = PathFinder(evaluator, 768)
                val results = mutableListOf<String>()
                var nanos = 0L
                var cpuNanos = 0L
                repeat(4) {
                    for (goal in goals) {
                        val started = System.nanoTime()
                        val startedCpu = cpuClock.currentThreadCpuTime
                        val path = finder.findPath(region, npc, setOf(goal), 48f, 0, 1f)
                        cpuNanos += cpuClock.currentThreadCpuTime - startedCpu
                        nanos += System.nanoTime() - started
                        results += signature(path)
                    }
                }
                return ReadMeasurement(evaluator.reads, evaluator.chunkReads, evaluator.typeChecks, evaluator.duplicateChecks, nanos, cpuNanos, results)
            }
            measure(false)
            measure(true)
            val before = measure(false)
            val after = measure(true)
            // Let both native paths compile before comparing CPU time. The counting
            // getter intentionally changes dispatch and is not a timing baseline.
            repeat(12) {
                measure(false, false)
                measure(true, false)
            }
            val baselineTimes = mutableListOf<Long>()
            val cachedTimes = mutableListOf<Long>()
            val baselineCpuTimes = mutableListOf<Long>()
            val cachedCpuTimes = mutableListOf<Long>()
            repeat(7) { iteration ->
                for (cached in if (iteration % 2 == 0) listOf(false, true) else listOf(true, false)) {
                    val measured = measure(cached, false)
                    helper.assertTrue(measured.routes == before.routes, "Removing counters changed route signatures")
                    (if (cached) cachedTimes else baselineTimes).add(measured.nanos)
                    (if (cached) cachedCpuTimes else baselineCpuTimes).add(measured.cpuNanos)
                }
            }
            val timing = "referenceMedianMillis=${baselineTimes.sorted()[3] / 1_000_000.0} cachedMedianMillis=${cachedTimes.sorted()[3] / 1_000_000.0} referenceCpuMedianMillis=${baselineCpuTimes.sorted()[3] / 1_000_000.0} cachedCpuMedianMillis=${cachedCpuTimes.sorted()[3] / 1_000_000.0} referenceSamples=$baselineTimes cachedSamples=$cachedTimes referenceCpuSamples=$baselineCpuTimes cachedCpuSamples=$cachedCpuTimes lithium=${ModList.get().isLoaded("lithium")} dynamicBlocks=$dynamicBlocks"
            helper.assertTrue(before.routes == after.routes, "Cached block reads changed complete reachable/unreachable path signatures")
            val counts = "referenceReads=${before.reads} cachedReads=${after.reads} referenceCollisionReads=${before.collisionReads} cachedCollisionReads=${after.collisionReads} typeChecks=${before.checks} duplicateTypeChecks=${before.duplicates}"
            scene.result("MEASURED", "$counts $timing routes=${before.routes.take(3)}")
            helper.assertTrue(after.collisionReads * 2 < before.collisionReads && after.reads < before.reads, "Collision cache did not halve its underlying reads: $counts")

            // A new search must see edits immediately, even with the same evaluator and target.
            val evaluator = CountingEvaluator(true).apply { setCanPassDoors(true) }
            val finder = PathFinder(evaluator, 768)
            val goal = helper.absolutePos(BlockPos(42, 1, 110))
            val first = finder.findPath(region, npc, setOf(goal), 48f, 0, 1f)
            helper.assertTrue(first?.canReach() == true, "Terrain-change fixture did not start with a reachable post")
            helper.setBlock(BlockPos(42, 1, 110), Blocks.STONE)
            helper.setBlock(BlockPos(42, 2, 110), Blocks.STONE)
            val closed = finder.findPath(region, npc, setOf(goal), 48f, 0, 1f)
            helper.assertTrue(closed?.canReach() != true, "Cached block states outlived their search and hid a new collider")
            helper.setBlock(BlockPos(42, 1, 110), Blocks.AIR)
            helper.setBlock(BlockPos(42, 2, 110), Blocks.AIR)
            helper.assertTrue(finder.findPath(region, npc, setOf(goal), 48f, 0, 1f)?.canReach() == true, "Search did not see a reopened post")
            scene.result("PASS", "$counts $timing terrainInvalidation=PASS")
            helper.succeed()
        }
    }

    private data class ReadMeasurement(val reads: Int, val collisionReads: Int, val checks: Int, val duplicates: Int, val nanos: Long, val cpuNanos: Long, val routes: List<String>)

    private class CountingEvaluator(private val cached: Boolean, private val countReads: Boolean = true) : VehicleAwareNodeEvaluator() {
        var reads = 0
        var chunkReads = 0
        var typeChecks = 0
        var duplicateChecks = 0
        private val checked = HashSet<Long>()

        override fun prepare(level: PathNavigationRegion, mob: Mob) {
            super.prepare(level, mob)
            checked.clear()
            val counted = if (countReads) ReadCounter(level, { reads++ }, { chunkReads++ }) else level
            currentContext = PathfindingContext(counted, mob)
            collisionBlocks = if (cached) PathBlockCache(counted) else counted
        }

        override fun getPathTypeOfMob(context: PathfindingContext, x: Int, y: Int, z: Int, mob: Mob): PathType {
            if (countReads) {
                typeChecks++
                if (!checked.add(BlockPos.asLong(x, y, z))) duplicateChecks++
            }
            return super.getPathTypeOfMob(context, x, y, z, mob)
        }
    }

    /** Counts reads from the real region/chunks, including reads inside vanilla BlockCollisions. */
    private class ReadCounter(private val source: CollisionGetter, private val read: () -> Unit, private val collisionRead: () -> Unit) : CollisionGetter by source {
        override fun getBlockState(pos: BlockPos): BlockState {
            read()
            return source.getBlockState(pos)
        }

        override fun getChunkForCollisions(chunkX: Int, chunkZ: Int): BlockGetter? {
            val chunk = source.getChunkForCollisions(chunkX, chunkZ) ?: return null
            return object : BlockGetter by chunk {
                override fun getBlockState(pos: BlockPos): BlockState {
                    read()
                    collisionRead()
                    return chunk.getBlockState(pos)
                }
            }
        }

        override fun getBlockCollisions(entity: Entity?, box: AABB): Iterable<VoxelShape> = Iterable { BlockCollisions(this, entity, box, false) { _, shape -> shape } }

        override fun noBlockCollision(entity: Entity?, box: AABB): Boolean = super<CollisionGetter>.noBlockCollision(entity, box)
        override fun noCollision(entity: Entity?, box: AABB): Boolean = super<CollisionGetter>.noCollision(entity, box)
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 400, batch = "failed-combat-path")
    fun failedBlindApproachBacksOffAndResumesAfterTerrainOpens(helper: GameTestHelper) {
        val scene = Scene(helper, "failed-combat-path")
        for (x in 40..41) for (z in 0..223) for (y in 1..8) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        val shooter = scene.spawn(Vec3(30.5, 1.0, 100.5))
        scene.enemy(Vec3(150.5, 1.0, 100.5))
        val squad = scene.squad(listOf(shooter), TacticalPattern.FOLLOW_ORDER)
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
            helper.assertTrue(blockedSearches in 1..12, "Unchanged failed approach rebuilt $blockedSearches paths in 80 ticks")
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

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 100, batch = "friendly-fire-work")
    fun friendlyFireStopsCompletedAssessmentAndKeepsBlastAndMotionSafety(helper: GameTestHelper) {
        val scene = Scene(helper, "friendly-fire-work")
        val shooter = scene.spawn(Vec3(10.5, 1.0, 100.5)).apply { isNoAi = true }
        val blocker = scene.spawn(Vec3(20.5, 1.0, 100.5)).apply { isNoAi = true }
        val impactAlly = scene.spawn(Vec3(150.5, 1.0, 120.5)).apply { isNoAi = true }
        repeat(204) { index -> scene.spawn(Vec3(20.5 + index % 20 * 6, 1.0, 20.5 + index / 20 * 6)).isNoAi = true }
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
        helper.runAtTickTime(10) {
            Ports.guns = counting
            try {
                place(blocker, Vec3(20.5, 1.0, 100.5))
                place(impactAlly, Vec3(150.5, 1.0, 120.5))
                val evidence = mutableListOf<String>()
                for (variant in 0..3) {
                    val blast = if (variant == 1 || variant == 2) 6.0 else 0.0
                    if (variant == 1) place(impactAlly, Vec3(150.5, 1.0, 104.5))
                    if (variant == 2) place(blocker, Vec3(20.5, 1.0, 120.5))
                    if (variant == 3) {
                        place(blocker, Vec3(20.5, 1.0, 110.5))
                        blocker.pose = Pose.CROUCHING
                        blocker.deltaMovement = Vec3(0.0, 0.0, -2.0)
                    }
                    val expected = referenceAssessment(shooter, aim, 0.0, impact, blast)
                    val actual = FriendlyFireGuard.assess(shooter, aim, 0.0, impact, blast)
                    helper.assertTrue(actual.lineClear == expected.lineClear && actual.blastClear == expected.blastClear, "Optimization changed projectile/blast safety in variant $variant")
                    evidence += "variant=$variant line=${actual.lineClear} blast=${actual.blastClear}"
                }
                place(blocker, Vec3(20.5, 1.0, 100.5))
                blocker.pose = Pose.STANDING
                place(impactAlly, Vec3(150.5, 1.0, 120.5))
                bounds = 0
                var started = System.nanoTime()
                repeat(128) { referenceAssessment(shooter, aim, 0.0, impact, 0.0) }
                val referenceNanos = System.nanoTime() - started
                val referenceBounds = bounds
                bounds = 0
                started = System.nanoTime()
                repeat(128) { FriendlyFireGuard.assess(shooter, aim, 0.0, impact, 0.0) }
                val currentNanos = System.nanoTime() - started
                val currentBounds = bounds
                scene.result("MEASURED", "referenceBounds=$referenceBounds currentBounds=$currentBounds referenceMillis=${referenceNanos / 1_000_000.0} currentMillis=${currentNanos / 1_000_000.0} safety=$evidence")
                helper.assertTrue(referenceBounds >= 200 * 128 && currentBounds <= 4 * 128, "Completed line check still evaluates all 207 NPCs: reference=$referenceBounds current=$currentBounds")
                scene.result("PASS", "referenceBounds=$referenceBounds currentBounds=$currentBounds referenceMillis=${referenceNanos / 1_000_000.0} currentMillis=${currentNanos / 1_000_000.0} safety=$evidence")
                helper.succeed()
            } finally {
                Ports.guns = actualGuns
            }
        }
    }

    /** Frozen pre-optimization implementation, an oracle and work-count baseline for the benchmark. */
    private fun referenceAssessment(shooter: NpcEntity, aim: Vec3, spread: Double, impact: Vec3, blast: Double): FriendlyFireGuard.Assessment {
        val level = shooter.level() as ServerLevel
        val cone = FiringCone(shooter.eyePosition, aim, spread)
        val speed = Ports.guns.inHand(shooter)?.muzzleVelocity?.coerceAtLeast(1.0) ?: 20.0
        val flight = kotlin.math.ceil(shooter.eyePosition.distanceTo(aim) / speed) + 1.0
        val checkBlast = blast > 0.0
        val blastBox = AABB(impact, impact).inflate(blast)
        var lineClear = true
        var blastClear = true
        forEachReferenceAlly(shooter, { cone.bounds.intersects(Ports.guns.firingSafetyBounds(it, flight)) || (checkBlast && blastBox.contains(it.position())) }) { ally ->
            if (lineClear && Ports.guns.canHitEntity(shooter, ally) && cone.intersects(Ports.guns.firingSafetyBounds(ally, flight))) lineClear = false
            if (blastClear && checkBlast && (ally !is Player || (!ally.isCreative && !ally.isSpectator)) && ally.position().distanceTo(impact) <= blast) blastClear = false
            if (!lineClear && !blastClear) return FriendlyFireGuard.Assessment(false, false)
        }
        return FriendlyFireGuard.Assessment(lineClear, blastClear)
    }

    private inline fun forEachReferenceAlly(shooter: NpcEntity, near: (LivingEntity) -> Boolean, action: (LivingEntity) -> Unit) {
        val level = shooter.level() as ServerLevel
        for (npc in NpcRegistry.all(level)) {
            if (npc !== shooter && near(npc) && !SquadTeams.isHostile(shooter, npc)) action(npc)
        }
        for (player in level.players()) {
            if (near(player) && !SquadTeams.isHostile(shooter, player)) action(player)
        }
    }
}
