package com.sbwnpc.squad.init

import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.SBLConstants
import java.util.function.Supplier

/**
 * Registry for our own SmartBrainLib custom [MemoryModuleType]s. Same registration pattern and same
 * reason for [init] as [ModSensors] — forces these fields to initialize before the registry event
 * fires. No [com.mojang.serialization.Codec] passed (see [SBLConstants.SBL_LOADER]'s two-arg
 * overload) — these are all momentary combat-reaction state, never persisted to NBT.
 */
object ModMemories {
    /** TTL memory holding the last threat position while suppressed; presence alone means
     *  "suppressed". */
    val SUPPRESSING_THREAT: Supplier<MemoryModuleType<Vec3>> =
        SBLConstants.SBL_LOADER.registerMemoryType("suppressing_threat")

    /** TTL memory; presence alone means "alert, go investigate this position". */
    val ALERT_POSITION: Supplier<MemoryModuleType<Vec3>> =
        SBLConstants.SBL_LOADER.registerMemoryType("alert_position")

    /** UUID of a hostile drone this NPC (or a squadmate who shouted) knows is in the air nearby —
     *  see AntiDroneBehaviour. TTL memory: forgotten a few seconds after the last sighting. */
    val DRONE_THREAT: Supplier<MemoryModuleType<java.util.UUID>> =
        SBLConstants.SBL_LOADER.registerMemoryType("drone_threat")

    /** UUID of a hostile drone this NPC (or an ally who shouted) can hear but has not seen: an
     *  alarm and a direction, not a target to shoot at (#114) — see AntiDroneBehaviour. TTL memory. */
    val DRONE_HEARD: Supplier<MemoryModuleType<java.util.UUID>> =
        SBLConstants.SBL_LOADER.registerMemoryType("drone_heard")

    fun init() {}
}
