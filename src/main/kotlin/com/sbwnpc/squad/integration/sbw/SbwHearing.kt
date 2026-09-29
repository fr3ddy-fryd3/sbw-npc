package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.api.event.ShootEvent
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.value.AttachmentType
import com.sbwnpc.squad.combat.Hearing
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.level.ExplosionEvent

/** SuperbWarfare's shots and every explosion, handed to [Hearing]. */
@EventBusSubscriber
object SbwHearing {
    /** SBW's own "silent" barrel attachment. */
    private const val SUPPRESSOR = 2

    fun radius(data: GunData): Double {
        val base = data.get(GunProp.SOUND_RADIUS) * Hearing.GUNSHOT_SCALE
        return if (data.attachment.get(AttachmentType.BARREL) == SUPPRESSOR) base * Hearing.SUPPRESSED_SCALE else base
    }

    /** Players and anything else with a gun. NPCs are heard from GunAttackBehaviour instead. */
    @SubscribeEvent
    fun onShot(event: ShootEvent.Post) {
        val shooter = event.shooter ?: return
        if (shooter is NpcEntity) return
        Hearing.gunshot(event.level, shooter, radius(event.data))
    }

    @SubscribeEvent
    fun onExplosion(event: ExplosionEvent.Detonate) {
        val level = event.level as? ServerLevel ?: return
        val explosion = event.explosion
        Hearing.explosion(level, explosion.center(), explosion.radius(), explosion.indirectSourceEntity)
    }
}
