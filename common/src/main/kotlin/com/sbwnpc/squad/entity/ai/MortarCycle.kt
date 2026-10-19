package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.Entity

/** Accepted SBW shots own the cooldown, including crew handover, save/load and relocation. */
object MortarCycle {
    private const val LAST_SHOT = "sbwnpcLastMortarShot"
    const val FULL_CREW_TICKS = 100L
    const val SOLO_TICKS = FULL_CREW_TICKS * 3

    fun fireIfReady(mortar: Entity, gunner: NpcEntity) {
        val now = mortar.level().gameTime
        val crew = MortarClaims.activeCrew(mortar)
        val interval = if (crew >= 2) FULL_CREW_TICKS else SOLO_TICKS
        val data = Ports.loader.persistentData(mortar)
        if (data.contains(LAST_SHOT) && now - data.getLong(LAST_SHOT) < interval) return
        if (!Ports.mortars.fire(mortar, gunner)) return
        data.putLong(LAST_SHOT, now)
        DebugFlags.log(LogGroup.FIRE_SUPPORT, "mortar={} npc={} event=shot_accepted tick={} crew={} interval={} shells={}", mortar.uuid, gunner.uuid, now, crew, interval, gunner.mortarShellsLeft)
    }

    fun pack(mortar: Entity): CompoundTag =
        CompoundTag().apply {
            if (Ports.loader.persistentData(mortar).contains(LAST_SHOT)) putLong(LAST_SHOT, Ports.loader.persistentData(mortar).getLong(LAST_SHOT))
            putBoolean("Loaded", Ports.mortars.hasShell(mortar))
        }
    fun restore(mortar: Entity, data: CompoundTag) {
        if (data.contains(LAST_SHOT)) Ports.loader.persistentData(mortar).putLong(LAST_SHOT, data.getLong(LAST_SHOT))
        if (data.getBoolean("Loaded")) Ports.mortars.loadShell(mortar)
    }
}
