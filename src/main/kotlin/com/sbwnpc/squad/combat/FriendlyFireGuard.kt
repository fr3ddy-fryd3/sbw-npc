package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.sin

/**
 * Shared "don't shoot your own side" check for every ranged NPC goal.
 *
 * [hasClearLineOfFire] models the actual firing CONE, not an idealised zero-width ray — a shot
 * fired at [aimPoint] doesn't travel a perfectly straight line: SBW's `ProjectileSpreadTool`
 * treats spread as a cone angle in degrees around the aim direction. A first version
 * of this guard checked only the ideal line and let plenty of stray rounds through to allies
 * standing near, but not exactly on, that line — this checks the actual cone the round can end up
 * in instead.
 *
 * [hasClearBlastRadius] is an entirely separate, independent check for explosive weapons (M79,
 * thrown M67 grenade): even a "clean" shot on the direct line/cone can still down an ally standing
 * within the explosion radius of the impact point — that's a blast-splash risk, not a trajectory
 * risk, and needs its own gate at the target's position rather than anywhere along the shooter's
 * aim.
 *
 * The mortar's own indirect-fire feasibility check (`MortarOperatorBehaviour.canHitTarget`) is
 * unrelated — arcing fire onto a called-in point is handled by that goal's own logic already.
 */
object FriendlyFireGuard {

    private const val ALLY_RADIUS = 0.6
    private const val SIDESTEP_DISTANCE = 2.5
    private const val SIDESTEP_CHECK_RADIUS = 1.5

    /** SBW uses degrees; this also conservatively covers its older 0.0172275-radian conversion. */
    private const val SPREAD_RADIANS_PER_UNIT = Math.PI / 180.0

    /** True if no ally sits inside the actual firing cone toward [aimPoint] at the given [spread]
     *  (SBW's gun-spread units — pass 0.0 for an unspread/lobbed throw, which collapses this to a
     *  thin-line check plus each ally's own body-radius margin). */
    fun hasClearLineOfFire(shooter: NpcEntity, aimPoint: Vec3, spread: Double = 0.0): Boolean {
        val from = shooter.eyePosition
        val toAim = aimPoint.subtract(from)
        val aimDist = toAim.length()
        if (aimDist < 1.0e-6) return true
        val aimDir = toAim.scale(1.0 / aimDist)
        val maxAngle = SPREAD_RADIANS_PER_UNIT * spread

        // Include allies near the edge of wide spreads, especially when firing at drones. A
        // fixed 2-block search margin misses them before the angular check can run. The radius
        // uses the same maximum ally distance as the loop below; sin also bounds cones >= 90°.
        val coneRadius = (aimDist + ALLY_RADIUS) * sin(maxAngle.coerceIn(0.0, Math.PI / 2.0))
        val box = AABB(from, aimPoint).inflate(ALLY_RADIUS + maxOf(2.0, coneRadius))
        val allies = shooter.level().getEntitiesOfClass(LivingEntity::class.java, box) { candidate ->
            candidate !== shooter && (candidate is NpcEntity || candidate is Player) && !SquadTeams.isHostile(shooter, candidate)
        }
        for (ally in allies) {
            val toAlly = ally.boundingBox.center.subtract(from)
            val allyDist = toAlly.length()
            // Beyond the target's own distance (plus a little slack for its body), the round
            // would already have reached the target first — not actually in the way.
            if (allyDist < 1.0e-6 || allyDist > aimDist + ALLY_RADIUS) continue
            val cos = (toAlly.dot(aimDir) / allyDist).coerceIn(-1.0, 1.0)
            val angleToAlly = acos(cos)
            // The ally's own body isn't a point — widen the cone by its apparent angular radius
            // at its distance so a wide target right at the edge of the cone still counts.
            val angularMargin = atan(ALLY_RADIUS / allyDist)
            if (angleToAlly <= maxAngle + angularMargin) return false
        }
        return true
    }

    /** True if no ally sits within [blastRadius] of [impactPoint] — a separate check from the
     *  firing cone above, for weapons that damage an area at the target rather than just along
     *  the shot's path (M79 grenade rounds, thrown M67). */
    fun hasClearBlastRadius(shooter: NpcEntity, impactPoint: Vec3, blastRadius: Double): Boolean {
        if (blastRadius <= 0.0) return true
        val box = AABB(impactPoint, impactPoint).inflate(blastRadius)
        return shooter.level().getEntitiesOfClass(LivingEntity::class.java, box) { candidate ->
            candidate !== shooter && (candidate is NpcEntity || candidate is Player) && !SquadTeams.isHostile(shooter, candidate)
        }.none { it.position().distanceTo(impactPoint) <= blastRadius }
    }

    /** Steps [shooter] a short distance perpendicular to the shooter->[aimPoint] line, trying for
     *  an angle clear of whatever ally is currently in the way. Picks whichever side is currently
     *  less crowded with allies rather than a fixed left/right, so it doesn't reliably step
     *  straight into a second ally standing on the "wrong" side. Only meaningful for the firing-
     *  cone risk — repositioning the shooter doesn't change where an explosive round lands at the
     *  target, so callers should not use this to try to "fix" a blocked blast-radius check. */
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
