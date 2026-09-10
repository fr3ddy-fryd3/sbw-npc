package com.sbwnpc.squad.squad

enum class SquadOrder {
    /** Hold near the objective, don't chase far. */
    DEFEND,

    /** Wander loosely around the objective. */
    PATROL,

    /** Advance to the objective, engaging hostiles on the way. */
    ATTACK,

    /** No coordination — each member just fights and wanders on its own. */
    FREE;

    fun next(): SquadOrder = entries[(ordinal + 1) % entries.size]

    companion object {
        fun byOrdinal(i: Int): SquadOrder = entries.getOrElse(i) { FREE }
    }
}
