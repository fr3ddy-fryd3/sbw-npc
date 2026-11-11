package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.ai.tactics.ReconStage
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.GroupOrders
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.world.level.block.Blocks
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

    /**
     * Playtest 11.11, the same men on the same spots: the owner's ATTACK on 31,-15 at night. A scout's
     * first sighting reached Echo while the operation waited for its reports; Echo fired at a man none
     * of them could see, Bravo found them by their fire, and the wait ended with Echo under fire after
     * one report of three. Until the wait is over Echo holds its fire.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 3200, batch = "saved-forest-night", skyAccess = true)
    fun savedNightAttackInfantryHoldsFireForTheReports(helper: GameTestHelper) {
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(report)
        val replay = SavedBattleReplay(helper.level, Path.of(System.getProperty("sbwnpc.gameTestReplay")))
        val dayTime = helper.level.dayTime
        restoreOnEnd(helper, replay) { helper.level.dayTime = dayTime }
        replay.restore()
        helper.assertTrue(ATTACKERS.all { replay.manager.get(it) != null }, "The fixture lacks Charlie, Delta or Echo")
        val hold = FireHold(replay.npcs.filter { it.squadId == INFANTRY })
        NpcEntity.fullRateBrains = true
        helper.level.dayTime = MIDNIGHT
        val trace = report.resolve("attackers-night.csv")
        Files.writeString(trace, "tick,squad,npc,order,x,y,z,job\n")
        helper.onEachTick {
            val medic = replay.npcs.first { it.uuid == SPOTTED_MEDIC }
            if (helper.tick == ORDER_TICK) {
                // The fixture is the world of 10.11; on 11.11 Bravo had patrolled for 14 minutes first, and
                // its medic stood here, south of the objective, from the scouts' first sighting until he
                // was fired on. His patrol would walk him off: he stays until the wait is over.
                medic.teleportTo(29.5, 112.0, 20.5)
                medic.isNoAi = true
                GroupOrders.apply(helper.level, replay.manager, ATTACKERS, SquadOrder.ATTACK, 31, -15)
            }
            if (helper.tick % 20L == 0L) Files.writeString(trace, rows(helper, replay), APPEND)
            if (hold.check(helper)) medic.isNoAi = false
            if (helper.tick >= NIGHT_END_TICK) {
                // Without an enemy in Echo's sights during the wait the scene did not test the hold.
                helper.assertTrue(hold.targeted, "Echo never had an enemy in its sights while it waited (wait ended at ${hold.ended})")
                helper.succeed()
            }
        }
    }

    /**
     * Playtest 11.11, the owner's save after it (fixture `forest-stuck-1111`, Gabriel put back on his ledge): Charlie's scout Gabriel
     * Anderson had retreated onto a one-block calcite ledge, walls on two sides and drops of 5-6 blocks
     * on the others, and stood there for 90 minutes with a fall limit of 3. Aaron Anderson reached the
     * staging point, but the stage counted Gabriel too: Charlie never stalked and Echo waited for
     * reports that could not come. Boxed in, Gabriel takes the bounded drop off the ledge.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 3200, batch = "saved-stranded-scout", skyAccess = true)
    fun savedStrandedScoutDropsOffHisLedge(helper: GameTestHelper) = strandedScout(helper, walledIn = false)

    /**
     * The same save with Gabriel's ledge walled in, so no drop frees him: Charlie stalks on Aaron's
     * position alone.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 3200, batch = "saved-stranded-scout-walled", skyAccess = true)
    fun savedStrandedScoutDoesNotHoldHisSquad(helper: GameTestHelper) = strandedScout(helper, walledIn = true)

    private val RECON = UUID.fromString("3da81287-a7eb-4679-9c60-0107acfd58a7")
    private val MORTAR = UUID.fromString("fea9177c-5496-498f-84b9-64a222f9d3ff")
    private val INFANTRY = UUID.fromString("fa4340dd-3352-43bb-87da-073f84c61414")
    private val ATTACKERS = listOf(RECON, MORTAR, INFANTRY)
    private val OBJECTIVE = Vec3(38.5, 121.0, -17.5)

    /** Bravo's medic, the first man Charlie's scouts saw on 11.11. */
    private val SPOTTED_MEDIC = UUID.fromString("89477ccd-d524-4208-a24b-69823d5d8558")

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

    private fun strandedScout(helper: GameTestHelper, walledIn: Boolean) {
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(report)
        val replay = SavedBattleReplay(helper.level, Path.of(System.getProperty("sbwnpc.gameTestReplay")))
        restoreOnEnd(helper, replay)
        replay.restore()
        helper.assertTrue(ATTACKERS.all { replay.manager.get(it) != null }, "The fixture lacks Charlie, Delta or Echo")
        // The save is from after the owner dug Gabriel out at 13:13: the two calcite blocks he broke go
        // back, and both scouts stand where they stood at 13:06.
        LEDGE_WALL.forEach { helper.level.setBlockAndUpdate(it, Blocks.CALCITE.defaultBlockState()) }
        val gabriel = replay.npcs.first { it.uuid == STRANDED_SCOUT }
        gabriel.teleportTo(-66.7, 99.0, 150.3)
        replay.npcs.first { it.uuid == STAGED_SCOUT }.teleportTo(16.3, 101.0, 76.6)
        val ledge = gabriel.position()
        if (walledIn) {
            val feet = gabriel.blockPosition()
            for (dx in -1..1) for (dz in -1..1) for (dy in 0..2) {
                val pos = feet.offset(dx, dy, dz)
                if ((dx != 0 || dz != 0 || dy == 2) && helper.level.getBlockState(pos).isAir) helper.level.setBlockAndUpdate(pos, Blocks.BARRIER.defaultBlockState())
            }
        }
        NpcEntity.fullRateBrains = true
        val trace = report.resolve(if (walledIn) "stranded-walled.csv" else "stranded.csv")
        Files.writeString(trace, "tick,squad,npc,order,x,y,z,job\n")
        var stalked: Long? = null
        var offLedge: Long? = null
        helper.onEachTick {
            if (helper.tick == ORDER_TICK) GroupOrders.apply(helper.level, replay.manager, ATTACKERS, SquadOrder.ATTACK, 31, -11)
            if (helper.tick % 20L == 0L) Files.writeString(trace, rows(helper, replay), APPEND)
            val stage = NativeTactics.reconStage(helper.level, RECON)
            if (stalked == null && helper.tick > ORDER_TICK && stage != null && stage != ReconStage.STAGING) stalked = helper.tick
            if (offLedge == null && gabriel.position().distanceTo(ledge) > OFF_LEDGE_DISTANCE) offLedge = helper.tick
            if (helper.tick == ORDER_TICK + STRANDED_TICKS) {
                if (!walledIn) helper.assertTrue(offLedge != null, "Gabriel still on his ledge at ${gabriel.blockPosition()}")
                helper.assertTrue(stalked != null, "Charlie still in $stage, Gabriel at ${gabriel.blockPosition()}")
            }
            if (helper.tick >= STRANDED_END_TICK) helper.succeed()
        }
    }

    /** Puts the log groups, brain rate and replayed world back however the scene ends. */
    private fun restoreOnEnd(helper: GameTestHelper, replay: SavedBattleReplay, also: () -> Unit = {}) {
        val previousGroups = DebugFlags.enabledGroups
        DebugFlags.enabledGroups = previousGroups + setOf(LogGroup.ORDER, LogGroup.FIRE_SUPPORT, LogGroup.PATH, LogGroup.VEHICLE, LogGroup.HEARING, LogGroup.SPOTTING, LogGroup.STUCK)
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                DebugFlags.enabledGroups = previousGroups
                NpcEntity.fullRateBrains = false
                replay.close()
                also()
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

    /**
     * No man of [infantry] fires while its operation waits for the recon's reports, and at least one has
     * an enemy in his sights then, or the scene did not test the hold.
     */
    private class FireHold(private val infantry: List<NpcEntity>) {
        private var waited = false

        /** Whether a man had a target while the operation waited. */
        var targeted = false
            private set

        /** The tick the wait ended, once it has. */
        var ended: Long? = null
            private set

        /** Checks this tick; true on the tick the wait ends. */
        fun check(helper: GameTestHelper): Boolean {
            val waiting = helper.tick > ORDER_TICK && NativeTactics.holdsReconSightings(helper.level, INFANTRY)
            val living = infantry.filter { it.isAlive }
            if (waiting) {
                // A shot by the man's own record: not every gun's round joins the world as an entity.
                val shooter = living.firstOrNull { it.firedRecently(1) }
                if (shooter != null) helper.fail("Echo fired while its operation still waited for the recon's reports: ${shooter.uuid.toString().take(8)} at tick ${helper.tick}")
                waited = true
                if (living.any { it.target != null }) targeted = true
                return false
            }
            if (!waited || ended != null) return false
            ended = helper.tick
            return true
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

    /** Charlie's scout on the ledge at -67,99,150. */
    private val STRANDED_SCOUT = UUID.fromString("c570027c-eda4-4b38-ab3d-30521bed4efa")

    /** Aaron Anderson, Charlie's scout at the staging point. */
    private val STAGED_SCOUT = UUID.fromString("a54a15f5-2201-4511-8ecd-56b57ccf4b8a")
    private val LEDGE_WALL = listOf(BlockPos(-67, 100, 149), BlockPos(-67, 101, 149))
    private const val OFF_LEDGE_DISTANCE = 4.0

    /** 30 s: six recoveries take some 15 s, the drop and the stage's next pass the rest. */
    private const val STRANDED_TICKS = 600L

    /** The attack runs on for its trace. */
    private const val STRANDED_END_TICK = 3000L

    private const val ORDER_TICK = 20L

    /** 5 s: the order to the first step, every attacker; in the playtest they stood 9-17 s. */
    private const val SET_OFF_TICKS = 100L
    private const val SET_OFF_DISTANCE = 3.0
    private const val CREW_DRIFT = 4.0
    private const val END_TICK = 4000L

    /** The owner's night (`/time set night` and some minutes on): moonlight only. */
    private const val MIDNIGHT = 18000L

    /** The playtest's wait ran 47 s from the order; this leaves it room to end by the reports. */
    private const val NIGHT_END_TICK = 3000L
}
