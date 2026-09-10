package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.util.DefaultRandomPos
import java.util.EnumSet

/**
 * Squad-order movement. Low priority — the gun goal (chase & shoot) always wins when there's an
 * enemy; this only steers the NPC when it's otherwise idle.
 *
 * v1 behaviours:
 *  - DEFEND: return to within ~8 blocks of the objective, then hold.
 *  - PATROL: wander within ~12 blocks of the objective.
 *  - ATTACK: advance to the objective.
 *  - FREE / no squad / no objective: inactive (default wander applies).
 */
class SquadOrderGoal(private val mob: NpcEntity) : Goal() {

    init {
        setFlags(EnumSet.of(Flag.MOVE))
    }

    private var repathCooldown = 0

    private fun squad(): Squad? {
        val id = mob.squadId ?: return null
        val level = mob.level() as? ServerLevel ?: return null
        return SquadManager.get(level).get(id)
    }

    override fun canUse(): Boolean {
        if (mob.target != null) return false
        val squad = squad() ?: return false
        return squad.order != SquadOrder.FREE && squad.objective != null
    }

    override fun canContinueToUse() = canUse()

    override fun tick() {
        val squad = squad() ?: return
        val obj = squad.objective ?: return
        val distSqr = mob.blockPosition().distSqr(obj)

        if (repathCooldown > 0) repathCooldown--

        when (squad.order) {
            SquadOrder.ATTACK -> {
                if (distSqr > 9.0 && repathCooldown == 0) {
                    mob.navigation.moveTo(obj.x + 0.5, obj.y.toDouble(), obj.z + 0.5, 1.0)
                    repathCooldown = 20
                }
            }
            SquadOrder.DEFEND -> {
                if (distSqr > 64.0 && repathCooldown == 0) {
                    mob.navigation.moveTo(obj.x + 0.5, obj.y.toDouble(), obj.z + 0.5, 1.0)
                    repathCooldown = 20
                } else if (distSqr <= 64.0) {
                    mob.navigation.stop()
                }
            }
            SquadOrder.PATROL -> {
                if (mob.navigation.isDone && repathCooldown == 0) {
                    wanderNear(obj)
                    repathCooldown = 40 + mob.random.nextInt(40)
                }
            }
            SquadOrder.FREE -> {}
        }
    }

    private fun wanderNear(center: BlockPos) {
        val target = DefaultRandomPos.getPosTowards(
            mob, 12, 6,
            net.minecraft.world.phys.Vec3(center.x + 0.5, center.y.toDouble(), center.z + 0.5),
            Math.PI / 2.0
        ) ?: return
        mob.navigation.moveTo(target.x, target.y, target.z, 0.9)
    }
}
