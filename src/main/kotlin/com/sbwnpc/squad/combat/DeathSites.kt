package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Where a faction's NPCs went down to something nobody could name, and whether anyone has since
 * looked at the spot and found nothing there.
 *
 * Such a death raises an [Alarm] at the body, and every ally in earshot used to walk right up to it
 * — reported in-game as a crowd gathering on the spot whenever someone fell with no enemy in sight.
 * Now one look settles it for the whole faction: the first ally to see the spot from close by, with
 * nothing hostile in view, marks it checked, and everyone else still heading there turns back.
 *
 * Only for these deaths, not for gunfire alarms: there the alert points at a real enemy someone is
 * shooting at, and "I can see the spot" is not "there's nothing there".
 *
 * Runtime only, like [TeamAwareness]: a site is useful for as long as the alert it answers.
 */
object DeathSites {
    /** Two deaths this close together are the same site. */
    private const val SAME_SITE = 8.0
    /** How close an ally has to be for its look at the spot to count. */
    const val CONFIRM_RANGE = 16.0
    /** Matches the alert an investigator walks on (NpcEntity.ALERT_DURATION_TICKS). */
    private const val TTL_TICKS = 200L

    class Site(val pos: Vec3, val openedTick: Long) {
        var checked = false
    }

    private val byFaction = HashMap<SquadFaction, MutableList<Site>>()

    /** The site at [pos], opening one if there isn't. */
    fun open(faction: SquadFaction, pos: Vec3, tick: Long): Site {
        val sites = byFaction.getOrPut(faction) { ArrayList() }
        sites.removeIf { tick - it.openedTick > TTL_TICKS }
        return sites.firstOrNull { it.pos.distanceToSqr(pos) <= SAME_SITE * SAME_SITE }
            ?: Site(pos, tick).also { sites += it }
    }

    /** The live site [pos] belongs to, or null if [pos] isn't a death site. */
    fun at(faction: SquadFaction, pos: Vec3, tick: Long): Site? =
        byFaction[faction]?.firstOrNull {
            tick - it.openedTick <= TTL_TICKS && it.pos.distanceToSqr(pos) <= SAME_SITE * SAME_SITE
        }

    /** Close enough, in plain view, and not busy with an enemy of its own. */
    fun canConfirm(npc: NpcEntity, pos: Vec3): Boolean {
        if (npc.target != null) return false
        if (npc.position().distanceToSqr(pos) > CONFIRM_RANGE * CONFIRM_RANGE) return false
        val eye = npc.eyePosition
        val spot = pos.add(0.0, 0.5, 0.0)
        val hit = npc.level().clip(ClipContext(eye, spot, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc))
        return hit.type == HitResult.Type.MISS
    }

    fun clearAll() = byFaction.clear()
}
