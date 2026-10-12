package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.tactics.*
import com.sbwnpc.squad.combat.tactics.operation.AttackAssignment
import com.sbwnpc.squad.combat.tactics.operation.AttackCorridor
import com.sbwnpc.squad.combat.tactics.operation.AttackOperations
import com.sbwnpc.squad.combat.tactics.operation.AttackRole
import com.sbwnpc.squad.combat.tactics.operation.AttackStage
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.SquadPreset
import com.sbwnpc.squad.squad.Squad
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
import java.util.UUID

/** Geometry is not proof of maneuvering: these scenes run native locomotion, targeting and guns. */
@PrefixGameTestTemplate(false)
object AttackOperationGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 2000, batch = "operation-route-recovery")
    fun blockedWingUsesAnOpenedRouteWithoutEnemyDeathsOrNewOrders(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "operation-route-recovery")
        scene.onClose = { AttackOperations.clear(helper.level) }
        val groups = listOf(
            scene.deploy(SquadPreset.SEVEN, Vec3(24.5, 1.0, 96.5)),
            scene.deploy(SquadPreset.SIXTEEN, Vec3(24.5, 1.0, 112.5)),
            scene.deploy(SquadPreset.SEVEN, Vec3(24.5, 1.0, 128.5)),
        )
        for (x in 0..175) for (y in 1..5) helper.setBlock(BlockPos(x, y, 72), Blocks.STONE)
        val enemy = scene.enemy(Vec3(145.5, 1.0, 112.5))
        val squads = groups.map { scene.squad(it, TacticalPattern.BOUND, isolated = false, contact = enemy) }
        var blockedAt = -1L
        var operation = -1L
        var recoveredAt = -1L
        var stamp = -1
        var windows = 0
        helper.onEachTick {
            squads.forEach(scene::tick)
            val squad = squads.first()
            val assignment = AttackOperations.assignment(helper.level, squad) ?: return@onEachTick
            if (assignment.blocked && blockedAt < 0) {
                blockedAt = helper.tick
                operation = assignment.operation
                stamp = squad.orderStamp
                // Open a real walkable gate after the route has actually failed.
                for (x in 4..68) for (y in 1..5) helper.setBlock(BlockPos(x, y, 72), Blocks.AIR)
            }
            if (blockedAt < 0 || helper.tick % 20L != 0L) return@onEachTick
            helper.assertTrue(assignment.operation == operation && squad.orderStamp == stamp && enemy.isAlive, "Recovery required a new operation/order or killing the enemy")
            if (!assignment.blocked && assignment.revision >= 2 && recoveredAt < 0) recoveredAt = helper.tick
            val crossed = groups.first().count { helper.relativeVec(it.position()).z < 69.0 }
            val recent = groups.first().count { it.firedRecently(100) }
            if (recoveredAt >= 0 && crossed >= 5 && recent >= 3) windows++ else windows = 0
            if (helper.tick % 100L == 0L) scene.result("CHECK", "tick=${helper.tick} blockedAt=$blockedAt recoveredAt=$recoveredAt crossed=$crossed recent=$recent windows=$windows")
            if (windows >= 3) {
                helper.assertTrue(groups.flatten().all { it.isAlive }, "Quiet recovery lost soldiers")
                helper.assertTrue(squads.all { it.order == SquadOrder.ATTACK }, "Recovery changed player orders")
                scene.result("PASS", "blockedAt=$blockedAt recoveredAt=$recoveredAt crossed=$crossed recent=$recent sameOperation=$operation")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 180, batch = "operation-dead-contact")
    fun deadContactCannotRecreateAnAttackOperation(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "operation-dead-contact")
        scene.onClose = { AttackOperations.clear(helper.level) }
        val enemy = scene.enemy(Vec3(120.5, 1.0, 100.5))
        val squads = List(2) { index ->
            val members = List(7) { scene.spawn(Vec3(24.5, 1.0, 86.5 + index * 28 + it * 2)).apply { isNoAi = true } }
            scene.squad(members, TacticalPattern.BOUND, isolated = false, contact = enemy)
        }
        helper.runAtTickTime(20) {
            helper.assertTrue(AttackOperations.assignment(helper.level, squads.first()) != null, "No original live-contact operation")
            enemy.kill()
        }
        helper.onEachTick {
            if (helper.tick < 40 || helper.tick % 10L != 0L) return@onEachTick
            squads.forEach { squad ->
                helper.assertTrue(AttackOperations.assignment(helper.level, squad) == null, "Dead contact recreated an operation at tick ${helper.tick}")
            }
            if (helper.tick >= 100) {
                scene.result("PASS", "deadAndRemoved=true noOperationUntil=${helper.tick}")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1000, batch = "attack-short-range")
    fun attackingShotgunnerApproachesFromOutOfRangeAndFires(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "attack-short-range")
        val npc = scene.spawn(Vec3(30.5, 1.0, 100.5), com.sbwnpc.squad.npc.NpcClass.SHOTGUNNER)
        val enemy = scene.enemy(Vec3(140.5, 1.0, 100.5))
        val scout = scene.spawn(Vec3(140.5, 1.0, 160.5), com.sbwnpc.squad.npc.NpcClass.MEDIC).apply { isNoAi = true }
        val squad = scene.squad(listOf(npc, scout), TacticalPattern.REORIENT, contact = enemy)
        helper.onEachTick {
            if (helper.tick % 40L == 0L) {
                helper.assertTrue(DetectionSightline.canSeeWithin(scout, enemy, 72.0), "Scout lost actual contact")
                SquadTactics.observe(scout, enemy)
            }
            scene.tick(squad)
            if (npc.lastShotTick >= 0) {
                val distance = npc.position().distanceTo(enemy.position())
                helper.assertTrue(distance <= 49.0 && distance >= 17.0, "Shotgunner fired outside its range or charged into contact: $distance")
                helper.assertTrue(helper.relativeVec(npc.position()).x >= 91.0, "No physical approach into shotgun range")
                scene.result("PASS", "tick=${helper.tick} distance=$distance at=${helper.relativeVec(npc.position())}")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 900, batch = "operation-held-bound")
    fun deployedSquadPhysicallyAdvancesAfterLongSectorHold(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "operation-held-bound")
        val members = List(12) { scene.spawn(Vec3(30.5, 1.0, 76.5 + it * 6)) }
        val enemy = scene.enemy(Vec3(150.5, 1.0, 109.5))
        val squad = scene.squad(members, TacticalPattern.COORDINATED_ATTACK, contact = enemy)
        val plan = requireNotNull(squad.tactics.plan)
        val held = helper.absoluteVec(Vec3(30.5, 1.0, 109.5))
        val goal = held.add(18.0, 0.0, 0.0)
        val slots = members.associate { it.uuid to (it.position().z - held.z) }
        var starts = emptyMap<UUID, Vec3>()
        fun assess() {
            val moving = helper.tick >= 450
            val assignment = AttackAssignment(
                1, AttackRole.CENTER, if (moving) AttackStage.DEPLOYING else AttackStage.ENGAGING,
                if (moving) 1 else 0, if (moving) goal else held, if (moving) goal else held, Vec3(1.0, 0.0, 0.0),
                AttackCorridor(listOf(held, goal), 40.0), !moving, false, false, slots,
            )
            val view = TacticalSnapshotCollector.collect(squad, helper.level, members, members.first()).copy(attackAssignment = assignment)
            squad.tactics.snapshot = view
            plan.tick(squad.id, squad.tactics, view)
        }
        assess()
        helper.onEachTick {
            if (helper.tick == 450L) starts = members.associate { it.uuid to it.position() }
            if (helper.tick % 10L == 0L) assess()
            helper.assertTrue(plan.status != TacticalStatus.FAILED, "New bound failed at tick ${helper.tick}: ${plan.failure}, phaseSince=${plan.phaseSince}")
            if (helper.tick > 450 && helper.tick % 20L == 0L) {
                val advanced = members.count { it.position().x - starts.getValue(it.uuid).x >= 10.0 }
                scene.result("CHECK", "tick=${helper.tick} advanced=$advanced phase=${plan.phase} phaseSince=${plan.phaseSince}")
                if (advanced >= 9) {
                    helper.assertTrue(members.all { it.isAlive }, "Quiet bound lost soldiers")
                    scene.result("PASS", "held450=true advanced=$advanced tick=${helper.tick} phaseSince=${plan.phaseSince}")
                    helper.succeed()
                }
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "operation_arena", skyAccess = true, timeoutTicks = 2400, batch = "operation-blind-ridge")
    fun coordinatedSquadsLeaveBlindSectorsOnUnevenGroundAndSustainFire(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "operation-blind-ridge", depth = 384)
        scene.onClose = { AttackOperations.clear(helper.level) }
        for (x in 0..351) for (z in 0..383) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        for (x in 150..205) {
            for (z in 0..383) {
                val height = minOf(8, (x - 150) / 2 + 1, (205 - x) / 2 + 1)
                for (y in 1..height) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
            }
        }
        val groups = listOf(32 to 192.5, 16 to 240.5).map { (size, center) ->
            List(size) { index ->
                if (index == size - 1) {
                    // One fixed scout in each squad maintains a real reported contact.
                    scene.spawn(Vec3(248.5, 1.0, if (size == 32) 130.5 else 254.5)).apply { isNoAi = true }
                } else {
                    scene.spawn(Vec3(110.5, 1.0, center + (index - (size - 1) / 2.0) * 6))
                }
            }
        }
        val enemy = scene.enemy(Vec3(248.5, 1.0, 192.5))
        val squads = groups.map { scene.squad(it, TacticalPattern.BOUND, isolated = false, contact = enemy) }
        val before = groups.flatten().associate { it.uuid to it.position() }
        var windows = 0
        var approachOrders = 0
        val revisions = mutableMapOf<UUID, Int>()
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        val trace = CombatParticipationTrace(helper.level, report.resolve("blind-ridge-participation.csv"))
        val close = scene.onClose
        scene.onClose = {
            trace.close()
            close()
        }
        helper.onEachTick {
            if (helper.tick % 40L == 0L) {
                groups.forEach { group ->
                    val scout = group.last()
                    helper.assertTrue(DetectionSightline.canSeeWithin(scout, enemy, 72.0), "Fixed scout lost real visual contact")
                    SquadTactics.observe(scout, enemy)
                }
            }
            squads.forEach(scene::tick)
            groups.flatten().forEachIndexed { i, npc -> if (helper.tick % 40L == i % 40L) trace.sample(helper.tick, npc) }
            if (helper.tick % 20L != 0L) return@onEachTick
            val assignments = squads.mapNotNull { AttackOperations.assignment(helper.level, it) }
            squads.forEach { squad ->
                val assignment = AttackOperations.assignment(helper.level, squad) ?: return@forEach
                val previous = revisions.put(squad.id, assignment.revision)
                if (previous != null && previous != assignment.revision && !assignment.deployed) approachOrders++
            }
            val active = groups.first()
            val advanced = active.count { it.position().x - before.getValue(it.uuid).x >= 50.0 }
            val firing = active.count { it.firedRecently(100) }
            if (advanced >= 20 && firing >= 20) windows++ else windows = 0
            if (helper.tick % 100L == 0L) scene.result("CHECK", "tick=${helper.tick} advanced=$advanced firing=$firing windows=$windows approaches=$approachOrders")
            if (windows >= 5) {
                helper.assertTrue(approachOrders > 0, "No operation bound was ordered after the line became blind")
                helper.assertTrue(active.all { it.isAlive }, "Quiet terrain attack lost soldiers")
                helper.assertTrue(active.all { it.position().subtract(enemy.position()).horizontalDistance() >= 47.0 }, "Attack charged into the target")
                scene.result("PASS", "tick=${helper.tick} advanced=$advanced firing=$firing windows=$windows approaches=$approachOrders")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "operation_arena", skyAccess = true, timeoutTicks = 1800, batch = "operation-reinforcement")
    fun lateReservePhysicallyReplacesSparseFrontAndStartsFiring(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "operation-reinforcement", depth = 384)
        scene.onClose = { AttackOperations.clear(helper.level) }
        for (x in 176..351) for (z in 0..383) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val enemy = scene.enemy(Vec3(300.5, 1.0, 192.5))
        val groups = List(3) { group ->
            List(16) { i -> scene.spawn(Vec3(132.5 + i % 4 * 2, 1.0, 166.5 + group * 20 + i / 4 * 2)).apply { isNoAi = true } }
        }
        val squads = groups.map { scene.squad(it, TacticalPattern.BOUND, isolated = false, contact = enemy) }
        var reinforcement: Squad? = null
        var members = emptyList<NpcEntity>()
        var starting = Vec3.ZERO
        var oldCenter: UUID? = null
        var reserved = false
        var activatedAt = -1L
        var firingWindows = 0
        helper.runAtTickTime(20) {
            // Model the established front explicitly: two combatants per sector, a visible
            // scout, and healthy replacements still at the distant barracks. Only the
            // arriving reinforcement runs normal locomotion and weapons throughout.
            squads.forEachIndexed { index, squad ->
                AttackOperations.assignment(helper.level, squad)
                val lane = requireNotNull(AttackOperations.lane(helper.level, squad.id))
                if (lane.role == AttackRole.CENTER) oldCenter = squad.id
                val facing = enemy.position().subtract(lane.sector).multiply(1.0, 0.0, 1.0).normalize()
                val side = Vec3(-facing.z, 0.0, facing.x)
                groups[index].take(2).forEach { npc ->
                    val point = lane.sector.add(side.scale(lane.slots.getValue(npc.uuid)))
                    npc.moveTo(point.x, point.y, point.z, 0f, 0f)
                }
                val scout = groups[index][2]
                val point = enemy.position().add(-48.0, 0.0, index * 6.0)
                scout.moveTo(point.x, point.y, point.z, -90f, 0f)
            }
        }
        helper.runAtTickTime(220) {
            val lane = requireNotNull(AttackOperations.lane(helper.level, requireNotNull(oldCenter)))
            val origin = helper.relativeVec(lane.route.first()).add(-32.0, 0.0, 0.0)
            members = List(32) { i -> scene.spawn(origin.add(i % 8 * 2.0, 0.0, i / 8 * 2.0)) }
            starting = members.fold(Vec3.ZERO) { sum, npc -> sum.add(npc.position()) }.scale(1.0 / members.size)
            reinforcement = scene.squad(members, TacticalPattern.BOUND, isolated = false, contact = enemy)
        }
        helper.onEachTick {
            squads.forEach(scene::tick)
            if (helper.tick >= 40 && helper.tick % 40L == 0L) {
                groups.forEach { group ->
                    val scout = group[2]
                    helper.assertTrue(DetectionSightline.canSeeWithin(scout, enemy, 72.0), "Scout lost its physical observation")
                    SquadTactics.observe(scout, enemy)
                }
            }
            if (helper.tick % 20L != 0L) return@onEachTick
            val squad = reinforcement ?: return@onEachTick
            val assignment = AttackOperations.assignment(helper.level, squad) ?: return@onEachTick
            if (assignment.role == AttackRole.RESERVE) {
                reserved = true
            } else if (activatedAt < 0) {
                helper.assertTrue(reserved, "Late force never joined as a reserve")
                activatedAt = helper.tick
            }
            val recent = members.count { it.firedRecently(100) }
            if (activatedAt >= 0 && recent >= 8) firingWindows++
            if (helper.tick % 100L == 0L) scene.result("CHECK", "tick=${helper.tick} role=${assignment.role} activatedAt=$activatedAt recent=$recent firingWindows=$firingWindows")
            if (activatedAt >= 0 && firingWindows >= 5) {
                val advanced = members.count { it.position().x - starting.x >= 15.0 }
                helper.assertTrue(advanced >= 16, "Promoted reserve has not physically advanced: $advanced/32")
                helper.assertTrue(members.all { it.isAlive }, "Reinforcement suffered friendly fire/environmental deaths")
                helper.assertTrue(squads.all { it.order == SquadOrder.ATTACK } && squad.order == SquadOrder.ATTACK, "Promotion changed player orders")
                scene.result("PASS", "reserved=$reserved activatedAt=$activatedAt advanced=$advanced recent=$recent firingWindows=$firingWindows enemyDamage=${enemy.maxHealth - enemy.health}")
                helper.succeed()
            }
        }
    }

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
