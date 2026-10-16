package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.CoverPeekSearch
import com.sbwnpc.squad.entity.ai.SeekCoverBehaviour
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path

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
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 200, batch = "cover-fallback-budget")
    fun queuedFallbackPathsObeyNativeBudgetAndAllResume(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "cover-fallback-budget", recordTrace = false)
        val searches = mutableMapOf<Long, Int>()
        val tick = SeekCoverBehaviour::class.java.getDeclaredMethod("tick", NpcEntity::class.java).apply { isAccessible = true }
        val actors = List(16) { i ->
            val npc = scene.spawn(Vec3(20.5, 1.0, 12.5 + i * 12)).apply { isNoAi = true }
            val threat = npc.eyePosition.add(96.0, 0.0, 0.0)
            npc.suppress(threat)
            val behaviour = SeekCoverBehaviour()
            val goal = npc.blockPosition().offset(-8, 0, 0)
            field("pendingFallback").set(behaviour, goal)
            field("fallbackThreat").set(behaviour, threat)
            (npc.navigation as VehicleAwareNavigation).pathWorkObserver = { _, _, native -> if (native) searches.merge(helper.tick, 1, Int::plus) }
            Triple(npc, behaviour, goal)
        }
        scene.onClose = { actors.forEach { (npc, _, _) -> (npc.navigation as VehicleAwareNavigation).pathWorkObserver = null } }
        helper.onEachTick {
            if (helper.tick < 20) return@onEachTick
            actors.forEach { (npc, behaviour, goal) ->
                npc.setOnGround(true)
                if (field("coverTarget").get(behaviour) != goal) tick.invoke(behaviour, npc)
            }
            val assigned = actors.count { (_, behaviour, goal) -> field("coverTarget").get(behaviour) == goal }
            val maximum = searches.values.maxOrNull() ?: 0
            helper.assertTrue(maximum <= 4, "Fallback paths exceeded native budget: $searches")
            if (assigned == actors.size) {
                helper.assertTrue(searches.values.sum() == 16, "Fallback admission rebuilt or omitted routes: $searches")
                scene.result("PASS", "assigned=$assigned native=${searches.values.sum()} maximumNativePerTick=$maximum tick=${helper.tick}")
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
            peeked = peeked || npc.coverPeekPoint != null
            beganReply = beganReply || npc.incomingFire.replying(helper.level.gameTime)
            if (helper.tick % 40L == 0L) scene.result("CHECK", "tick=${helper.tick} at=${helper.relativeVec(npc.position())} peek=${npc.coverPeekPoint?.let(helper::relativeVec)} roundsLeft=${npc.incomingFire.roundsLeft} peeked=$peeked beganReply=$beganReply lastShot=${npc.lastShotTick}")
            if (helper.tick > 20 && peeked && beganReply && npc.incomingFire.roundsLeft == 0) {
                helper.assertTrue(npc.lastShotTick >= 0 && npc.isAlive, "No live physical return-fire shooter")
                scene.result("PASS", "peeked=$peeked beganReply=$beganReply roundsLeft=0 at=${helper.relativeVec(npc.position())} tick=${helper.tick}")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 220, batch = "cover-peek-budget")
    fun synchronizedCoverPeeksObeyNativeBudgetAndAllReceivePaths(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "cover-peek-budget", recordTrace = false)
        val searches = mutableMapOf<Long, Int>()
        val calls = mutableMapOf<Long, Int>()
        val tick = SeekCoverBehaviour::class.java.getDeclaredMethod("tick", NpcEntity::class.java).apply { isAccessible = true }
        val actors = List(16) { i ->
            val z = 12.5 + i * 12
            val npc = scene.spawn(Vec3(20.5, 1.0, z)).apply { isNoAi = true }
            val enemy = scene.enemy(Vec3(60.5, 1.0, z))
            npc.target = enemy
            npc.rememberVisible(enemy)
            for (y in 1..2) helper.setBlock(BlockPos(22, y, z.toInt()), Blocks.STONE)
            val behaviour = SeekCoverBehaviour()
            field("phase").set(behaviour, field("phase").type.enumConstants.single { it.toString() == "IN_COVER" })
            field("coverTarget").set(behaviour, npc.blockPosition())
            field("phaseUntilTick").setInt(behaviour, 0)
            (npc.navigation as VehicleAwareNavigation).pathWorkObserver = { _, _, native ->
                calls.merge(helper.tick, 1, Int::plus)
                if (native) searches.merge(helper.tick, 1, Int::plus)
            }
            npc to behaviour
        }
        scene.onClose = { actors.forEach { (npc, _) -> (npc.navigation as VehicleAwareNavigation).pathWorkObserver = null } }
        helper.onEachTick {
            if (helper.tick < 20) return@onEachTick
            // Component scene: real raycasts, vanilla A*, and production cover transitions.
            // NoAI suppresses other behaviors; actors stand on an actual solid floor.
            actors.forEach { (npc, behaviour) ->
                npc.setOnGround(true)
                if (npc.coverPeekPoint == null) tick.invoke(behaviour, npc)
            }
            val assigned = actors.count { it.first.coverPeekPoint != null }
            val maximum = searches.values.maxOrNull() ?: 0
            val report = "tick=${helper.tick} assigned=$assigned/16 native=${searches.values.sum()} maximumNativePerTick=$maximum pathCalls=${calls.values.sum()}\n"
            Files.writeString(Path.of(System.getProperty("sbwnpc.gameTestReports")).resolve("cover-peek-budget.txt"), report)
            helper.assertTrue(maximum <= 4, "Cover peek bypassed the native path budget: $report")
            if (assigned == actors.size) {
                helper.assertTrue(searches.values.sum() > 0, "Scene did not perform any actual native searches")
                helper.assertTrue(actors.all { it.first.navigation.path?.canReach() == true }, "Peek discarded a verified reachable path")
                scene.result("PASS", report.trim())
                helper.succeed()
            }
        }
    }

    private fun field(name: String) = SeekCoverBehaviour::class.java.getDeclaredField(name).apply { isAccessible = true }
}
