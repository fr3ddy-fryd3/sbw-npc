package com.sbwnpc.squad.combat

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Load real SBW classes through the test game loader, including their mixin transformations. */
class SbwMixinIntegrationTest {
    @Test
    fun `boarding and drone hooks are applied to the runtime classes`() {
        val hooks = listOf(
            "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity" to "boardAlongsideAlliedDriver",
            "com.atsuishio.superbwarfare.data.gun.GunData" to "applyDroneSpread",
            "com.atsuishio.superbwarfare.entity.vehicle.base.AutoAimableEntity" to "markAutonomousShot",
            "com.atsuishio.superbwarfare.entity.vehicle.base.AutoAimableEntity" to "applyDroneRayAccuracy"
        )
        for ((className, hook) in hooks) {
            val type = Class.forName(className, false, javaClass.classLoader)
            assertTrue(type.declaredMethods.any { it.name.contains(hook) }, "Missing runtime hook: $className.$hook")
        }
    }
}
