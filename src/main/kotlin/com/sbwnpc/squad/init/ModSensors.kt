package com.sbwnpc.squad.init

import com.sbwnpc.squad.entity.ai.SquadTargetSensor
import net.minecraft.world.entity.ai.sensing.SensorType
import net.tslat.smartbrainlib.SBLConstants
import java.util.function.Supplier

/**
 * Registry for our own SmartBrainLib [net.tslat.smartbrainlib.api.core.sensor.ExtendedSensor]
 * types. Mirrors the library's own internal registration pattern (its own `SBLSensors`/
 * `SBLMemoryTypes` classes) — [SBLConstants.SBL_LOADER.registerSensorType] adds to SmartBrainLib's
 * OWN DeferredRegister (namespace "smartbrainlib"), so this only needs to run once, early — [init]
 * exists purely so [com.sbwnpc.squad.SquadMod] can force this object's static fields to initialize
 * before the registry event fires, same trick the library itself uses.
 *
 * (Field name confirmed by decompiling the actual dependency jar with javap — the git `master`
 * branch of this library, which is a newer unreleased API, calls this `SBLConstants.PLATFORM`
 * instead; that name doesn't exist in the version we actually depend on.)
 */
object ModSensors {
    val SQUAD_TARGET: Supplier<SensorType<SquadTargetSensor>> =
        SBLConstants.SBL_LOADER.registerSensorType("squad_target") { SquadTargetSensor() }

    fun init() {}
}
