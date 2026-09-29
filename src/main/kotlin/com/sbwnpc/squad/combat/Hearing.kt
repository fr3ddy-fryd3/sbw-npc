package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * What NPCs hear of the other side: gunfire and explosions. A noise gives no target — it only turns
 * a listener's head toward it, and sends those free to go and look there
 * ([NpcEntity.hear]). What they find they must see for themselves.
 *
 * Shots heard by the shooter's own side are [Alarm]'s business (they know what their man is firing
 * at); this is only for the side being shot at, and for noises nobody owns.
 */
object Hearing {
    /** A gun's own sound radius (SBW's `SoundRadius`, 8–32) is this many times how far it's heard. */
    const val GUNSHOT_SCALE = 3.0
    /** A suppressor on top of the quieter radius SBW already gives it. */
    const val SUPPRESSED_SCALE = 0.5
    /** Blocks per point of explosion power. */
    private const val EXPLOSION_SCALE = 12.0
    private const val MIN_EXPLOSION = 24.0
    private const val MAX_EXPLOSION = 128.0
    /** A machine gun is heard as it opens up, not ten times a second. */
    private const val SHOOTER_INTERVAL_TICKS = 20L

    private val lastShot = HashMap<UUID, Long>()

    /** [shooter] fired a gun heard [radius] blocks around it. */
    fun gunshot(level: ServerLevel, shooter: Entity, radius: Double) {
        val now = level.gameTime
        val last = lastShot[shooter.uuid]
        if (last != null && now - last < SHOOTER_INTERVAL_TICKS) return
        lastShot[shooter.uuid] = now
        if (lastShot.size > 512) lastShot.entries.removeIf { now - it.value > SHOOTER_INTERVAL_TICKS }
        noise(level, shooter.eyePosition, radius, shooter)
    }

    /** An explosion of [power] went off at [at], set off by [source] if anyone. */
    fun explosion(level: ServerLevel, at: Vec3, power: Float, source: Entity?) {
        noise(level, at, (power * EXPLOSION_SCALE).coerceIn(MIN_EXPLOSION, MAX_EXPLOSION), source)
    }

    private fun noise(level: ServerLevel, at: Vec3, radius: Double, source: Entity?) {
        NpcRegistry.forEachWithin(level, at, radius, exclude = source) { npc ->
            // Nobody's noise is everybody's; a known one only the other side's.
            if (npc.isAlive && (source == null || SquadTeams.isHostile(npc, source))) npc.hear(at)
        }
    }

    fun clearAll() = lastShot.clear()
}
