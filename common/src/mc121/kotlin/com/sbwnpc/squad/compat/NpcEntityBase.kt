package com.sbwnpc.squad.compat

import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.level.Level

/**
 * The NPC's vanilla base, with the overrides whose signatures change between Minecraft versions
 * turned into version-free hooks. This is the 1.21 file (NeoForge and Fabric); Forge on 1.20.1 has
 * its own, with the same hooks.
 */
abstract class NpcEntityBase(type: EntityType<out PathfinderMob>, level: Level) : PathfinderMob(type, level) {
    private var definer: SynchedEntityData.Builder? = null

    /** Declares the NPC's synced data with [defineData]. */
    protected abstract fun defineNpcData()

    protected fun <T> defineData(accessor: EntityDataAccessor<T>, value: T) {
        requireNotNull(definer) { "Synced data is declared in defineNpcData" }.define(accessor, value)
    }

    final override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        definer = builder
        defineNpcData()
        definer = null
    }

    /** The size and eye height for [pose], or null to leave it to the entity type. */
    protected open fun poseDimensions(pose: Pose): EntityDimensions? = null

    final override fun getDefaultDimensions(pose: Pose): EntityDimensions = poseDimensions(pose) ?: super.getDefaultDimensions(pose)

    /** Sets up the NPC as it is spawned, before vanilla finishes the spawn. */
    protected abstract fun prepareSpawn()

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    final override fun finalizeSpawn(
        level: ServerLevelAccessor,
        difficulty: DifficultyInstance,
        spawnType: MobSpawnType,
        spawnGroupData: SpawnGroupData?,
    ): SpawnGroupData? {
        prepareSpawn()
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData)
    }

    /** Finishes spawning this NPC the way a spawn egg would. */
    fun spawnNpc(level: ServerLevelAccessor, difficulty: DifficultyInstance): SpawnGroupData? =
        finalizeSpawn(level, difficulty, MobSpawnType.SPAWN_EGG, null)

    /** Drops what the NPC carries when it dies, after vanilla's own drops. */
    protected abstract fun dropNpcLoot(damageSource: DamageSource, recentlyHit: Boolean)

    final override fun dropCustomDeathLoot(level: ServerLevel, damageSource: DamageSource, recentlyHit: Boolean) {
        super.dropCustomDeathLoot(level, damageSource, recentlyHit)
        dropNpcLoot(damageSource, recentlyHit)
    }
}
