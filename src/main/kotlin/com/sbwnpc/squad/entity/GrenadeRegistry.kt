package com.sbwnpc.squad.entity

import com.sbwnpc.squad.domain.port.Ports
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent

/**
 * Every live hand grenade per level ([EntityIndex]). Every NPC asks "is a
 * grenade about to go off next to me?" every tick, and the answer is almost always "there are no
 * grenades at all", which this makes a single empty-map lookup.
 *
 * Only grenades on a timed fuse: they lie there long enough to run from. Contact-fuzed rounds go
 * off before anyone could react.
 */
@EventBusSubscriber
object GrenadeRegistry : EntityIndex({ Ports.grenades.isTimedGrenade(it) }) {
    @SubscribeEvent
    fun onJoin(event: EntityJoinLevelEvent) = join(event)

    @SubscribeEvent
    fun onLeave(event: EntityLeaveLevelEvent) = leave(event)
}
