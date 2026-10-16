package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.squad.SquadManager
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.tslat.smartbrainlib.util.BrainUtils

/** Player contacts describe a life, not the persistent profile UUID used by the next entity. */
@EventBusSubscriber(modid = "sbwnpc")
object PlayerContactCleanup {
    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun onDeath(event: LivingDeathEvent) {
        (event.entity as? ServerPlayer)?.let { forget(it, "death") }
    }

    @SubscribeEvent
    fun onRespawn(event: PlayerEvent.PlayerRespawnEvent) {
        (event.entity as? ServerPlayer)?.let { forget(it, "respawn") }
    }

    @SubscribeEvent
    fun onLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        (event.entity as? ServerPlayer)?.let { forget(it, "logout") }
    }

    private fun forget(player: ServerPlayer, reason: String) {
        val id = player.uuid
        TeamAwareness.forget(id)
        var squads = 0
        for (squad in SquadManager.get(player.server).all()) {
            val state = squad.tactics
            if (state.contacts.remove(id) != null || state.snapshot?.contacts?.any { it.id == id } == true) {
                // Invalidate both sources: a sensor can ask for a cached snapshot in the same tick.
                state.snapshot = state.snapshot?.let { view -> view.copy(contacts = view.contacts.filter { it.id != id }) }
                state.nextAssessment = Long.MIN_VALUE
                squads++
            }
        }
        var targets = 0
        for (level in player.server.allLevels) {
            for (npc in NpcRegistry.all(level)) {
                if (npc.target?.uuid == id) {
                    BrainUtils.setTargetOfEntity(npc, null)
                    targets++
                }
                if (npc.lastHurtByMob?.uuid == id) npc.lastHurtByMob = null
                npc.forgetContact(id)
            }
        }
        DebugFlags.log(LogGroup.ORDER, "[tactics] event=player_contact_reset target={} reason={} squads={} targets={}", id, reason, squads, targets)
    }
}
