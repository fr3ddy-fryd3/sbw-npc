package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.UUID

/** Explicit local saved-world replays; never registered without gameTestReplay. */
@PrefixGameTestTemplate(false)
object SavedBattleGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 1300, batch = "saved-battle", skyAccess = true)
    fun savedBattleMaintainsCombatParticipation(helper: GameTestHelper) {
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
        observer = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "replay-observer")).apply {
            gameMode.changeGameModeForPlayer(GameType.CREATIVE)
            moveTo(810.0, 110.0, -1000.0, 0f, 0f)
            setNoGravity(true)
            helper.level.addNewPlayer(this)
        }
        trace = CombatParticipationTrace(helper.level, report.resolve("participation.csv"))
        val members = replay.npcs
        val windows = report.resolve("windows.csv")
        Files.writeString(windows, "tick,squad,alive,armed,recent,pattern,phase\n")
        Files.writeString(report.resolve("fixture-limits.txt"), "Saved reload, not live AI continuation. NPCs=${members.size}, chunks=${replay.chunks}; saved weapons/ammo/health/orders, no replenishment. Brain targets/tasks/stance restart; no player combatant, ordinary biome/light scheduling of isolated server; copied native blocks/vehicles.\n")
        val stalledWindows = mutableMapOf<UUID, Int>()
        helper.onEachTick {
            for ((index, npc) in members.withIndex()) {
                if (helper.tick % 40L == (index % 40).toLong()) trace!!.sample(helper.tick, npc)
            }
            if (helper.tick % 100L != 0L) return@onEachTick
            trace!!.flush()
            for (squad in replay.manager.all()) {
                val alive = members.filter { it.isAlive && it.squadId == squad.id }
                val armed = alive.filter { Ports.guns.roundsLeft(it.primaryGun()) > 0 && !it.busyWithRole() }
                val recent = armed.count { it.firedRecently(100) }
                Files.writeString(windows, "${helper.tick},${squad.name},${alive.size},${armed.size},$recent,${squad.tactics.plan?.pattern},${squad.tactics.plan?.phase}\n", APPEND)
                // Fix the criterion before the baseline: a sustained near-total standstill is a
                // failure, regardless of a few early shots or the final battle's casualty ratio.
                val contact = squad.tactics.snapshot?.contacts?.any { helper.level.getEntity(it.id)?.isAlive == true } == true
                if (helper.tick >= 400 && contact && armed.size >= 8 && recent < (armed.size + 3) / 4) {
                    stalledWindows[squad.id] = (stalledWindows[squad.id] ?: 0) + 1
                }
            }
            if (helper.tick == 1200L) {
                val stalled = replay.manager.all().filter { (stalledWindows[it.id] ?: 0) >= 3 }.map { it.name }
                Files.writeString(report.resolve("result.txt"), "stalledSquads=$stalled samplesAverageMs=${trace!!.averageMillis()}\n")
                helper.assertTrue(stalled.isEmpty(), "Saved battle has sustained mass inactivity: $stalled; see participation.csv/windows.csv")
                helper.succeed()
            }
        }
    }
}
