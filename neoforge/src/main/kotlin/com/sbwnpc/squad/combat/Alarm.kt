package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import net.minecraft.server.level.ServerLevel
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.world.phys.Vec3

/**
 * Auditory-style stimulus: alerts nearby allied NPCs toward [investigatePos] WITHOUT granting them a
 * target — they go investigate ("go check that out"), they don't get a free lock on an enemy they
 * haven't actually seen themselves. Distinct from [TeamAwareness], which is "someone has direct line
 * of sight on a specific hostile" (a real lead); this is "something happened over there, worth a
 * look". [hearOrigin] and [investigatePos] are deliberately separate: who's in earshot is decided
 * from where the SOUND came from (the shooter), but where they should actually walk to is the
 * THREAT, not the ally who happened to be standing there — an earlier version conflated the two
 * (both were the shooter's own position), which put every alerted ally on a beeline to stand right
 * next to whichever squadmate fired, reported in-game as "why are they all running to stack on the
 * guy who spotted the enemy instead of doing something else".
 *
 * Two callers: [com.sbwnpc.squad.entity.ai.GunAttackBehaviour] raises this every time it actually
 * fires (hearOrigin = the shooter, investigatePos = the target it's shooting at), and
 * [NpcEntity.die] raises it at the death position for both (no better lead available) when the
 * killer can't be resolved into a proper [TeamAwareness] report.
 */
object Alarm {
    /**
     * [source] went down to something nobody could name. Unlike [raise], this can end on the spot:
     * if an ally already has the body in plain view and nothing hostile in its sights, it has seen
     * all there is to see, and nobody else is sent to look — see [DeathSites].
     */
    fun raiseDeath(source: NpcEntity, radius: Double) {
        val level = source.level() as? ServerLevel ?: return
        val pos = source.position()
        val faction = SquadTeams.factionOf(source) ?: return raise(source, pos, pos, radius)
        val tick = level.gameTime
        val site = DeathSites.open(faction, pos, tick)
        if (site.checked) return
        var seen = false
        NpcRegistry.forEachWithin(level, pos, DeathSites.CONFIRM_RANGE, exclude = source) {
            if (!seen && !SquadTeams.isHostile(source, it) && DeathSites.canConfirm(it, pos)) seen = true
        }
        if (seen) {
            site.checked = true
            return
        }
        raise(source, pos, pos, radius)
    }

    fun raise(source: NpcEntity, hearOrigin: Vec3, investigatePos: Vec3, radius: Double) {
        val level = source.level() as? ServerLevel ?: return
        NpcRegistry.forEachWithin(level, hearOrigin, radius, exclude = source) {
            if (!SquadTeams.isHostile(source, it)) it.alert(investigatePos)
        }
    }
}
