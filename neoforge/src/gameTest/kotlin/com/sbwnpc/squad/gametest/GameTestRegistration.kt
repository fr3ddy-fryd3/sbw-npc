package com.sbwnpc.squad.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestGenerator
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterGameTestsEvent

/** Optional method-name selection, using NeoForge's normal GameTest registration event. */
@EventBusSubscriber(modid = "sbwnpc", bus = EventBusSubscriber.Bus.MOD)
object GameTestRegistration {
    @SubscribeEvent
    fun register(event: RegisterGameTestsEvent) {
        val classes = listOf(
            FactionLogisticsGameTests::class.java,
            VehicleSupplyGameTests::class.java,
            ExperienceGameTests::class.java,
            SquadProgressionGameTests::class.java,
            InfantryMovementGameTests::class.java,
            LadderNavigationGameTests::class.java,
            ScaffoldingGameTests::class.java,
            SidearmGameTests::class.java,
            AmmunitionTacticsGameTests::class.java,
            MortarCrewGameTests::class.java,
            HelicopterOrbitGameTests::class.java,
            DefensiveOverwatchGameTests::class.java,
            RoofedOverwatchGameTests::class.java,
            AttackDeploymentGameTests::class.java,
            TerrainAttackGameTests::class.java,
            FiringPostSearchGameTests::class.java,
            FoliageCombatGameTests::class.java,
            HearingGameTests::class.java,
            CombatIdleGameTests::class.java,
            CombatPerformanceGameTests::class.java,
            TacticalSearchGameTests::class.java,
            HtnTacticsGameTests::class.java,
            FollowOrderGameTests::class.java,
            FireSupportGameTests::class.java,
            TargetMemoryGameTests::class.java,
            SquadAreaGameTests::class.java,
            CoverBudgetGameTests::class.java,
            AllyDeathGameTests::class.java,
            DroneFlightGameTests::class.java,
            ObserverLookGameTests::class.java,
        )
        // Saved-world replays require an explicit local fixture; ordinary suites stay portable.
        val replayClasses = if (System.getProperty("sbwnpc.gameTestReplay", "").isNotBlank()) listOf(SavedBattleGameTests::class.java) else emptyList()
        val battleClasses = if (System.getProperty("sbwnpc.gameTestBattleSaves", "").isNotBlank()) listOf(RealTerrainBattleGameTests::class.java) else emptyList()
        val methods = (classes + replayClasses + battleClasses).flatMap { it.declaredMethods.toList() }.filter { it.isAnnotationPresent(GameTest::class.java) || it.isAnnotationPresent(GameTestGenerator::class.java) }
        val selected = System.getProperty("sbwnpc.gameTestFilter", "").split(',').filter { it.isNotBlank() }.toSet()
        require(selected.all { name -> methods.any { it.name == name } }) { "Unknown GameTest: $selected; available: ${methods.map { it.name }}" }
        methods.filter { selected.isEmpty() || it.name in selected }.forEach(event::register)
    }
}
