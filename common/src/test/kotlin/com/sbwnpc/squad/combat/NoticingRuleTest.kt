package com.sbwnpc.squad.combat

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * "Has he noticed this enemy" is one rule, [Noticing] (#114, #190). A bare [DetectionSightline] check
 * answers only whether a line is clear, so spotting time, light and any later rule such as wariness
 * (#186) never reach a consumer that decides by it.
 *
 * Bare checks stay where they ask about a line of fire or a point rather than about noticing. This
 * pins how many each file has, so a new one is a decision made in the open: either the call goes
 * through [Noticing], or it is a line or point check and the count here is raised with a reason in
 * the PR. When a call is migrated the count has to come down too.
 */
class NoticingRuleTest {
    private val bareCheck = Regex("""DetectionSightline\.(canSee|canSeeWithin|visible)\(""")

    @Test
    fun `bare sightline checks are only where they were counted`() {
        val sources = File("src/main/kotlin")
        require(sources.isDirectory) { "Run from the common module: ${sources.absolutePath}" }
        val actual = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(sources).invariantSeparatorsPath to bareCheck.findAll(it.readText()).count() }
            .filter { it.second > 0 }
            .toMap()
        val drift = (actual.keys + COUNTED.keys).sorted().mapNotNull { file ->
            val now = actual[file] ?: 0
            val counted = COUNTED[file] ?: 0
            if (now == counted) null else "$file: $now bare checks, $counted counted"
        }
        assertTrue(drift.isEmpty(), "Route noticing through Noticing, or update COUNTED for a line or point check:\n${drift.joinToString("\n")}")
    }

    private companion object {
        val COUNTED = mapOf(
            "com/sbwnpc/squad/ai/integration/NativePositionAction.kt" to 2,
            "com/sbwnpc/squad/ai/integration/NativeTactics.kt" to 5,
            "com/sbwnpc/squad/combat/FiringOpportunity.kt" to 1,
            "com/sbwnpc/squad/combat/VehicleTargeting.kt" to 3,
            "com/sbwnpc/squad/combat/tactics/SquadTactics.kt" to 1,
            "com/sbwnpc/squad/combat/tactics/TacticalSnapshotCollector.kt" to 2,
            "com/sbwnpc/squad/entity/NpcEntity.kt" to 3,
            "com/sbwnpc/squad/entity/ai/AntiDroneBehaviour.kt" to 1,
            "com/sbwnpc/squad/entity/ai/DroneOperatorBehaviour.kt" to 1,
            "com/sbwnpc/squad/entity/ai/GrenadeThrowBehaviour.kt" to 1,
            "com/sbwnpc/squad/entity/ai/GrenadeUseBehaviour.kt" to 4,
            "com/sbwnpc/squad/entity/ai/GunAttackBehaviour.kt" to 1,
            "com/sbwnpc/squad/entity/ai/HelicopterPilotBehaviour.kt" to 2,
            "com/sbwnpc/squad/entity/ai/HelicopterRideBehaviour.kt" to 1,
            "com/sbwnpc/squad/entity/ai/SeekCoverBehaviour.kt" to 3,
            "com/sbwnpc/squad/entity/ai/SquadTargetSensor.kt" to 5,
            "com/sbwnpc/squad/entity/ai/VehicleCombatSupportBehaviour.kt" to 1,
        )
    }
}
