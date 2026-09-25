package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.projectile.Projectile

/** Drone identification is explicit; helicopters and other airborne targets keep normal accuracy. */
object DroneCombat {
    private val DRONE_TARGETS = TagKey.create(
        Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("sbwnpc", "drone_targets")
    )

    @JvmStatic
    fun isDrone(target: Entity): Boolean =
        Ports.drones.isDrone(target) || target.type.`is`(DRONE_TARGETS)

    @JvmStatic
    fun spreadForTarget(baseSpread: Double, target: Entity?): Double =
        DroneAccuracy.adjustSpread(baseSpread, target != null && (isDrone(target) || isDrone(target.rootVehicle)))

    fun isHostileDrone(shooter: NpcEntity, target: Entity): Boolean {
        if (!isDrone(target) || !target.isAlive || target.isSpectator) return false
        if (Ports.vehicles.isWreck(target)) return false
        // An explicit drone faction wins. Remote-controlled drones normally have no scoreboard
        // team; resolve their actual controller/owner instead of treating all drones as enemies.
        if (target.team != null) return SquadTeams.isHostile(shooter, target)
        val operator = when {
            Ports.drones.isPiloted(target) -> Ports.drones.controllerOf(target)
            target is Projectile -> target.owner
            target is OwnableEntity -> target.owner
            else -> null
        } ?: return false
        return SquadTeams.isHostile(shooter, operator)
    }
}
