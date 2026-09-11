package com.sbwnpc.squad.entity

import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import java.util.UUID

/**
 * Stationary, destructible resupply point for a squad. Phase 5.5 scope is purely a periodic
 * respawner of missing members — future resource/ammo logistics will build on this same object as
 * the source point, per the plan, not implemented yet. Deliberately a separate placed object (not
 * auto-created at a squad's deploy point) so it can be built, moved to, and destroyed independent
 * of when/where a squad happened to first form.
 *
 * No AI, no movement — `setNoAi(true)` plus an empty `registerGoals()`. Modeled as a `Mob` (not a
 * plain `Entity`/custom block) specifically so it inherits ordinary `hurt()`/health/death handling
 * for free — that's the same reason `NpcEntity` already works cleanly with SBW's guns/explosives,
 * and a block would have needed a bespoke damage-tracking mechanism to get the same thing.
 */
class BarracksEntity(type: EntityType<out BarracksEntity>, level: Level) : PathfinderMob(type, level) {

    var owner: UUID? = null
    var faction: SquadFaction? = null
    private var nextRespawnTick = 0

    init {
        setNoAi(true)
    }

    override fun registerGoals() {
        // Intentionally empty — a barracks never moves or fights on its own.
    }

    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    override fun finalizeSpawn(
        level: ServerLevelAccessor,
        difficulty: DifficultyInstance,
        spawnType: MobSpawnType,
        spawnGroupData: SpawnGroupData?
    ): SpawnGroupData? {
        faction?.let { SquadTeams.assign(this, it) }
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData)
    }

    override fun tick() {
        super.tick()
        val level = level() as? ServerLevel ?: return
        val fac = faction ?: return
        if (tickCount < nextRespawnTick) return
        nextRespawnTick = tickCount + RESPAWN_INTERVAL_TICKS
        SquadManager.get(level).respawnAtBarracks(level, uuid, position(), fac)
    }

    override fun die(cause: DamageSource) {
        (level() as? ServerLevel)?.let { level ->
            val mgr = SquadManager.get(level)
            mgr.squadsAtBarracks(uuid).forEach { mgr.assignBarracks(it.id, null) }
        }
        super.die(cause)
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        super.addAdditionalSaveData(compound)
        owner?.let { compound.putUUID("Owner", it) }
        faction?.let { compound.putString("Faction", it.name) }
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        super.readAdditionalSaveData(compound)
        owner = if (compound.hasUUID("Owner")) compound.getUUID("Owner") else null
        faction = runCatching { SquadFaction.valueOf(compound.getString("Faction")) }.getOrNull()
    }

    companion object {
        private const val RESPAWN_INTERVAL_TICKS = 600 // ~30s
        private const val BASE_HEALTH = 60.0

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder =
            Mob.createMobAttributes().add(Attributes.MAX_HEALTH, BASE_HEALTH).add(Attributes.ARMOR, 4.0)
    }
}
