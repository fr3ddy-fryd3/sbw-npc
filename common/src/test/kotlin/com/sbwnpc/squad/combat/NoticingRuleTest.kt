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
            val counted = COUNTED[file]?.count ?: 0
            if (now == counted) null else "$file: $now bare checks, $counted counted"
        }
        assertTrue(drift.isEmpty(), "Route noticing through Noticing, or update COUNTED for a line or point check:\n${drift.joinToString("\n")}")
    }

    @Test
    fun `every counted file says why its checks are not noticing`() {
        val missing = COUNTED.filterValues { it.reason.isBlank() }.keys
        assertTrue(missing.isEmpty(), "Say why these keep a bare check: $missing")
    }

    private class Counted(val count: Int, val reason: String)

    private companion object {
        val COUNTED = mapOf(
            "com/sbwnpc/squad/ai/integration/NativePositionAction.kt" to Counted(2, "line to a firing post"),
            "com/sbwnpc/squad/ai/integration/NativeTactics.kt" to Counted(5, "witnesses of an area and posts: who could have seen a point, not who noticed an enemy"),
            "com/sbwnpc/squad/combat/FiringOpportunity.kt" to Counted(1, "line of fire"),
            "com/sbwnpc/squad/combat/VehicleTargeting.kt" to Counted(3, "a gunner's or aircrew's own sight is instant by design, and weaponPermits is an aim line"),
            "com/sbwnpc/squad/combat/tactics/SquadTactics.kt" to Counted(1, "line to an enemy a squadmate already has"),
            "com/sbwnpc/squad/combat/tactics/TacticalSnapshotCollector.kt" to Counted(2, "who of the squad sees the contact, for the picture"),
            "com/sbwnpc/squad/entity/NpcEntity.kt" to Counted(3, "line checks on its own target"),
            "com/sbwnpc/squad/entity/ai/AntiDroneBehaviour.kt" to Counted(1, "line to the drone it has already noticed through Noticing"),
            "com/sbwnpc/squad/entity/ai/DroneOperatorBehaviour.kt" to Counted(1, "line to a target it has already noticed through Noticing"),
            "com/sbwnpc/squad/entity/ai/GrenadeThrowBehaviour.kt" to Counted(1, "line of the throw"),
            "com/sbwnpc/squad/entity/ai/GrenadeUseBehaviour.kt" to Counted(2, "line to its own current target, which it has noticed"),
            "com/sbwnpc/squad/entity/ai/GunAttackBehaviour.kt" to Counted(1, "line of fire"),
            "com/sbwnpc/squad/entity/ai/HelicopterPilotBehaviour.kt" to Counted(2, "aircrew keep their sight"),
            "com/sbwnpc/squad/entity/ai/HelicopterRideBehaviour.kt" to Counted(1, "concealment of the chosen target: line of fire"),
            "com/sbwnpc/squad/entity/ai/SeekCoverBehaviour.kt" to Counted(3, "cover points and exposure"),
            "com/sbwnpc/squad/entity/ai/SquadTargetSensor.kt" to Counted(5, "inside the sensor: the sight half of the scan, the spotting time sits beside it; close threats and squadmates' targets"),
            "com/sbwnpc/squad/entity/ai/VehicleCombatSupportBehaviour.kt" to Counted(1, "line to an already chosen target"),
        )
    }
}
