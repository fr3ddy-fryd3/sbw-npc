package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.tactics.SquadTacticalState
import com.sbwnpc.squad.combat.tactics.TacticalAim
import com.sbwnpc.squad.combat.tactics.TacticalContact
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.combat.tactics.TacticalMember
import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.combat.tactics.TacticalSnapshot
import com.sbwnpc.squad.combat.tactics.TacticalTask
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.GameType
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import net.tslat.smartbrainlib.util.BrainUtils
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.UUID

/** Explicit local saved-world replays; never registered without gameTestReplay. */
@PrefixGameTestTemplate(false)
object SavedBattleGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 500, batch = "saved-delta-search")
    fun savedDeltaFindsReachableFiringPost(helper: GameTestHelper) = deltaSearch(helper, blocker = false)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 500, batch = "saved-delta-friendly-blocker")
    fun savedDeltaFindsPostAroundFriendlyBlocker(helper: GameTestHelper) = deltaSearch(helper, blocker = true)

    private fun deltaSearch(helper: GameTestHelper, blocker: Boolean) {
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(report)
        val replay = SavedBattleReplay(helper.level, Path.of(System.getProperty("sbwnpc.gameTestReplay")))
        replay.restore()
        val actor = replay.npcs.single { it.uuid == UUID.fromString("759990e2-8bf3-4def-a580-7d1c273eb383") }
        val enemy = replay.npcs.single { it.uuid == UUID.fromString("31c68a47-9b72-4e18-82aa-8f991f95bcc0") }
        val nearby = replay.npcs.single { it.uuid == UUID.fromString("a8859332-8250-467b-a428-e4057ddf601f") }
        val ally = replay.npcs.single { it.uuid == UUID.fromString("a2271d9c-4211-44cc-99d7-3661882a48cb") }
        replay.entities.filter { it !== actor && it !== enemy && it !== nearby && !(blocker && it === ally) }.forEach(Entity::discard)
        ally.isNoAi = true
        ally.moveTo(-447.50311383615343, 72.0, -321.61584782529314)
        listOf(actor, enemy, nearby).forEach { it.isNoAi = true }
        actor.moveTo(-441.56022599617796, 72.0, -337.01172289231465)
        enemy.moveTo(-479.77518544040225, 72.0, -220.086437417955)
        nearby.moveTo(-503.45810541691213, 65.0, -330.6135644769231)
        BrainUtils.setTargetOfEntity(actor, enemy)
        val task = TacticalTask(TacticalJob.COVER, actor.position(), enemy.position(), 1).apply {
            localFirePost = actor.position()
            expandedFireSearch = true
        }
        val trace = report.resolve(
            when {
                blocker -> "delta-search-friendly.csv"
                else -> "delta-search.csv"
            },
        )
        Files.writeString(trace, "tick,outcome,probe,probes,scored,rejections,position\n")
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                FiringSpots.release(actor.uuid)
                replay.close()
            }
        })
        helper.onEachTick {
            actor.setOnGround(true)
            if (blocker && helper.tick == 0L) helper.assertTrue(!com.sbwnpc.squad.combat.FriendlyFireGuard.hasClearLineOfFire(actor, enemy.eyePosition, actor.spread), "Frozen ally does not reproduce the blocked firing lane")
            val view = TacticalSnapshot(
                helper.level.gameTime,
                SquadOrder.ATTACK,
                1,
                actor.position(),
                enemy.position(),
                listOf(TacticalMember(actor.uuid, actor.position(), actor.npcClass)),
                listOf(TacticalContact(enemy.uuid, enemy.position(), helper.level.gameTime), TacticalContact(nearby.uuid, nearby.position(), helper.level.gameTime)),
            )
            val result = TacticalPositions.find(actor, task, view)
            if (helper.tick % 10L == 0L || result.outcome != TacticalPositions.Outcome.DEFERRED) {
                Files.writeString(trace, "${helper.tick},${result.outcome},${task.search?.probeIndex},${task.search?.probes?.size},${task.search?.scored?.size},\"${task.search?.rejections ?: result.rejections}\",\"${result.position}\"\n", APPEND)
            }
            if (result.outcome != TacticalPositions.Outcome.DEFERRED) {
                helper.assertTrue(result.outcome == TacticalPositions.Outcome.FOUND, "Delta has no reachable firing post in frozen scene: $result")
                helper.assertTrue(TacticalPositions.firingReady(actor, task, result.position!!), "Delta selected post cannot shoot the fixed enemy")
                helper.succeed()
            }
        }
    }

    /** Static search isolation from the failed replay; not a replacement for the battle criterion. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 1300, batch = "saved-battle", skyAccess = true)
    fun savedBattleMaintainsCombatParticipation(helper: GameTestHelper) {
        replay(helper, attackersOnly = false)
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 1300, batch = "saved-battle-attack", skyAccess = true)
    fun savedBattleAttackersMaintainCombatParticipation(helper: GameTestHelper) {
        replay(helper, attackersOnly = true)
    }

    private fun replay(helper: GameTestHelper, attackersOnly: Boolean) {
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(report)
        val source = Path.of(System.getProperty("sbwnpc.gameTestReplay"))
        val fixture = report.resolve("fixture")
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = fixture.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(destination) else Files.copy(path, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val replay = SavedBattleReplay(helper.level, fixture)
        val previousGroups = DebugFlags.enabledGroups
        DebugFlags.enabledGroups = previousGroups + setOf(LogGroup.ORDER, LogGroup.PATH)
        val pacer = CombatTickPacer()
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
        var trace: CombatParticipationTrace? = null
        var observer: net.minecraft.server.level.ServerPlayer? = null
        var closed = false
        fun cleanup() {
            if (closed) return
            closed = true
            trace?.close()
            observer?.let { helper.level.removePlayerImmediately(it, Entity.RemovalReason.DISCARDED) }
            replay.close()
            DebugFlags.enabledGroups = previousGroups
            NeoForge.EVENT_BUS.unregister(pacer)
        }
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
        })
        replay.restore()
        val checked = replay.manager.all().filter { !attackersOnly || it.order == SquadOrder.ATTACK }.mapTo(HashSet()) { it.id }
        helper.assertTrue(checked.isNotEmpty(), "The fixture contains no squads in the requested scope")
        observer = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "replay-observer")).apply {
            gameMode.changeGameModeForPlayer(GameType.CREATIVE)
            val combatants = replay.npcs
            moveTo(combatants.map { it.x }.average(), combatants.maxOf { it.y } + 24.0, combatants.map { it.z }.average(), 0f, 0f)
            setNoGravity(true)
            helper.level.addNewPlayer(this)
        }
        trace = CombatParticipationTrace(helper.level, report.resolve("participation.csv"))
        val members = replay.npcs
        val windows = report.resolve("windows.csv")
        Files.writeString(windows, "tick,squad,alive,armed,recent,pattern,phase\n")
        Files.writeString(report.resolve("fixture-limits.txt"), "Saved reload, not live AI continuation. NPCs=${members.size}, chunks=${replay.chunks}; saved weapons/ammo/health/orders, no replenishment. Brain targets/tasks/stance restart; UUID-seeded NPC random generators; no player combatant, ordinary biome/light scheduling of isolated server; copied native blocks/vehicles. Assert attackersOnly=$attackersOnly; all squads still recorded.\n")
        val watched = UUID.fromString(System.getProperty("sbwnpc.gameTestWatchNpc", "f8104e12-9378-4094-8cc7-54145ce8df70"))
        val sniper = members.firstOrNull { it.uuid == watched }
        val sniperTrace = report.resolve("sniper-lifecycle.csv")
        Files.writeString(sniperTrace, "tick,job,anchor,post,target,actorPosition,navDone\n")
        val stalledWindows = mutableMapOf<UUID, Int>()
        helper.onEachTick {
            val task = sniper?.let(com.sbwnpc.squad.ai.integration.NativeTactics::assignment)
            if (helper.tick % 10L == 0L) {
                Files.writeString(sniperTrace, "${helper.tick},${task?.job},\"${task?.anchor}\",\"${task?.position}\",${sniper?.target?.uuid},\"${sniper?.position()}\",${sniper?.navigation?.isDone}\n", APPEND)
            }
            for ((index, npc) in members.withIndex()) {
                if (helper.tick % 40L == (index % 40).toLong()) trace!!.sample(helper.tick, npc)
            }
            if (helper.tick % 100L != 0L) return@onEachTick
            trace!!.flush()
            for (squad in replay.manager.all()) {
                val alive = members.filter { it.isAlive && it.squadId == squad.id }
                val armed = alive.filter { Ports.guns.roundsLeft(it.primaryGun()) > 0 && !it.busyWithRole() }
                val recent = armed.count { it.firedRecently(100) }
                Files.writeString(windows, "${helper.tick},${squad.name},${alive.size},${armed.size},$recent,htn,${squad.members.firstNotNullOfOrNull { helper.level.getEntity(it) as? com.sbwnpc.squad.entity.NpcEntity }?.let(com.sbwnpc.squad.ai.integration.NativeTactics::attackPhase)}\n", APPEND)
                // Fix the criterion before the baseline: a sustained near-total standstill is a
                // failure, regardless of a few early shots or the final battle's casualty ratio.
                val contact = squad.tactics.snapshot?.contacts?.any { helper.level.getEntity(it.id)?.isAlive == true } == true
                if (helper.tick >= 400 && contact && armed.size >= 8 && recent < (armed.size + 3) / 4) {
                    stalledWindows[squad.id] = (stalledWindows[squad.id] ?: 0) + 1
                }
            }
            if (helper.tick == 1200L) {
                val stalled = replay.manager.all().filter { (stalledWindows[it.id] ?: 0) >= 3 }.map { it.name }
                val failed = replay.manager.all().filter { it.id in checked && (stalledWindows[it.id] ?: 0) >= 3 }.map { it.name }
                Files.writeString(report.resolve("result.txt"), "stalledSquads=$stalled failedScope=$failed attackersOnly=$attackersOnly samplesAverageMs=${trace!!.averageMillis()}\n")
                helper.assertTrue(failed.isEmpty(), "Saved battle has sustained mass inactivity in requested scope: $failed; all squads: $stalled; see participation.csv/windows.csv")
                helper.succeed()
            }
        }
    }
}
