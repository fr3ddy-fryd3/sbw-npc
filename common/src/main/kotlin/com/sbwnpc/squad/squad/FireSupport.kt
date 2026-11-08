package com.sbwnpc.squad.squad

import com.sbwnpc.squad.ai.tactics.CommandOutcome
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * Tanks and mortars sent with infantry in one group attack, supporting it.
 *
 * A group order gives the tank a MOVE and the mortar its ATTACK under the same command as the
 * infantry's ATTACK. Its tank keeps the infantry's pace until contact by its own plan
 * ([com.sbwnpc.squad.ai.tactics.TankSupport]); mortars shell the area round the objective until the
 * infantry gets there. Support sent without infantry behaves as before.
 */
object FireSupport {
    /** Infantry this close to the objective has arrived; the mortars stop shelling the area. */
    const val ARRIVED_RADIUS = 48.0

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
     * The salvos a mortar fires at an attack's point when other squads (riflemen, drone operators,
     * helicopters) attack with it (owner, 08.11): enough to soften the point, then it waits for them
     * to find the enemy instead of shelling an empty area for the whole approach.
     */
    const val PREPARATION_SALVOS = 5

    /** Whether any squad other than a mortar squad attacks under [squad]'s command. */
    fun partnered(level: ServerLevel, squad: Squad): Boolean {
        val command = squad.command?.takeIf { squad.commandOutcome == CommandOutcome.ACTIVE }?.id ?: return false
        val manager = SquadManager.get(level)
        return manager.all().any { it.id != squad.id && it.command?.id == command && it.commandOutcome == CommandOutcome.ACTIVE && !manager.isMortarSquad(it) }
    }

    /** Whether the infantry attacking with [squad] is still on its way to the objective. */
    fun infantryApproaching(level: ServerLevel, squad: Squad): Boolean {
        val objective = squad.objective?.center ?: return false
        val partners = infantry(level, squad)
        if (partners.isEmpty()) return false
        val men = partners.flatMap { it.members }.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive }
        return men.isNotEmpty() && men.none { horizontal(it.position(), objective) <= ARRIVED_RADIUS }
    }

    private fun horizontal(a: Vec3, b: Vec3) = Math.hypot(a.x - b.x, a.z - b.z)
}
