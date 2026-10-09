package com.sbwnpc.squad.gametest

import com.atsuishio.superbwarfare.data.gun.GunData
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FiringPostArrival
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import com.sbwnpc.squad.init.ModBlocks
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ScaffoldingBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Block-for-block copy of the player's roofed tower; template origin was (-55, -61, -30). */
@PrefixGameTestTemplate(false)
object RoofedOverwatchGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "roofed_watchtower", timeoutTicks = 1200, batch = "roofed-tower")
    fun supportOccupiesPlayerRoofedTower(helper: GameTestHelper) = occupy(helper, SquadOrder.DEFEND)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "roofed_watchtower", timeoutTicks = 1200, batch = "roofed-patrol")
    fun patrolSupportOccupiesPlayerRoofedTower(helper: GameTestHelper) = occupy(helper, SquadOrder.PATROL)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "roofed_watchtower", timeoutTicks = 1600, batch = "roofed-fall")
    fun fallenSupportReturnsToPlayerRoofedTower(helper: GameTestHelper) = occupy(helper, SquadOrder.DEFEND, fall = true)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "roofed_watchtower", timeoutTicks = 1800, batch = "roofed-exit")
    fun supportLeavesPlayerRoofedTower(helper: GameTestHelper) = departure(helper, SquadOrder.DEFEND, Departure.MARCH)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "roofed_watchtower", timeoutTicks = 1800, batch = "roofed-patrol-exit")
    fun patrolSniperLeavesPlayerRoofedTower(helper: GameTestHelper) = departure(helper, SquadOrder.PATROL, Departure.MARCH, NpcClass.SNIPER)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "roofed_watchtower", timeoutTicks = 1800, batch = "roofed-retreat")
    fun sniperRetreatsFromPlayerRoofedTower(helper: GameTestHelper) = departure(helper, SquadOrder.DEFEND, Departure.RETREAT, NpcClass.SNIPER)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "roofed_watchtower", timeoutTicks = 2400, batch = "roofed-supply")
    fun sentryResuppliesAndReturnsToPlayerRoofedTower(helper: GameTestHelper) = departure(helper, SquadOrder.DEFEND, Departure.SUPPLY, NpcClass.MACHINE_GUNNER)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "roofed_watchtower", timeoutTicks = 1600, batch = "scaffold-post")
    fun supportReservesAndHoldsScaffoldTower(helper: GameTestHelper) = occupy(helper, SquadOrder.DEFEND, scaffolding = true)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "roofed_watchtower", timeoutTicks = 2000, batch = "scaffold-fall")
    fun fallenSupportReturnsToScaffoldTower(helper: GameTestHelper) = occupy(helper, SquadOrder.DEFEND, fall = true, scaffolding = true)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "roofed_watchtower", timeoutTicks = 2400, batch = "scaffold-supply")
    fun patrolSniperResuppliesAndReturnsToScaffoldTower(helper: GameTestHelper) = departure(helper, SquadOrder.PATROL, Departure.SUPPLY, NpcClass.SNIPER, scaffolding = true)

    private enum class Departure { MARCH, RETREAT, SUPPLY }

    /** Only orders and real gun ammunition change; the brain owns every path and physical step. */
    private fun departure(helper: GameTestHelper, order: SquadOrder, mode: Departure, onlySupport: NpcClass? = null, scaffolding: Boolean = false) {
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        val name = "${if (scaffolding) "scaffold" else "roofed"}-exit-$order-$mode-${onlySupport ?: "mixed"}"
        val trace = StringBuilder("tick,x,y,z,ladder,ground,collision,dy,nextX,nextY,nextZ,done,health,ammo,resupplying\n")
        val destination = BlockPos(43, 2, 69)
        var sentry: NpcEntity? = null
        var previousPost: Vec3? = null
        var departureTick = 0L
        var descended = false
        var supplied = false
        var returnSince: Long? = null
        occupy(helper, order, onlySupport = onlySupport, scaffolding = scaffolding, onOccupied = { npc, post ->
            sentry = npc
            previousPost = post
            departureTick = helper.tick
            val manager = SquadManager.get(helper.level)
            val squad = npc.currentSquad()!!
            when (mode) {
                Departure.MARCH, Departure.RETREAT -> {
                    manager.setOrder(squad.id, if (mode == Departure.MARCH) SquadOrder.MOVE else SquadOrder.RETREAT)
                    manager.setObjective(helper.level, squad.id, helper.absolutePos(destination))
                }

                Departure.SUPPLY -> {
                    helper.setBlock(destination, ModBlocks.SUPPLY.get())
                    (helper.getBlockEntity(destination) as SupplyBlockEntity).faction = SquadFaction.CAT
                    val data = GunData.from(npc.mainHandItem)
                    data.ammo.set(0)
                    data.virtualAmmo.set(0)
                    helper.assertTrue(npc.ammoFraction() == 0.0 && npc.wantsResupply(), "Empty sentry did not request actual Supply")
                }
            }
            val path = (npc.navigation as VehicleAwareNavigation).createPositionPath(helper.absolutePos(destination))
            val nodes = path?.let { p -> (0 until p.nodeCount).map { relativeNode(helper, p.getNode(it).asBlockPos()) } }
            Files.writeString(report.resolve("$name-planned.txt"), "reachable=${path?.canReach()}\nnodes=$nodes\n")
        })
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                if (mode == Departure.SUPPLY) helper.setBlock(destination, Blocks.AIR)
            }
        })
        helper.onEachTick {
            val npc = sentry ?: return@onEachTick
            if (mode != Departure.SUPPLY && helper.tick - departureTick >= 10) {
                helper.assertTrue(previousPost !in FiringSpots.nearby(previousPost!!, 1.0, UUID(0, 0)), "New order retained the old platform reservation")
            }
            val at = helper.relativeVec(npc.position())
            if (npc.onClimbable() && npc.deltaMovement.y < -0.01 && at.y < 6.5) descended = true
            val next = npc.navigation.path?.takeUnless { it.isDone }?.nextNodePos?.let { relativeNode(helper, it) }
            trace.append("${helper.tick},${at.x},${at.y},${at.z},${npc.onClimbable()},${npc.onGround()},${npc.horizontalCollision},${npc.deltaMovement.y},${next?.x},${next?.y},${next?.z},${npc.navigation.isDone},${npc.health},${npc.ammoFraction()},${npc.resupplying}\n")
            if (helper.tick % 10L == 0L) Files.writeString(report.resolve("$name.csv"), trace)
            helper.assertTrue(npc.isAlive && npc.health == npc.maxHealth, "Leaving the tower caused fall damage")
            val arrived = when (mode) {
                Departure.MARCH -> {
                    val squad = npc.currentSquad()!!
                    val home = npc.homeCenter()!!
                    val slot = SquadFormation.moveSlotTarget(npc, home, home.subtract(squad.moveAssembly!!.center))
                    at.y < 3.0 && npc.onGround() && npc.position().distanceTo(slot) <= 1.5 && npc.navigation.isDone
                }

                Departure.RETREAT -> {
                    val squad = npc.currentSquad()!!
                    val task = squad.tactics.plan?.tasks?.get(npc.uuid)
                    // OrderArrival changes RETREAT to DEFEND after 3/4 of the squad arrives.
                    // A late support may already be holding its newly assigned defensive post.
                    val heldNewPost = squad.order == SquadOrder.DEFEND && task?.position?.let {
                        it != previousPost && FiringPostArrival.reached(npc.position(), it)
                    } == true
                    at.y < 3.0 && npc.onGround() && npc.navigation.isDone &&
                        (npc.position().distanceTo(npc.homeCenter()!!) <= SquadFormation.ARRIVAL_RADIUS || heldNewPost)
                }

                Departure.SUPPLY -> {
                    if (at.y < 3.0 && npc.ammoFraction() >= 0.8 && npc.position().distanceTo(helper.absolutePos(destination).center) <= SupplyBlockEntity.RADIUS) supplied = true
                    val task = npc.currentSquad()!!.tactics.plan?.tasks?.get(npc.uuid)
                    val returned = supplied && !npc.resupplying && task?.job == TacticalJob.OVERWATCH &&
                        task.position == previousPost && npc.onGround() && FiringPostArrival.reached(npc.position(), previousPost!!)
                    if (!returned) {
                        returnSince = null
                    } else if (returnSince == null) {
                        returnSince = helper.tick
                    }
                    returnSince?.let { helper.tick - it >= 100 } == true
                }
            }
            if (arrived) {
                helper.assertTrue(descended, "Sentry jumped out instead of descending the ladder")
                if (mode == Departure.SUPPLY) helper.assertTrue(previousPost in FiringSpots.nearby(previousPost!!, 1.0, UUID(0, 0)), "Returning sentry did not reclaim the post")
                Files.writeString(report.resolve("$name-result.txt"), "arrived=${helper.tick}\ndeparture=$departureTick\ndescended=$descended\nsupplied=$supplied\nhealth=${npc.health}\n")
                helper.succeed()
            }
        }
    }

    private fun relativeNode(helper: GameTestHelper, pos: BlockPos): BlockPos = BlockPos.containing(helper.relativeVec(Vec3.atLowerCornerOf(pos)))

    private fun occupy(helper: GameTestHelper, order: SquadOrder, fall: Boolean = false, onlySupport: NpcClass? = null, scaffolding: Boolean = false, supportIndex: Int = 5, onOccupied: ((NpcEntity, Vec3) -> Unit)? = null) {
        // Template blocks start one block above the structure origin used by GameTestHelper.
        helper.assertTrue(helper.getBlockState(BlockPos(44, 1, 39)).`is`(Blocks.GRASS_BLOCK), "Copied ground is at the wrong height")
        if (scaffolding) {
            for (y in 2..6) helper.setBlock(BlockPos(44, y, 43), Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.DISTANCE, 0).setValue(ScaffoldingBlock.BOTTOM, y == 2))
        }
        val roles = listOf(NpcClass.RIFLEMAN, NpcClass.RIFLEMAN, NpcClass.RIFLEMAN, NpcClass.RIFLEMAN, NpcClass.SNIPER, NpcClass.MACHINE_GUNNER, NpcClass.MEDIC)
        val places = listOf(
            Vec3(-12.286, -60.0, 26.408),
            Vec3(8.016, -60.0, 14.593),
            Vec3(0.059, -60.0, 7.5),
            Vec3(-33.511, -60.0, 5.273),
            Vec3(-13.627, -60.0, 6.38),
            Vec3(-10.692, -60.0, 9.646),
            Vec3(-29.385, -60.0, 3.448),
        )
        val npcs = places.mapIndexed { index, at ->
            AttackDeploymentGameTests.spawn(
                helper,
                at.add(55.0, 62.0, 30.0),
                SquadFaction.CAT,
                randomSeed = index.toLong(),
                role = if (onlySupport == null) {
                    roles[index]
                } else {
                    when (index) {
                        4, 5 -> if (index == supportIndex) onlySupport else NpcClass.RIFLEMAN
                        else -> roles[index]
                    }
                },
            )
        }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.CAT, npcs.map { it.uuid })
        manager.setOrder(squad.id, order)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(43, 1, 42)))
        val oldGroups = DebugFlags.enabledGroups
        DebugFlags.enabledGroups = oldGroups + LogGroup.ORDER
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(report)
        val trace = StringBuilder("tick,role,x,y,z,postX,postY,postZ,job,recovery,closest,lastProgress\n")
        val name = "${if (scaffolding) "scaffold" else "roofed"}-tower-$order${if (fall) "-fall" else ""}${onlySupport?.let { "-$it" } ?: ""}"
        var climbed = false
        var settledSince: Long? = null
        var displaced: NpcEntity? = null
        var retainedPost: Vec3? = null
        var landed = false
        var removedFloor: BlockPos? = null
        var floorState: BlockState? = null
        var completed = false
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                DebugFlags.enabledGroups = oldGroups
                manager.disband(helper.level, squad.id)
                npcs.forEach {
                    FiringSpots.release(it.uuid)
                    it.discard()
                }
            }
        })
        helper.onEachTick {
            if (completed) return@onEachTick
            val supports = npcs.filter { it.npcClass in setOf(NpcClass.SNIPER, NpcClass.MACHINE_GUNNER) }
            if (supports.any { it.onClimbable() }) climbed = true
            val high = supports.filter { npc ->
                val task = squad.tactics.plan?.tasks?.get(npc.uuid) ?: return@filter false
                val post = task.position ?: return@filter false
                val relative = helper.relativeVec(post)
                task.job == TacticalJob.OVERWATCH && !task.localFireRecovery && relative.y in 6.5..7.5 &&
                    FiringPostArrival.reached(npc.position(), post) && npc.onGround() && TacticalPositions.protected(npc, task, post)
            }
            if (helper.tick % 10L == 0L) {
                supports.forEach { npc ->
                    val task = squad.tactics.plan?.tasks?.get(npc.uuid)
                    val at = helper.relativeVec(npc.position())
                    val post = task?.position?.let(helper::relativeVec)
                    trace.append("${helper.tick},${npc.npcClass},${at.x},${at.y},${at.z},${post?.x},${post?.y},${post?.z},${task?.job},${task?.localFireRecovery},${task?.closest},${task?.lastProgress}\n")
                }
                Files.writeString(report.resolve("$name.csv"), trace)
            }
            displaced?.let { npc ->
                helper.assertTrue(npc.isAlive, "Displaced sentry died")
                val task = squad.tactics.plan?.tasks?.get(npc.uuid)
                helper.assertTrue(task != null && task.position == retainedPost && task.job == TacticalJob.OVERWATCH && !task.localFireRecovery, "Displacement discarded the sheltered platform")
                helper.assertTrue(retainedPost in FiringSpots.nearby(retainedPost!!, 1.0, UUID(0, 0)), "Displacement lost the platform reservation")
                if (!landed && npc.onGround() && helper.relativeVec(npc.position()).y < 3.0) {
                    landed = true
                    helper.level.setBlockAndUpdate(removedFloor!!, floorState!!)
                }
            }
            val arrived = if (displaced == null) high.isNotEmpty() else landed && displaced in high
            if (!arrived) {
                settledSince = null
            } else {
                if (settledSince == null) settledSince = helper.tick
                if (helper.tick - settledSince!! >= 100) {
                    helper.assertTrue(climbed, "No actual ladder ascent took place")
                    val post = squad.tactics.plan!!.tasks[high.first().uuid]!!.position!!
                    helper.assertTrue(post in FiringSpots.nearby(post, 1.0, UUID(0, 0)), "Occupied post is unreserved")
                    if (fall && displaced == null) {
                        displaced = high.first()
                        retainedPost = post
                        settledSince = null
                        // Remove the supporting floor until the sentry actually lands, then
                        // restore the same block: the walls/roof prevent a simple shove outside.
                        removedFloor = BlockPos.containing(post).below()
                        floorState = helper.level.getBlockState(removedFloor!!)
                        helper.level.setBlockAndUpdate(removedFloor!!, Blocks.AIR.defaultBlockState())
                        return@onEachTick
                    }
                    Files.writeString(report.resolve("$name-result.txt"), "settled=${high.size}\nclimbed=$climbed\npost=${helper.relativeVec(post)}\nlanded=$landed\n")
                    completed = true
                    if (onOccupied == null) helper.succeed() else onOccupied(high.first(), post)
                }
            }
        }
    }
}
