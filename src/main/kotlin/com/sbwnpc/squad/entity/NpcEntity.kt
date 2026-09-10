package com.sbwnpc.squad.entity

import com.atsuishio.superbwarfare.resource.model.EntityModelReloadListener
import com.sbwnpc.squad.SquadMod.Companion.loc
import com.sbwnpc.squad.client.animation.NpcAnimationInstance
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.level.Level

/**
 * Base squad-member entity. This commit only wires up movement/idle behaviour and the
 * placeholder Bedrock model/animations so it can be spawned and visually verified in-game.
 * Combat AI (weapon handling, squad orders) lands in a follow-up commit.
 */
open class NpcEntity(type: EntityType<out NpcEntity>, level: Level) : PathfinderMob(type, level) {
    open val animationInstance: NpcAnimationInstance? =
        if (this.level().isClientSide) NpcAnimationInstance(this) else null
    open val modelInstance = EntityModelReloadListener.getModel(MODEL)?.createInstance()

    override fun registerGoals() {
        super.registerGoals()
        this.goalSelector.addGoal(0, FloatGoal(this))
        this.goalSelector.addGoal(1, RandomLookAroundGoal(this))
        this.goalSelector.addGoal(2, WaterAvoidingRandomStrollGoal(this, 0.8))
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
