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
import com.sbwnpc.squad.combat.tactics.TacticalPattern
import com.sbwnpc.squad.combat.tactics.TacticalPlan
import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.combat.tactics.TacticalSnapshot
import com.sbwnpc.squad.combat.tactics.TacticalTask
import com.sbwnpc.squad.combat.tactics.operation.AttackAssignment
import com.sbwnpc.squad.combat.tactics.operation.AttackCorridor
import com.sbwnpc.squad.combat.tactics.operation.AttackRole
import com.sbwnpc.squad.combat.tactics.operation.AttackStage
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
    /** Static search isolation from the failed replay; not a replacement for the battle criterion. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 400, batch = "saved-sniper-search")
    fun savedSniperFindsReachableFiringPost(helper: GameTestHelper) = sniperSearch(helper, revisionChanges = false)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 80, batch = "saved-sniper-revisions")
    fun savedSniperSearchSurvivesHoldingLaneRevisions(helper: GameTestHelper) = sniperSearch(helper, revisionChanges = true)

    private fun sniperSearch(helper: GameTestHelper, revisionChanges: Boolean) {
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
        val plan = TacticalPlan(1, TacticalPattern.COORDINATED_ATTACK, enemy.position(), helper.level.gameTime, 1)
        val owner = SquadTacticalState()
        val assignment = AttackAssignment(
            operation = 1,
            role = AttackRole.CENTER,
            stage = AttackStage.ENGAGING,
            revision = 0,
            waypoint = sniper.position(),
            sector = sniper.position(),
            facing = enemy.position().subtract(sniper.position()).multiply(1.0, 0.0, 1.0).normalize(),
            corridor = AttackCorridor(listOf(sniper.position()), 32.0),
            deployed = true,
            blocked = true,
            externalCover = false,
            slots = mapOf(sniper.uuid to 0.0),
        )
        var task = TacticalTask(TacticalJob.COVER, sniper.position(), enemy.position(), 1).apply {
            localFirePost = sniper.position()
            expandedFireSearch = true
        }
        val trace = report.resolve(if (revisionChanges) "sniper-search-revisions.csv" else "sniper-search.csv")
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
                attackAssignment = assignment.copy(revision = (helper.tick / 2).toInt()),
            )
            if (revisionChanges) {
                plan.tick(sniper.currentSquad()!!.id, owner, view)
                task = plan.tasks.getValue(sniper.uuid)
                if (helper.tick == 0L) {
                    task.localFirePost = sniper.position()
                    task.expandedFireSearch = true
                }
            }
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
        val sniper = members.firstOrNull { it.uuid == UUID.fromString("f8104e12-9378-4094-8cc7-54145ce8df70") }
        val sniperTrace = report.resolve("sniper-lifecycle.csv")
        Files.writeString(sniperTrace, "tick,task,job,anchor,search,probe,probes,aimShift,restart,target,position,transit,contacts,phase\n")
        var lastTask: TacticalTask? = null
        var lastSearch: Any? = null
        val stalledWindows = mutableMapOf<UUID, Int>()
        helper.onEachTick {
            val squad = sniper?.currentSquad()
            val task = squad?.tactics?.plan?.tasks?.get(sniper?.uuid)
            val search = task?.search
            if (helper.tick % 10L == 0L || task !== lastTask || search !== lastSearch) {
                val aim = task?.let { TacticalAim.resolve(sniper!!, it) }
                Files.writeString(sniperTrace, "${helper.tick},${task?.let { System.identityHashCode(it) }},${task?.job},\"${task?.anchor}\",${search?.let { System.identityHashCode(it) }},${search?.probeIndex},${search?.probes?.size},${search?.aim?.eye?.let { old -> aim?.eye?.distanceTo(old) }},${search?.needsRestart(sniper!!.position(), aim)},${sniper?.target?.uuid},\"${task?.position}\",\"${task?.fireTransit}\",\"${squad?.tactics?.snapshot?.contacts?.map { it.id to it.position }}\",${squad?.tactics?.plan?.phase}\n", APPEND)
            }
            lastTask = task
            lastSearch = search
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
                val failed = replay.manager.all().filter { it.id in checked && (stalledWindows[it.id] ?: 0) >= 3 }.map { it.name }
                Files.writeString(report.resolve("result.txt"), "stalledSquads=$stalled failedScope=$failed attackersOnly=$attackersOnly samplesAverageMs=${trace!!.averageMillis()}\n")
                helper.assertTrue(failed.isEmpty(), "Saved battle has sustained mass inactivity in requested scope: $failed; all squads: $stalled; see participation.csv/windows.csv")
                helper.succeed()
            }
        }
    }
}
