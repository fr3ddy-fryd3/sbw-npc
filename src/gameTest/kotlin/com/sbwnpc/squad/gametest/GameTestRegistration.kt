package com.sbwnpc.squad.gametest

import net.minecraft.gametest.framework.GameTest
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterGameTestsEvent

/** Optional method-name selection, using NeoForge's normal GameTest registration event. */
@EventBusSubscriber(modid = "sbwnpc", bus = EventBusSubscriber.Bus.MOD)
object GameTestRegistration {
    @SubscribeEvent
    fun register(event: RegisterGameTestsEvent) {
        val classes = listOf(
            InfantryMovementGameTests::class.java,
            LadderNavigationGameTests::class.java,
            DefensiveOverwatchGameTests::class.java,
            RoofedOverwatchGameTests::class.java,
            AttackDeploymentGameTests::class.java,
            TerrainAttackGameTests::class.java,
            FiringPostSearchGameTests::class.java,
            FoliageCombatGameTests::class.java,
            HearingGameTests::class.java,
            FlankCombatGameTests::class.java,
            TacticalExecutionGameTests::class.java,
            HeightAttackGameTests::class.java,
            ContactDeploymentGameTests::class.java,
        )
        val methods = classes.flatMap { it.declaredMethods.toList() }.filter { it.isAnnotationPresent(GameTest::class.java) }
        val selected = System.getProperty("sbwnpc.gameTestFilter", "").split(',').filter { it.isNotBlank() }.toSet()
        require(selected.all { name -> methods.any { it.name == name } }) { "Unknown GameTest: $selected; available: ${methods.map { it.name }}" }
        methods.filter { selected.isEmpty() || it.name in selected }.forEach(event::register)
    }
}
