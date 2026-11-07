package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity

/**
 * A vehicle's free seats held for its own squad (#255, owner's agreed rule).
 *
 * A vehicle whose driver or pilot is an NPC of squad S keeps as many free seats as S has members
 * not aboard within [RADIUS] blocks; anyone else is refused them (`reserved`), and the seats beyond
 * that count stay open to all. Every boarding check reads it: an NPC of another squad who
 * happened to be closer took the seats the vehicle's own squad was walking to.
 *
 * Nothing is stored: the hold is derived from the current state each time, so a member who walks
 * off past [RADIUS], dies or boards frees his seat by himself.
 */
object SeatHold {
    /** How far from the vehicle a member of its squad still has a seat kept for him. */
    const val RADIUS = 60.0

    /**
     * The seats [vehicle] holds against [boarding]: none for a man of the squad its NPC driver or
     * pilot belongs to, none without such a driver, else the members of that squad off any
     * vehicle within [RADIUS] of it.
     */
    fun heldFor(vehicle: Entity, boarding: Entity): Int {
        val driver = Ports.vehicles.seating(vehicle).firstOrNull() as? NpcEntity ?: return 0
        val squad = driver.currentSquad() ?: return 0
        if ((boarding as? NpcEntity)?.squadId == squad.id) return 0
        val level = vehicle.level() as? ServerLevel ?: return 0
        return squad.members.count { id ->
            val member = level.getEntity(id) ?: return@count false
            member.isAlive && member.vehicle == null && member.distanceToSqr(vehicle) <= RADIUS * RADIUS
        }
    }

    /** Whether [boarding] is refused every free seat of [vehicle] that [occupied] leaves: all of them are held. */
    fun refuses(vehicle: Entity, boarding: Entity, occupied: Int): Boolean = refuses(Ports.vehicles.seatCount(vehicle), occupied, heldFor(vehicle, boarding))

    /** The count: of [seats], [occupied] are taken and [held] of the rest are kept for the vehicle's squad. */
    fun refuses(seats: Int, occupied: Int, held: Int): Boolean = seats - occupied in 1..held
}
