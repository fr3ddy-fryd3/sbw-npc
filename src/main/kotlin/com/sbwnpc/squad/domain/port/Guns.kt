package com.sbwnpc.squad.domain.port

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** How the trigger works. */
enum class TriggerMode { SEMI, BURST, AUTO }

/**
 * A gun in someone's hands, worked through the one holding it. Get it from [Guns.inHand] on the
 * tick it is used and don't keep it: the stack underneath can be swapped at any time.
 */
interface HandGun {
    val roundsPerMinute: Double
    /** Blocks per tick. */
    val muzzleVelocity: Double
    /** Zero for anything that doesn't explode on impact. */
    val explosionRadius: Double
    val pellets: Int
    /** Per pellet. */
    val damage: Double
    val triggerMode: TriggerMode

    /** The next shot needs a fresh trigger pull: a semi-automatic, or a burst that has just ended. */
    val needsTriggerReset: Boolean

    /** Anything left to fire, in the magazine or in reserve. */
    fun hasAmmo(): Boolean

    /** Runs the gun's own timers and starts a reload or a bolt cycle when one is due. Once a tick
     *  while the gun is in use. */
    fun operate()

    fun canShoot(): Boolean

    /** Pitch (Minecraft `xRot`, degrees, negative is up) that drops this gun's round onto [to]
     *  when fired from [from], for rounds slow and heavy enough for the drop to matter. Null when
     *  a straight aim is good enough, or when the round can't reach [to] at all. */
    fun arcPitch(from: Vec3, to: Vec3): Float?

    fun shootAt(spread: Double, zoom: Boolean, target: UUID)

    fun shootAt(spread: Double, point: Vec3)

    /** Hurts [target] as one of this gun's bullets would. */
    fun inflictHit(target: LivingEntity, damage: Float)

    /** Spends one shot's ammunition and burst without firing anything — for a shot resolved
     *  without a projectile. */
    fun spendShot()
}

interface Guns {
    fun inHand(holder: LivingEntity): HandGun?

    fun isGun(stack: ItemStack): Boolean

    /** A gun of [item] with a full magazine and [reserve] rounds behind it, or empty if [item] is
     *  not a gun. */
    fun issue(item: ResourceLocation, holder: LivingEntity, reserve: Int): ItemStack

    /** Magazine plus reserve carried with the gun itself; zero for anything that isn't a gun. */
    fun roundsLeft(stack: ItemStack): Int

    /** The part of a gun's state that shows on it: two stacks of the same gun look alike when
     *  this is equal. Null for anything that isn't a gun. */
    fun looks(stack: ItemStack): Any?
}
