package com.sbwnpc.squad.gametest

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import com.mojang.logging.LogUtils
import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.ai.tactics.AttackPhase
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModEntities
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.SquadPreset
import com.sbwnpc.squad.squad.GroupOrders
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestGenerator
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.gametest.framework.TestFunction
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Scripted battles on terrain imported from a local save: the squads of a scenario in
 * `battles/scenarios.json` deploy at the points the owner fought from, defenders with their AI
 * and weapons, and the run fails on the figures a playtest log is read for (#63).
 *
 * Registered only with `-PgameTestBattleSaves=<saves folder>`; the save is never written, its
 * region files are copied into the report first. `-PgameTestBattles=a,b` runs only those.
 */
object RealTerrainBattleGameTests {
    private val LOGGER = LogUtils.getLogger()

    /** Thresholds a scenario may override under `expect`. */
    private data class Expect(val maxFriendlyShare: Double, val maxChurnPerManMinute: Double, val assault: Boolean, val taken: Boolean)

    private data class Group(val preset: SquadPreset, val x: Int, val z: Int, val afterTicks: Int)

    private data class Scenario(val name: String, val world: String, val objective: BlockPos, val ticks: Int, val attackers: List<Group>, val defenders: List<Group>, val expect: Expect)

    @JvmStatic
    @GameTestGenerator
    fun realTerrainBattles(): Collection<TestFunction> {
        val wanted = System.getProperty("sbwnpc.gameTestBattles", "").split(',').filter { it.isNotBlank() }.toSet()
        return scenarios().filter { wanted.isEmpty() || it.name in wanted }.map { scenario ->
            // One batch each: every scenario fights at the save's own coordinates.
            TestFunction("battle-${scenario.name}", "battle_${scenario.name}", "sbwnpc:movement_arena", Rotation.NONE, scenario.ticks + 200, 0, true, false, 1, 1, true) { helper -> run(helper, scenario) }
        }
    }

    private fun scenarios(): List<Scenario> {
        val text = RealTerrainBattleGameTests::class.java.getResourceAsStream("/battles/scenarios.json")!!.use { String(it.readAllBytes()) }
        return JsonParser.parseString(text).asJsonObject.entrySet().map { (name, element) ->
            val json = element.asJsonObject
            val objective = json.getAsJsonArray("objective").map { it.asInt }
            val expect = json.getAsJsonObject("expect") ?: JsonObject()
            Scenario(
                name,
                json.get("world").asString,
                BlockPos(objective[0], objective[1], objective[2]),
                json.get("ticks").asInt,
                groups(json, "attackers"),
                groups(json, "defenders"),
                Expect(
                    expect.get("maxFriendlyShare")?.asDouble ?: 0.35,
                    expect.get("maxChurnPerManMinute")?.asDouble ?: 2.0,
                    expect.get("assault")?.asBoolean ?: true,
                    expect.get("taken")?.asBoolean ?: true,
                ),
            )
        }
    }

    private fun groups(json: JsonObject, key: String) =
        json.getAsJsonArray(key).map { entry ->
            val group = entry.asJsonObject
            val at = group.getAsJsonArray("at").map { it.asInt }
            Group(SquadPreset.valueOf(group.get("preset").asString), at[0], at[1], group.get("afterTicks")?.asInt ?: 0)
        }

    private fun run(helper: GameTestHelper, scenario: Scenario) {
        val level = helper.level
        val report = Path.of(System.getProperty("sbwnpc.gameTestReports")).resolve("battle-${scenario.name}")
        Files.createDirectories(report)
        val radius = (scenario.attackers + scenario.defenders).maxOf { kotlin.math.hypot((it.x - scenario.objective.x).toDouble(), (it.z - scenario.objective.z).toDouble()) }.toInt() + 64
        val replay = SavedBattleReplay(level, copyRegions(Path.of(System.getProperty("sbwnpc.gameTestBattleSaves")).resolve(scenario.world), report.resolve("fixture"), scenario.objective, radius))
        replay.restoreTerrain(scenario.objective, radius)
        val manager = SquadManager.get(level)
        val previousGroups = DebugFlags.enabledGroups
        DebugFlags.enabledGroups = previousGroups + setOf(LogGroup.ORDER, LogGroup.FIRE)
        val pacer = CombatTickPacer()
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
        val npcs = mutableListOf<NpcEntity>()
        val squads = mutableListOf<UUID>()
        val observer = FakePlayerFactory.get(level, GameProfile(UUID.randomUUID(), "battle-observer")).apply {
            gameMode.changeGameModeForPlayer(GameType.CREATIVE)
            moveTo(scenario.objective.x + 0.5, scenario.objective.y + 32.0, scenario.objective.z + 0.5, 0f, 0f)
            setNoGravity(true)
            level.addNewPlayer(this)
        }
        var closed = false
        fun cleanup() {
            if (closed) return
            closed = true
            squads.forEach { manager.deleteSquad(level, it) }
            npcs.forEach(Entity::discard)
            level.removePlayerImmediately(observer, Entity.RemovalReason.DISCARDED)
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

        fun deploy(group: Group, faction: SquadFaction): Pair<UUID, List<NpcEntity>> {
            val members = group.preset.composition.mapIndexed { index, role ->
                val offset = SquadFormation.gridOffset(index, group.preset.composition.size)
                val x = group.x + 0.5 + offset.x
                val z = group.z + 0.5 + offset.z
                ModEntities.NPC.get().create(level)!!.apply {
                    moveTo(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt()).toDouble(), z, 0f, 0f)
                    npcClass = role
                    spawnFaction = faction
                    finalizeSpawn(level, level.getCurrentDifficultyAt(blockPosition()), MobSpawnType.COMMAND, null)
                    SquadTeams.assign(this, faction)
                    check(level.addFreshEntity(this)) { "Battle NPC could not be added" }
                }
            }
            npcs += members
            // One commander per side: a group order refuses squads of different owners.
            val squad = manager.create(level, UUID.nameUUIDFromBytes("battle:$faction".toByteArray()), faction, members.map { it.uuid })
            squads += squad.id
            return squad.id to members
        }

        val defence = scenario.defenders.map { deploy(it, SquadFaction.COW) }
        defence.forEach { (id, _) -> manager.issueOrderAt(level, id, SquadOrder.DEFEND, scenario.objective) }
        val attack = mutableListOf<Pair<UUID, List<NpcEntity>>>()
        fun send(groups: List<Group>) {
            val deployed = groups.map { deploy(it, SquadFaction.PIG) }
            attack += deployed
            val replies = GroupOrders.apply(level, manager, deployed.map { it.first }, SquadOrder.ATTACK, scenario.objective.x, scenario.objective.z)
            helper.assertTrue(replies.none { "cancelled" in it || "unchanged" in it }, "The attack order was refused: $replies")
        }
        // The defence settles in before the attack is ordered, as it had in the playtest.
        helper.runAtTickTime(100) { send(scenario.attackers.filter { it.afterTicks == 0 }) }
        scenario.attackers.filter { it.afterTicks > 0 }.groupBy { it.afterTicks }.forEach { (after, groups) -> helper.runAtTickTime(100L + after) { send(groups) } }

        val marker = "battle-start ${scenario.name} ${UUID.randomUUID()}"
        LOGGER.info(marker)
        val phases = LinkedHashMap<AttackPhase, Int>()
        val timeline = report.resolve("timeline.csv")
        Files.writeString(timeline, "tick,attackersAlive,defendersAlive,phase\n")
        helper.onEachTick {
            if (helper.tick % 20L != 0L) return@onEachTick
            val phase = attack.flatMap { it.second }.firstOrNull { it.isAlive }?.let(NativeTactics::attackPhase)
            if (phase != null) phases.putIfAbsent(phase, helper.tick.toInt())
            Files.writeString(timeline, "${helper.tick},${attack.sumOf { (_, m) -> m.count { it.isAlive } }},${defence.sumOf { (_, m) -> m.count { it.isAlive } }},$phase\n", java.nio.file.StandardOpenOption.APPEND)
        }
        helper.runAtTickTime(scenario.ticks.toLong()) {
            val attackers = attack.flatMap { it.second }
            val metrics = BattleLogMetrics(attack.mapTo(HashSet()) { it.first }, attackers.mapTo(HashSet()) { it.uuid })
            Files.readAllLines(Path.of("logs/latest.log")).dropWhile { marker !in it }.forEach(metrics::accept)
            val manMinutes = attackers.size * (scenario.ticks - 100) / 1200.0
            val churn = metrics.intermediateReached / manMinutes
            val defendersAlive = defence.sumOf { (_, m) -> m.count { it.isAlive } }
            val taken = phases.containsKey(AttackPhase.CLEAR) || defendersAlive == 0
            val summary = buildString {
                appendLine("scenario=${scenario.name}")
                appendLine("attackers=${attackers.size} alive=${attackers.count { it.isAlive }}")
                appendLine("defenders=${defence.sumOf { it.second.size }} alive=$defendersAlive")
                appendLine("phases=${phases.entries.joinToString { "${it.key}@${it.value}" }}")
                appendLine("taken=$taken")
                appendLine("fireGates=${metrics.fireGates.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }}")
                appendLine("friendlyShare=${"%.2f".format(metrics.share("FRIENDLY_OR_VEHICLE"))} readyShare=${"%.2f".format(metrics.share("READY"))}")
                appendLine("intermediateReachedPerManMinute=${"%.2f".format(churn)} exhaustedPerManMinute=${"%.2f".format(metrics.exhaustedSearches / manMinutes)}")
            }
            Files.writeString(report.resolve("summary.txt"), summary)
            LOGGER.info("[battle] {}", summary.replace('\n', ' '))
            val expect = scenario.expect
            helper.assertTrue(metrics.share("FRIENDLY_OR_VEHICLE") <= expect.maxFriendlyShare, "Friendly or vehicle in the lane held over ${expect.maxFriendlyShare} of shots; see summary.txt")
            helper.assertTrue(churn <= expect.maxChurnPerManMinute, "Movers ran between posts: ${"%.2f".format(churn)} intermediate posts per man per minute; see summary.txt")
            helper.assertTrue(!expect.assault || phases.containsKey(AttackPhase.ASSAULT), "The attack never assaulted; see timeline.csv")
            helper.assertTrue(!expect.taken || taken, "The objective was not taken; see timeline.csv")
            helper.succeed()
        }
    }

    /** Copies the region files covering [radius] round [center] so the save itself is never opened for writing. */
    private fun copyRegions(save: Path, fixture: Path, center: BlockPos, radius: Int): Path {
        val target = fixture.resolve("region")
        Files.createDirectories(target)
        val regions = ((center.x - radius) shr 9)..((center.x + radius) shr 9)
        val rows = ((center.z - radius) shr 9)..((center.z + radius) shr 9)
        for (x in regions) {
            for (z in rows) {
                val file = save.resolve("region/r.$x.$z.mca")
                if (Files.exists(file)) Files.copy(file, target.resolve(file.fileName), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return fixture
    }
}
