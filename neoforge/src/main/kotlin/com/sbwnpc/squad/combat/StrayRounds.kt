package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.EntityIndex
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.projectile.Projectile
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent

/**
 * Clears away NPC rounds that have stopped in the air. A round that misses flies on, and one that
 * leaves the chunks where entities tick stops there — loaded, but never ticked again, so the
 * couple of seconds it has to live never run out. A big fight left thousands of them hanging
 * along the edge of the ticking area, every one an entity the server keeps and sends to players.
 * Players' own rounds are SBW's to look after and left alone.
 */
@EventBusSubscriber
object StrayRounds : EntityIndex({ Ports.guns.isRound(it) }) {
    private const val SWEEP_TICKS = 20

    @SubscribeEvent
    fun onJoin(event: EntityJoinLevelEvent) = join(event)

    @SubscribeEvent
    fun onLeave(event: EntityLeaveLevelEvent) = leave(event)

    fun tick(server: MinecraftServer) {
        if (server.tickCount % SWEEP_TICKS != 0) return
        for (level in server.allLevels) {
            // Collected first: discarding fires the leave event, which takes it out of the index.
            val stuck = all(level).filter {
                !it.isRemoved && !level.isPositionEntityTicking(it.blockPosition()) && (it as? Projectile)?.owner is NpcEntity
            }
            stuck.forEach { it.discard() }
            if (stuck.isNotEmpty()) {
                DebugFlags.log(LogGroup.PERF, "{} stray NPC rounds cleared in {}, {} rounds in flight", stuck.size, level.dimension().location(), all(level).size)
            }
        }
    }
}
