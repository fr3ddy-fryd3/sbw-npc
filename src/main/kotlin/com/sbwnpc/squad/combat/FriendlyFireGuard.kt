package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Shared "don't shoot your own side" check for every ranged NPC goal that fires along a straight
 * line (rifle/MG/sniper/grenadier's thrown grenade and M79). The mortar's own indirect-fire
 * feasibility check (`MortarOperatorGoal.canHitTarget`) is unrelated — arcing fire onto a
 * called-in point doesn't share this particular risk the same way a flat shot does.
 */
object FriendlyFireGuard {

    private const val ALLY_RADIUS = 0.6
    private const val SIDESTEP_DISTANCE = 2.5
    private const val SIDESTEP_CHECK_RADIUS = 1.5

    /** True if no ally sits between [shooter]'s eyes and [aimPoint]. */
    fun hasClearLineOfFire(shooter: NpcEntity, aimPoint: Vec3): Boolean {
        val from = shooter.eyePosition
        val segment = aimPoint.subtract(from)
        val lengthSqr = segment.lengthSqr()
        if (lengthSqr < 1.0e-6) return true

        val box = AABB(from, aimPoint).inflate(ALLY_RADIUS + 1.0)
        val allies = shooter.level().getEntitiesOfClass(LivingEntity::class.java, box) { candidate ->
            candidate !== shooter && (candidate is NpcEntity || candidate is Player) && !SquadTeams.isHostile(shooter, candidate)
        }
        for (ally in allies) {
            val toAlly = ally.boundingBox.center.subtract(from)
            val t = toAlly.dot(segment) / lengthSqr
            if (t <= 0.0 || t >= 1.0) continue // behind the shooter, or past the target — not in the way
            val closest = from.add(segment.scale(t))
            if (closest.distanceTo(ally.boundingBox.center) < ALLY_RADIUS) return false
        }
        return true
    }

    /** Steps [shooter] a short distance perpendicular to the shooter->[aimPoint] line, trying for
     *  an angle clear of whatever ally is currently in the way. Picks whichever side is currently
     *  less crowded with allies rather than a fixed left/right, so it doesn't reliably step
     *  straight into a second ally standing on the "wrong" side. */
    fun sidestepAwayFromAllies(shooter: NpcEntity, aimPoint: Vec3) {
        val dir = aimPoint.subtract(shooter.eyePosition)
        val flat = Vec3(dir.x, 0.0, dir.z)
        if (flat.lengthSqr() < 1.0e-6) return
        val perp = Vec3(-flat.z, 0.0, flat.x).normalize()

        val from = shooter.position()
        val optionA = from.add(perp.scale(SIDESTEP_DISTANCE))
        val optionB = from.add(perp.scale(-SIDESTEP_DISTANCE))

        fun crowding(p: Vec3) = shooter.level().getEntitiesOfClass(
            LivingEntity::class.java, AABB(p, p).inflate(SIDESTEP_CHECK_RADIUS)
        ) { it !== shooter && (it is NpcEntity || it is Player) && !SquadTeams.isHostile(shooter, it) }.size

        val chosen = if (crowding(optionA) <= crowding(optionB)) optionA else optionB
        shooter.navigation.moveTo(chosen.x, chosen.y, chosen.z, 1.0)
    }
}
