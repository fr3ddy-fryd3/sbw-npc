package com.sbwnpc.squad

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * SuperbWarfare may not stay around, so everything the addon knows about it has to live in one
 * place: the adapter under `integration/sbw/` (plus the mixins, which patch SBW classes by
 * definition). Everything else reaches SBW through the ports in `domain/port/`.
 *
 * [LEGACY] lists the files that still use SBW directly (an import or a fully qualified name). It may only shrink: a new file there
 * fails the first test, a file that no longer imports SBW fails the second until it is removed.
 */
class SbwBoundaryTest {
    private val kotlinRoot = File(System.getProperty("sbwnpc.projectDir", "."), "src/main/kotlin/com/sbwnpc/squad")

    private fun importers(): Set<String> {
        assertTrue(kotlinRoot.isDirectory, "Sources not found at $kotlinRoot")
        return kotlinRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.useLines { lines -> lines.any { "com.atsuishio." in it } } }
            .map { it.relativeTo(kotlinRoot).invariantSeparatorsPath }
            .filterNot { it.startsWith(ADAPTER) }
            .toSet()
    }

    @Test
    fun `no new file imports SuperbWarfare outside the adapter`() {
        val offenders = importers() - LEGACY
        assertTrue(offenders.isEmpty(), "Import SBW only in $ADAPTER, go through a port instead: $offenders")
    }

    @Test
    fun `legacy list names only files that still import SuperbWarfare`() {
        val cleared = LEGACY - importers()
        assertTrue(cleared.isEmpty(), "No longer import SBW, drop them from LEGACY: $cleared")
    }

    private companion object {
        const val ADAPTER = "integration/sbw/"

        val LEGACY = setOf(
            "client/NpcModel.kt",
            "combat/DroneCombat.kt",
            "combat/GrenadeThrower.kt",
            "entity/ai/AntiDroneBehaviour.kt",
            "entity/ai/DroneOperatorBehaviour.kt",
            "entity/ai/GrenadeEvadeBehaviour.kt",
            "entity/ai/MedicHealBehaviour.kt",
            "entity/ai/MortarDeployment.kt",
            "entity/ai/MortarLoaderBehaviour.kt",
            "entity/ai/MortarOperatorBehaviour.kt",
            "entity/ai/VehicleTransportBehaviour.kt",
            "entity/DroneRegistry.kt",
            "entity/GrenadeRegistry.kt",
            "entity/NpcEntity.kt",
            "init/ModCreativeTab.kt",
            "item/SquadToolItem.kt",
            "network/ModNetwork.kt",
            "squad/SquadDeployment.kt",
        )
    }
}
