package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * What NPCs hear of the other side: gunfire, explosions, and footsteps — players' and NPCs'. A noise gives no target — it only turns
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
    private val lastStep = HashMap<UUID, Long>()

    private const val WALK_RADIUS = 4.0
    private const val SPRINT_RADIUS = 8.0
    private const val LANDING_RADIUS = 3.0
    /** Blocks a tick over which an NPC is taken to be running rather than walking. */
    private const val NPC_RUN_SPEED = 0.2

    /** [shooter] fired a gun heard [radius] blocks around it. */
    fun gunshot(level: ServerLevel, shooter: Entity, radius: Double) {
        val now = level.gameTime
        val last = lastShot[shooter.uuid]
        if (last != null && now - last < SHOOTER_INTERVAL_TICKS) return
        lastShot[shooter.uuid] = now
        if (lastShot.size > 512) lastShot.entries.removeIf { now - it.value > SHOOTER_INTERVAL_TICKS }
        noise(level, shooter.eyePosition, radius, shooter, "gunshot")
    }

    /**
     * [walker] — a player or an NPC — took a step, or came down from a jump when [landing].
     * Walking is heard close by, running twice as far; crouching isn't heard at all. Once a second
     * per walker is plenty — a step is a step.
     */
    fun footstep(level: ServerLevel, walker: Entity, landing: Boolean) {
        // A player sneaking, an NPC crouched.
        if (walker.isSteppingCarefully || walker.pose == net.minecraft.world.entity.Pose.CROUCHING) return
        val now = level.gameTime
        val last = lastStep[walker.uuid]
        if (last != null && now - last < SHOOTER_INTERVAL_TICKS) return
        lastStep[walker.uuid] = now
        if (lastStep.size > 1024) lastStep.entries.removeIf { now - it.value > SHOOTER_INTERVAL_TICKS }
        // An NPC never sets the sprint flag; it runs when it's moving at a run.
        val running = walker.isSprinting ||
            (walker !is net.minecraft.world.entity.player.Player && walker.deltaMovement.horizontalDistance() > NPC_RUN_SPEED)
        val radius = when {
            landing -> LANDING_RADIUS
            running -> SPRINT_RADIUS
            else -> WALK_RADIUS
        }
        noise(level, walker.position(), radius, walker, if (landing) "landing" else if (running) "running" else "walking")
    }

    /** An explosion of [power] went off at [at], set off by [source] if anyone. */
    fun explosion(level: ServerLevel, at: Vec3, power: Float, source: Entity?) {
        noise(level, at, (power * EXPLOSION_SCALE).coerceIn(MIN_EXPLOSION, MAX_EXPLOSION), source, "explosion $power")
    }

    private fun noise(level: ServerLevel, at: Vec3, radius: Double, source: Entity?, kind: String) {
        var heard = 0
        var inRange = 0
        NpcRegistry.forEachWithin(level, at, radius, exclude = source) { npc ->
            inRange++
            // Nobody's noise is everybody's; a known one only the other side's.
            if (npc.isAlive && (source == null || SquadTeams.isHostile(npc, source))) {
                npc.hear(at)
                heard++
            }
        }
        // A player's noise is logged even unheard, to tell "nobody near" from "nobody hostile".
        if ((heard > 0 || source is net.minecraft.world.entity.player.Player) && DebugFlags.LOGGING_ENABLED) {
            DebugFlags.log(
                "[hearing-debug] {} by {} at {} radius {} heard by {} of {} NPCs in range",
                kind, source?.let { it.uuid.toString().take(8) + " " + it.type.descriptionId } ?: "nobody",
                BlockPos.containing(at), "%.0f".format(radius), heard, inRange
            )
        }
    }

    fun clearAll() {
        lastShot.clear()
        lastStep.clear()
    }
}
