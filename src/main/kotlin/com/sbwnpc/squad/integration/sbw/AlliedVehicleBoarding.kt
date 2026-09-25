package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModTags
import com.atsuishio.superbwarfare.item.IVehicleInteract
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.vehicle.DriverAllegiance
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.NameTagItem
import net.neoforged.neoforge.common.util.FakePlayer

/** SBW's normal interaction replaces a non-player driver. Join allied NPC crews instead. */
object AlliedVehicleBoarding {
    /** Called from the base vehicle interaction, after subclass-specific loading/dyeing actions. */
    @JvmStatic
    fun interact(vehicle: VehicleEntity, player: Player, hand: InteractionHand): InteractionResult? {
        val driver = vehicle.firstPassenger as? NpcEntity ?: return null
        if (player.vehicle != null || player.isShiftKeyDown || player.isSpectator || player is FakePlayer) return null
        if (!vehicle.isAlive || vehicle.locked || vehicle.isWreck) return null
        if (!player.level().isClientSide && !DriverAllegiance.isAlliedDriver(player, driver)) return null

        // These interactions precede boarding in SBW and must retain their original behavior.
        // SBW inspects mainHandItem even when the interaction is sent for the off hand.
        val stack = player.mainHandItem
        val item = stack.item
        val itemResult = if (stack.`is`(ModTags.Items.TOOLS_CROWBAR)) {
            vehicle.onCrowbarInteract(stack, player, hand)
        } else if (item is IVehicleInteract) {
            item.onInteractVehicle(vehicle, stack, player, hand)
        } else null
        if (itemResult != null) return itemResult
        // A null tool result means "continue to boarding", especially for a crowbar used without
        // shift. Do not fall through to SBW and invoke the tool twice or eject the driver.
        if ((item is NameTagItem && stack.has(DataComponents.CUSTOM_NAME)) ||
            stack.`is`(ModItems.C4_BOMB.get()) || stack.`is`(ModItems.DETONATOR.get())) return null

        if (player.level().isClientSide) {
            // SBW also ejects its NPC driver on the client, before its server-side mount check.
            // Suppress that prediction; the server resolves ownership and the unsynced faction pick.
            return InteractionResult.SUCCESS
        }
        // Consume even a failed/full-seat attempt: falling through would eject the NPC driver.
        if (vehicle.getOrderedPassengers().drop(1).none { it == null }) return InteractionResult.FAIL

        // Seat zero is occupied, so SBW adds the player to the first free passenger seat.
        // Keep its own capacity/mount checks and all existing crew reservations intact.
        if (player.startRiding(vehicle, false)) {
            player.isSprinting = false
            return InteractionResult.CONSUME
        }
        return InteractionResult.FAIL
    }
}
