package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.CombatPosition
import com.sbwnpc.squad.combat.SquadAreas
import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.Squad
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
object SquadAreaGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "operation_arena", skyAccess = true, timeoutTicks = 1800, batch = "area-distant-wall")
    fun distantPatrolAreaBehindWallIsReplacedWithoutAbandoningReachableOriginalOrder(helper: GameTestHelper) = distantPatrolArea(helper, wall = true)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "operation_arena", skyAccess = true, timeoutTicks = 1800, batch = "area-distant-clear")
    fun reachableDistantPatrolAreaStaysStableUntilTheSquadArrives(helper: GameTestHelper) = distantPatrolArea(helper, wall = false)

    private fun distantPatrolArea(helper: GameTestHelper, wall: Boolean) {
        val fixture = Fixture(helper, if (wall) "area-distant-wall" else "area-distant-clear")
        val groups = List(2) { fixture.group(32, SquadOrder.PATROL, Vec3(176.5, 1.0, 192.5)) }
        groups.flatMap { it.second }.forEach { it.isNoAi = true }
        if (wall) for (y in 1..6) for (z in 0..383) helper.setBlock(BlockPos(190, y, z), Blocks.STONE)
        var members: List<NpcEntity>? = null
        var original: Vec3? = null
        var settled = 0
        helper.runAtTickTime(40) {
            members = groups.single { it.second.first().homeCenter() != it.second.first().orderCenter() }.second
            original = members!!.first().homeCenter()
            helper.assertTrue(helper.relativeVec(original!!).x > 260 && members!!.all { it.position().distanceTo(original!!) > 72 }, "Fixture must start farther than the validation threshold with a wall before it")
            members!!.forEach { it.isNoAi = false }
        }
        helper.onEachTick {
            val active = members ?: return@onEachTick
            if (helper.tick % 20L != 0L) return@onEachTick
            val home = active.first().homeCenter()!!
            val near = active.count { it.position().subtract(home).horizontalDistance() <= SquadFormation.patrolRadius(active.size) + 3 }
            if (helper.tick % 100L == 0L) fixture.scene.result("CHECK", "tick=${helper.tick} home=${helper.relativeVec(home)} original=${original?.let(helper::relativeVec)} occupied=$near xRange=${active.minOf { helper.relativeVec(it.position()).x }}..${active.maxOf { helper.relativeVec(it.position()).x }} zRange=${active.minOf { helper.relativeVec(it.position()).z }}..${active.maxOf { helper.relativeVec(it.position()).z }} distances=${active.map { it.position().subtract(home).horizontalDistance() }.sorted()}")
            if (!wall) helper.assertTrue(home == original, "Reachable distant area changed before the squad arrived")
            val placed = if (wall) home != original && helper.relativeVec(home).x < 190 else home == original
            if (placed && near >= 24) settled++
            if (settled >= 5) {
                helper.assertTrue(active.all { it.isAlive }, "Patrol relocation caused environmental deaths")
                fixture.scene.result("PASS", "previous=${helper.relativeVec(original!!)} replacement=${helper.relativeVec(home)} occupied=$near stableWindows=$settled tick=${helper.tick}")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "operation_arena", skyAccess = true, timeoutTicks = 800, batch = "area-contact-fire")
    fun defendersFireBeforeArrivingInTheirAssignedAreaAndKeepMovingThere(helper: GameTestHelper) {
        val fixture = Fixture(helper, "area-contact-fire")
        val groups = List(2) { fixture.group(16, SquadOrder.DEFEND, Vec3(176.5, 1.0, 192.5)) }
        groups.flatMap { it.second }.forEach { it.isNoAi = true }
        var members: List<NpcEntity>? = null
        var home: Vec3? = null
        var initialDistance = 0.0
        val firedOutside = mutableSetOf<UUID>()
        helper.runAtTickTime(40) {
            members = groups.single { it.second.first().homeCenter() != it.second.first().orderCenter() }.second
            home = members!!.first().homeCenter()
            val center = members!!.fold(Vec3.ZERO) { sum, npc -> sum.add(npc.position()) }.scale(1.0 / members!!.size)
            initialDistance = center.distanceTo(home!!)
            fixture.scene.enemy(helper.relativeVec(center).add(-40.0, 0.0, 0.0))
            members!!.forEach { npc ->
                npc.moveTo(npc.x, npc.y, npc.z, 90f, 0f)
                npc.yHeadRot = 90f
                npc.isNoAi = false
            }
        }
        helper.onEachTick {
            val active = members ?: return@onEachTick
            active.filter { npc -> npc.firedRecently(1) && !CombatPosition.withinArea(home!!, npc.position(), CombatPosition.defendRadius(npc.npcClass, active.size)) }.forEach { firedOutside += it.uuid }
            if (helper.tick < 100 || helper.tick % 20L != 0L) return@onEachTick
            val center = active.fold(Vec3.ZERO) { sum, npc -> sum.add(npc.position()) }.scale(1.0 / active.size)
            val advanced = initialDistance - center.distanceTo(home!!)
            fixture.scene.result("CHECK", "tick=${helper.tick} firedOutside=${firedOutside.size} advancedTowardSector=$advanced targets=${active.count { it.target != null }}")
            if (firedOutside.size >= 8 && advanced >= 12.0) {
                helper.assertTrue(active.all { it.isAlive && it.lastHurtByMob == null }, "Scene relied on incoming damage or suffered friendly fire")
                fixture.scene.result("PASS", "firedOutside=${firedOutside.size}/16 advancedTowardSector=$advanced contactBeforeDamage=true")
                helper.succeed()
            }
        }
    }

    /**
     * A defended area walled off from its men is replaced by one they can reach, under the native
     * search limit, apart from its neighbour; then the men are let walk and most of them get into it.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "operation_arena", skyAccess = true, timeoutTicks = 900, batch = "area-blocked")
    fun unreachableAreaIsReplacedWithBoundedNativeValidation(helper: GameTestHelper) {
        val fixture = Fixture(helper, "area-blocked")
        val groups = List(2) { fixture.group(16, SquadOrder.DEFEND, Vec3(176.5, 1.0, 192.5)) }
        groups.flatMap { it.second }.forEach { it.isNoAi = true }
        var shifted: List<NpcEntity>? = null
        var original: Vec3? = null
        var settled = 0
        var searches = 0
        var walkingFrom: Long? = null
        fixture.afterClose = { groups.flatMap { it.second }.forEach { (it.navigation as VehicleAwareNavigation).pathWorkObserver = null } }
        helper.runAtTickTime(40) {
            shifted = wallOffShiftedGroup(helper, groups) { searches++ }
            original = shifted!!.first().homeCenter()
        }
        helper.onEachTick {
            val members = shifted ?: return@onEachTick
            // NoAI disables normal grounding as well as locomotion: while the area is validated
            // the men stand on a solid floor, and walk only once it is chosen.
            if (walkingFrom == null) members.forEach { it.setOnGround(true) }
            val home = requireNotNull(members.first().homeCenter())
            if (helper.tick % 20L == 0L) fixture.scene.result("CHECK", "tick=${helper.tick} home=${helper.relativeVec(home)} original=${helper.relativeVec(original!!)} searches=$searches actor=${helper.relativeVec(members.first().position())} onGround=${members.first().onGround()} canPlan=${(members.first().navigation as VehicleAwareNavigation).canPlan}")
            if (home == original || helper.relativeVec(home).x >= 230) return@onEachTick
            val from = walkingFrom
            if (from == null) {
                if (helper.tick % 20L == 0L) settled++
                if (settled < 3) return@onEachTick
                assertReplacement(helper, searches, home, groups.single { it.second !== members }.second)
                // Validation alone could pick a point that only looks reachable: the men walk to it.
                members.forEach { it.isNoAi = false }
                walkingFrom = helper.tick
                return@onEachTick
            }
            val inside = insideArea(members, home)
            if (inside >= members.size * 3 / 4) {
                fixture.scene.result("PASS", "previous=${helper.relativeVec(original!!)} replacement=${helper.relativeVec(home)} nativeSearches=$searches inside=$inside/${members.size} walked=${helper.tick - from}")
                helper.succeed()
            }
            helper.assertTrue(helper.tick - from < WALK_TICKS, "Only $inside of ${members.size} men reached the replacement area at ${helper.relativeVec(home)}")
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "operation_arena", skyAccess = true, timeoutTicks = 1200, batch = "area-patrol")
    fun nearbyFreePatrolOrdersRoamSeparateAreas(helper: GameTestHelper) = holding(helper, SquadOrder.PATROL)

    /** A free patrol's point moves within its area: the men drift on, not all through the new point (#176). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "operation_arena", skyAccess = true, timeoutTicks = 1400, batch = "area-patrol-shift")
    fun freePatrolDriftsOnWhenItsPointMoves(helper: GameTestHelper) {
        val fixture = Fixture(helper, "area-patrol-shift")
        val (squad, members) = fixture.group(8, SquadOrder.PATROL, Vec3(200.5, 1.0, 200.5))
        var shiftedAt = 0L
        val crossed = HashSet<UUID>()
        helper.onEachTick {
            if (shiftedAt == 0L) {
                val first = members.first().homeCenter() ?: return@onEachTick
                if (helper.tick < 300 || members.any { it.position().subtract(first).horizontalDistance() > SquadFormation.patrolRadius(members.size) }) return@onEachTick
                SquadManager.get(helper.level).setObjective(helper.level, squad.id, BlockPos.containing(first.add(16.0, 0.0, 0.0)))
                shiftedAt = helper.tick
                return@onEachTick
            }
            val point = requireNotNull(members.first().homeCenter())
            members.filter { it.position().subtract(point).horizontalDistance() <= 3.0 }.forEach { crossed += it.uuid }
            if (helper.tick - shiftedAt >= 400) {
                helper.assertTrue(crossed.size <= 2, "${crossed.size} of ${members.size} men walked through the moved patrol point")
                fixture.scene.result("PASS", "shiftedAt=$shiftedAt point=${helper.relativeVec(point)} crossed=${crossed.size}")
                helper.succeed()
            }
        }
    }

    private fun holding(helper: GameTestHelper, order: SquadOrder) {
        val fixture = Fixture(helper, "area-${order.name.lowercase()}")
        val groups = listOf(fixture.group(16, order, Vec3(176.5, 1.0, 192.5)), fixture.group(16, order, Vec3(188.5, 1.0, 192.5)))
        var homes: List<Vec3>? = null
        var windows = 0
        helper.onEachTick {
            if (helper.tick < 40 || helper.tick % 20L != 0L) return@onEachTick
            val now = groups.map { requireNotNull(it.second.first().homeCenter()) }
            helper.assertTrue(now[0].subtract(now[1]).horizontalDistance() >= 60.0, "Nearby orders did not receive separate areas: $now")
            if (homes == null) homes = now
            helper.assertTrue(homes == now, "Holding areas changed while the order and membership were unchanged")
            val near = groups.mapIndexed { index, (_, members) ->
                members.count { member ->
                    member.position().subtract(now[index]).horizontalDistance() <= if (order == SquadOrder.PATROL) SquadFormation.patrolRadius(members.size) + 3.0 else SquadFormation.defendArrivalRadius(members.size) + 3.0
                }
            }
            if (helper.tick % 100L == 0L) {
                fixture.scene.result("CHECK", "tick=${helper.tick} homes=${now.map(helper::relativeVec)} occupied=$near members=${groups.map { (_, members) -> members.map { npc -> "${helper.relativeVec(npc.position())}:alert=${npc.isAlert()}:target=${npc.target?.uuid}:done=${npc.navigation.isDone}:path=${npc.navigation.path?.target}:job=${com.sbwnpc.squad.ai.integration.NativeTactics.assignment(npc)?.job}" } }}")
            }
            if (helper.tick >= 300 && near.all { it >= 12 }) windows++
            if (windows >= 5) {
                fixture.scene.result("PASS", "homes=${now.map(helper::relativeVec)} occupied=$near stableWindows=$windows")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "operation_arena", skyAccess = true, timeoutTicks = 1400, batch = "area-retreat")
    fun twoFullRetreatSquadsReachOwnRalliesAndKeepThemWhenDefending(helper: GameTestHelper) {
        val fixture = Fixture(helper, "area-retreat")
        val groups = List(2) { index -> fixture.group(32, SquadOrder.RETREAT, Vec3(176.5 + index * 6, 1.0, 280.5)) }
        var homes: List<Vec3>? = null
        var defendedAt = 0L
        helper.onEachTick {
            if (helper.tick < 40 || helper.tick % 20L != 0L) return@onEachTick
            val now = groups.map { requireNotNull(it.second.first().homeCenter()) }
            if (homes == null) {
                homes = now
                helper.assertTrue(now[0].subtract(now[1]).horizontalDistance() >= 60.0, "Retreat rallies overlap")
            }
            helper.assertTrue(homes == now, "RETREAT -> DEFEND moved a settled squad back toward the common objective")
            if (groups.all { it.first.order == SquadOrder.DEFEND }) {
                if (defendedAt == 0L) defendedAt = helper.tick
                if (helper.tick - defendedAt >= 100) {
                    val counts = groups.mapIndexed { i, (_, members) -> members.count { it.position().subtract(now[i]).horizontalDistance() <= 33.0 } }
                    helper.assertTrue(counts.all { it >= 24 }, "Full retreat squads did not settle in their own defensive areas: $counts")
                    fixture.scene.result("PASS", "homes=${now.map(helper::relativeVec)} defendedAt=$defendedAt occupied=$counts")
                    helper.succeed()
                }
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "operation_arena", skyAccess = true, timeoutTicks = 1300, batch = "area-work")
    fun fourDefendSquadsMeasureNativeWorkAndHoldSeparateAreas(helper: GameTestHelper) {
        val fixture = Fixture(helper, "area-work")
        val groups = List(4) { fixture.group(32, SquadOrder.DEFEND, Vec3(176.5, 1.0, 192.5)) }
        val work = NativeCombatWork(helper, groups.flatMap { it.second })
        fixture.afterClose = { work.close() }
        helper.onEachTick {
            if (helper.tick < 400 || helper.tick % 100L != 0L) return@onEachTick
            val homes = groups.map { it.second.first().homeCenter() ?: return@onEachTick }
            for (a in homes.indices) for (b in a + 1 until homes.size) helper.assertTrue(homes[a].distanceTo(homes[b]) >= 60, "Large squad areas overlap")
            val counts = groups.mapIndexed { index, (_, members) -> members.count { it.position().subtract(homes[index]).horizontalDistance() <= 33.0 } }
            // Three quarters of each squad in its own area ends the scene; by the deadline, the
            // stragglers of a 32-man squad still walking leave it at five eighths.
            val settled = counts.all { it >= OCCUPIED }
            if (helper.tick >= 1200) helper.assertTrue(counts.all { it >= OCCUPIED_BY_DEADLINE }, "Large defenders did not occupy separated areas: $counts")
            if (settled || helper.tick >= 1200) {
                work.report()
                fixture.scene.result("PASS", "tick=${helper.tick} homes=${homes.map(helper::relativeVec)} occupied=$counts")
                helper.succeed()
            }
        }
    }

    /** Men of a 32-man defence inside its area that settle it early, and the least by the deadline. */
    private const val OCCUPIED = 24
    private const val OCCUPIED_BY_DEADLINE = 20

    /**
     * Walls off the area of the group whose area was shifted off its order point, puts its men on
     * this side of the wall and counts their native path searches with [onSearch].
     */
    private fun wallOffShiftedGroup(helper: GameTestHelper, groups: List<Pair<Squad, List<NpcEntity>>>, onSearch: () -> Unit): List<NpcEntity> {
        val shifted = groups.single { it.second.first().homeCenter() != it.second.first().orderCenter() }.second
        for (y in 1..5) for (z in 0..383) helper.setBlock(BlockPos(230, y, z), Blocks.STONE)
        shifted.forEachIndexed { index, npc ->
            val point = helper.absoluteVec(Vec3(218.5 + index % 4, 1.0, 184.5 + index / 4))
            npc.moveTo(point.x, point.y, point.z, 0f, 0f)
            (npc.navigation as VehicleAwareNavigation).pathWorkObserver = { _, _, native -> if (native) onSearch() }
        }
        return shifted
    }

    /** The replacement obeyed the native search limit and keeps clear of [neighbour]'s area. */
    private fun assertReplacement(helper: GameTestHelper, searches: Int, home: Vec3, neighbour: List<NpcEntity>) {
        helper.assertTrue(searches in 1..8, "Area validation did not obey its native-search limit: $searches")
        helper.assertTrue(neighbour.first().homeCenter()!!.distanceTo(home) >= 68.0, "Replacement area overlaps its neighbor")
    }

    /** How many of [members] stand in the area round [home]. */
    private fun insideArea(members: List<NpcEntity>, home: Vec3): Int {
        val radius = CombatPosition.defendRadius(members.first().npcClass, members.size) + AREA_SLACK
        return members.count { Math.hypot(it.x - home.x, it.z - home.z) <= radius }
    }

    /** A man counts inside his area with a step of slack at its edge. */
    private const val AREA_SLACK = 3.0

    /** Ticks the men get to walk into the replacement area: it lies within a few dozen blocks. */
    private const val WALK_TICKS = 600L

    private class Fixture(val helper: GameTestHelper, name: String) {
        val scene = CombatIdleGameTests.Scene(helper, name, depth = 384, recordTrace = name in setOf("area-patrol", "area-blocked"))
        private val manager = SquadManager.get(helper.level)
        private val squads = mutableListOf<Squad>()
        var afterClose: () -> Unit = {}
        init {
            for (x in 176..351) for (z in 0..383) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
            scene.onClose = {
                afterClose()
                squads.forEach { manager.deleteSquad(helper.level, it.id) }
                SquadAreas.clearAll()
            }
        }
        fun group(size: Int, order: SquadOrder, objective: Vec3, role: NpcClass = NpcClass.RIFLEMAN): Pair<Squad, List<NpcEntity>> {
            val index = squads.size
            val members = List(size) { i -> scene.spawn(Vec3(158.5 + i % 8 * 2.0, 1.0, 154.5 + i / 8 * 2.0 + index * 12.0), role) }
            val squad = manager.create(helper.level, UUID.randomUUID(), requireNotNull(members.first().spawnFaction), members.map { it.uuid })
            manager.setOrder(squad.id, order)
            manager.setObjective(helper.level, squad.id, BlockPos.containing(helper.absoluteVec(objective)))
            squads += squad
            return squad to members
        }
    }
}
