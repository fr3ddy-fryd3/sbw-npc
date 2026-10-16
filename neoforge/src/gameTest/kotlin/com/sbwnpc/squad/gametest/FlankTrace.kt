package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.tactics.FlankingState
import com.sbwnpc.squad.combat.tactics.TacticalJob
import com.sbwnpc.squad.combat.tactics.TacticalPhase
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.Squad
import net.minecraft.gametest.framework.GameTestHelper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.UUID

/** Physical movement measurements; the observer never assigns slots or changes navigation. */
class FlankTrace(private val helper: GameTestHelper, private val squad: Squad, private val members: List<NpcEntity>) {
    private val directory = Path.of(System.getProperty("sbwnpc.gameTestReports"))
    private val episodes = HashMap<Int, Episode>()
    private val movers = HashSet<UUID>()
    var orderCrossings = 0
        private set
    var longestCrowding = 0
        private set
    var maxArrived = 0
        private set
    private val completed = HashSet<Int>()
    val completedCount get() = completed.size
    val moverCount get() = movers.size

    private class Episode(val initial: Map<UUID, Double>) {
        val started = HashSet<UUID>()
        val crossed = HashSet<Pair<UUID, UUID>>()
        val crowded = HashMap<Pair<UUID, UUID>, Int>()
    }

    init {
        Files.writeString(directory.resolve("attack-flanks.csv"), "tick,plan,movers,arrived,minSpacing,longestCrowding,orderCrossings\n")
        Files.writeString(directory.resolve("attack-flank-crossings.csv"), "tick,plan,first,second,initialSeparation,currentSeparation\n")
    }

    fun sample() {
        val plan = squad.tactics.plan ?: return
        val state = plan.behavior as? FlankingState ?: return
        val moving = members.filter { it.isAlive && plan.tasks[it.uuid]?.job == TacticalJob.FLANK }.map { it.uuid }.toSet()
        // This scene attacks along +X. Measure the physical lateral axis independently of the plan.
        val side = net.minecraft.world.phys.Vec3(0.0, 0.0, 1.0)
        val episode = episodes[plan.id] ?: if (moving.isEmpty()) {
            return
        } else {
            Episode(members.filter { it.isAlive }.associate { it.uuid to it.position().dot(side) })
                .also { episodes[plan.id] = it }
        }
        episode.started.addAll(moving)
        movers.addAll(moving)
        maxArrived = maxOf(maxArrived, state.arrived.size)
        if (plan.phase == TacticalPhase.COMPLETED && episode.started.size >= 6 && state.arrived.containsAll(episode.started)) completed.add(plan.id)
        val alive = members.filter { it.isAlive }
        var minSpacing = Double.POSITIVE_INFINITY
        for (a in alive.indices) {
            for (b in a + 1 until alive.size) {
                val first = alive[a]
                val second = alive[b]
                val pair = first.uuid to second.uuid
                val separation = first.position().subtract(second.position()).horizontalDistance()
                // Keep measuring after arrival, when a flanker becomes a covering shooter.
                if (first.uuid in episode.started || second.uuid in episode.started) {
                    minSpacing = minOf(minSpacing, separation)
                    val duration = if (separation < 3.0) (episode.crowded[pair] ?: 0) + 1 else 0
                    episode.crowded[pair] = duration
                    longestCrowding = maxOf(longestCrowding, duration)
                } else {
                    episode.crowded.remove(pair)
                }
                if (first.uuid !in episode.started || second.uuid !in episode.started) continue
                val initial = (episode.initial[first.uuid] ?: continue) - (episode.initial[second.uuid] ?: continue)
                val current = first.position().subtract(second.position()).dot(side)
                if (kotlin.math.abs(initial) >= 3.0 && initial * current < 0.0 && kotlin.math.abs(current) >= 3.0 && episode.crossed.add(pair)) {
                    orderCrossings++
                    Files.writeString(directory.resolve("attack-flank-crossings.csv"), "${helper.tick},${plan.id},${first.uuid},${second.uuid},$initial,$current\n", APPEND)
                }
            }
        }
        if (helper.tick % 10 == 0L) {
            Files.writeString(directory.resolve("attack-flanks.csv"), "${helper.tick},${plan.id},${moving.size},${state.arrived.size},$minSpacing,$longestCrowding,$orderCrossings\n", APPEND)
        }
    }

    fun finish() {
        Files.writeString(directory.resolve("attack-flank-summary.txt"), "movers=$moverCount\nmaxArrived=$maxArrived\ncompleted=$completedCount\norderCrossings=$orderCrossings\nlongestCrowding=$longestCrowding\n")
    }
}
