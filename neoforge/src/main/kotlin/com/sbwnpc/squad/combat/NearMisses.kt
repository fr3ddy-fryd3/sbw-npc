package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * A bullet striking the ground or a wall next to an NPC with nothing to fight makes it wary of
 * where the round came from (#66), the way a shot that hits it does in [NpcEntity.hurt].
 *
 * The bearing is taken back along the round's flight, not from its shooter: a man hears the crack
 * and sees the dirt fly, he does not learn where the enemy stands. Called from
 * `BulletImpactMixin`: SBW raises no NeoForge impact event when a bullet hits a block.
 */
object NearMisses {
    /** How close (3 blocks) to a man a round has to land for him to notice it. */
    private const val RADIUS = 3.0

    /** How far back along the flight the man looks. */
    private const val LOOK_BACK = 16.0

    fun landed(round: Projectile, impact: Vec3) {
        val level = round.level() as? ServerLevel ?: return
        val flight = round.deltaMovement
        if (flight == Vec3.ZERO) return
        val from = impact.subtract(flight.normalize().scale(LOOK_BACK))
        val shooter = round.owner
        // Every round of a firefight lands somewhere: the entity sections round the impact are cheap
        // to ask, a scan of every NPC in the level per round is not.
        level.getEntitiesOfClass(NpcEntity::class.java, AABB(impact, impact).inflate(RADIUS)) { it.isAlive && it.target == null }.forEach {
            if (shooter == null || !SquadTeams.sameSide(it, shooter)) it.becomeWary(from)
        }
    }
}
