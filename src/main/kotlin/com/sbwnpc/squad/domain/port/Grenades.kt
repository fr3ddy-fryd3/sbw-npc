package com.sbwnpc.squad.domain.port

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

interface Grenades {
    /** How far a thrown hand grenade's blast reaches. */
    val blastRadius: Double

    /** Throws a hand grenade to land on [target], leading it by [targetVelocity]. */
    fun throwAt(thrower: LivingEntity, level: ServerLevel, target: Vec3, targetVelocity: Vec3 = Vec3.ZERO)

    /** A grenade on a timed fuse — one that lies there long enough to run from. Contact-fuzed
     *  rounds don't count. */
    fun isTimedGrenade(entity: Entity): Boolean
}
