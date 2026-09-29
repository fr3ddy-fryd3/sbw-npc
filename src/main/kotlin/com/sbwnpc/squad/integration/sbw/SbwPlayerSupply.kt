package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModSounds
import com.sbwnpc.squad.domain.port.PlayerSupply
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import java.util.function.Supplier

/** [PlayerSupply] over SuperbWarfare. */
object SbwPlayerSupply : PlayerSupply {

    private class Kit(val name: String, val items: List<Pair<Supplier<out Item>, Int>>)

    private val KITS = listOf(
        Kit("Rifleman", listOf(ModItems.HAND_GRENADE to 2, ModItems.M18_SMOKE_GRENADE to 1, ModItems.MEDICAL_KIT to 1)),
        Kit("Grenadier", listOf(ModItems.GRENADE_40MM to 12, ModItems.HAND_GRENADE to 2, ModItems.MEDICAL_KIT to 1)),
        Kit("Anti-tank", listOf(ModItems.RPG_ROCKET_STANDARD to 4, ModItems.MEDICAL_KIT to 1)),
        Kit("Defender", listOf(ModItems.RGO_GRENADE to 3, ModItems.CLAYMORE_MINE to 2, ModItems.MEDICAL_KIT to 1)),
        Kit("Medic", listOf(ModItems.MEDICAL_KIT to 4, ModItems.M18_SMOKE_GRENADE to 2)),
        Kit("Sapper", listOf(ModItems.C4_BOMB to 2, ModItems.TM_62 to 2, ModItems.CLAYMORE_MINE to 2)),
    )

    override val kits: List<String> = KITS.map { it.name }

    override fun kitContents(index: Int): List<String> =
        KITS.getOrNull(index)?.items?.map { (item, count) -> "${count}× ${ItemStack(item.get()).hoverName.string}" }.orEmpty()

    override fun issueKit(player: ServerPlayer, index: Int): Boolean {
        val kit = KITS.getOrNull(index) ?: return false
        var given = false
        for ((supplier, count) in kit.items) {
            val item = supplier.get()
            val missing = count - player.inventory.countItem(item)
            if (missing <= 0) continue
            val stack = ItemStack(item, missing)
            if (!player.inventory.add(stack)) player.drop(stack, false)
            given = true
        }
        if (given) {
            player.level().playSound(null, player.blockPosition(), ModSounds.BULLET_SUPPLY.get(), SoundSource.PLAYERS, 1f, 1f)
        }
        return given
    }
}
