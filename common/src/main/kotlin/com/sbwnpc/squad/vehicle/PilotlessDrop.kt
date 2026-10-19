package com.sbwnpc.squad.vehicle

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity

/**
 * Brings a helicopter down once its pilot's seat has been empty in the air for [GRACE_TICKS], run
 * by whoever is still aboard.
 *
 * SBW flies whoever is first in the vanilla passenger list, not seat 0. After the pilot dies a
 * gunner takes that place; pressing nothing, he left SBW's own vertical damping holding the aircraft
 * in the air, with the dead pilot's last stick input still set (#127). Holding the collective down
 * does not help either: with anyone aboard SBW keeps the rotor power at a floor about the hover
 * power. So the engine is cut, as the pilot does on the pad, and the aircraft falls. The grace
 * period leaves a player time to take the controls.
 */
class PilotlessDrop {
    private var emptySince: Long? = null

    /** Called every tick by a crewman aboard [heli]. */
    fun tick(level: ServerLevel, heli: Entity) {
        if (Ports.vehicles.seating(heli).getOrNull(PILOT_SEAT) != null || Helicopters.isGrounded(level, heli)) {
            emptySince = null
            return
        }
        val since = emptySince ?: level.gameTime.also { emptySince = it }
        if (level.gameTime - since < GRACE_TICKS) return
        if (level.gameTime - since == GRACE_TICKS) DebugFlags.log(LogGroup.HELI, "{} has had no pilot for {} ticks, going down", heli.uuid, GRACE_TICKS)
        Ports.vehicles.neutralControls(heli)
        Ports.vehicles.shutDownEngine(heli)
    }

    private companion object {
        const val PILOT_SEAT = 0
        const val GRACE_TICKS = 100L
    }
}
