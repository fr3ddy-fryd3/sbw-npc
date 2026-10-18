package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.world.entity.ai.goal.Goal
import java.util.EnumSet
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A wary NPC with nothing to fight sweeps its eyes over the sector the threat came from, instead
 * of vanilla's random look-around (#66). Ends when the wariness does, or when it gets a target.
 */
class WaryScanGoal(private val npc: NpcEntity) : Goal() {
    private var nextTurn = 0

    init {
        flags = EnumSet.of(Flag.LOOK)
    }

    private fun eligible() = npc.target == null && npc.wary() && !npc.listening() && !npc.lookOwnedElsewhere()

    override fun canUse(): Boolean = eligible()

    override fun canContinueToUse(): Boolean = eligible()

    override fun start() {
        nextTurn = 0
    }

    override fun tick() {
        if (npc.tickCount < nextTurn) return
        val threat = npc.waryToward() ?: return
        nextTurn = npc.tickCount + TURN_TICKS
        val bearing = atan2(threat.z - npc.z, threat.x - npc.x)
        val angle = bearing + (npc.random.nextDouble() * 2 - 1) * SECTOR
        npc.lookControl.setLookAt(npc.x + cos(angle) * LOOK_DISTANCE, npc.eyeY, npc.z + sin(angle) * LOOK_DISTANCE)
    }

    private companion object {
        /** How often (2 s) the eyes move to another point of the sector. */
        const val TURN_TICKS = 40

        /** Half the sector swept round the threat's bearing, 70 degrees. */
        const val SECTOR = Math.PI * 7 / 18

        const val LOOK_DISTANCE = 16.0
    }
}
