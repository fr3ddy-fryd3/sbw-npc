package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.GrenadeKind
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/** Shared grenade trajectory and friendly-fire gate for regular and reserve throws. */
object GrenadeThrower {
    /** Close enough to reach, far enough not to catch the blast — for every thrower. */
    const val MIN_RANGE = 5.0
    const val MAX_RANGE = 16.0

    fun isSafeToThrow(thrower: NpcEntity, target: Vec3, kind: GrenadeKind): Boolean =
        FriendlyFireGuard.hasClearLineOfFire(thrower, target) &&
            FriendlyFireGuard.hasClearBlastRadius(thrower, target, Ports.grenades.blastRadius(kind))

    /**
     * The grenade [thrower] should throw at [target] now, or null for none: the one it would
     * rather use (NpcEntity.grenadeToThrow), else the other. Either has to be safe for its own
     * blast; the RGO also needs a clear flight, since it goes off on whatever it touches first —
     * a branch or a wall edge on the way would burst it beside the thrower. The RGN bounces off
     * and keeps its fuse, so it can go round where the RGO can't.
     */
    fun pick(thrower: NpcEntity, level: ServerLevel, target: Vec3, targetVelocity: Vec3 = Vec3.ZERO): GrenadeKind? {
        val first = thrower.grenadeToThrow() ?: return null
        val second = if (first == GrenadeKind.DEFENSIVE) GrenadeKind.OFFENSIVE else GrenadeKind.DEFENSIVE
        return listOf(first, second).firstOrNull { kind ->
            thrower.grenadesLeft(kind) > 0 && isSafeToThrow(thrower, target, kind) &&
                (kind != GrenadeKind.DEFENSIVE || Ports.grenades.arcClear(thrower, level, target, targetVelocity, kind, NEAR_TARGET))
        }
    }

    /** An RGO touching down this close to its point has done what it was thrown for. */
    private const val NEAR_TARGET = 2.5

    /** Throws [kind] and takes it off [thrower]'s count. */
    fun throwAt(thrower: NpcEntity, level: ServerLevel, target: Vec3, kind: GrenadeKind, targetVelocity: Vec3 = Vec3.ZERO) {
        Ports.grenades.throwAt(thrower, level, target, targetVelocity, kind)
        thrower.spendGrenade(kind)
    }
}
