package com.sbwnpc.squad.compat

import com.sbwnpc.squad.entity.NpcPosture
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor

/**
 * The NPC's vanilla base, with the overrides whose signatures change between Minecraft versions
 * turned into version-free hooks. This is the 1.20.1 file (Forge); NeoForge and Fabric on 1.21
 * have their own, with the same hooks.
 */
abstract class NpcEntityBase(type: EntityType<out PathfinderMob>, level: Level) : PathfinderMob(type, level) {
    /** Declares the NPC's synced data with [defineData]. */
    protected abstract fun defineNpcData()

    protected fun <T> defineData(accessor: EntityDataAccessor<T>, value: T) {
        entityData.define(accessor, value)
    }

    final override fun defineSynchedData() {
        super.defineSynchedData()
        defineNpcData()
    }

    /** The size for [pose], or null to leave it to the entity type. */
    protected open fun poseDimensions(pose: Pose): EntityDimensions? = null

    final override fun getDimensions(pose: Pose): EntityDimensions = poseDimensions(pose) ?: super.getDimensions(pose)

    // 1.20.1 dimensions carry no eye height; this is the entity type's 1.74 and the crouching 1.35 of NpcPosture.
    final override fun getStandingEyeHeight(pose: Pose, dimensions: EntityDimensions): Float =
        if (pose == Pose.CROUCHING) NpcPosture.CROUCHING_EYE_HEIGHT else NpcPosture.STANDING_EYE_HEIGHT

    /** Sets up the NPC as it is spawned, before vanilla finishes the spawn. */
    protected abstract fun prepareSpawn()

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    final override fun finalizeSpawn(
        level: ServerLevelAccessor,
        difficulty: DifficultyInstance,
        spawnType: MobSpawnType,
        spawnGroupData: SpawnGroupData?,
        tag: CompoundTag?,
    ): SpawnGroupData? {
        prepareSpawn()
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData, tag)
    }

    /** Finishes spawning this NPC the way a spawn egg would. */
    fun spawnNpc(level: ServerLevelAccessor, difficulty: DifficultyInstance): SpawnGroupData? =
        finalizeSpawn(level, difficulty, MobSpawnType.SPAWN_EGG, null, null)

    /** Drops what the NPC carries when it dies, after vanilla's own drops. */
    protected abstract fun dropNpcLoot(damageSource: DamageSource, recentlyHit: Boolean)

    final override fun dropCustomDeathLoot(damageSource: DamageSource, looting: Int, recentlyHit: Boolean) {
        super.dropCustomDeathLoot(damageSource, looting, recentlyHit)
        dropNpcLoot(damageSource, recentlyHit)
    }
}
