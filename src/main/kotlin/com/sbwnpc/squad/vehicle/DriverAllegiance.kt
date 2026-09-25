package com.sbwnpc.squad.vehicle

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.PlayerFactionRegistry
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player

/** Whether a player may ride with an NPC driver as one of its own side. */
object DriverAllegiance {
    fun isAlliedDriver(player: Player, driver: NpcEntity): Boolean {
        val squad = driver.currentSquad()
        if (squad?.owner == player.uuid || player.isAlliedTo(driver)) return true

        // Players normally have no scoreboard team: their chosen faction is server SavedData.
        // An explicit scoreboard team takes precedence over that default, including hostile teams.
        val level = player.level() as? ServerLevel ?: return false
        val playerFaction = SquadTeams.factionOf(player)
            ?: if (player.team == null) PlayerFactionRegistry.get(level).get(player.uuid) else null
        val driverFaction = SquadTeams.factionOf(driver) ?: squad?.faction
        return playerFaction != null && playerFaction == driverFaction
    }
}
