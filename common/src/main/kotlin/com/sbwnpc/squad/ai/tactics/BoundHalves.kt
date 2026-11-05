package com.sbwnpc.squad.ai.tactics

import java.util.UUID

/**
 * How a squad bounding by halves splits its movers for the first turn (#262, owner 2.11): each
 * bound is covered by a heavy gun where the squad has one.
 *
 * The heavy guns (machine gunners and snipers) are dealt out first, alternately, starting with the
 * half that fires first, so a lone heavy gun covers the first rush and two of them stand in
 * different halves. The rest alternate along the front as before, filling each half to its share,
 * so each half still covers all of the front.
 */
object BoundHalves {
    /**
     * The half that moves first, out of [alongFront] (the movers in order along the front) where
     * [heavy] are the heavy guns among them. The moving half is the larger by one when the count is odd.
     */
    fun firstMoving(alongFront: List<UUID>, heavy: Set<UUID>): Set<UUID> {
        val guns = alongFront.filter { it in heavy }
        // More guns than the firing half holds: the rest rush with the first half.
        val firingGuns = minOf((guns.size + 1) / 2, alongFront.size / 2)
        val moving = guns.filterIndexed { index, _ -> index % 2 == 1 || index / 2 >= firingGuns }.toMutableSet()
        var firingRoom = alongFront.size / 2 - (guns.size - moving.size)
        var movingRoom = (alongFront.size + 1) / 2 - moving.size
        alongFront.filter { it !in heavy }.forEachIndexed { index, id ->
            if (movingRoom > 0 && (index % 2 == 0 || firingRoom <= 0)) {
                moving += id
                movingRoom--
            } else {
                firingRoom--
            }
        }
        return moving
    }
}
