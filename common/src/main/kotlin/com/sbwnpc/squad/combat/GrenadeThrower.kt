package com.sbwnpc.squad.combat

import com.sbwnpc.squad.domain.port.GrenadeKind
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/** Shared grenade trajectory and friendly-fire gate for regular and reserve throws. */
object GrenadeThrower {
    /** Close enough to reach, roughly clear of the blast; [isSafeToThrow] checks each grenade's own. */
    const val MIN_RANGE = 5.0
    const val MAX_RANGE = 16.0

    /** Every throw path, including the grenade from a foxhole, uses the same reach limit. */
    internal fun inRange(from: Vec3, target: Vec3): Boolean = from.distanceTo(target) in MIN_RANGE..MAX_RANGE

    /**
     * Whether [kind] thrown at [target] spares [thrower]'s side, the thrower included. The ally check
     * leaves the thrower out, and an M67's blast (6 blocks) reaches past [MIN_RANGE]: a man threw one
     * at a zombie five blocks off and killed himself and a medic (in-game 25.10).
     */
    fun isSafeToThrow(thrower: NpcEntity, target: Vec3, kind: GrenadeKind): Boolean {
        val blast = Ports.grenades.blastRadius(kind)
        return thrower.position().distanceTo(target) > blast + SELF_MARGIN &&
            FriendlyFireGuard.hasClearLineOfFire(thrower, target) &&
            FriendlyFireGuard.hasClearBlastRadius(thrower, target, blast)
    }

    /**
     * The grenade [thrower] should throw at [target] now, or null for none: the one it would
     * rather use (NpcEntity.grenadeToThrow), else the other. Either has to be safe for its own
     * blast and a reachable flight. A collision near the target is fine; a grenade landing
     * short behind a branch or wall is kept instead of spent.
     */
    fun pick(thrower: NpcEntity, level: ServerLevel, target: Vec3, targetVelocity: Vec3 = Vec3.ZERO): GrenadeKind? {
        if (!inRange(thrower.position(), target)) return null
        val first = thrower.grenadeToThrow() ?: return null
        val second = if (first == GrenadeKind.DEFENSIVE) GrenadeKind.OFFENSIVE else GrenadeKind.DEFENSIVE
        return listOf(first, second).firstOrNull { kind ->
            canThrow(thrower, level, target, targetVelocity, kind)
        }
    }

    /** [thrower] still carries a [kind], and it is safe round the aim and reaches clear. */
    private fun canThrow(thrower: NpcEntity, level: ServerLevel, target: Vec3, targetVelocity: Vec3, kind: GrenadeKind): Boolean {
        if (thrower.grenadesLeft(kind) <= 0 || !isSafeToThrow(thrower, target, kind)) return false
        return reachesClear(thrower, level, target, targetVelocity, kind)
    }

    /**
     * Whether the grenade flies clear and spares [thrower]'s side where it really strikes: a throw
     * that passes its target can land past it, among the men behind. An M67 rolls on after its first
     * strike, so its blast is widened by [ROLL_MARGIN]. Allies are taken where they stand now.
     */
    private fun reachesClear(thrower: NpcEntity, level: ServerLevel, target: Vec3, targetVelocity: Vec3, kind: GrenadeKind): Boolean {
        val landing = Ports.grenades.landing(thrower, level, target, targetVelocity, kind, NEAR_TARGET, lobs = true) ?: return false
        val blast = Ports.grenades.blastRadius(kind) + if (kind == GrenadeKind.OFFENSIVE) ROLL_MARGIN else 0.0
        // The ally check leaves the thrower out, and a strike on a hull or a bounce can come back short.
        return thrower.position().distanceTo(landing) > blast + SELF_MARGIN && FriendlyFireGuard.hasClearBlastRadius(thrower, landing, blast)
    }

    /**
     * How far an M67 may roll after its first strike before the fuse runs out: an estimate, not
     * worked out from SBW's bounce damping. It holds an M67 back from allies up to 8 blocks from
     * where it strikes, not 6.
     */
    private const val ROLL_MARGIN = 2.0

    /** An RGO touching down this close to its point has done what it was thrown for. */
    private const val NEAR_TARGET = 2.5

    /** A grenade lands up to [NEAR_TARGET] short, and its target keeps moving. */
    private const val SELF_MARGIN = 2.0

    /** Rechecks reach at release: a moving target may have left range since [pick]. */
    fun throwAt(thrower: NpcEntity, level: ServerLevel, target: Vec3, kind: GrenadeKind, targetVelocity: Vec3 = Vec3.ZERO): Boolean {
        if (!inRange(thrower.position(), target) || !canThrow(thrower, level, target, targetVelocity, kind)) return false
        if (!Ports.grenades.throwAt(thrower, level, target, targetVelocity, kind, NEAR_TARGET)) return false
        thrower.spendGrenade(kind)
        DebugFlags.log(LogGroup.GRENADE, "{} threw {} at {} ({} blocks, blast {})", thrower.uuid, kind, target, thrower.position().distanceTo(target), Ports.grenades.blastRadius(kind))
        return true
    }
}
