package com.sbwnpc.squad.ai

import com.sbwnpc.squad.entity.Cap
import com.sbwnpc.squad.entity.NpcActivity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tactical decisions belong to Utility, HTN, state and strategy (`docs/AI-ARCHITECTURE-DESIGN.md`,
 * `AGENTS.md`); SmartBrainLib behaviours only execute what the planner chose, apart from the
 * design's explicit mandatory rules. A behaviour, an activity that takes a man's feet from his plan,
 * or a memory type that starts one is where a decision slips out of the planner unseen: #237 hid
 * men from armour through a new memory and the cover behaviour while their plans stood suspended.
 *
 * This pins the three lists, so a new entry is a decision made in the open: it fails here until it
 * is added with the layer it serves and the owner's decision behind it. Entries marked [UNAUDITED]
 * predate the rule and are being sorted by #249. Vanilla memories the executors set, such as the
 * look target and the path, are execution and not counted.
 */
class DecisionLayerRuleTest {
    private val memory = Regex("""val\s+([A-Z_]+)\s*:\s*Supplier<MemoryModuleType<""")
    private val declaration = Regex("""\b(?:class|object)\s+(\w+)(?:(?!\b(?:class|object)\b)[^{])*?:\s*(?:[\w.]+\.)?Extended(?:Behaviour|Sensor)<""")

    @Test
    fun `every behaviour and sensor is listed with its layer`() {
        val sources = File("src/main/kotlin")
        require(sources.isDirectory) { "Run from the common module: ${sources.absolutePath}" }
        val actual = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> declaration.findAll(file.readText()).map { it.groupValues[1] } }
            .toSortedSet()
        val added = actual - BEHAVIOURS.keys
        val removed = BEHAVIOURS.keys - actual
        assertTrue(
            added.isEmpty() && removed.isEmpty(),
            "A behaviour executes what the planner chose; a decision goes into an HTN domain (AGENTS.md). " +
                "Add a new one to BEHAVIOURS only with the owner's approval and the reason, drop a removed one.\n" +
                "Not listed: $added\nListed but gone: $removed",
        )
    }

    @Test
    fun `activities that suspend the plan are only those listed`() {
        // An activity ranked above TACTIC that holds the feet takes them from the plan.
        val suspending = NpcActivity.entries.filter { it.ordinal > NpcActivity.TACTIC.ordinal && Cap.FEET in it.holds }.toSet()
        assertEquals(
            SUSPENDING.keys,
            suspending,
            "A new reason to suspend a man's plan needs the owner's approval (AGENTS.md); list it in SUSPENDING with the reason.",
        )
    }

    @Test
    fun `brain memories are only those listed`() {
        val file = File("src/main/kotlin/com/sbwnpc/squad/init/ModMemories.kt")
        require(file.isFile) { "Run from the common module: ${file.absolutePath}" }
        val actual = memory.findAll(file.readText()).map { it.groupValues[1] }.toSortedSet()
        assertEquals(
            MEMORIES.keys,
            actual,
            "A memory that starts a behaviour can carry a decision past the planner (AGENTS.md); list a new one in MEMORIES with the owner's approval and the reason.",
        )
    }

    @Test
    fun `every entry says why`() {
        val missing = (BEHAVIOURS + MEMORIES + SUSPENDING.mapKeys { it.key.name }).filterValues { it.isBlank() }.keys
        assertTrue(missing.isEmpty(), "Say why these are allowed: $missing")
    }

    private companion object {
        const val UNAUDITED = "predates the rule; layer to be decided by #249"

        val BEHAVIOURS = sortedMapOf(
            "AntiDroneBehaviour" to UNAUDITED,
            "BlindReturnFireBehaviour" to UNAUDITED,
            "DroneOperatorBehaviour" to UNAUDITED,
            "FollowRideBehaviour" to UNAUDITED,
            "GrenadeEvadeBehaviour" to UNAUDITED,
            "GrenadeThrowBehaviour" to UNAUDITED,
            "GrenadeUseBehaviour" to UNAUDITED,
            "GunAttackBehaviour" to UNAUDITED,
            "HelicopterGunnerBehaviour" to UNAUDITED,
            "HelicopterPilotBehaviour" to UNAUDITED,
            "HelicopterRideBehaviour" to UNAUDITED,
            "InvestigateBehaviour" to UNAUDITED,
            "MedicHealBehaviour" to UNAUDITED,
            "MortarCrewBehaviour" to UNAUDITED,
            "OutOfAmmoBehaviour" to UNAUDITED,
            "ResupplyBehaviour" to UNAUDITED,
            "SeekCoverBehaviour" to UNAUDITED,
            "SelfTreatBehaviour" to UNAUDITED,
            "SquadOrderBehaviour" to UNAUDITED,
            "SquadTacticalBehaviour" to UNAUDITED,
            "SquadTargetSensor" to UNAUDITED,
            "VehicleCombatSupportBehaviour" to UNAUDITED,
            "VehicleCrewBehaviour" to UNAUDITED,
            "VehicleTransportBehaviour" to UNAUDITED,
        )

        val MEMORIES = sortedMapOf(
            "SUPPRESSING_THREAT" to UNAUDITED,
            "ALERT_POSITION" to UNAUDITED,
            "DRONE_THREAT" to UNAUDITED,
            "DRONE_HEARD" to UNAUDITED,
        )

        val SUSPENDING = mapOf(
            NpcActivity.RESUPPLY to UNAUDITED,
            NpcActivity.HEAL to UNAUDITED,
            NpcActivity.COVER to UNAUDITED,
            NpcActivity.PEEK to UNAUDITED,
            NpcActivity.EVADE to UNAUDITED,
            NpcActivity.DUG_IN to UNAUDITED,
            NpcActivity.MORTAR_ERRAND to UNAUDITED,
            NpcActivity.CARRYING_MORTAR to UNAUDITED,
            NpcActivity.ANTI_DRONE to UNAUDITED,
            NpcActivity.DRONE to UNAUDITED,
            NpcActivity.MORTAR_CREW to UNAUDITED,
            NpcActivity.SEAT to UNAUDITED,
            NpcActivity.VEHICLE to UNAUDITED,
        )
    }
}
