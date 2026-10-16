package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.tactics.*
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.UUID
import java.util.function.Consumer

/** Tests execution, not automatic selection. No fake readiness, target memory or navigation. */
@PrefixGameTestTemplate(false)
object TacticalExecutionGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1200, batch = "bound-executor")
    fun boundAlternatesMovingGroups(helper: GameTestHelper) = runScene(helper, TacticalPattern.BOUND)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1200, batch = "withdrawal-executor")
    fun breakContactWithdrawsBothGroups(helper: GameTestHelper) = runScene(helper, TacticalPattern.BREAK_CONTACT)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1200, batch = "height-executor")
    fun heightAttackClimbsReachableSlope(helper: GameTestHelper) = runScene(helper, TacticalPattern.ATTACK_HEIGHT)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1200, batch = "hold-height-executor")
    fun holdHeightFiresWithoutLeavingPlateau(helper: GameTestHelper) = runScene(helper, TacticalPattern.HOLD_HEIGHT)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", timeoutTicks = 1200, batch = "reorient-executor")
    fun reorientFiresWithoutCharging(helper: GameTestHelper) = runScene(helper, TacticalPattern.REORIENT)

    private fun runScene(helper: GameTestHelper, pattern: TacticalPattern) {
        val uphill = pattern == TacticalPattern.ATTACK_HEIGHT
        val plateau = pattern == TacticalPattern.HOLD_HEIGHT
        val holding = plateau || pattern == TacticalPattern.REORIENT
        for (x in 0..175) {
            val height = when {
                // The crest is 50 blocks from the enemy, leaving a legal firing position.
                uphill -> ((x - 44) / 2).coerceIn(0, 8)

                plateau && x in 16..56 -> 10

                else -> 0
            }
            for (z in 0..223) for (y in 0..height) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        }
        val startHeight = if (plateau) 11.0 else 1.0
        val identityPrefix = UUID.randomUUID().mostSignificantBits
        val members = (0..7).map {
            // Repeatable ordering, unique identities per run, assigned before server registration.
            AttackDeploymentGameTests.spawn(helper, Vec3(40.5, startHeight, 91.5 + it * 6), SquadFaction.PIG, UUID(identityPrefix, it.toLong()))
        }
        val initial = members.associate { it.uuid to it.position() }
        val enemies = (0..2).map {
            AttackDeploymentGameTests.spawn(helper, Vec3(110.5, if (uphill) 9.0 else 1.0, 106.5 + it * 6), SquadFaction.COW).apply {
                isNoAi = true
                getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 5000.0
                health = maxHealth
            }
        }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, members.map { it.uuid })
        manager.setOrder(squad.id, if (holding) SquadOrder.DEFEND else SquadOrder.ATTACK)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(if (holding) 40 else 110, startHeight.toInt(), 112)))
        DebugFlags.enabledGroups = setOf(LogGroup.ORDER, LogGroup.PATH, LogGroup.STUCK)
        TacticalRuntimeEvents.bind(squad, helper.level)
        squad.tactics.select(TacticalChoice(pattern, enemies[1].position()), squad.orderStamp, helper.level.gameTime)
        val selectedPlan = squad.tactics.plan!!
        squad.tactics.nextAssessment = Long.MAX_VALUE
        val witness = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "state-observer"))
        witness.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
        val at = helper.absoluteVec(Vec3(80.5, 20.0, 112.5))
        witness.moveTo(at.x, at.y, at.z, 0.0f, 0.0f)
        witness.setNoGravity(true)
        helper.level.addNewPlayer(witness)
        val directory = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(directory)
        val name = "${pattern.name.lowercase()}-executor"
        val csv = directory.resolve("$name.csv")
        Files.writeString(csv, "tick,npc,x,y,z,phase,bounds,job,ammo,lastShot\n")
        val fired = HashSet<UUID>()
        val moved = HashSet<UUID>()
        val firedAfterMove = HashSet<UUID>()
        val rounds = Consumer<EntityJoinLevelEvent> { event ->
            val round = event.entity as? Projectile
            val owner = round?.owner?.uuid
            if (event.level === helper.level && round != null && Ports.guns.isRound(round) && owner in initial) {
                fired += owner!!
                if (owner in moved) firedAfterMove += owner
            }
        }
        val pacer = CombatTickPacer()
        NeoForge.EVENT_BUS.addListener(rounds)
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
        var cleaned = false
        fun cleanup() {
            if (cleaned) return
            cleaned = true
            NeoForge.EVENT_BUS.unregister(rounds)
            NeoForge.EVENT_BUS.unregister(pacer)
            manager.deleteSquad(helper.level, squad.id)
            (members + enemies).forEach { it.discard() }
            helper.level.removePlayerImmediately(witness, Entity.RemovalReason.DISCARDED)
        }
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
        })
        fun result(outcome: String) {
            val plan = selectedPlan
            Files.writeString(directory.resolve("$name.txt"), "result=$outcome\ntick=${helper.tick}\npattern=$pattern\nphase=${plan.phase}\nfailure=${plan.failure}\nbounds=${plan.bounds}\nmoved=${moved.size}\nfired=${fired.size}\nfiredAfterMove=${firedAfterMove.size}\n")
        }
        helper.onEachTick {
            if (helper.tick == 5L) {
                helper.assertTrue(members.all { helper.level.getEntity(it.uuid) === it }, "Fixture members are not indexed by their UUIDs")
            }
            if (squad.tactics.plan !== selectedPlan || selectedPlan.phase == TacticalPhase.FAILED) {
                result("FAIL")
                helper.fail("$pattern failed or was replaced: ${selectedPlan.failure}; see $name.csv")
            }
            if (helper.tick % 10 == 0L) {
                val view = TacticalSnapshotCollector.collect(squad, helper.level, members, members.first())
                squad.tactics.snapshot = view
                TacticalCoordinator.advance(squad.id, squad.tactics, selectedPlan, view)
            }
            val plan = squad.tactics.plan!!
            for (npc in members) {
                val start = initial.getValue(npc.uuid)
                val point = npc.position()
                val shift = point.subtract(start)
                when (pattern) {
                    TacticalPattern.BOUND -> if (shift.x >= 4.0) moved += npc.uuid
                    TacticalPattern.BREAK_CONTACT -> if (shift.x <= -4.0) moved += npc.uuid
                    TacticalPattern.ATTACK_HEIGHT -> if (shift.x >= 12.0 && shift.y >= 4.0) moved += npc.uuid
                    TacticalPattern.HOLD_HEIGHT -> helper.assertTrue(point.y >= start.y - 0.1, "Defender left plateau")
                    TacticalPattern.REORIENT -> helper.assertTrue(shift.x < 12.0 && shift.horizontalDistance() < 24.0, "Reorient charged or abandoned its defensive post")
                    else -> error("Unsupported execution fixture: $pattern")
                }
                val closest = enemies.minOf { point.subtract(it.position()).horizontalDistance() }
                // Navigation permits a 0.75-block deviation along the safe path, unlike an endpoint.
                helper.assertTrue(closest >= 47.25, "${npc.uuid} crossed path clearance: distance=$closest, position=${helper.relativeVec(point)}")
                if (helper.tick % 10 == 0L) {
                    val relative = helper.relativeVec(point)
                    Files.writeString(csv, "${helper.tick},${npc.uuid},${relative.x},${relative.y},${relative.z},${plan.phase},${plan.bounds},${plan.tasks[npc.uuid]?.job},${Ports.guns.roundsLeft(npc.mainHandItem)},${npc.lastShotTick}\n", APPEND)
                }
            }
            val achieved = when (pattern) {
                TacticalPattern.BOUND, TacticalPattern.BREAK_CONTACT -> plan.bounds >= 2 && moved.size >= 6 && firedAfterMove.size >= 6
                TacticalPattern.ATTACK_HEIGHT -> plan.bounds >= 2 && moved.size >= 6 && firedAfterMove.size >= 6
                else -> helper.tick >= 200 && fired.size >= 6
            }
            if (achieved) {
                result("PASS")
                helper.succeed()
            }
        }
        helper.runAtTickTime(1199) {
            result("FAIL")
            helper.fail("$pattern execution incomplete; see $name.csv")
        }
    }
}
