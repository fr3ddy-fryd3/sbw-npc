package com.sbwnpc.squad.combat

import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack

/**
 * The launcher a machine gunner carries for things its belt cannot hurt.
 *
 * Two rockets, and no separate counter for them: the launcher's own ammunition is the count. When
 * it runs dry the gunner simply stops swapping to it and fights on with the machine gun, which is
 * also what happens if it never finds a target worth the rocket.
 *
 * [NpcEntity.antiArmourWeapon] always holds whichever of the two weapons is NOT in the gunner's
 * hands, so a swap is a straight exchange and there is no state to get out of step.
 */
object AntiArmourKit {
    private val RPG = ResourceLocation.fromNamespaceAndPath("superbwarfare", "rpg")

    /** Rockets issued. One goes in the tube, the rest is carried. */
    private const val ROCKETS = 2

    /** A loaded launcher, or empty if SuperbWarfare has no such item (it always does — this is a
     *  registry lookup, not a guess about the mod being present). */
    fun issue(npc: NpcEntity): ItemStack {
        val item = BuiltInRegistries.ITEM.getOptional(RPG).orElse(null) as? GunItem ?: return ItemStack.EMPTY
        val data = GunData.from(ItemStack(item))
        data.virtualAmmo.set(ROCKETS)
        data.reloadAmmo(npc)
        data.save()
        return data.stack
    }

    fun isLauncher(stack: ItemStack): Boolean =
        !stack.isEmpty && BuiltInRegistries.ITEM.getKey(stack.item) == RPG

    /** Whether there is anything left to fire. */
    fun loaded(npc: NpcEntity): Boolean {
        val stack = if (isLauncher(npc.mainHandItem)) npc.mainHandItem else npc.antiArmourWeapon
        if (!isLauncher(stack)) return false
        val data = GunData.from(stack)
        return data.ammo.get() > 0 || data.virtualAmmo.get() > 0
    }

    /**
     * Whether this target is worth a rocket: anything riding a vehicle that is still a going
     * concern. That covers a tank's crew and a helicopter's pilot alike, and leaves infantry to
     * the machine gun.
     */
    fun worthARocket(npc: NpcEntity, target: LivingEntity?): Boolean {
        val ride = target?.vehicle as? VehicleEntity ?: return false
        return ride.isAlive && !ride.isWreck && ride !== npc.vehicle
    }

    /** True once the gunner is holding what it should be holding. */
    fun wield(npc: NpcEntity, launcher: Boolean): Boolean {
        val holding = isLauncher(npc.mainHandItem)
        if (holding == launcher) return true
        val other = npc.antiArmourWeapon
        if (other.isEmpty) return false
        // A straight exchange — whatever was in hand becomes the stowed weapon.
        npc.antiArmourWeapon = npc.mainHandItem
        npc.setItemInHand(InteractionHand.MAIN_HAND, other)
        return isLauncher(npc.mainHandItem) == launcher
    }
}
