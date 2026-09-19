package com.sbwnpc.squad.squad

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import org.joml.Vector3f

/**
 * Small "you selected this one" cue for the command tool — a particle puff above each NPC
 * currently in a loose pick (the "who goes into the new squad" phase), refreshed on a short
 * interval. Sent only to the selecting player via the targeted-particle overload (same idiom
 * SquadManager.spawnObjectiveMarker already uses), not a synced glow outline every nearby player
 * would also see — deliberately no client sync / render-layer work beyond that.
 *
 * Deliberately NOT shown for an already-selected/commanded squad (SquadSelection.selectedSquad) —
 * that state is meant to persist for as long as you're commanding it, unlike a loose pick, and a
 * whole squad (up to 16) glowing forever would be exactly the noise this was meant to avoid.
 */
object SelectionHighlight {
    private const val INTERVAL_TICKS = 10
    private var nextTick = 0
    private val COLOR = Vector3f(1f, 1f, 1f)

    fun tick(server: MinecraftServer) {
        val now = server.tickCount
        if (now < nextTick) return
        nextTick = now + INTERVAL_TICKS

        val options = DustParticleOptions(COLOR, 1.0f)
        for (player in server.playerList.players) {
            val level = player.level() as? ServerLevel ?: continue
            val members = SquadSelection.looseOf(player.uuid)
            for (id in members) {
                val npc = level.getEntity(id) as? NpcEntity ?: continue
                level.sendParticles(
                    player, options, true,
                    npc.x, npc.y + npc.bbHeight + 0.3, npc.z,
                    3, 0.15, 0.05, 0.15, 0.0
                )
            }
        }
    }
}
