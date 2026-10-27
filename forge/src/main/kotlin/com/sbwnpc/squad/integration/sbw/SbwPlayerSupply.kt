package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.capability.player.PlayerVariable
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.sbwnpc.squad.combat.SidearmKit
import com.sbwnpc.squad.domain.port.Kit
import com.sbwnpc.squad.domain.port.KitOption
import com.sbwnpc.squad.domain.port.PlayerSupply
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.function.Supplier

/**
 * [PlayerSupply] over SuperbWarfare. A kit is the NPC class of the same name: one of its guns with
 * a full magazine, the class's starting rounds behind it, and its kit. Rounds a gun draws from the
 * player's own ammunition store (SBW's player variable) go there; rounds that are items — 40 mm
 * grenades, rockets — come as items.
 */
object SbwPlayerSupply : PlayerSupply {

    private val RPG = ResourceLocation.fromNamespaceAndPath("superbwarfare", "rpg")

    private class Spec(
        val name: String,
        val cls: NpcClass,
        extras: List<Pair<Supplier<out Item>, Int>>,
        val launcher: Boolean = false,
    ) {
        val extras = extras + listOf(Supplier { Items.BREAD } to 8)
    }

    private fun fighter(medkits: Int = 1) =
        listOf(
            ModItems.HAND_GRENADE to 2,
            ModItems.RGO_GRENADE to 2,
            ModItems.MEDICAL_KIT to medkits,
        )

    /** Drone Warfare addon's FPV goggles: its FPV drone is flown only with them on, plus a Monitor. */
    private val ADDON_FPV_GOGGLES_ID = ResourceLocation.fromNamespaceAndPath("sbwdroneconfig", "fpv_goggles")

    private fun addonItem(id: ResourceLocation): Item? = BuiltInRegistries.ITEM.getOptional(id).orElse(null)

    /** With the Drone Warfare addon, the FPV drone its own NPC operators fly ([SbwDrones]) and the
     *  goggles to fly it with; without it, SBW's drone. */
    private fun droneKit(): List<Pair<Supplier<out Item>, Int>> {
        val fpv = addonItem(SbwDrones.ADDON_FPV_DRONE_ID)
        val goggles = addonItem(ADDON_FPV_GOGGLES_ID)
        val drones = if (fpv != null && goggles != null) {
            listOf(Supplier { fpv } to 10, Supplier { goggles } to 1)
        } else {
            listOf(ModItems.DRONE to 10)
        }
        return drones + listOf(ModItems.MONITOR to 1, ModItems.MEDICAL_KIT to 1)
    }

    /** Built on first use: the addon's items are looked up in the item registry. */
    private val SPECS by lazy {
        listOf(
            Spec("Rifleman", NpcClass.RIFLEMAN, fighter()),
            // Fresh RPGs select TBG by default; supply rounds they can reload without switching types.
            Spec("Machine gunner", NpcClass.MACHINE_GUNNER, fighter() + (ModItems.RPG_ROCKET_TBG to 2), launcher = true),
            Spec("Sniper", NpcClass.SNIPER, fighter()),
            Spec("Grenadier", NpcClass.GRENADIER, fighter()),
            Spec("Medic", NpcClass.MEDIC, fighter(medkits = 10)),
            Spec("Drone operator", NpcClass.DRONE_OPERATOR, droneKit()),
            Spec("Shotgunner", NpcClass.SHOTGUNNER, fighter()),
        )
    }

    /** Built on first use: the gun data behind the ammunition lines needs the registries. */
    override val kits: List<Kit> by lazy {
        SPECS.map { spec ->
            Kit(
                spec.name,
                spec.cls.weaponPool.map { weapon ->
                    val items = mutableListOf<Pair<ResourceLocation, Int>>()
                    ammoFor(spec.cls, weapon)?.let { (ammo, count) -> items += key(ammo) to count }
                    val pistol = SidearmKit.playerWeapon(weapon)
                    items += pistol to 1
                    items += key(ModItems.KNIFE.get()) to 1
                    ammoFor(SidearmKit.startingRounds(pistol), pistol)?.let { (ammo, count) -> items += key(ammo) to count }
                    if (spec.launcher) items += RPG to 1
                    spec.extras.forEach { (item, count) -> items += key(item.get()) to count }
                    KitOption(weapon, items.groupBy { it.first }.map { (item, entries) -> item to entries.sumOf { it.second } })
                },
            )
        }
    }

    private fun key(item: Item) = BuiltInRegistries.ITEM.getKey(item)

    private fun gunItem(id: ResourceLocation): GunItem? = BuiltInRegistries.ITEM.getOptional(id).orElse(null) as? GunItem

    /** A gun of [item] with its magazine full. */
    private fun loaded(item: GunItem): ItemStack {
        val data = GunData.from(ItemStack(item))
        data.ammo.set(data.get(GunProp.MAGAZINE))
        // SBW's ammo setter clears its reserve cache but does not mark state dirty.
        // Persist the loaded magazine before vanilla copies this stack into inventory.
        data.nbtVersion.invalidateState()
        data.save()
        return data.stack
    }

    /** The class's starting rounds behind a full magazine, as the item that stands for them. */
    private fun ammoFor(cls: NpcClass, weapon: ResourceLocation): Pair<Item, Int>? = ammoFor(cls.startingRounds(weapon), weapon)

    private fun ammoFor(total: Int, weapon: ResourceLocation): Pair<Item, Int>? {
        val gun = gunItem(weapon) ?: return null
        val data = GunData.from(ItemStack(gun))
        val consumer = data.selectedAmmoConsumer()
        if (!consumer.initialized()) consumer.init()
        val rounds = total - data.get(GunProp.MAGAZINE)
        if (rounds <= 0) return null
        return when (consumer.type) {
            AmmoConsumer.AmmoConsumeType.PLAYER_AMMO -> consumer.playerAmmoType?.let { it.item to rounds }

            AmmoConsumer.AmmoConsumeType.ITEM ->
                consumer.stack().takeUnless { it.isEmpty }?.let { it.item to rounds / consumer.loadAmount.coerceAtLeast(1) }

            else -> null
        }
    }

    override fun issueAmmo(player: ServerPlayer, kit: Int): Boolean {
        val spec = SPECS.getOrNull(kit) ?: return false
        var given = false
        spec.cls.weaponPool.forEach { weapon ->
            given = topUpLoadoutAmmo(player, spec, weapon) || given
        }
        if (spec.launcher) given = topUpItems(player, ModItems.RPG_ROCKET_TBG.get(), 2) || given
        if (given) player.level().playSound(null, player.blockPosition(), ModSounds.BULLET_SUPPLY.get(), SoundSource.PLAYERS, 1f, 1f)
        return given
    }

    override fun issueKit(player: ServerPlayer, kit: Int, option: Int, faction: SquadFaction?): Boolean {
        val spec = SPECS.getOrNull(kit) ?: return false
        val weapon = spec.cls.weaponPool.getOrNull(option) ?: return false
        var given = false

        gunItem(weapon)?.let { gun ->
            if (player.inventory.countItem(gun) == 0) given = give(player, loaded(gun)) || given
        }
        val pistol = SidearmKit.playerWeapon(weapon)
        gunItem(pistol)?.let { gun -> if (player.inventory.countItem(gun) == 0) given = give(player, loaded(gun)) || given }
        given = topUpItems(player, ModItems.KNIFE.get(), 1) || given
        given = topUpLoadoutAmmo(player, spec, weapon) || given
        if (spec.launcher) {
            gunItem(RPG)?.let { if (player.inventory.countItem(it) == 0) given = give(player, loaded(it)) || given }
        }
        for ((item, count) in spec.extras) given = topUpItems(player, item.get(), count) || given

        faction?.let {
            val (helmet, vest) = SbwGear.uniform(it.greenUniform)
            given = wear(player, EquipmentSlot.HEAD, helmet) || given
            given = wear(player, EquipmentSlot.CHEST, vest) || given
        }

        if (given) {
            player.level().playSound(null, player.blockPosition(), ModSounds.BULLET_SUPPLY.get(), SoundSource.PLAYERS, 1f, 1f)
        }
        return given
    }

    private fun topUpLoadoutAmmo(player: ServerPlayer, spec: Spec, weapon: ResourceLocation): Boolean {
        val primary = gunItem(weapon) ?: return false
        val pistolId = SidearmKit.playerWeapon(weapon)
        val pistol = gunItem(pistolId) ?: return topUpAmmo(player, primary, spec.cls.startingRounds(weapon))
        val primaryData = GunData.from(ItemStack(primary))
        val primaryConsumer = primaryData.selectedAmmoConsumer().also { if (!it.initialized()) it.init() }
        val pistolConsumer = GunData.from(ItemStack(pistol)).selectedAmmoConsumer().also { if (!it.initialized()) it.init() }
        // MP5 and its backup draw from one SBW HandgunAmmo store. Include both reserves
        // in the desired total; never add ammunition to the player's current balance.
        val sharedReserve = if (primaryConsumer.type == AmmoConsumer.AmmoConsumeType.PLAYER_AMMO &&
            primaryConsumer.playerAmmoType != null && primaryConsumer.playerAmmoType == pistolConsumer.playerAmmoType
        ) {
            (spec.cls.startingRounds(weapon) - primaryData.get(GunProp.MAGAZINE)).coerceAtLeast(0)
        } else {
            0
        }
        val mainGiven = topUpAmmo(player, primary, spec.cls.startingRounds(weapon))
        return topUpAmmo(player, pistol, SidearmKit.startingRounds(pistolId) + sharedReserve) || mainGiven
    }

    /** Brings the rounds behind [gun] up to [total] less a magazine — into the player's ammunition
     *  store or as items, whichever the gun draws from. */
    private fun topUpAmmo(player: ServerPlayer, gun: GunItem, total: Int): Boolean {
        val data = GunData.from(ItemStack(gun))
        val consumer = data.selectedAmmoConsumer()
        if (!consumer.initialized()) consumer.init()
        val rounds = total - data.get(GunProp.MAGAZINE)
        if (rounds <= 0) return false
        return when (consumer.type) {
            AmmoConsumer.AmmoConsumeType.PLAYER_AMMO -> {
                val type = consumer.playerAmmoType ?: return false
                var given = false
                PlayerVariable.modify(player) { variable ->
                    if (type.get(variable) < rounds) given = type.set(variable, rounds)
                }
                given
            }

            AmmoConsumer.AmmoConsumeType.ITEM -> {
                val stack = consumer.stack()
                !stack.isEmpty && topUpItems(player, stack.item, rounds / consumer.loadAmount.coerceAtLeast(1))
            }

            else -> false
        }
    }

    private fun topUpItems(player: ServerPlayer, item: Item, count: Int): Boolean {
        var missing = count - player.inventory.countItem(item)
        if (missing <= 0) return false
        while (missing > 0) {
            val batch = minOf(missing, item.maxStackSize)
            give(player, ItemStack(item, batch))
            missing -= batch
        }
        return true
    }

    private fun give(player: ServerPlayer, stack: ItemStack): Boolean {
        if (!player.inventory.add(stack)) player.drop(stack, false)
        return true
    }

    private fun wear(player: ServerPlayer, slot: EquipmentSlot, stack: ItemStack): Boolean {
        if (stack.isEmpty || !player.getItemBySlot(slot).isEmpty) return false
        player.setItemSlot(slot, stack)
        return true
    }
}
