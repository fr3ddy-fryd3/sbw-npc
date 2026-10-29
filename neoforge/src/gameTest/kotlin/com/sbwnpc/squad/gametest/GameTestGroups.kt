package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.integration.sbw.VehicleAimGameTests
import com.sbwnpc.squad.integration.sbw.VehicleRankGameTests

/**
 * The scene classes by domain, so a change is checked by running what it touches (`--group`) instead
 * of listing method names. A class may sit in several groups.
 *
 * [check] fails the server start when a registered class is in no group or a group names a class
 * that is not registered: a new scene must not stay out of every group, silently.
 */
internal object GameTestGroups {
    private val groups: Map<String, List<Class<*>>> = linkedMapOf(
        "overwatch" to listOf(
            DefensiveOverwatchGameTests::class.java,
            RoofedOverwatchGameTests::class.java,
            FarDefenceGameTests::class.java,
            ScaffoldingGameTests::class.java,
            TreePlatformGameTests::class.java,
            ObserverLookGameTests::class.java,
        ),
        "orders" to listOf(
            HtnTacticsGameTests::class.java,
            SquadManoeuvreGameTests::class.java,
            SquadAreaGameTests::class.java,
            FollowOrderGameTests::class.java,
            WithdrawalGameTests::class.java,
            AttackDeploymentGameTests::class.java,
            TerrainAttackGameTests::class.java,
        ),
        "posts" to listOf(
            TacticalSearchGameTests::class.java,
            FiringPostSearchGameTests::class.java,
            FiringLineGameTests::class.java,
            DefensivePostSpacingGameTests::class.java,
            CoverBudgetGameTests::class.java,
            FoliageCombatGameTests::class.java,
            TargetMemoryGameTests::class.java,
        ),
        "movement" to listOf(
            InfantryMovementGameTests::class.java,
            LadderNavigationGameTests::class.java,
            MarchRouteGameTests::class.java,
        ),
        "combat" to listOf(
            CombatIdleGameTests::class.java,
            AllyDeathGameTests::class.java,
            SidearmGameTests::class.java,
            AmmunitionTacticsGameTests::class.java,
            HostileMobApiGameTests::class.java,
            HostileMobCombatGameTests::class.java,
        ),
        "perception" to listOf(
            NoticingGameTests::class.java,
            SightRangeGameTests::class.java,
            HearingGameTests::class.java,
        ),
        "vehicles" to listOf(
            VehicleSeatGameTests::class.java,
            VehicleSupplyGameTests::class.java,
            VehicleSupportOrderGameTests::class.java,
            VehicleSupportSightlineGameTests::class.java,
            VehicleAimGameTests::class.java,
            VehicleRankGameTests::class.java,
            VehicleBendGameTests::class.java,
            SquadTransportGameTests::class.java,
            SquadTransitPriorityGameTests::class.java,
        ),
        "aircraft" to listOf(
            HelicopterOrbitGameTests::class.java,
            DroneFlightGameTests::class.java,
        ),
        "fire-support" to listOf(
            MortarCrewGameTests::class.java,
            FireSupportGameTests::class.java,
        ),
        "logistics" to listOf(
            FactionLogisticsGameTests::class.java,
            ExperienceGameTests::class.java,
            SquadProgressionGameTests::class.java,
        ),
        "performance" to listOf(
            CombatPerformanceGameTests::class.java,
            AllRolesLoadGameTests::class.java,
        ),
    )

    /**
     * The classes of the groups named in [names], or every [registered] class when none is named.
     * An unknown name stops the run with the known ones.
     */
    fun select(registered: List<Class<*>>, names: List<String>): List<Class<*>> {
        check(registered)
        if (names.isEmpty()) return registered
        val unknown = names.filter { it !in groups }
        require(unknown.isEmpty()) { "Unknown GameTest group: $unknown; available: ${groups.keys}" }
        return names.flatMap { groups.getValue(it) }.distinct()
    }

    private fun check(registered: List<Class<*>>) {
        val grouped = groups.values.flatten().toSet()
        val loose = registered.filter { it !in grouped }
        require(loose.isEmpty()) { "GameTest classes in no group (add them to GameTestGroups): ${loose.map { it.simpleName }}" }
        val stray = grouped.filter { it !in registered }
        require(stray.isEmpty()) { "GameTestGroups names classes GameTestRegistration does not register: ${stray.map { it.simpleName }}" }
    }
}
