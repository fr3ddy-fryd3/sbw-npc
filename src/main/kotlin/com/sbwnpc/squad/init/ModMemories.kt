package com.sbwnpc.squad.init

import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.SBLConstants
import java.util.function.Supplier

/**
 * Registry for our own SmartBrainLib custom [MemoryModuleType]s. Same registration pattern and same
 * reason for [init] as [ModSensors] — forces these fields to initialize before the registry event
 * fires. No [com.mojang.serialization.Codec] passed (see [SBLConstants.SBL_LOADER]'s two-arg
 * overload) — these are all momentary combat-reaction state, never persisted to NBT, exactly like
 * the plain fields they replace never were either.
 */
object ModMemories {
    /** SmartBrain migration step 6. Replaces `NpcEntity.suppressedUntilTick`/`threatPos` — TTL
     *  memory holding the last threat position while suppressed; presence alone means "suppressed". */
    val SUPPRESSING_THREAT: Supplier<MemoryModuleType<Vec3>> =
        SBLConstants.SBL_LOADER.registerMemoryType("suppressing_threat")

    /** SmartBrain migration step 6. Replaces `NpcEntity.coverPhase`'s `combatLockedByCover()` signal
     *  (not the phase enum itself, which stays private inside [com.sbwnpc.squad.entity.ai.SeekCoverBehaviour]
     *  — only the externally-read "hands off, I own the mob right now" flag needs to be visible
     *  outside that class, to [com.sbwnpc.squad.entity.ai.GunAttackBehaviour] and
     *  [com.sbwnpc.squad.entity.ai.GrenadeThrowBehaviour]). Present while locked (moving to/holding/
     *  returning to cover), absent while peeking out to fire or not suppressed at all. */
    val COVER_HOLD: Supplier<MemoryModuleType<Boolean>> =
        SBLConstants.SBL_LOADER.registerMemoryType("cover_hold")

    /** SmartBrain migration step 7. Replaces `NpcEntity.alertUntilTick`/`alertPos` — TTL memory,
     *  presence alone means "alert, go investigate this position". */
    val ALERT_POSITION: Supplier<MemoryModuleType<Vec3>> =
        SBLConstants.SBL_LOADER.registerMemoryType("alert_position")

    /** Same "hands off, I own the mob right now" idiom as [COVER_HOLD], owned by
     *  [com.sbwnpc.squad.entity.ai.MedicHealBehaviour] instead: present while a medic is actively
     *  moving to/treating a CRITICALLY wounded ally (the one case where healing must win over an
     *  ongoing firefight, per user decision) — read by [com.sbwnpc.squad.entity.ai.GunAttackBehaviour]
     *  the same way it already reads [COVER_HOLD]. Not set for the ordinary opportunistic heal (no
     *  live target of its own) — nothing to lock out there, GunAttackBehaviour is already inactive
     *  without a target. */
    val MEDIC_HEALING: Supplier<MemoryModuleType<Boolean>> =
        SBLConstants.SBL_LOADER.registerMemoryType("medic_healing")

    /** UUID of a hostile drone this NPC (or a squadmate who shouted) knows is in the air nearby —
     *  see AntiDroneBehaviour. TTL memory: forgotten a few seconds after the last sighting. */
    val DRONE_THREAT: Supplier<MemoryModuleType<java.util.UUID>> =
        SBLConstants.SBL_LOADER.registerMemoryType("drone_threat")

    fun init() {}
}
