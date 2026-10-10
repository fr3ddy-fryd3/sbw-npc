package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack

/** Persistent backup small arms; supply readiness continues to describe the primary weapon. */
object SidearmKit {
    const val FIRE_RANGE = 32.0

    fun hasPrimaryAmmo(npc: NpcEntity): Boolean = Ports.guns.roundsLeft(npc.primaryGun()) > 0

    fun issue(npc: NpcEntity): ItemStack {
        val green = (SquadTeams.factionOf(npc) ?: npc.spawnFaction ?: SquadFaction.DEFAULT).greenUniform
        val name = if (green) {
            "mp_443"
        } else if (npc.random.nextBoolean()) {
            "glock_17"
        } else {
            "m_1911"
        }
        val magazine = if (name == "m_1911") 7 else 17
        val reserve = if (name == "m_1911") 63 else 68
        return Ports.guns.issue(ResourceLocation.fromNamespaceAndPath("superbwarfare", name), npc, magazine + reserve)
    }

    fun reserveRounds(stack: ItemStack): Int = if (BuiltInRegistries.ITEM.getKey(stack.item).path == "m_1911") 63 else 68

    /** A drone monitor temporarily replaces the drawn pistol, just as it replaces a rifle. */
    fun sync(npc: NpcEntity) {
        if (!npc.usingSidearm) return
        val drawn = if (Ports.drones.isMonitor(npc.mainHandItem)) npc.stowedWeapon else npc.mainHandItem
        if (Ports.guns.isGun(drawn) && !AntiArmourKit.isLauncher(drawn)) npc.sidearmWeapon = drawn
    }

    fun restorePrimary(npc: NpcEntity) {
        if (!npc.usingSidearm) return
        sync(npc)
        Ports.guns.persist(npc.sidearmWeapon)
        npc.setItemInHand(InteractionHand.MAIN_HAND, npc.sidearmPrimaryWeapon)
        npc.sidearmPrimaryWeapon = ItemStack.EMPTY
        trace(npc, "primary_restored")
    }

    /** Reloading an empty magazine with reserve rounds is not a reason to switch. */
    fun update(npc: NpcEntity) {
        sync(npc)
        if (npc.operatingDrone || Ports.drones.isMonitor(npc.mainHandItem)) return
        if (AntiArmourKit.isLauncher(npc.mainHandItem)) {
            if (Ports.guns.roundsLeft(npc.mainHandItem) > 0) return
            AntiArmourKit.wield(npc, launcher = false)
        }
        if (npc.usingSidearm) {
            if (Ports.guns.roundsLeft(npc.primaryGun()) > 0) restorePrimary(npc)
            return
        }
        val primary = npc.primaryGun()
        if (!Ports.guns.isGun(primary) || Ports.guns.roundsLeft(primary) > 0 || Ports.guns.roundsLeft(npc.sidearmWeapon) <= 0) return
        Ports.guns.persist(primary)
        npc.sidearmPrimaryWeapon = primary
        npc.setItemInHand(InteractionHand.MAIN_HAND, npc.sidearmWeapon)
        trace(npc, "primary_exhausted")
    }

    fun hasSmallArmsAmmo(npc: NpcEntity): Boolean = Ports.guns.roundsLeft(npc.primaryGun()) > 0 || Ports.guns.roundsLeft(npc.sidearmWeapon) > 0

    private fun trace(npc: NpcEntity, reason: String) {
        DebugFlags.log(LogGroup.AMMO, "{} weapon_switch reason={} hand={} primaryRounds={} pistolRounds={}", npc.uuid, reason, BuiltInRegistries.ITEM.getKey(npc.mainHandItem.item), Ports.guns.roundsLeft(npc.primaryGun()), Ports.guns.roundsLeft(npc.sidearmWeapon))
    }
}
