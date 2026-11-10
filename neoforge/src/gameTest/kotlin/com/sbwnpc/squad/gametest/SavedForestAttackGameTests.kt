package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.GroupOrders
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.UUID

/**
 * Playtest 10.11, snowy conifer forest (fixture `forest-attack-1011`, the world saved before it):
 * the owner's ATTACK of Charlie (recon), Delta (mortar) and Echo up the slope at 38,-18. Every
 * attacker stood about 10 s after the order (#312), and when the attack was called off the mortar
 * crew retreated onto its objective (#313). Registered only with gameTestReplay, like
 * [SavedBattleGameTests].
 */
@PrefixGameTestTemplate(false)
object SavedForestAttackGameTests {
    /** Brains at full rate, as with the owner looking on. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 4200, batch = "saved-forest-attack", skyAccess = true)
    fun savedForestAttackStartsAtOnceAndItsSupportFallsBack(helper: GameTestHelper) = forestAttack(helper, watched = true)

    /**
     * The same attack ordered from the map with no player near: the men's brains tick once in four
     * (the AI level of detail), and their post searches waited 28-48 ticks for the ray queue (#312).
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 4200, batch = "saved-forest-attack-far", skyAccess = true)
    fun savedForestAttackOrderedFromAfarStartsAtOnce(helper: GameTestHelper) = forestAttack(helper, watched = false)

    private val RECON = UUID.fromString("3da81287-a7eb-4679-9c60-0107acfd58a7")
    private val MORTAR = UUID.fromString("fea9177c-5496-498f-84b9-64a222f9d3ff")
    private val INFANTRY = UUID.fromString("fa4340dd-3352-43bb-87da-073f84c61414")
    private val ATTACKERS = listOf(RECON, MORTAR, INFANTRY)
    private val OBJECTIVE = Vec3(38.5, 121.0, -17.5)

    private fun forestAttack(helper: GameTestHelper, watched: Boolean) {
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(report)
        val replay = SavedBattleReplay(helper.level, Path.of(System.getProperty("sbwnpc.gameTestReplay")))
        restoreOnEnd(helper, replay)
        replay.restore()
        helper.assertTrue(ATTACKERS.all { replay.manager.get(it) != null }, "The fixture lacks Charlie, Delta or Echo")
        NpcEntity.fullRateBrains = watched
        val setOff = SetOff(replay.npcs.filter { it.squadId == RECON || it.squadId == INFANTRY })
        val crew = CrewRetreat(replay)
        val trace = report.resolve(if (watched) "attackers.csv" else "attackers-far.csv")
        Files.writeString(trace, "tick,squad,npc,order,x,y,z,job\n")
        helper.onEachTick {
            if (helper.tick == ORDER_TICK) GroupOrders.apply(helper.level, replay.manager, ATTACKERS, SquadOrder.ATTACK, 38, -18)
            setOff.check(helper)
            crew.check(helper)
            if (helper.tick % 20L == 0L) Files.writeString(trace, rows(helper, replay), APPEND)
            // Whether the fight goes badly enough to call the attack off differs run to run: the
            // retreat is checked when it does, and the call-off itself by the attack_arena scenes.
            if (helper.tick >= END_TICK) helper.succeed()
        }
    }

    /** Puts the log groups, brain rate and replayed world back however the scene ends. */
    private fun restoreOnEnd(helper: GameTestHelper, replay: SavedBattleReplay) {
        val previousGroups = DebugFlags.enabledGroups
        DebugFlags.enabledGroups = previousGroups + setOf(LogGroup.ORDER, LogGroup.FIRE_SUPPORT, LogGroup.PATH, LogGroup.VEHICLE)
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                DebugFlags.enabledGroups = previousGroups
                NpcEntity.fullRateBrains = false
                replay.close()
            }
        })
    }

    /** Every walking attacker steps off within [SET_OFF_TICKS] of the order (#312). */
    private class SetOff(private val walkers: List<NpcEntity>) {
        private val start = walkers.associateWith { it.position() }
        private val moved = HashSet<UUID>()

        fun check(helper: GameTestHelper) {
            walkers.filter { it.position().distanceTo(start.getValue(it)) >= SET_OFF_DISTANCE }.mapTo(moved) { it.uuid }
            if (helper.tick != ORDER_TICK + SET_OFF_TICKS) return
            val idle = walkers.filter { it.isAlive && it.uuid !in moved }.map { it.uuid.toString().take(8) }
            helper.assertTrue(idle.isEmpty(), "Attackers still on their start $SET_OFF_TICKS ticks after the order: $idle")
        }
    }

    /** Once Delta is told to retreat, none of its crew gets nearer the attack's objective (#313). */
    private class CrewRetreat(private val replay: SavedBattleReplay) {
        private var from: Map<UUID, Double>? = null

        fun check(helper: GameTestHelper) {
            val crew = replay.npcs.filter { it.isAlive && it.squadId == MORTAR }
            if (from == null && replay.manager.get(MORTAR)?.order == SquadOrder.RETREAT) from = crew.associate { it.uuid to it.position().distanceTo(OBJECTIVE) }
            val start = from ?: return
            for (man in crew) {
                val was = start[man.uuid] ?: continue
                val now = man.position().distanceTo(OBJECTIVE)
                helper.assertTrue(now >= was - CREW_DRIFT, "Mortarman ${man.uuid.toString().take(8)} retreated towards the attack's objective: ${"%.0f".format(was)} -> ${"%.0f".format(now)} blocks")
            }
        }
    }

    private fun rows(helper: GameTestHelper, replay: SavedBattleReplay) =
        replay.npcs.filter { it.isAlive && it.squadId in ATTACKERS }.joinToString("") { npc ->
            val squad = replay.manager.get(npc.squadId!!)
            "${helper.tick},${squad?.name},${npc.uuid.toString().take(8)},${squad?.order},${"%.1f".format(npc.x)},${"%.1f".format(npc.y)},${"%.1f".format(npc.z)},${NativeTactics.assignment(npc)?.job}\n"
        }

    private const val ORDER_TICK = 20L

    /** 5 s: the order to the first step, every attacker; in the playtest they stood 9-17 s. */
    private const val SET_OFF_TICKS = 100L
    private const val SET_OFF_DISTANCE = 3.0
    private const val CREW_DRIFT = 4.0
    private const val END_TICK = 4000L
}
