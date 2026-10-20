package com.sbwnpc.squad.domain.port

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

/** The two hand grenades an NPC carries: the offensive RGN, and the defensive RGO for holding
 *  ground or falling back. */
enum class GrenadeKind { OFFENSIVE, DEFENSIVE }

interface Grenades {
    /** How far the bigger of the two hand grenades' blasts reaches — for keeping clear of any. */
    val blastRadius: Double

    /** How far [kind]'s blast reaches. */
    fun blastRadius(kind: GrenadeKind): Double

    /** Throws a [kind] hand grenade to land on [target], leading it by [targetVelocity]. */
    fun throwAt(thrower: LivingEntity, level: ServerLevel, target: Vec3, targetVelocity: Vec3 = Vec3.ZERO, kind: GrenadeKind = GrenadeKind.OFFENSIVE)

    /**
     * Whether a [kind] grenade thrown at [target] flies clear all the way: nothing it would touch —
     * a block, or anyone but the enemy — before it gets within [nearTarget] of the point.
     * Rejects impossible ballistic solutions for both grenade types.
     */
    fun arcClear(thrower: LivingEntity, level: ServerLevel, target: Vec3, targetVelocity: Vec3, kind: GrenadeKind, nearTarget: Double): Boolean

    /** One [kind] hand grenade as the item a player throws. */
    fun item(kind: GrenadeKind): net.minecraft.world.item.ItemStack

    /** Throws a smoke grenade to land on [target]; it screens the spot after a few seconds. */
    fun throwSmoke(thrower: LivingEntity, level: ServerLevel, target: Vec3)

    /** Whether [entity] is a piece of a smoke grenade's cloud, out yet or not. */
    fun isSmokeCloud(entity: Entity): Boolean

    /** Whether a smoke piece ([isSmokeCloud]) is out now, hiding what stands behind it. */
    fun smokeIsOut(entity: Entity): Boolean

    /** A grenade on a timed fuse — one that lies there long enough to run from. Contact-fuzed
     *  rounds don't count. */
    fun isTimedGrenade(entity: Entity): Boolean
}
