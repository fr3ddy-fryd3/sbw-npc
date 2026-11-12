package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import java.util.UUID

/**
 * A helicopter's rotor, heard [RADIUS] blocks around in a straight line (#246). Hearers of the
 * other side turn towards it and grow wary of that side: only a direction. None walks off to look
 * at an aircraft, and it is still to be seen ([Noticing]) before anyone takes it as a target.
 * Apart from [Hearing], whose noises send men to look.
 */
object RotorNoise {
    /** How far a rotor carries. */
    const val RADIUS = 96.0

    private const val INTERVAL_TICKS = 20L
    private const val KEPT = 256
    private val last = HashMap<UUID, Long>()

    /** [heli], flown by [pilot], is in the air: once a second per aircraft. */
    fun heard(level: ServerLevel, heli: Entity, pilot: Entity) {
        val now = level.gameTime
        val previous = last[heli.uuid]
        if (previous != null && now - previous < INTERVAL_TICKS) return
        last[heli.uuid] = now
        if (last.size > KEPT) last.entries.removeIf { now - it.value > INTERVAL_TICKS }
        val at = heli.position()
        var hearers = 0
        NpcRegistry.forEachWithin(level, at, RADIUS, exclude = pilot) { npc ->
            if (!npc.isAlive || npc.vehicle === heli || !SquadTeams.isHostile(npc, pilot)) return@forEachWithin
            hearers++
            // Wary only, not hear(): a heard spot goes on the squad's alarm list, and its scouts were
            // sent to search a point in the sky.
            if (npc.target == null) npc.becomeWary(at)
        }
        if (hearers > 0) DebugFlags.log(LogGroup.HEARING, "rotor of {} heard by {} within {}", heli.uuid, hearers, RADIUS)
    }

    fun clearAll() = last.clear()
}
