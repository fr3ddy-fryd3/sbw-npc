package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.ai.tactics.AttackPhase
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.GroupOrders
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.UUID
import java.util.function.Consumer

/** Public commands, production HTN, real navigation and SBW projectiles; no forced tactical plans. */
@PrefixGameTestTemplate(false)
object HtnTacticsGameTests {
    private const val DAY_TIME = 6000L
    private const val NIGHT_TIME = 18000L

    /** The light a lantern two blocks off gives the spot it lights: the BRIGHT band. */
    private const val LANTERN_LIGHT = 12
    private const val LIGHT_SETTLE_TICKS = 5L

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1600, batch = "htn-compact")
    fun htnJointAttack64OnCompactFront(helper: GameTestHelper) = jointAttack(helper, wide = false)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1600, batch = "htn-wide")
    fun htnJointAttack64OnWideFront(helper: GameTestHelper) = jointAttack(helper, wide = true)

    /** In-game 21.10: one ATTACK for 28 squads froze the server choosing their approach sectors (#140). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 900, batch = "htn-many-squads")
    fun htnTwentyEightSquadsAttackTogether(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "htn-many-squads")
        val manager = SquadManager.get(helper.level)
        val groups = (0 until 28).map { squad ->
            (0 until 4).map { index -> scene.spawn(Vec3(10.5 + squad % 7 * 6 + index, 1.0, 40.5 + squad / 7 * 10)) }
        }
        val members = groups.flatten()
        val origins = members.associate { it.uuid to it.position() }
        for (index in 0 until 8) scene.enemy(Vec3(145.5 + index / 4 * 2, 1.0, 100.5 + index % 4 * 4))
        val squads = groups.map { group -> manager.create(helper.level, UUID(90, 28), requireNotNull(group.first().spawnFaction), group.map { it.uuid }) }
        scene.onClose = { squads.forEach { manager.deleteSquad(helper.level, it.id) } }
        val objective = helper.absolutePos(BlockPos(148, 1, 106))
        GroupOrders.apply(helper.level, manager, squads.map { it.id }, SquadOrder.ATTACK, objective.x, objective.z)
        helper.onEachTick {
            if (helper.tick < 600) return@onEachTick
            val moved = members.count { it.position().distanceTo(origins.getValue(it.uuid)) >= 12.0 }
            helper.assertTrue(moved >= 56, "Only $moved of ${members.size} attackers moved")
            scene.result("PASS", "tick=${helper.tick} moved=$moved")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1800, batch = "htn-area")
    fun htnEmptyAreaRequiresOccupationAndQuietInterval(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "htn-area")
        val manager = SquadManager.get(helper.level)
        val actors = (0 until 4).map { scene.spawn(Vec3(24.5, 1.0, 80.5 + it * 3)) }
        val squad = manager.create(helper.level, UUID(90, 3), requireNotNull(actors.first().spawnFaction), actors.map { it.uuid })
        scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        val goal = helper.absolutePos(BlockPos(84, 1, 86))
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, goal)
        val original = requireNotNull(squad.command)
        var occupiedAt: Long? = null
        var injectedAt: Long? = null
        var inspectedAt: Long? = null
        helper.onEachTick {
            val occupied = actors.count { it.position().distanceTo(goal.center) <= 24 } >= 2
            if (occupied && occupiedAt == null) occupiedAt = helper.tick
            if (actors.any { actor -> NativeTactics.assignment(actor)?.job == TacticalJob.SEARCH && actor.position().distanceTo(goal.center) <= 24 && com.sbwnpc.squad.combat.Vision.inCone(actor.position(), actor.yHeadRot, goal.center) && com.sbwnpc.squad.combat.DetectionSightline.visible(helper.level, actor.eyePosition, goal.center.add(0.0, 1.0, 0.0), actor) }) inspectedAt = inspectedAt ?: helper.tick
            if (occupiedAt != null && injectedAt == null && helper.tick - occupiedAt!! >= 300) {
                actors.forEach { it.incomingFire.record(it.position(), Vec3(-1.0, 0.0, 0.0), null, helper.level.gameTime) }
                injectedAt = helper.tick
            }
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} occupiedAt=$occupiedAt incomingAt=$injectedAt order=${squad.order} actors=${actors.map { helper.relativeVec(it.position()) to NativeTactics.assignment(it) }}")
            helper.assertTrue(squad.command == original, "Internal mode changed the requested order")
            if (squad.order == SquadOrder.DEFEND) {
                helper.assertTrue(occupiedAt != null && injectedAt != null && inspectedAt != null, "Attack completed before physical occupation and renewed threat")
                helper.assertTrue(helper.tick - injectedAt!! >= 600, "Renewed incoming fire did not reset success interval")
                helper.assertTrue(squad.commandOutcome == com.sbwnpc.squad.ai.tactics.CommandOutcome.COMPLETED, "Physical success was not recorded")
                // What the map receives must already show the defence, not the finished attack.
                val feed = com.sbwnpc.squad.map.MapFeed.build(helper.level.server, helper.level, squad.owner, squad.faction)
                val entry = feed.getList("Squads", net.minecraft.nbt.Tag.TAG_COMPOUND.toInt()).map { it as net.minecraft.nbt.CompoundTag }.single { it.getUUID("Id") == squad.id }
                scene.result("MAP", "order=${entry.getString("Order")} goalOrder=${entry.getString("GoalOrder")} goal=${entry.getIntArray("Goal").toList()}")
                helper.assertTrue(entry.getString("Order") == "DEFEND" && entry.getString("GoalOrder") == "DEFEND", "Map still shows ${entry.getString("Order")}/${entry.getString("GoalOrder")} after the attack completed")
                scene.result("PASS", "tick=${helper.tick} occupiedAt=$occupiedAt incomingAt=$injectedAt inspectedAt=$inspectedAt reason=${squad.commandReason}")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1300, batch = "htn-600-movement")
    fun htnSixHundredActiveNpcMovementBudget(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "htn-600", recordTrace = false)
        val manager = SquadManager.get(helper.level)
        val groups = (0 until 20).map { group ->
            val x = 10.5 + group % 4 * 40
            val z = 10.5 + group / 4 * 40
            (0 until 30).map { index -> scene.spawn(Vec3(x + index % 6 * 4, 1.0, z + index / 6 * 4)) }
        }
        val members = groups.flatten()
        val origins = members.associate { it.uuid to it.position() }
        val squads = groups.map { group -> manager.create(helper.level, UUID(90, 600), requireNotNull(group.first().spawnFaction), group.map { it.uuid }) }
        val work = NativeCombatWork(helper, members)
        scene.onClose = {
            work.close()
            squads.forEach { manager.deleteSquad(helper.level, it.id) }
        }
        squads.forEachIndexed { index, squad ->
            val group = groups[index]
            val center = Vec3(group.map { it.x }.average(), group.first().y, group.map { it.z }.average())
            manager.issueOrderAt(helper.level, squad.id, SquadOrder.MOVE, BlockPos.containing(center.add(10.0, 0.0, 0.0)))
        }
        helper.runAtTickTime(1200) {
            val active = members.count { it.isAlive && !it.isNoAi && it.tickCount >= 1100 && NativeTactics.reports(it).isNotEmpty() }
            val moved = members.count { it.position().distanceTo(origins.getValue(it.uuid)) >= 5 }
            helper.assertTrue(active == 600, "Some NPCs were unloaded, disabled or did not execute HTN: $active/600")
            helper.assertTrue(moved >= 450, "Movement benchmark left most NPCs stationary: $moved/600")
            work.report()
            scene.result("PASS", "active=$active moved=$moved tick=${helper.tick}; see operation-work.txt for measured tick times; this is movement, not 600-person combat")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1300, batch = "htn-armed")
    fun htnJointAttackAgainstArmedDefenders(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "htn-armed")
        val manager = SquadManager.get(helper.level)
        val groups = (0..1).map { group -> (0 until 8).map { index -> scene.spawn(Vec3(28.5 + index / 4 * 4, 1.0, 72.5 + group * 32 + index % 4 * 4)) } }
        val defenders = (0 until 8).map { index -> scene.spawn(Vec3(128.5 + index / 4 * 4, 1.0, 80.5 + index % 4 * 8), faction = com.sbwnpc.squad.npc.SquadFaction.COW) }
        val squads = groups.map { group -> manager.create(helper.level, UUID(90, 4), requireNotNull(group.first().spawnFaction), group.map { it.uuid }) }
        val defence = manager.create(helper.level, UUID(91, 4), requireNotNull(defenders.first().spawnFaction), defenders.map { it.uuid })
        val all = groups.flatten() + defenders
        val attackerIds = groups.flatten().mapTo(HashSet()) { it.uuid }
        val defenderIds = defenders.mapTo(HashSet()) { it.uuid }
        val origins = groups.flatten().associate { it.uuid to it.position() }
        val shots = HashSet<UUID>()
        var friendlyHits = 0
        val rounds = Consumer<EntityJoinLevelEvent> { event ->
            val round = event.entity as? Projectile ?: return@Consumer
            val owner = round.owner?.uuid ?: return@Consumer
            if (event.level === helper.level && Ports.guns.isRound(round)) shots += owner
        }
        val damage = Consumer<LivingDamageEvent.Post> { event ->
            val victim = event.entity.uuid
            val shooter = event.source.entity?.uuid
            if (event.newDamage > 0 && ((victim in attackerIds && shooter in attackerIds) || (victim in defenderIds && shooter in defenderIds))) friendlyHits++
        }
        NeoForge.EVENT_BUS.addListener(rounds)
        NeoForge.EVENT_BUS.addListener(damage)
        scene.onClose = {
            NeoForge.EVENT_BUS.unregister(rounds)
            NeoForge.EVENT_BUS.unregister(damage)
            (squads + defence).forEach { manager.deleteSquad(helper.level, it.id) }
        }
        val target = helper.absolutePos(BlockPos(130, 1, 92))
        manager.issueOrderAt(helper.level, defence.id, SquadOrder.DEFEND, target)
        GroupOrders.apply(helper.level, manager, squads.map { it.id }, SquadOrder.ATTACK, target.x, target.z)
        helper.onEachTick {
            if (helper.tick % 100L != 0L) return@onEachTick
            val attackShots = shots.count { it in attackerIds }
            val defenceShots = shots.count { it in defenderIds }
            val moved = groups.flatten().count { it.position().distanceTo(origins.getValue(it.uuid)) >= 8 }
            scene.result("RUNNING", "tick=${helper.tick} attackerShooters=$attackShots defenderShooters=$defenceShots moved=$moved alive=${all.count { it.isAlive }} friendlyHits=$friendlyHits")
            if (helper.tick >= 600 && attackShots >= 8 && defenceShots >= 4 && moved >= 8) {
                helper.assertTrue(groups.all { group -> group.count { it.uuid in shots } >= 3 }, "One attacking squad did not engage")
                helper.assertTrue(friendlyHits == 0, "Armed HTN battle caused friendly damage")
                scene.result("PASS", "tick=${helper.tick} attackerShooters=$attackShots defenderShooters=$defenceShots moved=$moved alive=${all.count { it.isAlive }} friendlyHits=$friendlyHits")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1500, batch = "htn-blocked")
    fun htnBlockedSquadDoesNotStopOtherAttackers(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "htn-blocked")
        val manager = SquadManager.get(helper.level)
        for (y in 1..4) {
            for (x in 20..42) {
                helper.setBlock(BlockPos(x, y, 40), net.minecraft.world.level.block.Blocks.STONE)
                helper.setBlock(BlockPos(x, y, 72), net.minecraft.world.level.block.Blocks.STONE)
            }
            for (z in 40..72) {
                helper.setBlock(BlockPos(20, y, z), net.minecraft.world.level.block.Blocks.STONE)
                helper.setBlock(BlockPos(42, y, z), net.minecraft.world.level.block.Blocks.STONE)
            }
        }
        val blocked = (0 until 8).map { scene.spawn(Vec3(25.5 + it / 4 * 5, 1.0, 46.5 + it % 4 * 5)) }
        val free = (0 until 8).map { scene.spawn(Vec3(25.5 + it / 4 * 4, 1.0, 92.5 + it % 4 * 5)) }
        val groups = listOf(blocked, free)
        val squads = groups.map { group -> manager.create(helper.level, UUID(90, 5), requireNotNull(group.first().spawnFaction), group.map { it.uuid }) }
        (0 until 4).forEach { scene.enemy(Vec3(145.5, 1.0, 98.5 + it * 5)) }
        val origins = free.associate { it.uuid to it.position() }
        scene.onClose = { squads.forEach { manager.deleteSquad(helper.level, it.id) } }
        val target = helper.absolutePos(BlockPos(145, 1, 106))
        GroupOrders.apply(helper.level, manager, squads.map { it.id }, SquadOrder.ATTACK, target.x, target.z)
        var observedFailure = false
        helper.onEachTick {
            if (helper.tick % 20L != 0L) return@onEachTick
            observedFailure = observedFailure || blocked.any { NativeTactics.reports(it).values.any { report -> report.reason?.startsWith("local_") == true } }
            val firing = free.count { it.firedRecently(100) }
            val moved = free.count { it.position().distanceTo(origins.getValue(it.uuid)) >= 12 }
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} localFailure=$observedFailure freeFiring=$firing freeMoved=$moved")
            if (helper.tick >= 600 && observedFailure && firing >= 4 && moved >= 6) {
                helper.assertTrue(
                    blocked.all { actor ->
                        val relative = helper.relativeVec(actor.position())
                        relative.x in 20.0..43.0 && relative.z in 40.0..73.0
                    },
                    "Blocked squad escaped the physical control",
                )
                scene.result("PASS", "tick=${helper.tick} localFailure=$observedFailure freeFiring=$firing freeMoved=$moved")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1600, batch = "htn-reinforcement")
    fun htnReinforcementJoinsOnArrivalAndCanLeaveIndependently(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "htn-reinforcement")
        val manager = SquadManager.get(helper.level)
        val principal = UUID(90, 6)
        val groups = (0..1).map { group -> (0 until 8).map { index -> scene.spawn(Vec3(24.5 + index / 4 * 4, 1.0, 76.5 + group * 32 + index % 4 * 4)) } }
        val squads = groups.map { group -> manager.create(helper.level, principal, requireNotNull(group.first().spawnFaction), group.map { it.uuid }) }.toMutableList()
        (0 until 4).forEach { scene.enemy(Vec3(145.5, 1.0, 94.5 + it * 6)) }
        scene.onClose = { squads.forEach { manager.deleteSquad(helper.level, it.id) } }
        val target = helper.absolutePos(BlockPos(145, 1, 106))
        GroupOrders.apply(helper.level, manager, squads.map { it.id }, SquadOrder.ATTACK, target.x, target.z)
        var reinforcement: List<NpcEntity>? = null
        var issuedAt: Long? = null
        var joinedAt: Long? = null
        var leftAt: Long? = null
        var pendingObserved = false
        var marchObserved = false
        var firedOn = false
        var phase: com.sbwnpc.squad.ai.tactics.AttackPhase? = null
        var phaseSince = 0L
        var retainedTickets = emptyMap<UUID, com.sbwnpc.squad.ai.planning.ExecutionTicket>()
        helper.onEachTick {
            if (helper.tick == 400L) {
                reinforcement = (0 until 4).map { scene.spawn(Vec3(5.5, 1.0, 98.5 + it * 4)) }
                val group = reinforcement!!
                val squad = manager.create(helper.level, principal, requireNotNull(group.first().spawnFaction), group.map { it.uuid })
                squads += squad
                // The map's "Join attack" sends the operation's exact point as a map order.
                GroupOrders.apply(helper.level, manager, listOf(squad.id), SquadOrder.ATTACK, target.x, target.z)
                helper.assertTrue(squads.first().command?.goal == squad.command?.goal, "Map and direct point orders produced different goals: ${squads.first().command?.goal} vs ${squad.command?.goal}")
                issuedAt = helper.tick
            }
            val group = reinforcement ?: return@onEachTick
            val npc = group.first()
            val original = groups.first().first()
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} pendingObserved=$pendingObserved joined=$joinedAt left=$leftAt original=${NativeTactics.operation(original)} reinforcement=${NativeTactics.operation(npc)} actors=${group.map { helper.relativeVec(it.position()) }}")
            if (joinedAt == null && issuedAt != null && helper.tick > issuedAt!! + 10 && !NativeTactics.coordinated(npc)) {
                pendingObserved = true
                helper.assertTrue(!NativeTactics.holdsArea(squads.last().id), "Pending reinforcement runs its own area completion")
            }
            // Far from the fight and unfired on, it makes for the operation's entry instead of
            // fighting where it stands: in-game 16.10 an enemy 280 m away stopped a reinforcement for good.
            if (group.any { it.incomingFire.recentPoint(helper.level.gameTime) != null }) firedOn = true
            if (joinedAt == null && issuedAt != null && helper.tick > issuedAt!! + 40 && !firedOn && group.all { helper.relativeVec(it.position()).x < 40.0 }) {
                val entry = requireNotNull(NativeTactics.operation(original)).entry.center
                val astray = group.filter { npc -> NativeTactics.assignment(npc).let { it == null || it.anchor.distanceTo(Vec3(entry.x, entry.y, entry.z)) > 1.0 } }
                helper.assertTrue(astray.isEmpty(), "Distant reinforcement is not marching to the entry: ${astray.map { helper.relativeVec(it.position()) to NativeTactics.assignment(it) }}")
                marchObserved = true
            }
            // A reinforcement joins a fight in progress: once it reaches the fight it is under fire.
            if (joinedAt == null && helper.tick % 20L == 0L && group.any { helper.relativeVec(it.position()).x >= 65.0 }) group.forEach { it.incomingFire.record(it.position(), Vec3(1.0, 0.0, 0.0), null, helper.level.gameTime) }
            if (joinedAt == null && NativeTactics.coordinated(npc)) {
                helper.assertTrue(pendingObserved, "Reinforcement was counted before travelling to the operation")
                helper.assertTrue(marchObserved, "Reinforcement was never seen marching from afar")
                val operation = requireNotNull(NativeTactics.operation(original))
                helper.assertTrue(group.any { operation.entry.contains(com.sbwnpc.squad.ai.coordination.Point3(it.x, it.y, it.z)) }, "Reinforcement joined with nobody inside its physical entry area")
                joinedAt = helper.tick
            }
            NativeTactics.attackPhase(original).let {
                if (it != phase) {
                    phase = it
                    phaseSince = helper.tick
                }
            }
            // A phase change re-plans the attackers by itself; leave between changes, so only the
            // reinforcement's departure is measured.
            if (joinedAt != null && leftAt == null && helper.tick - joinedAt!! >= 100 && helper.tick - phaseSince >= 60) {
                helper.assertTrue(group.all { helper.relativeVec(it.position()).x >= 17.5 }, "A reinforcement support member stayed near its distant spawn: ${group.map { helper.relativeVec(it.position()) to NativeTactics.reports(it) }}")
                retainedTickets = groups.flatten().mapNotNull { actor -> NativeTactics.reports(actor)["position"]?.ticket?.let { actor.uuid to it } }.toMap()
                manager.issueOrderAt(helper.level, squads.last().id, SquadOrder.MOVE, BlockPos.containing(npc.position().add(-20.0, 0.0, 0.0)))
                leftAt = helper.tick
            }
            if (leftAt != null && helper.tick - leftAt!! >= 20) {
                helper.assertTrue(NativeTactics.operation(npc)?.id != NativeTactics.operation(original)?.id, "Separate command did not remove the reinforcement")
                helper.assertTrue(NativeTactics.operation(original)?.members?.size == 2, "Separate command disrupted original operation")
                val kept = groups.flatten().count { actor -> retainedTickets[actor.uuid] != null && NativeTactics.reports(actor)["position"]?.ticket == retainedTickets[actor.uuid] }
                helper.assertTrue(kept >= 8, "Removing reinforcement rebuilt most unrelated NPC executions: $kept/16 phase=$phase since=$phaseSince left=$leftAt")
                scene.result("PASS", "pending=$pendingObserved issued=$issuedAt joined=$joinedAt left=$leftAt unaffectedExecutions=$kept")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1200, batch = "htn-defend-join")
    fun htnSquadJoinsHeldDefenceAtItsPoint(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "htn-defend-join")
        val manager = SquadManager.get(helper.level)
        val principal = UUID(90, 7)
        val holders = (0 until 4).map { scene.spawn(Vec3(80.5 + it % 2 * 3, 1.0, 96.5 + it / 2 * 3)) }
        val squad = manager.create(helper.level, principal, requireNotNull(holders.first().spawnFaction), holders.map { it.uuid })
        var joiner: com.sbwnpc.squad.squad.Squad? = null
        var joiners = emptyList<NpcEntity>()
        scene.onClose = { listOfNotNull(squad, joiner).forEach { manager.deleteSquad(helper.level, it.id) } }
        val point = helper.absolutePos(BlockPos(82, 1, 98))
        GroupOrders.apply(helper.level, manager, listOf(squad.id), SquadOrder.DEFEND, point.x, point.z)
        var pendingObserved = false
        helper.onEachTick {
            val operation = NativeTactics.operation(holders.first())
            if (helper.tick == 100L) {
                // The map's "Join defend" sends the operation's exact point, here from another side.
                val area = requireNotNull(operation?.objective as? com.sbwnpc.squad.ai.coordination.ObjectiveKey.Area) { "Defence formed no operation" }
                joiners = (0 until 4).map { scene.spawn(Vec3(82.5 + it * 3, 1.0, 150.5)) }
                joiner = manager.create(helper.level, principal, requireNotNull(joiners.first().spawnFaction), joiners.map { it.uuid })
                GroupOrders.apply(helper.level, manager, listOf(joiner!!.id), SquadOrder.DEFEND, area.x, area.z)
            }
            if (joiners.isEmpty()) return@onEachTick
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} pending=$pendingObserved holders=${operation?.members?.size} joiners=${joiners.map { helper.relativeVec(it.position()) }}")
            if (!NativeTactics.coordinated(joiners.first())) {
                if (helper.tick > 110) pendingObserved = true
                return@onEachTick
            }
            helper.assertTrue(pendingObserved, "Joining squad was counted before it reached the defended point")
            helper.assertTrue(NativeTactics.operation(joiners.first())?.id == operation?.id, "Joining squad formed its own defence")
            scene.result("PASS", "tick=${helper.tick} members=${operation?.members?.size}")
            helper.succeed()
        }
    }

    /**
     * Two squads given one common order 9 blocks beside a held defence's point join that defence
     * instead of starting another beside it. In-game 16.10 a click a few blocks off the point
     * made a second defence, and the map showed both.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1600, batch = "htn-defend-join-beside")
    fun htnOrderBesideDefenceJoinsIt(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "htn-defend-join-beside")
        val manager = SquadManager.get(helper.level)
        val principal = UUID(90, 17)
        val holders = (0 until 4).map { scene.spawn(Vec3(80.5 + it % 2 * 3, 1.0, 96.5 + it / 2 * 3)) }
        val squad = manager.create(helper.level, principal, requireNotNull(holders.first().spawnFaction), holders.map { it.uuid })
        var joining = emptyList<com.sbwnpc.squad.squad.Squad>()
        var joiners = emptyList<List<NpcEntity>>()
        scene.onClose = { (listOf(squad) + joining).forEach { manager.deleteSquad(helper.level, it.id) } }
        val point = helper.absolutePos(BlockPos(82, 1, 98))
        GroupOrders.apply(helper.level, manager, listOf(squad.id), SquadOrder.DEFEND, point.x, point.z)
        helper.onEachTick {
            val operation = NativeTactics.operation(holders.first())
            if (helper.tick == 100L) {
                val area = requireNotNull(operation?.objective as? com.sbwnpc.squad.ai.coordination.ObjectiveKey.Area) { "Defence formed no operation" }
                joiners = (0..1).map { group -> (0 until 4).map { scene.spawn(Vec3(76.5 + group * 12 + it * 2, 1.0, 140.5)) } }
                joining = joiners.map { group -> manager.create(helper.level, principal, requireNotNull(group.first().spawnFaction), group.map { it.uuid }) }
                GroupOrders.apply(helper.level, manager, joining.map { it.id }, SquadOrder.DEFEND, area.x + 9, area.z)
                helper.assertTrue(joining.all { it.tacticalObjective?.x == area.x && it.tacticalObjective?.z == area.z }, "Order beside the defence kept its own point: ${joining.map { it.tacticalObjective }}")
            }
            if (joiners.isEmpty()) return@onEachTick
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} members=${operation?.members?.size} joiners=${joiners.map { group -> NativeTactics.operation(group.first())?.id }}")
            if (joiners.any { !NativeTactics.coordinated(it.first()) }) return@onEachTick
            helper.assertTrue(joiners.all { NativeTactics.operation(it.first())?.id == operation?.id }, "Squads ordered beside the defence formed their own")
            scene.result("PASS", "tick=${helper.tick} members=${operation?.members?.size}")
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 4800, batch = "htn-take")
    fun htnAttackTakesDefendedObjective(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "htn-take")
        val manager = SquadManager.get(helper.level)
        val groups = (0..1).map { group -> (0 until 8).map { index -> scene.spawn(Vec3(28.5 + index / 4 * 4, 1.0, 72.5 + group * 32 + index % 4 * 4)) } }
        val defenders = (0 until 8).map { index -> scene.spawn(Vec3(128.5 + index / 4 * 4, 1.0, 80.5 + index % 4 * 8), faction = com.sbwnpc.squad.npc.SquadFaction.COW) }
        val squads = groups.map { group -> manager.create(helper.level, UUID(90, 8), requireNotNull(group.first().spawnFaction), group.map { it.uuid }) }
        val defence = manager.create(helper.level, UUID(91, 8), requireNotNull(defenders.first().spawnFaction), defenders.map { it.uuid })
        scene.onClose = { (squads + defence).forEach { manager.deleteSquad(helper.level, it.id) } }
        val target = helper.absolutePos(BlockPos(130, 1, 92))
        manager.issueOrderAt(helper.level, defence.id, SquadOrder.DEFEND, target)
        GroupOrders.apply(helper.level, manager, squads.map { it.id }, SquadOrder.ATTACK, target.x, target.z)
        var closest = Double.MAX_VALUE
        var enteredAt: Long? = null
        var defendersAtEntry = 0
        helper.onEachTick {
            val attackers = groups.flatten().filter { it.isAlive }
            attackers.minOfOrNull { it.position().distanceTo(target.center) }?.let { closest = minOf(closest, it) }
            if (enteredAt == null && attackers.count { it.position().distanceTo(target.center) <= 24 } >= 4) {
                enteredAt = helper.tick
                defendersAtEntry = defenders.count { it.isAlive }
            }
            if (helper.tick % 200L == 0L) scene.result("RUNNING", "tick=${helper.tick} attackers=${attackers.size} defenders=${defenders.count { it.isAlive }} closest=${closest.toInt()} inside=${attackers.count { it.position().distanceTo(target.center) <= 24 }} orders=${squads.map { it.order }}")
            if (squads.all { it.order == SquadOrder.DEFEND }) {
                helper.assertTrue(squads.all { it.commandReason == "occupied_and_inspected_area" }, "Attack ended without taking the objective: ${squads.map { it.commandReason }}")
                scene.result("PASS", "tick=${helper.tick} attackers=${attackers.size} defenders=${defenders.count { it.isAlive }} enteredAt=$enteredAt defendersAtEntry=$defendersAtEntry")
                helper.succeed()
            }
        }
    }

    /**
     * Spotting takes time (#114): by day a regular rifleman notices an enemy 70 blocks off after
     * about a second, not on the first scan; at night one 20 blocks off after a little longer, and
     * one at the edge of his 36-block reach in the dark (owner's table, 21.10) after longer still.
     * The unit tests check the cut-offs.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "spot-day")
    fun enemyAtSeventyBlocksIsNoticedByDayAfterAMoment(helper: GameTestHelper) = spotting(helper, "spot-day", Lighting.DAY, distance = 70.0, expectNoticed = 8L..200L)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 700, batch = "spot-night-far")
    fun enemyAtTheEdgeOfRangeIsNoticedLongAfterAtNight(helper: GameTestHelper) = spotting(helper, "spot-night-far", Lighting.NIGHT, distance = 35.0, expectNoticed = 30L..300L)

    /**
     * A lantern by the enemy at night lights him as by day. 70 blocks is twice a regular's reach in
     * the dark, so without its light the scene fails.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "spot-night-lantern")
    fun enemyByALanternIsNoticedAtNightAsByDay(helper: GameTestHelper) = spotting(helper, "spot-night-lantern", Lighting.LANTERN, distance = 70.0, expectNoticed = 8L..80L)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 700, batch = "spot-night-near")
    fun enemyInsideTheDarkRangeTakesSecondsToNoticeAtNight(helper: GameTestHelper) = spotting(helper, "spot-night-near", Lighting.NIGHT, distance = 20.0, expectNoticed = 15L..250L)

    /** The light the spotting scenes are played in: a lantern by the enemy at night in the last. */
    private enum class Lighting(val time: Long, val lantern: Boolean) { DAY(DAY_TIME, false), NIGHT(NIGHT_TIME, false), LANTERN(NIGHT_TIME, true) }

    private fun spotting(helper: GameTestHelper, name: String, lighting: Lighting, distance: Double, expectNoticed: LongRange?) {
        val lantern = lighting.lantern
        val scene = CombatIdleGameTests.Scene(helper, name)
        val manager = SquadManager.get(helper.level)
        val oldTime = helper.level.dayTime
        helper.level.setDayTime(lighting.time)
        val shooter = scene.spawn(Vec3(20.5, 1.0, 60.5))
        shooter.moveTo(shooter.x, shooter.y, shooter.z, -90f, 0f)
        shooter.yHeadRot = -90f
        shooter.yBodyRot = -90f
        val enemy = scene.enemy(Vec3(20.5 + distance, 1.0, 60.5))
        val lamp = BlockPos(20 + distance.toInt(), 1, 62)
        if (lantern) helper.setBlock(lamp, net.minecraft.world.level.block.Blocks.LANTERN)
        val squad = manager.create(helper.level, UUID(90, 12), requireNotNull(shooter.spawnFaction), listOf(shooter.uuid))
        manager.setOrder(squad.id, SquadOrder.DEFEND)
        manager.setObjective(helper.level, squad.id, shooter.blockPosition())
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            helper.level.setDayTime(oldTime)
            if (lantern) helper.setBlock(lamp, net.minecraft.world.level.block.Blocks.AIR)
        }
        var noticedAt: Long? = null
        helper.onEachTick {
            shooter.yHeadRot = -90f
            // Light spreads a few ticks after the block is set.
            if (lantern && helper.tick == LIGHT_SETTLE_TICKS) {
                val light = helper.level.getMaxLocalRawBrightness(enemy.blockPosition())
                helper.assertTrue(light >= LANTERN_LIGHT, "The lantern lights the enemy only to $light")
            }
            if (noticedAt == null && shooter.target?.uuid == enemy.uuid) noticedAt = helper.tick
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} light=${helper.level.getMaxLocalRawBrightness(enemy.blockPosition())} noticedAt=$noticedAt")
            if (expectNoticed == null) {
                helper.assertTrue(noticedAt == null, "Noticed an enemy beyond the dark range at tick $noticedAt")
                if (helper.tick >= 400) helper.succeed()
            } else {
                noticedAt?.let {
                    helper.assertTrue(it in expectNoticed, "Noticed at tick $it, expected within $expectNoticed")
                    helper.succeed()
                }
            }
        }
    }

    /**
     * A pond lies between the attackers and a defended point: the firing line laid out at range
     * falls on the water and the straight way in crosses it. In-game 16.10 the same, with a lake,
     * left an attack of 23 against 7 standing on the shore; here the posts move to the bank and
     * the assault walks round the water to take the point.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 4800, batch = "htn-pond")
    fun htnAttackGoesRoundWaterToTakeObjective(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "htn-pond")
        val manager = SquadManager.get(helper.level)
        // Deep enough to swim, as the lake was; a wading pond is just open ground.
        val pond = (66..100).flatMap { x -> (70..114).flatMap { z -> (-3..0).map { y -> BlockPos(x, y, z) } } }
        val ground = pond.associateWith { helper.getBlockState(it) }
        pond.forEach { helper.setBlock(it, net.minecraft.world.level.block.Blocks.WATER) }
        val groups = (0..1).map { group -> (0 until 8).map { index -> scene.spawn(Vec3(28.5 + index / 4 * 4, 1.0, 72.5 + group * 32 + index % 4 * 4)) } }
        val defenders = (0 until 8).map { index -> scene.spawn(Vec3(128.5 + index / 4 * 4, 1.0, 80.5 + index % 4 * 8), faction = com.sbwnpc.squad.npc.SquadFaction.COW) }
        val squads = groups.map { group -> manager.create(helper.level, UUID(90, 15), requireNotNull(group.first().spawnFaction), group.map { it.uuid }) }
        val defence = manager.create(helper.level, UUID(91, 15), requireNotNull(defenders.first().spawnFaction), defenders.map { it.uuid })
        scene.onClose = {
            (squads + defence).forEach { manager.deleteSquad(helper.level, it.id) }
            ground.forEach { (pos, state) -> helper.setBlock(pos, state) }
        }
        val target = helper.absolutePos(BlockPos(130, 1, 92))
        manager.issueOrderAt(helper.level, defence.id, SquadOrder.DEFEND, target)
        GroupOrders.apply(helper.level, manager, squads.map { it.id }, SquadOrder.ATTACK, target.x, target.z)
        var enteredAt: Long? = null
        helper.onEachTick {
            val attackers = groups.flatten().filter { it.isAlive }
            val inside = attackers.count { it.position().distanceTo(target.center) <= 24 }
            if (enteredAt == null && inside >= 4) enteredAt = helper.tick
            if (helper.tick % 200L == 0L) {
                val wet = attackers.count { it.isInWater }
                scene.result("RUNNING", "tick=${helper.tick} phase=${NativeTactics.attackPhase(attackers.firstOrNull() ?: groups[0][0])} attackers=${attackers.size} defenders=${defenders.count { it.isAlive }} inside=$inside wet=$wet actors=${attackers.map { helper.relativeVec(it.position()).let { p -> "${p.x.toInt()},${p.z.toInt()}" } }}")
            }
            if (squads.all { it.order == SquadOrder.DEFEND }) {
                helper.assertTrue(squads.all { it.commandReason == "occupied_and_inspected_area" }, "Attack ended without taking the objective: ${squads.map { it.commandReason }}")
                scene.result("PASS", "tick=${helper.tick} attackers=${attackers.size} defenders=${defenders.count { it.isAlive }} enteredAt=$enteredAt")
                helper.succeed()
            }
        }
    }

    /**
     * Losses count over the operation: one squad cut to a single man is 40% of the whole and only
     * a review; at 60% of everyone the attack is called off and every squad falls back.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1200, batch = "htn-losses")
    fun htnOperationWithdrawsAfterHeavyLosses(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "htn-losses")
        val manager = SquadManager.get(helper.level)
        val groups = (0..1).map { group -> (0 until 5).map { index -> scene.spawn(Vec3(28.5 + index * 3, 1.0, 80.5 + group * 24)) } }
        (0 until 4).forEach { scene.enemy(Vec3(150.5, 1.0, 88.5 + it * 4)) }
        val squads = groups.map { group -> manager.create(helper.level, UUID(90, 10), requireNotNull(group.first().spawnFaction), group.map { it.uuid }) }
        scene.onClose = { squads.forEach { manager.deleteSquad(helper.level, it.id) } }
        val target = helper.absolutePos(BlockPos(150, 1, 94))
        GroupOrders.apply(helper.level, manager, squads.map { it.id }, SquadOrder.ATTACK, target.x, target.z)
        helper.onEachTick {
            when (helper.tick) {
                200L -> groups[0].drop(1).forEach { it.kill() }

                300L -> {
                    helper.assertTrue(squads.all { it.order == SquadOrder.ATTACK }, "One squad's remnant made the whole operation withdraw: ${squads.map { it.order }}")
                    groups[1].take(2).forEach { it.kill() }
                }
            }
            if (helper.tick > 300 && squads.all { it.order == SquadOrder.RETREAT }) {
                helper.assertTrue(squads.all { it.commandReason == "operation_losses" && it.commandOutcome == com.sbwnpc.squad.ai.tactics.CommandOutcome.FAILED }, "Withdrawal not recorded as failed: ${squads.map { it.commandReason }}")
                val away = squads.all { squad -> squad.objective?.let { it.center.distanceTo(target.center) > 100 } == true }
                helper.assertTrue(away, "Fallback point not away from the objective: ${squads.map { it.objective }}")
                scene.result("PASS", "tick=${helper.tick} orders=${squads.map { it.order }} objectives=${squads.map { it.objective }}")
                helper.succeed()
            }
        }
    }

    private fun jointAttack(helper: GameTestHelper, wide: Boolean) {
        val name = if (wide) "htn-wide" else "htn-compact"
        val scene = CombatIdleGameTests.Scene(helper, name)
        val manager = SquadManager.get(helper.level)
        val principal = UUID(90, if (wide) 2 else 1)
        val groups = (0..1).map { squad ->
            (0 until 32).map { index ->
                scene.spawn(Vec3(24.5 + index / 8 * 3, 1.0, 68.5 + squad * 56 + index % 8 * 3))
            }
        }
        val members = groups.flatten()
        val origins = members.associate { it.uuid to it.position() }
        (0 until 16).forEach { index -> scene.enemy(Vec3(145.5 + index / 4 * 2, 1.0, if (wide) 16.5 + index * 12 else 100.5 + index % 4 * 4)) }
        val squads = groups.map { group -> manager.create(helper.level, principal, requireNotNull(group.first().spawnFaction), group.map { it.uuid }) }
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.writeString(report.resolve("$name-observed.csv"), "tick,squad,alive,moved,shootersRecent,actualProjectileShooters,frontWidth,rearGuards,htnExecutions,friendlyHits,phase\n")
        val shooters = HashSet<UUID>()
        val memberIds = origins.keys
        var friendlyHits = 0
        val rounds = Consumer<EntityJoinLevelEvent> { event ->
            val projectile = event.entity as? Projectile ?: return@Consumer
            if (event.level === helper.level && Ports.guns.isRound(projectile) && projectile.owner?.uuid in memberIds) shooters += projectile.owner!!.uuid
        }
        val damage = Consumer<LivingDamageEvent.Post> { event ->
            if (event.entity.uuid in memberIds && event.source.entity?.uuid in memberIds && event.newDamage > 0) friendlyHits++
        }
        NeoForge.EVENT_BUS.addListener(rounds)
        NeoForge.EVENT_BUS.addListener(damage)
        scene.onClose = {
            NeoForge.EVENT_BUS.unregister(rounds)
            NeoForge.EVENT_BUS.unregister(damage)
            squads.forEach { manager.deleteSquad(helper.level, it.id) }
        }
        val objective = helper.absolutePos(BlockPos(148, 1, 106))
        GroupOrders.apply(helper.level, manager, squads.map { it.id }, SquadOrder.ATTACK, objective.x, objective.z)
        var validWindows = 0
        helper.onEachTick {
            if (helper.tick % 50L != 0L) return@onEachTick
            val allViews = members.mapNotNull { NativeTactics.assignment(it) }
            val width = allViews.maxOfOrNull { it.anchor.z }?.minus(allViews.minOf { it.anchor.z }) ?: 0.0
            val rear = allViews.count { it.job == TacticalJob.RESERVE }
            val watchingRear = members.count { actor ->
                val post = NativeTactics.assignment(actor)
                post?.job == TacticalJob.RESERVE && post.focus?.let { actor.lookAngle.dot(it.subtract(actor.eyePosition).normalize()) > 0.5 && com.sbwnpc.squad.combat.Vision.inCone(actor.position(), actor.yHeadRot, it, 120.0) } == true
            }
            val htn = members.count { NativeTactics.reports(it).isNotEmpty() }
            if (!wide && helper.tick >= 600) Files.writeString(report.resolve("$name-rear.txt"), members.mapNotNull { actor -> NativeTactics.assignment(actor)?.takeIf { it.job == TacticalJob.RESERVE }?.let { post -> "actor=${actor.uuid} position=${helper.relativeVec(actor.position())} post=$post target=${actor.target?.uuid} body=${actor.yRot} head=${actor.yHeadRot} reports=${NativeTactics.reports(actor)}" } }.joinToString("\n"))
            val moved = members.count { it.position().distanceTo(origins.getValue(it.uuid)) >= 12.0 }
            val useful = groups.all { group -> group.count { it.firedRecently(100) } >= 8 }
            // This scene measures the firing line: the assault that follows it is a separate scene.
            val phase = members.firstNotNullOfOrNull { NativeTactics.attackPhase(it) }
            groups.forEachIndexed { index, group ->
                Files.writeString(report.resolve("$name-observed.csv"), "${helper.tick},$index,${group.count { it.isAlive }},${group.count { it.position().distanceTo(origins.getValue(it.uuid)) >= 12.0 }},${group.count { it.firedRecently(100) }},${group.count { it.uuid in shooters }},$width,$rear,$htn,$friendlyHits,$phase\n", APPEND)
            }
            // One NPC briefly between executions while it replans is not a broken firing line: such a
            // window neither counts nor resets the run. The assault now starts within a few windows,
            // so a reset there could leave too few to finish.
            when {
                helper.tick < 300 || phase == AttackPhase.ASSAULT || phase == AttackPhase.CLEAR || !useful || moved < 32 || shooters.size < 24 -> validWindows = 0
                htn == 64 -> validWindows++
            }
            if (validWindows >= 3) {
                helper.assertTrue(members.all { it.isAlive }, "Attacker died without return fire")
                helper.assertTrue(friendlyHits == 0, "HTN attack caused friendly damage")
                if (!wide) {
                    helper.assertTrue(rear >= 8, "Compact enemy used all surplus capacity on the front")
                    helper.assertTrue(watchingRear >= 4, "Rear guards did not physically watch their assigned sector: $watchingRear/$rear")
                }
                scene.result("PASS", "tick=${helper.tick} moved=$moved actualShooters=${shooters.size} recent=${groups.map { group -> group.count { it.firedRecently(100) } }} width=$width rear=$rear watchingRear=$watchingRear htn=$htn friendlyHits=$friendlyHits")
                helper.succeed()
            }
        }
    }
}
