package com.sbwnpc.squad.integration.sbw

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.ListTag
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/** Complete aircraft visuals inside SBW's existing lightweight BVR packet. */
object SbwAircraftSnapshots {
    private const val MARKER = "sbwnpc:aircraft_visuals"

    @JvmStatic
    fun isAircraft(entity: Entity): Boolean = entity is VehicleEntity &&
        (entity.vehicleType == VehicleType.AIRPLANE || entity.vehicleType == VehicleType.HELICOPTER)

    @JvmStatic
    fun write(entity: VehicleEntity, tag: CompoundTag) {
        if (!isAircraft(entity)) return
        writeMotion(tag, entity.position(), entity.deltaMovement, entity.yRot, entity.xRot)
        tag.putBoolean(MARKER, true)
        tag.putFloat("sbwnpc:roll", entity.roll)
        tag.putFloat("sbwnpc:fake_pitch", entity.fakePitch)
        tag.putFloat("sbwnpc:fake_roll", entity.fakeRoll)
        tag.putFloat("PropellerRot", entity.propellerRot)
        tag.putFloat("GearRot", entity.synchedGearRot)
        tag.putBoolean("GearUp", entity.gearUp)
        tag.putFloat("Power", entity.power)
        tag.putString("SkinId", entity.skinId)
    }

    // Entity.load expects vanilla lists; SBW's flat PosX/MotionX/Yaw fields are not consumed by it.
    internal fun writeMotion(tag: CompoundTag, position: Vec3, motion: Vec3, yaw: Float, pitch: Float) {
        tag.put("Pos", ListTag().apply { add(DoubleTag.valueOf(position.x)); add(DoubleTag.valueOf(position.y)); add(DoubleTag.valueOf(position.z)) })
        tag.put("Motion", ListTag().apply { add(DoubleTag.valueOf(motion.x)); add(DoubleTag.valueOf(motion.y)); add(DoubleTag.valueOf(motion.z)) })
        tag.put("Rotation", ListTag().apply { add(FloatTag.valueOf(yaw)); add(FloatTag.valueOf(pitch)) })
    }

    @JvmStatic
    fun read(entity: VehicleEntity, tag: CompoundTag) {
        if (!tag.getBoolean(MARKER)) return
        entity.roll = tag.getFloat("sbwnpc:roll")
        entity.prevRoll = entity.roll
        entity.fakePitch = tag.getFloat("sbwnpc:fake_pitch")
        entity.fakePitchO = entity.fakePitch
        entity.fakeRoll = tag.getFloat("sbwnpc:fake_roll")
        entity.fakeRollO = entity.fakeRoll
        entity.propellerRotO = entity.propellerRot
    }
}
