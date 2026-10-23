package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.api.event.ShootEvent
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.value.AttachmentType
import com.sbwnpc.squad.combat.Hearing
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.mixin.ExplosionAccessor
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Explosion
import net.minecraftforge.common.MinecraftForge
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
        MinecraftForge.EVENT_BUS.addListener(Consumer<ShootEvent.Post>(::onShot))
    }

    /** Players and anything else with a gun. NPCs are heard from GunAttackBehaviour instead. */
    private fun onShot(event: ShootEvent.Post) {
        val shooter = event.shooter ?: return
        if (shooter is NpcEntity) return
        Hearing.gunshot(event.level, shooter, radius(event.data))
    }

    /** Called by the explosion hook once an explosion has run. */
    @JvmStatic
    fun onExplosion(level: ServerLevel, explosion: Explosion) {
        Hearing.explosion(level, explosion.position, (explosion as ExplosionAccessor).sbwnpcRadius().toFloat(), explosion.indirectSourceEntity)
    }
}
