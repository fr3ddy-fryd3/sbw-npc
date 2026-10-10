package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.FiringPostArrival
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.function.Consumer

/** Natural assessments, search and ascent with four support NPCs competing for a small tower. */
@PrefixGameTestTemplate(false)
object DefensiveOverwatchGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 900, batch = "defend-tower")
    fun defendingSupportsReserveSmallTower(helper: GameTestHelper) = occupy(helper, SquadOrder.DEFEND)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 900, batch = "patrol-tower")
    fun freePatrolSupportsReserveSmallTower(helper: GameTestHelper) = occupy(helper, SquadOrder.PATROL)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 900, batch = "tower-fire")
    fun machineGunnerFiresFromReservedTower(helper: GameTestHelper) = occupy(helper, SquadOrder.DEFEND, contact = true)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 900, batch = "unreachable-tower")
    fun supportsChooseGroundCoverWhenTowerIsUnreachable(helper: GameTestHelper) = occupy(helper, SquadOrder.DEFEND, ladder = false)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1200, batch = "tower-fall")
    fun fallenSentryReturnsToReservedTower(helper: GameTestHelper) = occupy(helper, SquadOrder.DEFEND, fall = true)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1200, batch = "patrol-tower-fall")
    fun fallenPatrolSentryReturnsToReservedTower(helper: GameTestHelper) = occupy(helper, SquadOrder.PATROL, fall = true)

    private fun occupy(helper: GameTestHelper, order: SquadOrder, contact: Boolean = false, ladder: Boolean = true, fall: Boolean = false) {
        for (x in 0..175) for (z in 0..95) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        for (y in 1..10) {
            helper.setBlock(BlockPos(72, y, 64), Blocks.STONE)
            helper.setBlock(BlockPos(71, y, 64), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST))
        }
        for (x in 72..74) {
            for (z in 63..65) {
                helper.setBlock(BlockPos(x, 10, z), Blocks.STONE)
                if ((x != 73 || z != 64) && (x != 72 || z != 64)) helper.setBlock(BlockPos(x, 11, z), Blocks.STONE)
            }
        }
        if (!ladder) for (y in 4..6) helper.setBlock(BlockPos(71, y, 64), Blocks.AIR)
        // Sheltered ground alternatives; an NPC must not climb the tower just to find a free post.
        for ((x, z) in listOf(48 to 48, 48 to 76, 78 to 78)) {
            for (offset in -2..2) {
                helper.setBlock(BlockPos(x + 2, 1, z + offset), Blocks.STONE)
                helper.setBlock(BlockPos(x + offset, 1, z - 2), Blocks.STONE)
            }
        }
        val npcs = listOf(Vec3(42.5, 1.0, 58.5), Vec3(48.5, 1.0, 58.5), Vec3(42.5, 1.0, 64.5), Vec3(48.5, 1.0, 64.5)).mapIndexed { i, at ->
            AttackDeploymentGameTests.spawn(helper, at, SquadFaction.PIG, randomSeed = 100L + i, role = if (contact || i % 2 == 0) NpcClass.MACHINE_GUNNER else NpcClass.SNIPER)
        }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, npcs.map { it.uuid })
        manager.setOrder(squad.id, order)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(64, 1, 64)))
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(report)
        val name = "support-tower-$order${if (contact) {
            "-fire"
        } else if (!ladder) {
            "-unreachable"
        } else if (fall) {
            "-fall"
        } else {
            ""
        }}"
        val trace = StringBuilder("tick,member,x,y,z,job,postX,postY,postZ,ladder,pattern,closest,lastProgress,recovery\n")
        var reservedBeforeAscent = false
        var climbedLadder = false
        var stableSince: Long? = null
        var enemy: NpcEntity? = null
        var holder: NpcEntity? = null
        var heldPost: Vec3? = null
        var roundsFromTower = 0
        var contactSince = Long.MAX_VALUE
        var displacedAt: Long? = null
        var landedAt: Long? = null
        var returnedSince: Long? = null
        val oldGroups = DebugFlags.enabledGroups
        if (contact) DebugFlags.enabledGroups = oldGroups + setOf(LogGroup.ORDER, LogGroup.FIRE)
        if (fall) DebugFlags.enabledGroups = oldGroups + LogGroup.ORDER
        if (!ladder) DebugFlags.enabledGroups = oldGroups + setOf(LogGroup.ORDER, LogGroup.PATH)
        val witness = if (contact) {
            FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "tower-observer")).apply {
                gameMode.changeGameModeForPlayer(GameType.CREATIVE)
                val at = helper.absoluteVec(Vec3(95.5, 20.0, 70.5))
                moveTo(at.x, at.y, at.z, 0f, 0f)
                setNoGravity(true)
                helper.level.addNewPlayer(this)
            }
        } else {
            null
        }
        val rounds = Consumer<EntityJoinLevelEvent> { event ->
            val round = event.entity as? Projectile
            val actor = holder
            if (event.level === helper.level && round != null && Ports.guns.isRound(round) && actor != null && round.owner === actor &&
                heldPost?.let { FiringPostArrival.reached(actor.position(), it) } == true
            ) {
                roundsFromTower++
            }
        }
        val pacer = CombatTickPacer()
        if (contact) NeoForge.EVENT_BUS.addListener(rounds)
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                DebugFlags.enabledGroups = oldGroups
                NeoForge.EVENT_BUS.unregister(rounds)
                NeoForge.EVENT_BUS.unregister(pacer)
                enemy?.discard()
                witness?.let { helper.level.removePlayerImmediately(it, Entity.RemovalReason.DISCARDED) }
                manager.disband(helper.level, squad.id)
                npcs.forEach {
                    FiringSpots.release(it.uuid)
                    it.discard()
                }
            }
        })
        helper.onEachTick {
            val posts = npcs.mapNotNull { npc -> squad.tactics.plan?.tasks?.get(npc.uuid)?.position?.let { npc to it } }
            val high = posts.filter { helper.relativeVec(it.second).y >= 10.5 }
            helper.assertTrue(high.size <= if (ladder) 1 else 0, "Support reserved a crowded or unreachable tower: $high")
            for ((npc, point) in high) {
                if (helper.relativeVec(npc.position()).y < 3.0) {
                    val reservations = FiringSpots.nearby(point, 1.0, UUID(0, 0))
                    helper.assertTrue(point in reservations, "Tower destination is unreserved during the approach")
                    reservedBeforeAscent = true
                }
                if (npc.onClimbable()) climbedLadder = true
            }
            if (helper.tick % 10L == 0L) {
                for (npc in npcs) {
                    val task = squad.tactics.plan?.tasks?.get(npc.uuid)
                    val at = helper.relativeVec(npc.position())
                    val post = task?.position?.let(helper::relativeVec)
                    trace.append("${helper.tick},${npc.uuid},${at.x},${at.y},${at.z},${task?.job},${post?.x},${post?.y},${post?.z},${npc.onClimbable()},${squad.tactics.plan?.pattern},${task?.closest},${task?.lastProgress},${task?.localFireRecovery}\n")
                }
                Files.writeString(report.resolve("$name.csv"), trace)
            }
            if (!ladder && helper.tick % 100L == 0L) {
                for (npc in npcs) {
                    val task = squad.tactics.plan?.tasks?.get(npc.uuid)
                    val search = task?.search
                    DebugFlags.log(LogGroup.PATH, "[tactics-test] {} event=unfinished_overwatch tick={} searchAt={} probe={}/{} destinations={}/{} attempts={} rejected={}", npc.uuid, helper.tick, task?.nextSearch, search?.probeIndex, search?.probes?.size, search?.destinationIndex, search?.destinations?.size, search?.pathAttempts, search?.rejections)
                }
            }
            if (displacedAt != null) {
                val sentry = holder!!
                val point = heldPost!!
                helper.assertTrue(sentry.isAlive, "Sentry died during the controlled fall")
                val task = squad.tactics.plan?.tasks?.get(sentry.uuid)
                helper.assertTrue(task?.position == point && task.job == TacticalJob.OVERWATCH && !task.localFireRecovery, "Fall lost the assigned tower post: task=${task?.job} post=${task?.position} recovery=${task?.localFireRecovery}")
                helper.assertTrue(point in FiringSpots.nearby(point, 1.0, UUID(0, 0)), "Fall released the reserved tower slot")
                if (sentry.onGround() && helper.relativeVec(sentry.position()).y < 3.0 && landedAt == null) landedAt = helper.tick
                if (landedAt != null && FiringPostArrival.reached(sentry.position(), point) && sentry.onGround()) {
                    if (returnedSince == null) returnedSince = helper.tick
                    if (helper.tick - returnedSince!! >= 100) {
                        Files.writeString(report.resolve("$name-result.txt"), "displacedAt=$displacedAt\nlandedAt=$landedAt\nreturnedAt=$returnedSince\npostRetained=true\nreservationRetained=true\n")
                        helper.succeed()
                    }
                } else {
                    returnedSince = null
                }
                return@onEachTick
            }
            if (enemy != null) {
                if (helper.tick % 20L == 0L) Files.writeString(report.resolve("$name-sight.txt"), "tick=${helper.tick}\nposition=${helper.relativeVec(holder!!.position())}\npose=${holder!!.pose} eye=${holder!!.eyeHeight}\nyaw=${holder!!.yHeadRot} look=${holder!!.lookAngle}\nenemy=${helper.relativeVec(enemy!!.position())} alive=${enemy!!.isAlive}\nvisible=${DetectionSightline.trace(holder!!, enemy!!)}\ntarget=${holder!!.target?.uuid}\nfireReady=${TacticalPositions.firingReady(holder!!, squad.tactics.plan!!.tasks[holder!!.uuid]!!, holder!!.position())}\n")
                val task = squad.tactics.plan?.tasks?.get(holder!!.uuid)
                helper.assertTrue(task?.position == heldPost, "New contact displaced a usable tower post")
                helper.assertTrue(heldPost in FiringSpots.nearby(heldPost!!, 1.0, UUID(0, 0)), "Contact transition dropped the tower reservation")
                if (roundsFromTower >= 3 && helper.tick - contactSince >= 100) {
                    Files.writeString(report.resolve("$name-result.txt"), "roundsFromTower=$roundsFromTower\npostRetained=true\nreservationRetained=true\n")
                    helper.succeed()
                }
                return@onEachTick
            }
            val allSettled = posts.size == npcs.size && posts.all { (npc, point) ->
                val task = squad.tactics.plan!!.tasks[npc.uuid]!!
                task.job == TacticalJob.OVERWATCH && FiringPostArrival.reached(npc.position(), point) && TacticalPositions.protected(npc, task, point)
            }
            if (allSettled && high.size == if (ladder) 1 else 0) {
                if (stableSince == null) stableSince = helper.tick
                if (helper.tick - stableSince!! >= 100) {
                    helper.assertTrue(!ladder || (reservedBeforeAscent && climbedLadder), "Tower occupation did not exercise reservation and ladder ascent")
                    val occupied = posts.map { it.second }
                    for (a in occupied.indices) for (b in 0 until a) helper.assertTrue(occupied[a].distanceTo(occupied[b]) >= FiringSpots.MIN_SPACING, "Support posts overlap")
                    if (fall) {
                        holder = high.single().first
                        heldPost = high.single().second
                        displacedAt = helper.tick
                        // A physical shove through the ladder opening; no teleport or return path injection.
                        holder!!.push(-1.2, 0.15, 0.0)
                        return@onEachTick
                    }
                    if (contact) {
                        holder = high.single().first
                        contactSince = helper.tick
                        heldPost = high.single().second
                        helper.assertTrue(holder!!.npcClass == NpcClass.MACHINE_GUNNER, "Fixture must exercise a machine gunner on the tower")
                        enemy = AttackDeploymentGameTests.spawn(helper, Vec3(124.5, 1.0, 64.5), SquadFaction.COW).apply {
                            isNoAi = true
                            getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 10000.0
                            health = maxHealth
                        }
                        val hit = helper.level.clip(ClipContext(holder!!.eyePosition, enemy!!.eyePosition, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.of(holder!!)))
                        Files.writeString(report.resolve("$name-initial-ray.txt"), "from=${helper.relativeVec(holder!!.eyePosition)}\nto=${helper.relativeVec(enemy!!.eyePosition)}\nhit=${hit.type} pos=${helper.relativePos(hit.blockPos)} block=${helper.level.getBlockState(hit.blockPos)}\n")
                        // The downhill ray is visible standing but crosses the far edge of the
                        // parapet crouched. Natural perception must stand the sentry up first.
                        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
                        return@onEachTick
                    }
                    // Death releases the high slot before any later plan cleanup runs.
                    high.singleOrNull()?.let { (npc, point) ->
                        npc.hurt(helper.level.damageSources().genericKill(), Float.MAX_VALUE)
                        helper.assertTrue(!npc.isAlive && FiringSpots.nearby(point, 1.0, UUID(0, 0)).isEmpty(), "Dead sentry retained its tower reservation")
                    }
                    squad.tactics.deactivate(helper.level.gameTime, "test_post_release")
                    helper.assertTrue(occupied.all { FiringSpots.nearby(it, 1.0, UUID(0, 0)).isEmpty() }, "Released support posts remained reserved")
                    Files.writeString(report.resolve("$name-result.txt"), "settled=${posts.size}\nhigh=${high.size}\nreservedBeforeAscent=$reservedBeforeAscent\nclimbedLadder=$climbedLadder\nreleased=true\n")
                    helper.succeed()
                }
            } else {
                stableSince = null
            }
        }
    }
}
