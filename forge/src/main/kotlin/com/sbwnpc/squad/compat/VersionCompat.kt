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
import net.minecraft.world.entity.ai.goal.OpenDoorGoal
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.pathfinder.BlockPathTypes
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.world.level.block.AbstractGlassBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import java.util.UUID

/** Small calls whose names or shapes differ between Minecraft versions. This is the 1.20.1 file; NeoForge and Fabric have their own. */
object VersionCompat {
    /** The block position stored under [key], or null when there is none. */
    fun readBlockPos(tag: CompoundTag, key: String): BlockPos? =
        if (tag.contains(key, Tag.TAG_COMPOUND.toInt())) NbtUtils.readBlockPos(tag.getCompound(key)) else null

    /** [stack] written for saving; 1.20.1 needs no registries, so [owner] is not used. */
    @Suppress("UNUSED_PARAMETER")
    fun saveStack(owner: Entity, stack: ItemStack): Tag = stack.save(CompoundTag())

    @Suppress("UNUSED_PARAMETER")
    fun loadStack(owner: Entity, tag: CompoundTag): ItemStack = ItemStack.of(tag)

    fun shortGrass(): Block = Blocks.GRASS

    /** Whether [block] is a full glass block. */
    fun isGlassBlock(block: Block): Boolean = block is AbstractGlassBlock

    /** Whether [entity] is in water or lava. */
    fun isInLiquid(entity: Entity): Boolean = entity.isInWater || entity.isInLava

    /** Crouching size; on 1.20.1 the eye height is the entity's to report, so [eyeHeight] is not used here. */
    @Suppress("UNUSED_PARAMETER")
    fun crouchingDimensions(width: Float, height: Float, eyeHeight: Float): EntityDimensions = EntityDimensions.scalable(width, height)

    // 1.20.1 modifiers are keyed by UUID, so the id is hashed to a stable one.
    private fun uuidOf(id: ResourceLocation): UUID = UUID.nameUUIDFromBytes(id.toString().toByteArray())

    /** A transient `x(1 + amount)` speed-style modifier under [id], replacing one already there. */
    fun setMultipliedModifier(attribute: AttributeInstance, id: ResourceLocation, amount: Double) {
        val uuid = uuidOf(id)
        attribute.removeModifier(uuid)
        attribute.addTransientModifier(AttributeModifier(uuid, id.toString(), amount, AttributeModifier.Operation.MULTIPLY_TOTAL))
    }

    fun removeModifier(attribute: AttributeInstance, id: ResourceLocation) {
        attribute.removeModifier(uuidOf(id))
    }

    /** Vanilla forbids snow-filled nodes but gives the empty node above snow zero cost; keep [mob] off it. */
    fun avoidPowderSnow(mob: Mob) {
        mob.setPathfindingMalus(BlockPathTypes.DANGER_POWDER_SNOW, -1f)
    }

    /** SmartBrainLib 1.15 has no door behaviour; doors are opened by a vanilla goal ([addDoorGoal]) instead. */
    fun doorBehaviours(): Array<ExtendedBehaviour<NpcEntity>> = emptyArray()

    /** Opens and closes doors on the way, as SmartBrainLib's door behaviour does on 1.21. */
    fun addDoorGoal(mob: Mob) {
        mob.goalSelector.addGoal(1, OpenDoorGoal(mob, true))
    }
}
