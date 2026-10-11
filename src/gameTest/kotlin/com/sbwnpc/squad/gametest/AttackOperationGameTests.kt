package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.tactics.*
import com.sbwnpc.squad.combat.tactics.operation.AttackAssignment
import com.sbwnpc.squad.combat.tactics.operation.AttackCorridor
import com.sbwnpc.squad.combat.tactics.operation.AttackOperations
import com.sbwnpc.squad.combat.tactics.operation.AttackRole
import com.sbwnpc.squad.combat.tactics.operation.AttackStage
import com.sbwnpc.squad.npc.SquadPreset
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND

/** Geometry is not proof of maneuvering: these scenes run native locomotion, targeting and guns. */
@PrefixGameTestTemplate(false)
object AttackOperationGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 300, batch = "operation-step")
    fun nativeOperationTransitBatchKeepsMeaningfulProgress(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "operation-step")
        val npc = scene.spawn(Vec3(20.5, 1.0, 100.5))
        val enemy = scene.enemy(Vec3(140.5, 1.0, 100.5))
        val squad = scene.squad(listOf(npc), TacticalPattern.COORDINATED_ATTACK, contact = enemy)
        npc.isNoAi = true
        npc.target = enemy
        val goal = helper.absoluteVec(Vec3(32.5, 1.0, 100.5))
        val intent = TacticalTask(TacticalJob.ADVANCE, goal, enemy.position(), squad.tactics.plan!!.id)
        helper.onEachTick {
            if (helper.tick < 20) return@onEachTick
            npc.setOnGround(true)
            val now = helper.level.gameTime
            val view = TacticalSnapshot(now, SquadOrder.ATTACK, squad.orderStamp, npc.position(), enemy.position(), listOf(TacticalMember(npc.uuid, npc.position())), listOf(TacticalContact(enemy.uuid, enemy.position(), now)))
            val result = TacticalPositions.find(npc, intent, view)
            if (result.outcome == TacticalPositions.Outcome.DEFERRED) return@onEachTick
            scene.result("CHECK", "selected=${result.position?.let(helper::relativeVec)} goal=${helper.relativeVec(goal)} nativeEnd=${intent.navigationPath?.endNode?.asBlockPos()}")
            helper.assertTrue(result.outcome == TacticalPositions.Outcome.FOUND && result.position!!.distanceTo(goal) <= 3.0, "Native batch chose a tiny step although the full leg is reachable: $result")
            helper.assertTrue(intent.navigationPath?.canReach() == true, "No reachable native path owns the proposed leg")
            scene.result("PASS", "selected=${helper.relativeVec(result.position!!)} goal=${helper.relativeVec(goal)}")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 600, batch = "operation-cover")
    fun adjacentSquadFireNeverAuthorizesExposedPostsUnderIncomingFire(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "operation-cover")
        val npc = scene.spawn(Vec3(20.5, 1.0, 100.5))
        val enemy = scene.enemy(Vec3(140.5, 1.0, 100.5))
        val squad = scene.squad(listOf(npc), TacticalPattern.COORDINATED_ATTACK, contact = enemy)
        npc.isNoAi = true
        npc.target = enemy
        val goal = helper.absoluteVec(Vec3(32.5, 1.0, 100.5))
        val assignment = AttackAssignment(1, AttackRole.CENTER, AttackStage.DEPLOYING, 0, goal, goal, Vec3(1.0, 0.0, 0.0), AttackCorridor(listOf(npc.position(), goal), 32.0), false, false, true, mapOf(npc.uuid to 0.0))
        fun task() = TacticalTask(TacticalJob.ADVANCE, goal, enemy.position(), squad.tactics.plan!!.id).also { it.operationMember = npc.uuid }
        var intent = task()
        var openRejected = false
        helper.onEachTick {
            if (helper.tick < 20) return@onEachTick
            npc.setOnGround(true)
            val now = helper.level.gameTime
            val view = TacticalSnapshot(now, SquadOrder.ATTACK, squad.orderStamp, npc.position(), enemy.position(), listOf(TacticalMember(npc.uuid, npc.position())), listOf(TacticalContact(enemy.uuid, enemy.position(), now)), incoming = listOf(enemy.position()), attackAssignment = assignment)
            val result = TacticalPositions.find(npc, intent, view)
            if (result.outcome == TacticalPositions.Outcome.DEFERRED) return@onEachTick
            if (!openRejected) {
                helper.assertTrue(result.outcome == TacticalPositions.Outcome.UNREACHABLE && (result.rejections["exposed_advance_under_fire"] ?: 0) > 0, "Adjacent covering fire allowed an open-ground advance: $result")
                openRejected = true
                for (z in 92..108) helper.setBlock(BlockPos(35, 1, z), Blocks.STONE)
                intent = task()
            } else {
                helper.assertTrue(result.outcome == TacticalPositions.Outcome.FOUND, "Adding physical chest-high shelter did not produce a reachable post: $result")
                val point = result.position!!
                helper.assertTrue(Sightline.blocked(helper.level, enemy.eyePosition, point.add(0.0, 0.9, 0.0), npc), "Selected post has no physical body shelter")
                helper.assertTrue(!Sightline.blocked(helper.level, point.add(0.0, npc.eyeHeight.toDouble(), 0.0), enemy.eyePosition, npc), "Chest-high shelter also blocked the standing firing ray")
                scene.result("PASS", "openRejected=true nativeProtectedPost=${helper.relativeVec(point)}")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "operation_arena", skyAccess = true, timeoutTicks = 1900, batch = "operation-large")
    fun fourLargeSquadsKeepThreeSectorsAndAReserve(helper: GameTestHelper) = largeAttack(helper, profile = false)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "operation_arena", skyAccess = true, timeoutTicks = 1900, batch = "operation-work")
    fun largeAttackMeasuresNativeWorkWithoutDebugTrace(helper: GameTestHelper) = largeAttack(helper, profile = true)

    private fun largeAttack(helper: GameTestHelper, profile: Boolean) {
        val scene = CombatIdleGameTests.Scene(helper, "operation-large", depth = 384, recordTrace = !profile)
        var work: NativeCombatWork? = null
        scene.onClose = {
            work?.close()
            AttackOperations.clear(helper.level)
        }
        // Large wing fronts extend in X as they turn toward the target; provide actual ground.
        for (x in 0..351) for (z in 0..383) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val groups = listOf(168.5, 192.5, 216.5, 240.5).map { scene.deploy(SquadPreset.THIRTY_TWO, Vec3(120.5, 1.0, it)) }
        val enemy = scene.enemy(Vec3(241.5, 1.0, 192.5))
        val squads = groups.map { scene.squad(it, com.sbwnpc.squad.combat.tactics.TacticalPattern.BOUND, isolated = false, contact = enemy) }
        if (profile) work = NativeCombatWork(helper, groups.flatten())
        val windows = mutableMapOf<java.util.UUID, Int>()
        val reached = mutableSetOf<java.util.UUID>()
        val deployedOrigins = mutableMapOf<java.util.UUID, Pair<Vec3, Vec3>>()
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.writeString(report.resolve("operation-large-windows.csv"), "tick,squad,role,recent,deployed,blocked\n")
        helper.onEachTick {
            squads.forEach(scene::tick)
            if (helper.tick % 100 != 0L) return@onEachTick
            val assignments = squads.associateWith { AttackOperations.assignment(helper.level, it) }
            if (helper.tick >= 100) helper.assertTrue(assignments.values.mapNotNull { it?.role }.toSet() == AttackRole.entries.toSet(), "128-NPC operation lacks three sectors and a reserve")
            for ((squad, assignment) in assignments) {
                val group = groups[squads.indexOf(squad)]
                val recent = group.count { it.firedRecently(100) }
                if (assignment?.deployed == true && !assignment.blocked) {
                    reached += squad.id
                    val line = group.filter { it.uuid in assignment.slots }
                    val center = line.fold(Vec3.ZERO) { sum, npc -> sum.add(npc.position()) }.scale(1.0 / line.size.coerceAtLeast(1))
                    deployedOrigins.putIfAbsent(squad.id, center to assignment.facing)
                }
                Files.writeString(report.resolve("operation-large-windows.csv"), "${helper.tick},${squad.id},${assignment?.role},$recent,${assignment?.deployed},${assignment?.blocked}\n", APPEND)
                if (helper.tick >= 600 && assignment?.role != AttackRole.RESERVE && recent >= 8) windows[squad.id] = (windows[squad.id] ?: 0) + 1
            }
            if (helper.tick == 1800L) {
                val active = assignments.filterValues { it?.role != AttackRole.RESERVE }
                val all = groups.flatten()
                work?.report()
                scene.result("CHECK", "sustained=$windows active=${active.keys.map { it.id }} alive=${all.count { it.isAlive }}")
                helper.assertTrue(active.size == 3 && active.keys.all { (windows[it.id] ?: 0) >= 3 }, "Large attack still has idle active sectors: $windows")
                helper.assertTrue(active.keys.all { it.id in reached } && active.values.none { it?.blocked == true }, "Large active squads did not physically deploy or subsequently blocked")
                val advance = active.keys.associate { squad ->
                    val (origin, facing) = deployedOrigins.getValue(squad.id)
                    val assignment = assignments.getValue(squad)!!
                    val line = groups[squads.indexOf(squad)].filter { it.uuid in assignment.slots }
                    val center = line.fold(Vec3.ZERO) { sum, npc -> sum.add(npc.position()) }.scale(1.0 / line.size.coerceAtLeast(1))
                    squad.id to center.subtract(origin).dot(facing)
                }
                helper.assertTrue(advance.values.any { it >= 4.0 }, "Deployed sectors never physically advanced: $advance")
                helper.assertTrue(all.all { it.isAlive }, "Large attack lost NPCs to friendly fire/environment")
                scene.result("PASS", "sustained=$windows reached=$reached actualAdvance=$advance alive=${all.size}")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1900, batch = "operation-wings")
    fun unequalSquadsDeploySeparateWingsAndKeepFiring(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "operation-wings")
        scene.onClose = { AttackOperations.clear(helper.level) }
        val groups = listOf(
            scene.deploy(SquadPreset.SEVEN, Vec3(24.5, 1.0, 96.5)),
            scene.deploy(SquadPreset.SIXTEEN, Vec3(24.5, 1.0, 112.5)),
            scene.deploy(SquadPreset.SEVEN, Vec3(24.5, 1.0, 128.5)),
        )
        val enemy = scene.enemy(Vec3(145.5, 1.0, 112.5))
        val squads = groups.map { scene.squad(it, com.sbwnpc.squad.combat.tactics.TacticalPattern.BOUND, isolated = false, contact = enemy) }
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        val trace = CombatParticipationTrace(helper.level, report.resolve("operation-participation.csv"))
        Files.writeString(report.resolve("operation-windows.csv"), "tick,left,center,right\n")
        scene.onClose = {
            trace.close()
            AttackOperations.clear(helper.level)
        }
        val recentWindows = IntArray(3)
        helper.onEachTick {
            squads.forEach(scene::tick)
            groups.flatten().forEachIndexed { index, npc -> if (helper.tick % 40 == (index % 40).toLong()) trace.sample(helper.tick, npc) }
            if (helper.tick < 20) return@onEachTick
            helper.assertTrue(AttackOperations.assignment(helper.level, squads[1])?.role == AttackRole.CENTER, "16-man squad did not retain center")
            helper.assertTrue(AttackOperations.assignment(helper.level, squads[0])?.role == AttackRole.LEFT, "Left 7-man squad lost its wing")
            helper.assertTrue(AttackOperations.assignment(helper.level, squads[2])?.role == AttackRole.RIGHT, "Right 7-man squad lost its wing")
            if (helper.tick % 100 == 0L) {
                val recent = groups.map { group -> group.count { it.firedRecently(100) } }
                Files.writeString(report.resolve("operation-windows.csv"), "${helper.tick},${recent.joinToString(",")}\n", APPEND)
                if (helper.tick >= 600) recent.forEachIndexed { index, count -> if (count >= (groups[index].size + 3) / 4) recentWindows[index]++ }
                trace.flush()
            }
            if (helper.tick == 1800L) {
                val left = groups[0].map { helper.relativeVec(it.position()).z }.sorted()[3]
                val right = groups[2].map { helper.relativeVec(it.position()).z }.sorted()[3]
                scene.result("CHECK", "left=$left right=$right sustained=${recentWindows.toList()} roles=${squads.map { AttackOperations.assignment(helper.level, it)?.role }}")
                helper.assertTrue(left < 76.0 && right > 148.0, "Wings did not physically separate: left=$left right=$right")
                helper.assertTrue(recentWindows.all { it >= 3 }, "Some squads still have sustained mass inactivity: ${recentWindows.toList()}")
                helper.assertTrue(groups.flatten().all { it.isAlive }, "Stationary enemy scene lost soldiers to friendly fire/environment")
                scene.result("PASS", "left=$left right=$right sustained=${recentWindows.toList()}")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 850, batch = "operation-blocked-wing")
    fun blockedWingReportsFailureAndNewOrdersReleaseIt(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "operation-blocked-wing")
        scene.onClose = { AttackOperations.clear(helper.level) }
        val groups = listOf(
            scene.deploy(SquadPreset.SEVEN, Vec3(24.5, 1.0, 96.5)),
            scene.deploy(SquadPreset.SIXTEEN, Vec3(24.5, 1.0, 112.5)),
            scene.deploy(SquadPreset.SEVEN, Vec3(24.5, 1.0, 128.5)),
        )
        // A continuous wall cuts the left lateral corridor; there is no fake path override.
        for (x in 0..175) for (y in 1..5) helper.setBlock(BlockPos(x, y, 72), Blocks.STONE)
        val enemy = scene.enemy(Vec3(145.5, 1.0, 112.5))
        val squads = groups.map { scene.squad(it, com.sbwnpc.squad.combat.tactics.TacticalPattern.BOUND, isolated = false, contact = enemy) }
        var blockedAt = -1L
        helper.onEachTick {
            squads.forEach(scene::tick)
            val assignment = AttackOperations.assignment(helper.level, squads[0])
            if (assignment?.blocked == true && blockedAt < 0) {
                blockedAt = helper.tick
                helper.assertTrue(assignment.holding, "Failed wing still has a moving order")
                val manager = SquadManager.get(helper.level)
                manager.setOrder(squads[0].id, SquadOrder.MOVE)
                manager.setObjective(helper.level, squads[0].id, BlockPos.containing(helper.absoluteVec(Vec3(24.5, 1.0, 96.5))))
                helper.assertTrue(AttackOperations.assignment(helper.level, squads[0]) == null, "Changed player order retained an operation assignment")
            }
            if (blockedAt >= 0 && helper.tick >= blockedAt + 40) {
                helper.assertTrue(squads[0].tactics.snapshot?.attackAssignment == null, "Local tactics retained released operation")
                helper.assertTrue(AttackOperations.assignment(helper.level, squads[2])?.blocked == false, "Blocked left wing recalled the reachable right wing")
                scene.result("PASS", "blockedAt=$blockedAt newOrder=${squads[0].order}")
                helper.succeed()
            }
        }
        helper.runAtTickTime(800) { helper.fail("Inaccessible wing never explicitly stopped its failed maneuver") }
    }
}
