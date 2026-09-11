package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.item.misc.FiringParametersItem
import com.atsuishio.superbwarfare.item.misc.firingParameters
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import java.util.EnumSet

/**
 * Requires: the mortar is already loaded with shells (player-loaded — this goal doesn't handle
 * ammo logistics) and the squad has an ATTACK order with an objective or focus entity. Minimum
 * range / safe-distance checks are heuristics, not a faithful read of the mortar's own internal
 * aim-solver state — needs in-game tuning.
 */
class MortarOperatorGoal(private val mob: NpcEntity) : Goal() {

    private var mortar: MortarEntity? = null
    private var nextAimTick = 0

    init {
        setFlags(EnumSet.of(Flag.MOVE))
    }

    override fun canUse(): Boolean {
        if (mob.npcClass != NpcClass.MORTAR_OPERATOR) return false
        if (mob.target != null) return false // don't abandon self-defence
        val squad = mob.currentSquad() ?: return false
        if (squad.order != SquadOrder.ATTACK) return false
        if (fireTargetOf(squad) == null) return false

        val current = mortar
        if (current != null && current.isAlive && !MortarClaims.isClaimedByOther(current.uuid, mob.uuid)) return true

        val level = mob.level() as? ServerLevel ?: return false
        val found = level.getEntitiesOfClass(
            MortarEntity::class.java, AABB.ofSize(mob.position(), SEARCH_RANGE, SEARCH_RANGE, SEARCH_RANGE)
        ).firstOrNull { !MortarClaims.isClaimedByOther(it.uuid, mob.uuid) } ?: return false

        MortarClaims.claim(found.uuid, mob.uuid)
        mortar = found
        return true
    }

    override fun canContinueToUse() = canUse()

    override fun stop() {
        MortarClaims.release(mob.uuid)
        mortar = null
    }

    override fun tick() {
        val m = mortar ?: return
        val level = mob.level() as? ServerLevel ?: return
        val squad = mob.currentSquad() ?: return
        val target = fireTargetOf(squad) ?: return

        val dist = mob.position().distanceTo(m.position())
        if (dist > 2.5) {
            mob.navigation.moveTo(m.x, m.y, m.z, 1.0)
            return
        }
        mob.navigation.stop()

        if (target.distSqr(BlockPos.containing(m.position())) < MIN_RANGE_SQR) return
        if (friendlyNear(level, squad, target)) return

        if (mob.tickCount >= nextAimTick) {
            val stack = ItemStack(ModItems.FIRING_PARAMETERS.get())
            stack.firingParameters = FiringParametersItem.Parameters(target)
            m.setTarget(stack, mob, "Main")
            nextAimTick = mob.tickCount + 20
        }
        m.vehicleShoot(mob, "Main", null)
    }

    private fun fireTargetOf(squad: com.sbwnpc.squad.squad.Squad): BlockPos? {
        squad.focusEntity?.let { fid ->
            (mob.level() as? ServerLevel)?.getEntity(fid)?.takeIf { it.isAlive }?.let { return BlockPos.containing(it.position()) }
        }
        return squad.objective
    }

    private fun friendlyNear(level: ServerLevel, squad: com.sbwnpc.squad.squad.Squad, target: BlockPos): Boolean {
        val center = target.center
        return level.getEntitiesOfClass(NpcEntity::class.java, AABB.ofSize(center, SAFE_RADIUS * 2, SAFE_RADIUS * 2, SAFE_RADIUS * 2))
            .any { it.squadId != null && !SquadTeams.isHostile(mob, it) }
    }

    companion object {
        private const val SEARCH_RANGE = 40.0
        private const val MIN_RANGE_SQR = 15.0 * 15.0
        private const val SAFE_RADIUS = 10.0
    }
}
