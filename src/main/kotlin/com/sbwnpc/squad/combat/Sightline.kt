package com.sbwnpc.squad.combat

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Single shared raycast primitive for "is there solid terrain between these two points" — both
 * `SeekCoverBehaviour` (a candidate only counts as real cover if EVERY nearby threat is blocked
 * from it, and a peek point is only used if the target is NOT blocked from it) and
 * `GunAttackBehaviour`'s partial-cover firing-position logic (a candidate's concealment score is how
 * high up a raycast from the target gets blocked) need the exact same block-collider raycast.
 * Pulled out once both needed it, instead of duplicating the `ClipContext` call a second time.
 */
object Sightline {
    fun blocked(level: ServerLevel, from: Vec3, to: Vec3, passer: Entity): Boolean {
        val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, passer))
        return hit.type == HitResult.Type.BLOCK
    }
}
