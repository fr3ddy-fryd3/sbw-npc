package com.sbwnpc.squad.experience

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.Projectile
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import java.util.WeakHashMap

@EventBusSubscriber
object ExperienceEvents {
    private val healthBefore = WeakHashMap<LivingEntity, Float>()

    fun clear() = healthBefore.clear()

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun before(event: LivingDamageEvent.Pre) {
        if (!event.entity.level().isClientSide) healthBefore[event.entity] = event.entity.health
    }

    @SubscribeEvent
    fun after(event: LivingDamageEvent.Post) {
        val entity = event.entity
        val before = healthBefore.remove(entity) ?: return
        val lost = minOf(before, event.newDamage).coerceAtLeast(0f)
        if (entity.maxHealth > 0) ExperienceAwards.damage(entity, event.source, lost / entity.maxHealth.toDouble(), entity.health <= 0f)
    }

    @SubscribeEvent
    fun projectile(event: EntityJoinLevelEvent) {
        if (event.level.isClientSide) return
        val projectile = event.entity as? Projectile ?: return
        val owner = projectile.owner as? NpcEntity ?: return
        val shares = ExperienceAwards.shares(owner, projectile)
        val tag = net.minecraft.nbt.CompoundTag()
        shares.forEach { (id, share) -> tag.putDouble(id.toString(), share) }
        Ports.loader.persistentData(projectile).put("sbwnpcExperienceCrew", tag)
    }
}
