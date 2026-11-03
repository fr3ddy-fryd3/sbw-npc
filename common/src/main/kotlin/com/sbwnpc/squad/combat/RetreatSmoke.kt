package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * The smoke screen a squad lays the first time it is fired on as it falls back (#115): the
 * immediate reaction to fire kept from the old fighting withdrawal, now that the squad's plan
 * moves the men (#253). [SmokeScreen] places the grenades.
 *
 * Kept per order ([com.sbwnpc.squad.squad.Squad.orderStamp]), not per point: a second retreat to
 * the same point found the first one's screen already thrown and threw none. A throw that cannot be
 * made is tried again a second later, a few times, beside the grenades already down
 * ([SmokeScreen.lay]).
 */
class RetreatSmoke(private val level: ServerLevel) {
    private class Screen(val stamp: Int) {
        /** Where its grenades went, so later ones go beside them. */
        val thrown = mutableListOf<Vec3>()
        var tries = 0
        var nextTick = 0L
    }

    private val screens = HashMap<UUID, Screen>()

    /**
     * Throws what is left of [squad]'s screen for order [stamp], towards [toward]: the
     * nearest enemy its men see, or else where fire on them came from. [holding] are the men of a
     * bounding squad who stay to cover the others; they throw, or anyone of [members] when none of
     * them can.
     */
    fun cover(squad: UUID, stamp: Int, members: List<NpcEntity>, holding: Set<UUID>, toward: Vec3?) {
        val screen = screens[squad]?.takeIf { it.stamp == stamp } ?: Screen(stamp).also { screens[squad] = it }
        val wanted = SmokeScreen.grenadesFor(members.size)
        val now = level.gameTime
        val due = screen.thrown.size < wanted && screen.tries < TRIES && now >= screen.nextTick
        if (toward == null || !due) return
        screen.tries++
        screen.nextTick = now + RETRY_TICKS
        val covering = members.filter { it.uuid in holding }
        val throwers = if (SmokeScreen.throwers(covering).isEmpty()) members else covering
        val spots = SmokeScreen.lay(level, throwers, toward, wanted - screen.thrown.size, screen.thrown)
        screen.thrown += SmokeScreen.throwAt(level, throwers, spots)
    }

    /** [squad] no longer falls back, or is gone. */
    fun forget(squad: UUID) {
        screens.remove(squad)
    }

    fun clear() = screens.clear()

    private companion object {
        /** Attempts at the screen per retreat, [RETRY_TICKS] apart. */
        const val TRIES = 5
        const val RETRY_TICKS = 20L
    }
}
