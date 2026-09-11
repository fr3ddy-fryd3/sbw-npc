package com.sbwnpc.squad.entity

import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.sbwnpc.squad.entity.ai.GrenadeThrowGoal
import com.sbwnpc.squad.entity.ai.MortarClaims
import com.sbwnpc.squad.entity.ai.MortarOperatorGoal
import com.sbwnpc.squad.entity.ai.NpcGunAttackGoal
import com.sbwnpc.squad.entity.ai.SquadFocusTargetGoal
import com.sbwnpc.squad.entity.ai.SquadOrderGoal
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import java.util.UUID
import net.minecraft.ChatFormatting
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor

/**
 * Base squad-member entity. Role (class + rank) drives the loadout and combat tuning. Friend/foe
 * is by squad colour == vanilla scoreboard team (see [SquadTeams]); no team on either side means
 * neutral.
 */
open class NpcEntity(type: EntityType<out NpcEntity>, level: Level) : PathfinderMob(type, level) {

    var npcClass: NpcClass
        get() = NpcClass.byOrdinal(entityData.get(DATA_CLASS))
        set(value) = entityData.set(DATA_CLASS, value.ordinal)

    var npcRank: NpcRank
        get() = NpcRank.byOrdinal(entityData.get(DATA_RANK))
        set(value) = entityData.set(DATA_RANK, value.ordinal)

    /** Colour to put the NPC on its scoreboard team; set before finalizeSpawn. null = leave unteamed. */
    var spawnColor: ChatFormatting? = null

    /** Command group this NPC belongs to, if any. Server-side; persisted. */
    var squadId: UUID? = null

    fun currentSquad(): Squad? {
        val id = squadId ?: return null
        val lvl = level() as? ServerLevel ?: return null
        return SquadManager.get(lvl).get(id)
    }

    /** Where this NPC "belongs" per its squad: the guarded entity, else the objective point. */
    fun homeCenter(): Vec3? {
        val squad = currentSquad() ?: return null
        squad.focusEntity?.let { fid ->
            (level() as? ServerLevel)?.getEntity(fid)?.takeIf { it.isAlive }?.let { return it.position() }
        }
        return squad.objective?.let { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_CLASS, NpcClass.DEFAULT.ordinal)
        builder.define(DATA_RANK, NpcRank.DEFAULT.ordinal)
    }

    override fun registerGoals() {
        super.registerGoals()
        this.goalSelector.addGoal(0, FloatGoal(this))
        this.goalSelector.addGoal(1, NpcGunAttackGoal(this))
        this.goalSelector.addGoal(1, MortarOperatorGoal(this))
        this.goalSelector.addGoal(2, MeleeAttackGoal(this, 1.2, false))
        this.goalSelector.addGoal(2, GrenadeThrowGoal(this))
        this.goalSelector.addGoal(3, SquadOrderGoal(this))
        this.goalSelector.addGoal(4, RandomLookAroundGoal(this))
        this.goalSelector.addGoal(5, WaterAvoidingRandomStrollGoal(this, 0.8))

        this.targetSelector.addGoal(1, SquadFocusTargetGoal(this))
        this.targetSelector.addGoal(2, HurtByTargetGoal(this))
        this.targetSelector.addGoal(
            3,
            NearestAttackableTargetGoal(this, LivingEntity::class.java, 10, true, false) { this.isEnemy(it) }
        )
    }

    private fun isEnemy(other: LivingEntity): Boolean {
        if (other !is NpcEntity && other !is Player) return false
        if (other is Player && (other.isCreative || other.isSpectator)) return false
        return SquadTeams.isHostile(this, other)
    }

    // Squad members are always placed deliberately (spawn egg, deployer, recruitment) — never
    // ambient wildlife — so they must not despawn when the nearest player wanders off or dies and
    // respawns far away. Same as iron golems / tamed pets.
    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun finalizeSpawn(
        level: ServerLevelAccessor,
        difficulty: DifficultyInstance,
        spawnType: MobSpawnType,
        spawnGroupData: SpawnGroupData?
    ): SpawnGroupData? {
        applyRole()
        spawnColor?.let { SquadTeams.assign(this, it) }
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData)
    }

    /** (Re)applies health from rank and equips the class weapon with a loaded mag + reserve. */
    fun applyRole() {
        val healthAttr = getAttribute(Attributes.MAX_HEALTH)
        if (healthAttr != null) {
            healthAttr.baseValue = BASE_HEALTH * npcRank.healthMultiplier
            health = maxHealth
        }

        val gunItem = BuiltInRegistries.ITEM.getOptional(npcClass.weaponId).orElse(Items.AIR)
        if (gunItem is GunItem) {
            val gunData = GunData.from(ItemStack(gunItem))
            gunData.virtualAmmo.set(120)
            gunData.reloadAmmo(this)
            gunData.save()
            setItemInHand(InteractionHand.MAIN_HAND, gunData.stack)
        }
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        super.addAdditionalSaveData(compound)
        compound.putString("NpcClass", npcClass.name)
        compound.putString("NpcRank", npcRank.name)
        squadId?.let { compound.putUUID("SquadId", it) }
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        super.readAdditionalSaveData(compound)
        runCatching { npcClass = NpcClass.valueOf(compound.getString("NpcClass")) }
        runCatching { npcRank = NpcRank.valueOf(compound.getString("NpcRank")) }
        squadId = if (compound.hasUUID("SquadId")) compound.getUUID("SquadId") else null
    }

    override fun die(cause: net.minecraft.world.damagesource.DamageSource) {
        (level() as? ServerLevel)?.let { SquadManager.get(it).removeMemberEverywhere(uuid) }
        MortarClaims.release(uuid)
        super.die(cause)
    }

    companion object {
        private const val BASE_HEALTH = 20.0

        @JvmField
        val DATA_CLASS: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(NpcEntity::class.java, EntityDataSerializers.INT)

        @JvmField
        val DATA_RANK: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(NpcEntity::class.java, EntityDataSerializers.INT)

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder {
            return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, BASE_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 2.0)
                .add(Attributes.ARMOR, 2.0)
                .add(Attributes.FOLLOW_RANGE, 48.0)
        }
    }
}
