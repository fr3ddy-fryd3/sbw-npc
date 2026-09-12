package com.sbwnpc.squad.combat

import com.sbwnpc.squad.squad.SquadManager
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent

/**
 * Listens for ANY living entity's death (not just NpcEntity — a squad can be ATTACK-focused on a
 * vanilla mob/player, or DEFEND-guarding one, just as easily) and unsticks any squad that had it as
 * `focusEntity`. See [SquadManager.clearDeadFocus] for the actual failure mode this fixes.
 */
@EventBusSubscriber
object SquadFocusCleanup {
    @SubscribeEvent
    fun onDeath(event: LivingDeathEvent) {
        val level = event.entity.level() as? ServerLevel ?: return
        val deathPos = BlockPos.containing(event.entity.position())
        SquadManager.get(level).clearDeadFocus(event.entity.uuid, deathPos)
    }
}
