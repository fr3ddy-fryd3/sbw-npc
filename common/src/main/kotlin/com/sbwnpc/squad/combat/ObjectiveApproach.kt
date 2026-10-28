package com.sbwnpc.squad.combat

import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.compat.VersionCompat
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.phys.Vec3

/**
 * The cautious approach to an objective (#148): a man on foot within [RANGE] blocks of his squad's
 * ATTACK or MOVE objective is wary, so he looks toward it and spots sooner (#186), and on ATTACK he
 * slows from the run to a walk. His first target ends it for him until the squad is given a new
 * order; what follows is the wariness after a contact.
 *
 * MOVE already walks, so it only gets the wariness: slowing it again would make it crawl.
 */
object ObjectiveApproach {
    /** Blocks from the objective within which the approach is cautious. */
    const val RANGE = 100.0

    /** Ticks between distance checks; a first target is noticed every tick. */
    private const val CHECK_TICKS = 10

    private val SLOW_MODIFIER_ID = ModIdentity.loc("objective_approach")

    /** Walking pace over running pace on ATTACK, see `SquadOrderBehaviour.WALK_SPEED_MODIFIER`. */
    private const val WALK_PACE_AMOUNT = 0.6 - 1.0

    fun cautious(order: SquadOrder, distance: Double, onFoot: Boolean, spent: Boolean): Boolean = !spent && onFoot && distance <= RANGE && (order == SquadOrder.ATTACK || order == SquadOrder.MOVE)

    /** One per NPC. */
    class State {
        /** The objective the man is wary toward while the approach is cautious. */
        var toward: Vec3? = null
            private set
        private var spentStamp = -1
        private var spentSquad: java.util.UUID? = null

        fun update(npc: NpcEntity) {
            if (npc.target != null) {
                if (toward != null) {
                    val squad = npc.currentSquad()
                    spentSquad = squad?.id
                    spentStamp = squad?.orderStamp ?: -1
                    end(npc)
                }
                return
            }
            if ((npc.tickCount + npc.id) % CHECK_TICKS != 0) return
            val squad = npc.currentSquad()
            val objective = squad?.objective
            if (squad == null || objective == null) return end(npc)
            val spent = spentSquad == squad.id && spentStamp == squad.orderStamp
            val onFoot = npc.vehicle == null && !npc.vehicleTransport
            val distance = npc.position().distanceTo(Vec3.atCenterOf(objective))
            if (!cautious(squad.order, distance, onFoot, spent)) return end(npc)
            toward = Vec3.atCenterOf(objective)
            slow(npc, squad.order == SquadOrder.ATTACK)
        }

        private fun end(npc: NpcEntity) {
            toward = null
            slow(npc, false)
        }

        private fun slow(npc: NpcEntity, on: Boolean) {
            val speed = npc.getAttribute(Attributes.MOVEMENT_SPEED) ?: return
            if (on) VersionCompat.setMultipliedModifier(speed, SLOW_MODIFIER_ID, WALK_PACE_AMOUNT) else VersionCompat.removeModifier(speed, SLOW_MODIFIER_ID)
        }
    }
}
