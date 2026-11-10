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
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.GameType
import net.neoforged.neoforge.common.util.FakePlayerFactory
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
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 400, batch = "saved-sniper-search")
    fun savedSniperFindsReachableFiringPost(helper: GameTestHelper) = sniperSearch(helper)

    private fun sniperSearch(helper: GameTestHelper) {
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(report)
        val replay = SavedBattleReplay(helper.level, Path.of(System.getProperty("sbwnpc.gameTestReplay")))
        replay.restore()
        val sniper = replay.npcs.single { it.uuid == UUID.fromString("f8104e12-9378-4094-8cc7-54145ce8df70") }
        val enemy = replay.npcs.single { it.uuid == UUID.fromString("13342545-2838-41d1-8a83-fc642abf202a") }
        // Coordinates sampled at ticks 609/612 of oct13-idle-transit-progress. Preserve native
        // blocks and saved equipment; remove other combatants to isolate search from AI churn.
        replay.entities.filter { it !== sniper && it !== enemy }.forEach(Entity::discard)
        sniper.isNoAi = true
        enemy.isNoAi = true
        sniper.moveTo(-413.4751351142926, 75.0, -252.49745925857366)
        enemy.moveTo(-469.4374784844925, 72.0, -236.53581311422343)
        BrainUtils.setTargetOfEntity(sniper, enemy)
        val task = TacticalTask(TacticalJob.COVER, sniper.position(), enemy.position(), 1).apply {
            localFirePost = sniper.position()
            expandedFireSearch = true
        }
        val trace = report.resolve("sniper-search.csv")
        Files.writeString(trace, "tick,outcome,probe,probes,scored,rejections,position\n")
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                FiringSpots.release(sniper.uuid)
                replay.close()
            }
        })
        helper.onEachTick {
            sniper.setOnGround(true)
            val view = TacticalSnapshot(
                helper.level.gameTime,
                SquadOrder.ATTACK,
                1,
                sniper.position(),
                enemy.position(),
                listOf(TacticalMember(sniper.uuid, sniper.position(), sniper.npcClass)),
                listOf(TacticalContact(enemy.uuid, enemy.position(), helper.level.gameTime)),
            )
            val result = TacticalPositions.find(sniper, task, view)
            if (helper.tick % 10L == 0L || result.outcome != TacticalPositions.Outcome.DEFERRED) {
                Files.writeString(trace, "${helper.tick},${result.outcome},${task.search?.probeIndex},${task.search?.probes?.size},${task.search?.scored?.size},\"${task.search?.rejections}\",\"${result.position}\"\n", APPEND)
            }
            if (result.outcome != TacticalPositions.Outcome.DEFERRED) {
                helper.assertTrue(result.outcome == TacticalPositions.Outcome.FOUND, "Static saved sniper search failed: $result; see sniper-search.csv")
                helper.assertTrue(TacticalPositions.firingReady(sniper, task, result.position!!), "Selected post cannot shoot the fixed enemy")
                helper.succeed()
            }
        }
    }

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

    /**
     * Playtest 09.11, broken ground (fixture `recon-terrain-0911`): on its way in the recon squad saw
     * a sentry 63 blocks out, short of its ring; every spot nearer the ring was then too close to an
     * enemy, and it stood blind, never posting, never reporting, its infantry waiting on. Seeing the
     * enemy, it must take its post.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 1200, batch = "saved-recon-terrain", skyAccess = true)
    fun savedReconSeeingASentryTakesItsPost(helper: GameTestHelper) {
        val replay = SavedBattleReplay(helper.level, Path.of(System.getProperty("sbwnpc.gameTestReplay")))
        replay.restore()
        val recon = UUID.fromString("6776c410-c878-4dcb-8dcb-cac8303db472")
        val previousGroups = DebugFlags.enabledGroups
        DebugFlags.enabledGroups = previousGroups + LogGroup.ORDER
        helper.assertTrue(replay.manager.get(recon)?.order == SquadOrder.ATTACK, "The fixture's recon squad is not on its ATTACK order")
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = cleanup()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = cleanup()
            private fun cleanup() {
                DebugFlags.enabledGroups = previousGroups
                replay.close()
            }
        })
        helper.onEachTick {
            val stage = com.sbwnpc.squad.ai.integration.NativeTactics.reconStage(helper.level, recon)
            if (stage == com.sbwnpc.squad.ai.tactics.ReconStage.WATCH || stage == com.sbwnpc.squad.ai.tactics.ReconStage.REPORTED) helper.succeed()
            if (helper.tick >= 1150L) helper.fail("The recon squad never took its post: stage $stage")
        }
    }

    /**
     * Playtest 10.11, snowy conifer forest (fixture `forest-attack-1011`, the world saved before it):
     * the owner's ATTACK of Charlie (recon), Delta (mortar) and Echo up the slope at 38,-18. Every
     * attacker stood about 10 s after the order (#312), and when the attack was called off the mortar
     * crew retreated onto its objective (#313). Brains at full rate, as with the owner looking on.
     */
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

    private fun forestAttack(helper: GameTestHelper, watched: Boolean) {
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        Files.createDirectories(report)
        val replay = SavedBattleReplay(helper.level, Path.of(System.getProperty("sbwnpc.gameTestReplay")))
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
        replay.restore()
        val recon = UUID.fromString("3da81287-a7eb-4679-9c60-0107acfd58a7")
        val mortar = UUID.fromString("fea9177c-5496-498f-84b9-64a222f9d3ff")
        val infantry = UUID.fromString("fa4340dd-3352-43bb-87da-073f84c61414")
        val attackers = listOf(recon, mortar, infantry)
        helper.assertTrue(attackers.all { replay.manager.get(it) != null }, "The fixture lacks Charlie, Delta or Echo")
        val objective = net.minecraft.world.phys.Vec3(38.5, 121.0, -17.5)
        val walkers = replay.npcs.filter { it.squadId == recon || it.squadId == infantry }
        val start = walkers.associateWith { it.position() }
        NpcEntity.fullRateBrains = watched
        val firstMove = HashMap<UUID, Long>()
        var retreatFrom: Map<UUID, Double>? = null
        val trace = report.resolve("attackers.csv")
        Files.writeString(trace, "tick,squad,npc,order,x,y,z,job\n")
        helper.onEachTick {
            if (helper.tick == ORDER_TICK) com.sbwnpc.squad.squad.GroupOrders.apply(helper.level, replay.manager, attackers, SquadOrder.ATTACK, 38, -18)
            for (npc in walkers) {
                if (npc.uuid !in firstMove && npc.position().distanceTo(start.getValue(npc)) >= SET_OFF_DISTANCE) firstMove[npc.uuid] = helper.tick
            }
            if (helper.tick == ORDER_TICK + SET_OFF_TICKS) {
                val idle = walkers.filter { it.isAlive && it.uuid !in firstMove }.map { it.uuid.toString().take(8) }
                helper.assertTrue(idle.isEmpty(), "Attackers still on their start ${SET_OFF_TICKS} ticks after the order: $idle")
            }
            val crew = replay.npcs.filter { it.isAlive && it.squadId == mortar }
            if (retreatFrom == null && replay.manager.get(mortar)?.order == SquadOrder.RETREAT) retreatFrom = crew.associate { it.uuid to it.position().distanceTo(objective) }
            retreatFrom?.let { from ->
                crew.forEach { man ->
                    val was = from[man.uuid] ?: return@forEach
                    helper.assertTrue(man.position().distanceTo(objective) >= was - CREW_DRIFT, "Mortarman ${man.uuid.toString().take(8)} retreated towards the attack's objective: ${"%.0f".format(was)} -> ${"%.0f".format(man.position().distanceTo(objective))} blocks")
                }
            }
            if (helper.tick % 20L == 0L) {
                val rows = replay.npcs.filter { it.isAlive && it.squadId in attackers }.joinToString("") { npc ->
                    "${helper.tick},${replay.manager.get(npc.squadId!!)?.name},${npc.uuid.toString().take(8)},${replay.manager.get(npc.squadId!!)?.order},${"%.1f".format(npc.x)},${"%.1f".format(npc.y)},${"%.1f".format(npc.z)},${com.sbwnpc.squad.ai.integration.NativeTactics.assignment(npc)?.job}\n"
                }
                Files.writeString(trace, rows, APPEND)
            }
            if (helper.tick >= END_TICK) {
                helper.assertTrue(retreatFrom != null, "The attack was never called off in this run, so the support's retreat went unchecked")
                helper.succeed()
            }
        }
    }

    private const val ORDER_TICK = 20L

    /** 5 s: the order to the first step, every attacker; in the playtest they stood 9-17 s. */
    private const val SET_OFF_TICKS = 100L
    private const val SET_OFF_DISTANCE = 3.0
    private const val CREW_DRIFT = 4.0
    private const val END_TICK = 4000L
}
