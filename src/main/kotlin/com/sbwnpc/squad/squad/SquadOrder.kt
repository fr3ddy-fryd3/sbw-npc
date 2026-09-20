package com.sbwnpc.squad.squad

enum class SquadOrder {
    /** Hold near the objective, don't chase far. */
    DEFEND,

    /** Wander loosely around the objective. */
    PATROL,

    /** Advance to the objective, engaging hostiles on the way. */
    ATTACK,

    /** Move calmly to the objective, then wander nearby. */
    MOVE,

    /** Mortar crews only: walk shells across an area around the objective instead of stacking
     *  them all on the one point. Appended last so existing saved squads keep their ordinals. */
    BARRAGE;

    fun next(): SquadOrder = entries[(ordinal + 1) % entries.size]

    /** [next], but only through the orders this squad can actually be given. */
    fun next(available: List<SquadOrder>): SquadOrder {
        if (available.isEmpty()) return this
        val at = available.indexOf(this)
        return available[(at + 1) % available.size]
    }

    companion object {
        fun byOrdinal(i: Int): SquadOrder = entries.getOrElse(i) { MOVE }

        /**
         * What a squad of this shape can be ordered to do. One list, used by the command screen,
         * the quick-command HUD and the server's own validation, so the three cannot disagree.
         */
        fun availableFor(
            tank: Boolean,
            mortar: Boolean,
            gunship: Boolean = false,
            transport: Boolean = false
        ): List<SquadOrder> = when {
            tank -> listOf(MOVE)
            mortar -> listOf(ATTACK, DEFEND, BARRAGE)
            // A gunship is sent hunting, holds an area, or repositions.
            gunship -> listOf(ATTACK, DEFEND, MOVE)
            // A transport has nothing to attack with; it patrols with its gunners or relocates.
            transport -> listOf(DEFEND, MOVE)
            else -> entries.filter { it != BARRAGE }
        }
    }
}
