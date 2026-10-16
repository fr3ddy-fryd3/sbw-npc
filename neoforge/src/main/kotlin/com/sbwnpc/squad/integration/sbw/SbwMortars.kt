package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.item.misc.FiringParametersItem
import com.atsuishio.superbwarfare.item.misc.firingParameters
import com.atsuishio.superbwarfare.item.projectile.MortarShellItem
import com.atsuishio.superbwarfare.tools.TrajectoryCalculator
import com.sbwnpc.squad.domain.port.Mortars
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB

object SbwMortars : Mortars {
    private const val WEAPON = "Main"

    override fun isMortar(entity: Entity?): Boolean = entity is MortarEntity

    override fun within(level: Level, area: AABB, filter: (Entity) -> Boolean): List<Entity> =
        level.getEntitiesOfClass(MortarEntity::class.java, area) { filter(it) }

    override fun create(level: ServerLevel, yaw: Float): Entity =
        MortarEntity(level, yaw).also { it.intelligent = true }

    // A non-"intelligent" mortar auto-fires on any inventory change (MortarEntity.setChanged),
    // simulating a dumb mortar that discharges as soon as a shell is dropped in. The same flag a
    // player sets by binding a Monitor item.
    override fun takeControl(mortar: Entity) {
        (mortar as? MortarEntity)?.intelligent = true
    }

    override fun hasShell(mortar: Entity): Boolean {
        if (mortar !is MortarEntity) return false
        val loaded = mortar.getItems().firstOrNull()
        return loaded != null && !loaded.isEmpty && loaded.item is MortarShellItem
    }

    // The mortar's own container caps this slot at 1 shell (VehicleEntity.maxStackSize override):
    // it holds exactly one round in the tube at a time.
    override fun loadShell(mortar: Entity) {
        (mortar as? MortarEntity)?.setItem(0, ItemStack(ModItems.MORTAR_SHELL.get(), 1))
    }

    // Mirrors the feasibility check `MortarEntity.setTarget` does internally (both a flat and a
    // lofted trajectory are computed; at least one must exist and fit the turret's pitch limits).
    // The solver itself fails silently, keeping whatever aim it had, so this is the only way to know.
    override fun canReach(mortar: Entity, target: BlockPos): Boolean {
        if (mortar !is MortarEntity) return false
        val v = mortar.getProjectileVelocity(WEAPON).toDouble()
        val g = mortar.getProjectileGravity(WEAPON).toDouble()
        val aimPoint = target.center.add(0.0, -1.0, 0.0)
        val flat = TrajectoryCalculator.calculateLaunchVector(mortar.eyePosition, aimPoint, v, g, true)
        val high = TrajectoryCalculator.calculateLaunchVector(mortar.eyePosition, aimPoint, v, g, false)
        if (flat == null || high == null) return false
        val angle = -VehicleVecUtils.getXRotFromVector(flat).toFloat()
        val angle2 = -VehicleVecUtils.getXRotFromVector(high).toFloat()
        val minPitch = mortar.turretMinPitch
        val maxPitch = mortar.turretMaxPitch
        if (angle < -maxPitch || angle > -minPitch) {
            return angle2 > -maxPitch && angle2 < -minPitch
        }
        return true
    }

    override fun lay(mortar: Entity, target: BlockPos, scatter: Int, gunner: Entity) {
        if (mortar !is MortarEntity) return
        val stack = ItemStack(ModItems.FIRING_PARAMETERS.get())
        stack.firingParameters = FiringParametersItem.Parameters(target, scatter, false)
        mortar.setTarget(stack, gunner, WEAPON)
    }

    override fun fire(mortar: Entity, gunner: LivingEntity) {
        (mortar as? MortarEntity)?.vehicleShoot(gunner, WEAPON, null)
    }
}
