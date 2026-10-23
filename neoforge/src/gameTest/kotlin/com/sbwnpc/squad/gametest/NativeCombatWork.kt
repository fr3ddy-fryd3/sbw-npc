package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.tactics.TacticalPositions
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.function.Consumer

/** Actual native searches, distinct from navigation requests and resumed position cursors. */
internal class NativeCombatWork(private val helper: GameTestHelper, members: List<NpcEntity>) : AutoCloseable {
    private data class ActorWork(
        var calls: Int = 0,
        var searches: Int = 0,
        var queries: Int = 0,
        var cursors: Int = 0,
        var duplicateSameTick: Int = 0,
        var maximumTickSearches: Int = 0,
        var tick: Long = Long.MIN_VALUE,
        var searchesThisTick: Int = 0,
        val searchesByTick: MutableMap<Long, Int> = mutableMapOf(),
        val requestsThisTick: MutableSet<Set<BlockPos>> = mutableSetOf(),
    )
    private val actors = members.associate { it.uuid to ActorWork() }
    private val navigators = members.map { it.navigation as VehicleAwareNavigation }
    private val tickTimes = mutableListOf<Long>()
    private val pathTimes = mutableListOf<Long>()
    private var started = 0L
    private var closed = false
    private val pre = Consumer<ServerTickEvent.Pre> { started = System.nanoTime() }
    private val post = Consumer<ServerTickEvent.Post> {
        // Scene registered its wall-clock gun pacer before this listener. Sleep is excluded.
        if (helper.tick >= 200 && started != 0L) tickTimes += System.nanoTime() - started
    }
    private val damageTrace = Files.newBufferedWriter(Path.of(System.getProperty("sbwnpc.gameTestReports"), "operation-damage.csv"))
    private val damage = Consumer<LivingDamageEvent.Post> { event ->
        if (event.entity.uuid in actors) {
            val shooter = event.source.entity as? NpcEntity
            val weapon = shooter?.mainHandItem?.item?.let { net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it) }
            damageTrace.appendLine("${helper.tick},${event.entity.uuid},${shooter?.uuid},${event.source.directEntity?.uuid},$weapon,${event.newDamage},${event.entity.health},${shooter?.spawnFaction},${(event.entity as? NpcEntity)?.spawnFaction}")
            damageTrace.flush()
        }
    }
    init {
        damageTrace.appendLine("tick,victim,shooter,projectile,weapon,damage,health,shooterFaction,victimFaction")
        NeoForge.EVENT_BUS.addListener(damage)
        require(TacticalPositions.searchWorkObserver == null && navigators.all { it.pathWorkObserver == null })
        members.forEach { npc ->
            (npc.navigation as VehicleAwareNavigation).pathWorkObserver = { targets, nanos, native ->
                val row = actors.getValue(npc.uuid)
                row.calls++
                if (native) {
                    row.searches++
                    pathTimes += nanos
                    if (row.tick != helper.tick) {
                        row.tick = helper.tick
                        row.searchesThisTick = 0
                        row.requestsThisTick.clear()
                    }
                    row.searchesByTick[helper.tick] = (row.searchesByTick[helper.tick] ?: 0) + 1
                    row.searchesThisTick++
                    row.maximumTickSearches = maxOf(row.maximumTickSearches, row.searchesThisTick)
                    if (!row.requestsThisTick.add(targets.toSet())) row.duplicateSameTick++
                }
            }
        }
        TacticalPositions.searchWorkObserver = { npc, fresh ->
            actors[npc.uuid]?.let { if (fresh) it.cursors++ else it.queries++ }
        }
        NeoForge.EVENT_BUS.addListener(pre)
        NeoForge.EVENT_BUS.addListener(post)
    }

    /** [enforce] false only records the numbers: a fight is measured, not held to the movement scene's search limits. */
    fun report(enforce: Boolean = true) {
        val directory = Path.of(System.getProperty("sbwnpc.gameTestReports"))
        val duration = helper.tick.coerceAtLeast(1).toDouble()
        fun maximumSecond(row: ActorWork): Int =
            row.searchesByTick.keys.maxOfOrNull { start ->
                (start until start + 20).sumOf { row.searchesByTick[it] ?: 0 }
            } ?: 0
        Files.writeString(
            directory.resolve("operation-work.csv"),
            buildString {
                appendLine("npc,pathCalls,nativeSearches,nativeSearchesPerSimulatedSecond,maxNativePerTick,maxNativePer20Ticks,sameRequestSameTick,positionCalls,newPositionCursors")
                actors.forEach { (id: UUID, row) -> appendLine("$id,${row.calls},${row.searches},${row.searches * 20 / duration},${row.maximumTickSearches},${maximumSecond(row)},${row.duplicateSameTick},${row.queries},${row.cursors}") }
            },
        )
        fun percentile(values: List<Long>, fraction: Double): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            return sorted[((sorted.size - 1) * fraction).toInt()] / 1_000_000.0
        }
        Files.writeString(
            directory.resolve("operation-work.txt"),
            """
            NPCs=${actors.size} ticks=${helper.tick}
            navigationCalls=${actors.values.sumOf { it.calls }} actualNativeSearches=${actors.values.sumOf { it.searches }}
            maximumNativeSearchesPerNpcTick=${actors.values.maxOf { it.maximumTickSearches }} sameRequestDuplicatesWithinTick=${actors.values.sumOf { it.duplicateSameTick }}
            maximumNativeSearchesPerNpc20Ticks=${actors.values.maxOf(::maximumSecond)}
            positionQueries=${actors.values.sumOf { it.queries }} freshPositionCursors=${actors.values.sumOf { it.cursors }}
            sampledServerTicks=${tickTimes.size} tickMeanMs=${tickTimes.average() / 1_000_000.0} tickP50Ms=${percentile(tickTimes, 0.5)} tickP95Ms=${percentile(tickTimes, 0.95)} tickP99Ms=${percentile(tickTimes, 0.99)}
            nativeCallP50Ms=${percentile(pathTimes, 0.5)} nativeCallP95Ms=${percentile(pathTimes, 0.95)} nativeCallP99Ms=${percentile(pathTimes, 0.99)}
            Debug trace disabled. Timing includes instrumentation and headless server machinery; not client FPS or a before/after benchmark.
            """.trimIndent() + "\n",
        )
        if (!enforce) return
        helper.assertTrue(actors.values.none { it.duplicateSameTick > 0 }, "Identical native route was rebuilt repeatedly within a tick; see operation-work.csv")
        helper.assertTrue(actors.values.all { it.maximumTickSearches <= 2 }, "NPC repeatedly launched native searches within one tick; see operation-work.csv")
        helper.assertTrue(actors.values.all { maximumSecond(it) <= 20 }, "NPC rebuilt more than 20 native paths in one simulated second; see operation-work.csv")
        helper.assertTrue(pathTimes.isNotEmpty() && tickTimes.size >= 1000, "Native performance scene collected no usable work/tick samples")
    }
    override fun close() {
        if (closed) return
        closed = true
        navigators.forEach { it.pathWorkObserver = null }
        TacticalPositions.searchWorkObserver = null
        NeoForge.EVENT_BUS.unregister(damage)
        damageTrace.close()
        NeoForge.EVENT_BUS.unregister(pre)
        NeoForge.EVENT_BUS.unregister(post)
    }
}
