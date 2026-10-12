package com.sbwnpc.squad.gametest

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
    @GameTest(templateNamespace = "sbwnpc", template = "operation_arena", skyAccess = true, timeoutTicks = 240, batch = "area-blocked")
    fun unreachableAreaIsReplacedWithBoundedNativeValidation(helper: GameTestHelper) {
        val fixture = Fixture(helper, "area-blocked")
        val groups = List(2) { fixture.group(16, SquadOrder.DEFEND, Vec3(176.5, 1.0, 192.5)) }
        groups.flatMap { it.second }.forEach { it.isNoAi = true }
        var shifted: List<NpcEntity>? = null
        var original: Vec3? = null
        var settled = 0
        var searches = 0
        fixture.afterClose = { groups.flatMap { it.second }.forEach { (it.navigation as VehicleAwareNavigation).pathWorkObserver = null } }
        helper.runAtTickTime(40) {
            shifted = groups.single { it.second.first().homeCenter() != it.second.first().orderCenter() }.second
            original = shifted!!.first().homeCenter()
            for (y in 1..5) for (z in 0..383) helper.setBlock(BlockPos(230, y, z), Blocks.STONE)
            shifted!!.forEachIndexed { index, npc ->
                val point = helper.absoluteVec(Vec3(218.5 + index % 4, 1.0, 184.5 + index / 4))
                npc.moveTo(point.x, point.y, point.z, 0f, 0f)
                (npc.navigation as VehicleAwareNavigation).pathWorkObserver = { _, _, native -> if (native) searches++ }
            }
        }
        helper.onEachTick {
            val members = shifted ?: return@onEachTick
            // NoAI disables normal grounding as well as locomotion. This component scene
            // places actors on a solid floor and exercises native validation, not movement.
            members.forEach { it.setOnGround(true) }
            val home = requireNotNull(members.first().homeCenter())
            if (helper.tick % 20L == 0L) fixture.scene.result("CHECK", "tick=${helper.tick} home=${helper.relativeVec(home)} original=${original?.let(helper::relativeVec)} searches=$searches actor=${helper.relativeVec(members.first().position())} onGround=${members.first().onGround()} canPlan=${(members.first().navigation as VehicleAwareNavigation).canPlan}")
            if (home == original || helper.relativeVec(home).x >= 230) return@onEachTick
            if (helper.tick % 20L == 0L) settled++
            if (settled >= 3) {
                helper.assertTrue(searches in 1..8, "Area validation did not obey its native-search limit: $searches")
                val other = groups.single { it.second !== members }.second.first().homeCenter()!!
                helper.assertTrue(other.distanceTo(home) >= 68.0, "Replacement area overlaps its neighbor")
                fixture.scene.result("PASS", "previous=${helper.relativeVec(original!!)} reachableReplacement=${helper.relativeVec(home)} nativeSearches=$searches")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "operation_arena", skyAccess = true, timeoutTicks = 100, batch = "area-authority")
    fun explicitRoutesMoveAndVehicleRolesKeepTheirOwnOrderPoints(helper: GameTestHelper) {
        val fixture = Fixture(helper, "area-authority")
        val requested = Vec3(176.5, 1.0, 192.5)
        val groups = List(2) { fixture.group(16, SquadOrder.DEFEND, requested) }
        val driver = fixture.group(1, SquadOrder.DEFEND, requested, NpcClass.TANK_CREW)
        (groups.flatMap { it.second } + driver.second).forEach { it.isNoAi = true }
        helper.runAtTickTime(40) {
            val shifted = groups.single { it.second.first().homeCenter() != it.second.first().orderCenter() }
            val npc = shifted.second.first()
            val point = npc.orderCenter()
            helper.assertTrue(driver.second.first().homeCenter() == driver.second.first().orderCenter(), "Vehicle role received an infantry area")
            val manager = SquadManager.get(helper.level)
            manager.setOrder(shifted.first.id, SquadOrder.MOVE)
            helper.assertTrue(npc.homeCenter() == point, "Explicit MOVE retained the old holding area")
            manager.setOrder(shifted.first.id, SquadOrder.PATROL)
            shifted.first.routeId = UUID.randomUUID()
            helper.assertTrue(npc.homeCenter() == point, "Explicit patrol route received a free-patrol area")
            fixture.scene.result("PASS", "move=rawObjective route=rawObjective vehicleRole=rawObjective")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "operation_arena", skyAccess = true, timeoutTicks = 1000, batch = "area-defend")
    fun nearbyDefendOrdersPhysicallyOccupyDifferentStableAreas(helper: GameTestHelper) = holding(helper, SquadOrder.DEFEND)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "operation_arena", skyAccess = true, timeoutTicks = 1200, batch = "area-patrol")
    fun nearbyFreePatrolOrdersRoamSeparateAreas(helper: GameTestHelper) = holding(helper, SquadOrder.PATROL)

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
                fixture.scene.result("CHECK", "tick=${helper.tick} homes=${now.map(helper::relativeVec)} occupied=$near members=${groups.map { (_, members) -> members.map { npc -> "${helper.relativeVec(npc.position())}:alert=${npc.isAlert()}:target=${npc.target?.uuid}:done=${npc.navigation.isDone}:path=${npc.navigation.path?.target}:job=${npc.currentSquad()?.tactics?.plan?.tasks?.get(npc.uuid)?.job}" } }}")
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
        helper.runAtTickTime(1200) {
            work.report()
            val homes = groups.map { requireNotNull(it.second.first().homeCenter()) }
            val counts = groups.mapIndexed { index, (_, members) -> members.count { it.position().subtract(homes[index]).horizontalDistance() <= 33.0 } }
            helper.assertTrue(counts.all { it >= 24 }, "Large defenders did not occupy separated areas: $counts")
            for (a in homes.indices) for (b in a + 1 until homes.size) helper.assertTrue(homes[a].distanceTo(homes[b]) >= 60, "Large squad areas overlap")
            fixture.scene.result("PASS", "homes=${homes.map(helper::relativeVec)} occupied=$counts")
            helper.succeed()
        }
    }

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
