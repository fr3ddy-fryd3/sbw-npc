package com.sbwnpc.squad.combat.tactics

/** Complete registry: adding a pattern without an implementation fails immediately. */
class TacticalStateRegistry(factories: Map<TacticalPattern, () -> TacticalState>) {
    private val factories = factories.toMap()
    private data class Definition(val stability: TacticalStability, val flanking: Boolean, val defensive: Boolean)
    private val definitions = factories.mapValues {
        val state = it.value()
        Definition(state.stability, state is FlankingState, state.supportsDefensiveOverwatch)
    }
    init { require(factories.keys == TacticalPattern.entries.toSet()) { "Every tactical pattern must have a state factory" } }
    fun create(pattern: TacticalPattern): TacticalState = factories.getValue(pattern)()
    fun policy(pattern: TacticalPattern): TacticalStability = definitions.getValue(pattern).stability
    val flankingPatterns = definitions.filterValues { it.flanking }.keys.toSet()
    val defensivePatterns = definitions.filterValues { it.defensive }.keys.toSet()
    fun replacing(pattern: TacticalPattern, factory: () -> TacticalState) = TacticalStateRegistry(factories + (pattern to factory))
}

object TacticalStates {
    val registry = TacticalStateRegistry(mapOf(
        TacticalPattern.FOLLOW_ORDER to ::FollowOrderState,
        TacticalPattern.RETURN_FIRE to ::ReturnFireState,
        TacticalPattern.FLANK to ::FlankState,
        TacticalPattern.ENCIRCLE to ::EncircleState,
        TacticalPattern.BOUND to ::BoundState,
        TacticalPattern.FOCUS_SECTOR to ::FocusSectorState,
        TacticalPattern.DISLODGE to ::DislodgeState,
        TacticalPattern.ATTACK_HEIGHT to ::HeightAttackState,
        TacticalPattern.HOLD_HEIGHT to ::HoldHeightState,
        TacticalPattern.FILE to ::FileState,
        TacticalPattern.REORIENT to ::ReorientState,
        TacticalPattern.BREAK_CONTACT to ::BreakContactState,
        TacticalPattern.REPEL to ::RepelState,
        TacticalPattern.SEARCH to ::SearchState,
        TacticalPattern.PURSUE to ::PursueState,
        TacticalPattern.REORGANIZE to ::ReorganizeState,
        TacticalPattern.ANTI_ARMOUR to ::AntiArmourState,
        TacticalPattern.AVOID_ARMOUR to ::AvoidArmourState,
        TacticalPattern.EVADE to ::EvadeState,
        TacticalPattern.CONSOLIDATE to ::ConsolidateState
    ))
    fun create(pattern: TacticalPattern) = registry.create(pattern)
}
