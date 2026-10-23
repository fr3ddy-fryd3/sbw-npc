package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.api.event.ShootEvent
import com.atsuishio.superbwarfare.api.event.SuperbWarfareEvents
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.value.AttachmentType
import com.sbwnpc.squad.combat.Hearing
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Explosion
import java.util.function.Consumer

/** SuperbWarfare's shots and every explosion, handed to [Hearing]. */
object SbwHearing {
    /** SBW's own "silent" barrel attachment. */
    private const val SUPPRESSOR = 2

    fun radius(data: GunData): Double {
        val base = data.get(GunProp.SOUND_RADIUS) * Hearing.GUNSHOT_SCALE
        return if (data.attachment.get(AttachmentType.BARREL) == SUPPRESSOR) base * Hearing.SUPPRESSED_SCALE else base
    }

    fun register() {
        SuperbWarfareEvents.register(ShootEvent.Post::class.java, Consumer(::onShot))
    }

    /** Players and anything else with a gun. NPCs are heard from GunAttackBehaviour instead. */
    private fun onShot(event: ShootEvent.Post) {
        val shooter = event.shooter ?: return
        if (shooter is NpcEntity) return
        Hearing.gunshot(event.level, shooter, radius(event.data))
    }

    /** Called by the explosion mixin once an explosion has run. */
    @JvmStatic
    fun onExplosion(level: ServerLevel, explosion: Explosion) {
        Hearing.explosion(level, explosion.center(), explosion.radius(), explosion.indirectSourceEntity)
    }
}
