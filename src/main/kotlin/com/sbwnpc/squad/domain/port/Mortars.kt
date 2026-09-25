package com.sbwnpc.squad.domain.port

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB

/** Mortars, held as plain entities. Alive/wreck checks go through [Vehicles]. */
interface Mortars {
    fun isMortar(entity: Entity?): Boolean

    fun within(level: Level, area: AABB, filter: (Entity) -> Boolean = { true }): List<Entity>

    /** A new, unplaced mortar facing [yaw], already under AI control (see [takeControl]). */
    fun create(level: ServerLevel, yaw: Float): Entity

    /** Stops the mortar firing by itself the moment a shell goes in, so the crew decides when it
     *  fires. */
    fun takeControl(mortar: Entity)

    fun hasShell(mortar: Entity): Boolean

    fun loadShell(mortar: Entity)

    /** Whether the mortar's own solver can put a shell on [target] from where it stands: within
     *  ballistic range, at a pitch the tube can be laid to. */
    fun canReach(mortar: Entity, target: BlockPos): Boolean

    /** Lays the tube on [target], shells landing within [scatter] blocks of it. */
    fun lay(mortar: Entity, target: BlockPos, scatter: Int, gunner: Entity)

    fun fire(mortar: Entity, gunner: LivingEntity)
}
