package com.sbwnpc.squad.squad

import com.sbwnpc.squad.ai.tactics.CommandOutcome
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Tanks and mortars sent with infantry in one group attack, supporting it.
 *
 * A group order gives the tank a MOVE and the mortar its ATTACK under the same command as the
 * infantry's ATTACK. While none of the command's squads has made contact the tank keeps the
 * infantry's pace instead of racing ahead alone; mortars shell the area round the objective until
 * the infantry gets there. Support sent without infantry behaves as before.
 */
object FireSupport {
    /** How far a tank may run ahead of the leading infantryman before it lets the engine idle. */
    const val ESCORT_LEAD = 8.0

    /** Infantry this close to the objective has arrived; the mortars stop shelling the area. */
    const val ARRIVED_RADIUS = 48.0

    /** Commands whose squads have made contact; the escort pace no longer applies. */
    private val engaged = HashSet<UUID>()

    /** The infantry attacking under [squad]'s command, or empty when it was sent without any. */
    fun infantry(level: ServerLevel, squad: Squad): List<Squad> {
        val command = squad.command?.takeIf { squad.commandOutcome == CommandOutcome.ACTIVE }?.id ?: return emptyList()
        val manager = SquadManager.get(level)
        return manager.all().filter {
            it.id != squad.id && it.command?.id == command && it.commandOutcome == CommandOutcome.ACTIVE && it.order == SquadOrder.ATTACK &&
                !manager.isTankSquad(it) && !manager.isMortarSquad(it) && !manager.isGunshipSquad(it) && !manager.isTransportSquad(it)
        }
    }

    /**
     * Whether the tank driving [vehicle] for [driver] should let its engine idle this tick: it is
     * escorting infantry that has not met the enemy yet and is more than [ESCORT_LEAD] blocks
     * nearer [destination] than the leading infantryman.
     */
    fun holdEscortPace(level: ServerLevel, driver: NpcEntity, vehicle: Vec3, destination: Vec3): Boolean {
        val squad = driver.currentSquad() ?: return false
        val partners = infantry(level, squad)
        if (partners.isEmpty()) return false
        val command = squad.command!!.id
        if (command in engaged) return false
        if ((partners + squad).any { it.tactics.snapshot?.contacts?.isNotEmpty() == true }) {
            engaged += command
            return false
        }
        val lead = partners.flatMap { it.members }.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive }
            .minOfOrNull { horizontal(it.position(), destination) } ?: return false
        return horizontal(vehicle, destination) < lead - ESCORT_LEAD
    }

    /** Whether the infantry attacking with [squad] is still on its way to the objective. */
    fun infantryApproaching(level: ServerLevel, squad: Squad): Boolean {
        val objective = squad.objective?.center ?: return false
        val partners = infantry(level, squad)
        if (partners.isEmpty()) return false
        val men = partners.flatMap { it.members }.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive }
        return men.isNotEmpty() && men.none { horizontal(it.position(), objective) <= ARRIVED_RADIUS }
    }

    fun clearAll() = engaged.clear()

    private fun horizontal(a: Vec3, b: Vec3) = Math.hypot(a.x - b.x, a.z - b.z)
}
