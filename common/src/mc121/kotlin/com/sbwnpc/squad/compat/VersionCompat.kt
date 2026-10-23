package com.sbwnpc.squad.compat

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.ai.attributes.AttributeInstance
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.pathfinder.PathType
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import net.tslat.smartbrainlib.api.core.behaviour.custom.move.InteractWithDoor
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.TransparentBlock

/** Small calls whose names or shapes differ between Minecraft versions. This is the 1.21 file; Forge on 1.20.1 has its own. */
object VersionCompat {
    /** The block position stored under [key], or null when there is none. */
    fun readBlockPos(tag: CompoundTag, key: String): BlockPos? = NbtUtils.readBlockPos(tag, key).orElse(null)

    /** [stack] written for saving with [owner], whose world supplies the registries on 1.21. */
    fun saveStack(owner: Entity, stack: ItemStack): Tag = stack.save(owner.registryAccess())

    fun loadStack(owner: Entity, tag: CompoundTag): ItemStack = ItemStack.parseOptional(owner.registryAccess(), tag)

    fun shortGrass(): Block = Blocks.SHORT_GRASS

    /** Whether [block] is a full glass block (ice and the like count on 1.21). */
    fun isGlassBlock(block: Block): Boolean = block is TransparentBlock

    /** Whether [entity] is in water or lava. */
    fun isInLiquid(entity: Entity): Boolean = entity.isInLiquid

    /** Crouching size; 1.21 dimensions carry their own eye height. */
    fun crouchingDimensions(width: Float, height: Float, eyeHeight: Float): EntityDimensions = EntityDimensions.scalable(width, height).withEyeHeight(eyeHeight)

    /** A transient `x(1 + amount)` speed-style modifier under [id], replacing one already there. */
    fun setMultipliedModifier(attribute: AttributeInstance, id: ResourceLocation, amount: Double) {
        attribute.addOrUpdateTransientModifier(AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
    }

    fun removeModifier(attribute: AttributeInstance, id: ResourceLocation) {
        attribute.removeModifier(id)
    }

    /** Vanilla forbids snow-filled nodes but gives the empty node above snow zero cost; keep [mob] off it. */
    fun avoidPowderSnow(mob: Mob) {
        mob.setPathfindingMalus(PathType.DANGER_POWDER_SNOW, -1f)
    }

    /** SmartBrainLib's door interaction, which also holds a door open for other squad members. */
    fun doorBehaviours(): Array<ExtendedBehaviour<NpcEntity>> = arrayOf(InteractWithDoor<NpcEntity>())

    /** Door goals for the vanilla goal selector; 1.21 uses [doorBehaviours] in the brain instead. */
    fun addDoorGoal(mob: Mob) = Unit

    fun getWaterMalus(mob: Mob): Float = mob.getPathfindingMalus(PathType.WATER)

    fun getWaterBorderMalus(mob: Mob): Float = mob.getPathfindingMalus(PathType.WATER_BORDER)

    fun setWaterMalus(mob: Mob, malus: Float) = mob.setPathfindingMalus(PathType.WATER, malus)

    fun setWaterBorderMalus(mob: Mob, malus: Float) = mob.setPathfindingMalus(PathType.WATER_BORDER, malus)

    /** How high [mob] steps up without jumping. */
    fun stepHeight(mob: Mob): Float = mob.maxUpStep()

    fun setStepHeight(mob: Mob, height: Float) {
        mob.getAttribute(Attributes.STEP_HEIGHT)?.baseValue = height.toDouble()
    }
}
