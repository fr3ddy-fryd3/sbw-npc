package com.sbwnpc.squad.combat

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode

/** Load real SBW classes through the test game loader, including their mixin transformations. */
class SbwMixinIntegrationTest {
    @Test
    fun `boarding and drone hooks are applied to the runtime classes`() {
        val hooks = listOf(
            "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity" to "boardAlongsideAlliedDriver",
            "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity" to "checkNpcFiringLane",
            "com.atsuishio.superbwarfare.data.gun.GunData" to "applyDroneSpread",
            "com.atsuishio.superbwarfare.entity.vehicle.base.AutoAimableEntity" to "markAutonomousShot",
            "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity" to "writeAircraftVisuals",
            "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity" to "readAircraftVisuals"
        )
        for ((className, hook) in hooks) {
            val type = Class.forName(className, false, javaClass.classLoader)
            assertTrue(type.declaredMethods.any { it.name.contains(hook) }, "Missing runtime hook: $className.$hook")
        }
    }

    @Test
    fun `client aircraft injection descriptors match the actual SBW bytecode`() {
        // JUnit runs as a dedicated server. Inspect client bytecode without loading client classes.
        fun read(name: String): ClassNode = ClassNode().also { node ->
            javaClass.classLoader.getResourceAsStream("$name.class")!!.use { ClassReader(it).accept(node, ClassReader.SKIP_CODE) }
        }
        for (mixin in listOf("AircraftModelEntriesMixin", "AircraftModelReloadMixin", "AircraftRendererMixin", "AircraftWorldRenderMixin")) {
            val node = read("com/sbwnpc/squad/mixin/$mixin")
            val annotation = node.invisibleAnnotations.single { it.desc == "Lorg/spongepowered/asm/mixin/Mixin;" }
            val values = annotation.values.chunked(2).associate { it[0] as String to it[1] }
            val target = read((values.getValue("value") as List<*>).single().let { (it as Type).internalName })
            for (method in node.methods) {
                val inject = method.visibleAnnotations?.find { it.desc == "Lorg/spongepowered/asm/mixin/injection/Inject;" } ?: continue
                val fields = inject.values.chunked(2).associate { it[0] as String to it[1] }
                for (selector in fields.getValue("method") as List<*>) {
                    val signature = selector as String
                    val matches = target.methods.filter { if ('(' in signature) it.name + it.desc == signature else it.name == signature }
                    assertTrue(matches.size == 1, "$mixin.$signature must resolve to one SBW method, found ${matches.size}")
                }
            }
        }
    }
}
