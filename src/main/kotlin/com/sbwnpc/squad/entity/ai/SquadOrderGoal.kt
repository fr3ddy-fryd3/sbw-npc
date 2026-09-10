package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.util.DefaultRandomPos
import net.minecraft.world.phys.Vec3
import java.util.EnumSet

/**
 * Squad-order movement. Low priority — the gun goal (chase & shoot) always wins when there's an
 * enemy; this only steers the NPC when it's otherwise idle.
 *
 *  - DEFEND: return to within ~8 blocks of home (objective point / guarded entity), then hold.
 *  - PATROL: wander within ~12 blocks of home.
 *  - ATTACK: advance to home.
 *  - FREE / no squad / no home: inactive.
 */
class SquadOrderGoal(private val mob: NpcEntity) : Goal() {

    init {
        setFlags(EnumSet.of(Flag.MOVE))
    }

    private var repathCooldown = 0

    override fun canUse(): Boolean {
        if (mob.target != null) return false
        val squad = mob.currentSquad() ?: return false
        return squad.order != SquadOrder.FREE && mob.homeCenter() != null
    }

    override fun canContinueToUse() = canUse()

    override fun tick() {
        val order = mob.currentSquad()?.order ?: return
        val home = mob.homeCenter() ?: return
        val dist = mob.position().distanceTo(home)
        if (repathCooldown > 0) repathCooldown--

        when (order) {
            SquadOrder.ATTACK -> if (dist > 3.0 && repathCooldown == 0) {
                mob.navigation.moveTo(home.x, home.y, home.z, 1.0)
                repathCooldown = 20
            }
            SquadOrder.DEFEND -> {
                if (dist > 8.0 && repathCooldown == 0) {
                    mob.navigation.moveTo(home.x, home.y, home.z, 1.0)
                    repathCooldown = 20
                } else if (dist <= 8.0) {
                    mob.navigation.stop()
                }
            }
            SquadOrder.PATROL -> if (mob.navigation.isDone && repathCooldown == 0) {
                wanderNear(home)
                repathCooldown = 40 + mob.random.nextInt(40)
            }
            SquadOrder.FREE -> {}
        }
    }

    private fun wanderNear(center: Vec3) {
        val target = DefaultRandomPos.getPosTowards(mob, 12, 6, center, Math.PI / 2.0) ?: return
        mob.navigation.moveTo(target.x, target.y, target.z, 0.9)
    }
}
