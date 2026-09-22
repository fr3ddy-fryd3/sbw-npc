package com.sbwnpc.squad.vehicle

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.sbwnpc.squad.combat.DebugFlags
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * A helicopter whose pilot was killed, and the short grace period before it comes down.
 *
 * SuperbWarfare already does everything after that: an aircraft with no first passenger has its
 * controls zeroed and its rotor bled off, so it falls on its own, and a wreck of an aircraft
 * explodes the moment it touches the ground (`VehicleEntity` sets `isWreck` at zero health, then
 * detonates it on contact). So all this has to decide is *when* the machine stops being flyable —
 * done by taking its health, which is the same door SBW's own destruction path comes through.
 *
 * The grace period is the point: a gunner or a rifleman on the bench can climb into the empty seat
 * and fly it home. Only if nobody does does it become a wreck.
 */
object PilotlessHelicopters {

    /** How long the seat can stay empty before the aircraft is written off. */
    private const val GRACE_TICKS = 100L

    /** Helicopter id -> game time at which it is written off. Small by nature: one entry per
     *  aircraft that has actually lost its pilot, cleared as soon as it is resolved either way. */
    private val deadlines = HashMap<UUID, Long>()

    /** Called from the pilot's own death. */
    fun pilotDown(level: ServerLevel, heli: VehicleEntity) {
        if (!Helicopters.isHelicopter(heli) || heli.isWreck) return
        if (Helicopters.isGrounded(level, heli)) return // already down; nothing to fall
        deadlines.putIfAbsent(heli.uuid, level.gameTime + GRACE_TICKS)
        DebugFlags.log("[heli-debug] {} lost its pilot, {} ticks to recover", heli.uuid, GRACE_TICKS)
    }

    fun clear() = deadlines.clear()

    fun tick(server: MinecraftServer) {
        if (deadlines.isEmpty()) return
        val iterator = deadlines.entries.iterator()
        while (iterator.hasNext()) {
            val (id, deadline) = iterator.next()
            val heli = find(server, id)
            if (heli == null || heli.isWreck) {
                iterator.remove()
                continue
            }
            val level = heli.level() as? ServerLevel ?: continue
            // Someone took the controls, or it settled on its own before the time was up.
            if (heli.firstPassenger != null || Helicopters.isGrounded(level, heli)) {
                iterator.remove()
                continue
            }
            if (level.gameTime < deadline) continue
            iterator.remove()
            DebugFlags.log("[heli-debug] {} nobody took the controls, writing it off", heli.uuid)
            // SBW turns this into a wreck on its next tick and detonates it where it lands.
            heli.health = 0f
        }
    }

    private fun find(server: MinecraftServer, id: UUID): VehicleEntity? {
        for (level in server.allLevels) {
            (level.getEntity(id) as? VehicleEntity)?.let { return it }
        }
        return null
    }
}
