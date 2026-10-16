package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.world.phys.Vec3

/** Effective firing range, separate from contact detection and movement clearance. */
object WeaponReach {
    fun inRange(npc: NpcEntity, from: Vec3, target: Vec3): Boolean {
        val range = if (npc.usingSidearm) SidearmKit.FIRE_RANGE else npc.npcClass.maxFireDistance
        return from.distanceToSqr(target) <= range * range
    }
}
