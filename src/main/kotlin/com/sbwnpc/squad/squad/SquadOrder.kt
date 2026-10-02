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
    BARRAGE,

    /** Fall back to the objective, covering each other, then hold it (turns into DEFEND on
     *  arrival). Appended last for the same reason as [BARRAGE]. */
    RETREAT;

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
         * the quick-command HUD, map menus and the server's own validation.
         */
        fun availableFor(
            tank: Boolean,
            mortar: Boolean,
            gunship: Boolean = false,
            transport: Boolean = false
        ): List<SquadOrder> = when {
            tank -> listOf(MOVE, RETREAT)
            mortar -> listOf(ATTACK, DEFEND, BARRAGE, RETREAT)
            // A gunship is sent hunting, holds an area, or repositions.
            gunship -> listOf(ATTACK, DEFEND, MOVE, RETREAT)
            // AH-6 attacks by orbiting for its passengers' guns; MOVE still ends in a landing.
            transport -> listOf(ATTACK, DEFEND, MOVE, RETREAT)
            else -> entries.filter { it != BARRAGE }
        }

        /** Map point commands supported by at least one selected squad; patrol uses its route editor. */
        fun pointOrdersFor(available: Collection<List<SquadOrder>>): List<SquadOrder> {
            val supported = available.flatten().toSet()
            return listOf(MOVE, ATTACK, DEFEND, RETREAT, BARRAGE).filter { it in supported }
        }
    }
}
