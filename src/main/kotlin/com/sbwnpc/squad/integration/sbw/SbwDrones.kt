package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.data.CustomData
import com.atsuishio.superbwarfare.entity.projectile.SwarmDroneEntity
import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity
import com.atsuishio.superbwarfare.init.ModEntities
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.tools.CustomExplosion
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.domain.port.Drones
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

/**
 * Covers SBW's own `DroneEntity` and the Drone Warfare addon's subclasses alike.
 *
 * Kamikaze drones come in two kinds. Preferred: the addon's FPV drone — "the drone itself is the
 * weapon". Its crash-explosion mixin fires on destroy() for any drone WITHOUT SBW's kamikaze flag,
 * so detonating is just destroying it, and being shot down or falling into water explodes it
 * exactly like a player's would. Spawned by registry id so the addon stays an optional runtime
 * dependency. Fallback without the addon: SBW's bare drone with a kamikaze attachment, and our
 * own stand-in for SBW's `kamikazeExplosion`, which needs a player controller.
 */
object SbwDrones : Drones {
    /** Fallback warhead (no addon) — any SBW `drone_attachments` entry with IsKamikaze.
     *  RPG TBG: 150 dmg / r 11. */
    private val WARHEAD_ITEM = ModItems.RPG_ROCKET_TBG

    /** Drone Warfare addon's FPV drone ("cubed_fpv_drone"). A DroneEntity subclass, so it flies
     *  exactly like SBW's own. */
    private val ADDON_FPV_DRONE_ID = ResourceLocation.fromNamespaceAndPath("sbwdroneconfig", "cubed_fpv_drone")

    // DroneCrashExplosionSystem.FPV_CRASH_EXPLOSION_POWER = 5.8 (vanilla explosion power; damage
    // reaches ~2x that).
    private const val ADDON_FPV_BLAST_RADIUS = 12.0

    override fun isPiloted(entity: Entity?): Boolean = entity is DroneEntity

    override fun isDrone(entity: Entity?): Boolean = entity is DroneEntity || entity is SwarmDroneEntity

    override fun controllerOf(drone: Entity): Entity? = (drone as? DroneEntity)?.getController()

    override fun createKamikaze(level: ServerLevel): Entity {
        val addonType = BuiltInRegistries.ENTITY_TYPE.getOptional(ADDON_FPV_DRONE_ID).orElse(null)
        (addonType?.create(level) as? DroneEntity)?.let { return it }
        return DroneEntity(ModEntities.DRONE.get(), level).also(::armWarhead)
    }

    override fun blowsUpOnCrash(drone: Entity): Boolean =
        drone is DroneEntity && drone.type !== ModEntities.DRONE.get()

    override fun warheadRadius(blowsUpOnCrash: Boolean): Double =
        if (blowsUpOnCrash) ADDON_FPV_BLAST_RADIUS
        else CustomData.DRONE_ATTACHMENT[DroneEntity.getItemId(ItemStack(WARHEAD_ITEM.get()))]?.explosionRadius?.toDouble() ?: 0.0

    /** destroy() is what the addon's crash-explosion mixin hooks; it discards the entity itself. */
    override fun crash(drone: Entity) {
        if (drone !is DroneEntity || !drone.isAlive) return
        drone.isWreck = true
        drone.destroy()
    }

    // Same datapack-driven damage/radius and the same `CustomExplosion` as SBW's own kamikaze blast.
    override fun explodeWarhead(level: ServerLevel, operator: Entity, drone: Entity?, at: Vec3) {
        val payload = (drone as? DroneEntity)?.currentItem?.takeIf { !it.isEmpty } ?: ItemStack(WARHEAD_ITEM.get())
        val data = CustomData.DRONE_ATTACHMENT[DroneEntity.getItemId(payload)] ?: return
        val bomb = EntityType.byString(data.displayEntity()).map { it.create(level) }.orElse(null)
        val direct = drone ?: operator
        CustomExplosion.Builder(direct)
            .source(bomb ?: direct)
            .attacker(operator)
            .damage(data.explosionDamage)
            .radius(data.explosionRadius)
            .position(at)
            .explode()
        DebugFlags.log("[drone-debug] {} warhead detonated at {}", operator.uuid, at)
    }

    override fun setInputs(drone: Entity, forward: Boolean, back: Boolean, up: Boolean, down: Boolean) {
        if (drone !is DroneEntity) return
        drone.forwardInputDown = forward
        drone.backInputDown = back
        drone.upInputDown = up
        drone.downInputDown = down
        drone.leftInputDown = false
        drone.rightInputDown = false
    }

    override fun monitor(): ItemStack = ItemStack(ModItems.MONITOR.get())

    override fun isMonitor(stack: ItemStack): Boolean = stack.`is`(ModItems.MONITOR.get())

    /** Mirrors the player-side `DroneEntity.interact` attach branch for a kamikaze payload. */
    private fun armWarhead(drone: DroneEntity) {
        val payload = ItemStack(WARHEAD_ITEM.get())
        val data = CustomData.DRONE_ATTACHMENT[DroneEntity.getItemId(payload)] ?: return
        drone.currentItem = payload
        drone.entityData.set(DroneEntity.DISPLAY_ENTITY, data.displayEntity())
        drone.entityData.set(DroneEntity.IS_KAMIKAZE, true)
        drone.entityData.set(DroneEntity.MAX_AMMO, 1)
        drone.setAmmo(1)
        val scale = data.scale()
        val offset = data.offset()
        val rotation = data.rotation()
        drone.entityData.set(
            DroneEntity.DISPLAY_DATA, listOf(
                scale[0], scale[1], scale[2],
                offset[0], offset[1], offset[2],
                rotation[0], rotation[1], rotation[2],
                data.xLength, data.zLength,
                data.tickCount.toFloat()
            )
        )
    }
}
