package com.sbwnpc.squad.entity

import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.resource.model.EntityModelReloadListener
import com.sbwnpc.squad.SquadMod.Companion.loc
import com.sbwnpc.squad.client.animation.NpcAnimationInstance
import com.sbwnpc.squad.entity.ai.NpcGunAttackGoal
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityType
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
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor

/**
 * Base squad-member entity. Currently equips a hardcoded test weapon (AK-47) and will shoot at
 * the nearest player on sight — there's no squad/team system yet, so it can't tell friend from
 * foe. That's Phase 3's job (scoreboard-team-based targeting will replace the plain
 * NearestAttackableTargetGoal below). Weapon loadout will also stop being hardcoded once the
 * squad-role system exists.
 */
open class NpcEntity(type: EntityType<out NpcEntity>, level: Level) : PathfinderMob(type, level) {
    open val animationInstance: NpcAnimationInstance? =
        if (this.level().isClientSide) NpcAnimationInstance(this) else null
    open val modelInstance = EntityModelReloadListener.getModel(MODEL)?.createInstance()

    override fun registerGoals() {
        super.registerGoals()
        this.goalSelector.addGoal(0, FloatGoal(this))
        this.goalSelector.addGoal(1, NpcGunAttackGoal(this))
        this.goalSelector.addGoal(2, MeleeAttackGoal(this, 1.2, false))
        this.goalSelector.addGoal(3, RandomLookAroundGoal(this))
        this.goalSelector.addGoal(4, WaterAvoidingRandomStrollGoal(this, 0.8))

        this.targetSelector.addGoal(1, HurtByTargetGoal(this))
        this.targetSelector.addGoal(2, NearestAttackableTargetGoal(this, Player::class.java, true))
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun finalizeSpawn(
        level: ServerLevelAccessor,
        difficulty: DifficultyInstance,
        spawnType: MobSpawnType,
        spawnGroupData: SpawnGroupData?
    ): SpawnGroupData? {
        equipTestWeapon()
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData)
    }

    private fun equipTestWeapon() {
        val gunData = GunData.from(ItemStack(ModItems.AK_47.get()))
        gunData.virtualAmmo.set(90)
        gunData.reloadAmmo(this)
        gunData.save()
        this.setItemInHand(InteractionHand.MAIN_HAND, gunData.stack)
    }

    companion object {
        val MODEL = loc("models/bedrock/entity/npc_placeholder.geo.json")

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder {
            return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 2.0)
                .add(Attributes.ARMOR, 2.0)
                .add(Attributes.FOLLOW_RANGE, 48.0)
        }
    }
}
