package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.data.gun.FireMode
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.init.ModDamageTypes
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.sbwnpc.squad.domain.port.Guns
import com.sbwnpc.squad.domain.port.HandGun
import com.sbwnpc.squad.domain.port.TriggerMode
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.Tag
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.util.UUID

object SbwGuns : Guns {
    override fun inHand(holder: LivingEntity): HandGun? {
        val stack = holder.mainHandItem
        if (stack.item !is GunItem) return null
        return SbwHandGun(holder, GunData.from(stack))
    }

    override fun isGun(stack: ItemStack): Boolean = stack.item is GunItem

    override fun issue(item: ResourceLocation, holder: LivingEntity, reserve: Int): ItemStack {
        val gun = BuiltInRegistries.ITEM.getOptional(item).orElse(null) as? GunItem ?: return ItemStack.EMPTY
        val data = GunData.from(ItemStack(gun))
        data.virtualAmmo.set(reserve)
        data.reloadAmmo(holder)
        data.save()
        return data.stack
    }

    override fun roundsLeft(stack: ItemStack): Int {
        if (stack.item !is GunItem) return 0
        val data = GunData.from(stack)
        return data.ammo.get() + data.virtualAmmo.get()
    }

    // Attachments are all that render on a gun someone else is holding: ammo, heat and the
    // post-shot timers only drive the first-person animation.
    override fun looks(stack: ItemStack): Any? {
        if (stack.item !is GunItem) return null
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.unsafe ?: return Unit
        // GunData.KEY_ATTACHMENTS — private in SBW, mirrored here.
        return if (tag.contains("Attachments", Tag.TAG_COMPOUND.toInt())) tag.getCompound("Attachments") else Unit
    }
}

private const val TBG_ROCKET = "superbwarfare:rpg_rocket_tbg"
private const val TBG_BOOST = 1.03
private const val MAX_ARC_DEGREES = 45.0
private const val ARC_SEARCH_STEPS = 16

private class SbwHandGun(private val holder: LivingEntity, private val data: GunData) : HandGun {
    override val roundsPerMinute: Double get() = data.get(GunProp.RPM).toDouble()
    override val muzzleVelocity: Double get() = data.get(GunProp.VELOCITY).toDouble()
    override val explosionRadius: Double get() = data.get(GunProp.EXPLOSION_RADIUS)
    override val pellets: Int get() = data.get(GunProp.PROJECTILE_AMOUNT)
    override val damage: Double get() = data.get(GunProp.DAMAGE)

    override val triggerMode: TriggerMode
        get() = when (data.selectedFireModeInfo().mode) {
            FireMode.SEMI -> TriggerMode.SEMI
            FireMode.BURST -> TriggerMode.BURST
            // No mode selected: fire as the old code did, without a trigger reset.
            FireMode.AUTO, null -> TriggerMode.AUTO
        }

    override val needsTriggerReset: Boolean
        get() = when (triggerMode) {
            TriggerMode.SEMI -> true
            TriggerMode.BURST -> data.burstAmount.get() == 0
            TriggerMode.AUTO -> false
        }

    override fun hasAmmo(): Boolean = data.countBackupAmmo(holder) > 0 || data.hasEnoughAmmoToShoot(holder)

    override fun operate() {
        data.tick(holder, true)
        if (data.shouldStartReloading(holder)) data.startReload()
        if (data.shouldStartBolt()) data.startBolt()
    }

    override fun canShoot(): Boolean = data.canShoot(holder)

    // Flies the round the way FastThrowableProjectile does — move, then gravity — plus the TBG
    // rocket's own 3% a tick boost, and searches for the elevation whose path passes [to].
    // Bullets drop too little over a fight's distances to be worth it; only explosive rounds
    // (launchers) are slow and heavy enough.
    override fun arcPitch(from: Vec3, to: Vec3): Float? {
        if (explosionRadius <= 0.0) return null
        val gravity = data.get(GunProp.GRAVITY)
        if (gravity <= 0.0) return null
        val boost = if (data.get(GunProp.PROJECTILE).itemId.trim() == TBG_ROCKET) TBG_BOOST else 1.0
        val life = data.get(GunProp.PROJECTILE_LIFE)
        val dx = to.x - from.x
        val dz = to.z - from.z
        val range = Math.sqrt(dx * dx + dz * dz)
        val rise = to.y - from.y
        if (range < 1.0) return null

        // Height of the path where it crosses [range], or null if it never gets there.
        fun heightAt(elevationDeg: Double): Double? {
            val rad = Math.toRadians(elevationDeg)
            var vh = Math.cos(rad) * muzzleVelocity
            var vy = Math.sin(rad) * muzzleVelocity
            var h = 0.0
            var y = 0.0
            for (tick in 1..life) {
                val nh = h + vh
                if (nh >= range) return y + vy * (range - h) / vh
                h = nh
                y += vy
                vy -= gravity
                if (tick > 2) { vh *= boost; vy *= boost }
            }
            return null
        }

        // The low arc: height at the target grows with elevation up to the flat-fire maximum.
        var low = -MAX_ARC_DEGREES
        var high = MAX_ARC_DEGREES
        val top = heightAt(high) ?: return null
        if (top < rise) return null
        repeat(ARC_SEARCH_STEPS) {
            val mid = (low + high) / 2
            val y = heightAt(mid)
            if (y != null && y >= rise) high = mid else low = mid
        }
        return (-high).toFloat()
    }

    override fun shootAt(spread: Double, zoom: Boolean, target: UUID) = data.shoot(holder, spread, zoom, target)

    override fun shootAt(spread: Double, point: Vec3) = data.shoot(holder, spread, false, null, point)

    override fun inflictHit(target: LivingEntity, damage: Float) {
        target.hurt(ModDamageTypes.causeGunFireDamage(holder.level().registryAccess(), holder, holder), damage)
    }

    // Mirrors GunItem.shootInternal/afterShoot so the magazine, burst counter and reload logic
    // behave exactly as after a real shot.
    override fun spendShot() {
        if (triggerMode == TriggerMode.BURST) {
            val amount = data.burstAmount.get()
            data.burstAmount.set(if (amount == 0) data.get(GunProp.BURST_AMOUNT) - 1 else maxOf(0, amount - 1))
        }
        val cost = data.get(GunProp.AMMO_COST_PER_SHOOT)
        if (!data.useBackpackAmmo()) {
            data.ammo.set(data.ammo.get() - cost)
        } else {
            data.consumeBackupAmmo(holder, cost)
        }
        if (!data.hasEnoughAmmoToShoot(holder)) data.burstAmount.reset()
        data.save()
    }
}
