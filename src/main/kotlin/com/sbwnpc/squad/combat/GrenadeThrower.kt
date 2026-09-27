package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.GrenadeKind
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/** Shared grenade trajectory and friendly-fire gate for regular and reserve throws. */
object GrenadeThrower {
    fun isSafeToThrow(thrower: NpcEntity, target: Vec3, kind: GrenadeKind): Boolean =
        FriendlyFireGuard.hasClearLineOfFire(thrower, target) &&
            FriendlyFireGuard.hasClearBlastRadius(thrower, target, Ports.grenades.blastRadius(kind))

    /** Throws [kind] and takes it off [thrower]'s count. */
    fun throwAt(thrower: NpcEntity, level: ServerLevel, target: Vec3, kind: GrenadeKind, targetVelocity: Vec3 = Vec3.ZERO) {
        Ports.grenades.throwAt(thrower, level, target, targetVelocity, kind)
        thrower.spendGrenade(kind)
    }
}
