package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.phys.Vec3
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
    private var point: Vec3? = null

    init {
        flags = EnumSet.of(Flag.LOOK)
    }

    private fun eligible() = npc.target == null && npc.wary() && !npc.listening() && !npc.lookOwnedElsewhere()

    override fun canUse(): Boolean = eligible()

    override fun canContinueToUse(): Boolean = eligible()

    override fun start() {
        nextTurn = 0
        point = null
    }

    override fun tick() {
        val threat = npc.waryToward() ?: return
        if (npc.tickCount >= nextTurn) {
            nextTurn = npc.tickCount + TURN_TICKS
            val bearing = atan2(threat.z - npc.z, threat.x - npc.x)
            val angle = bearing + (npc.random.nextDouble() * 2 - 1) * SECTOR
            point = Vec3(npc.x + cos(angle) * LOOK_DISTANCE, npc.eyeY, npc.z + sin(angle) * LOOK_DISTANCE)
        }
        // Vanilla's LookControl turns the head for two ticks per setLookAt, about 10 degrees each:
        // asked once per turn, the head stopped a fraction of the way there.
        point?.let { npc.lookControl.setLookAt(it.x, it.y, it.z) }
    }

    private companion object {
        /** How often (2 s) the eyes move to another point of the sector. */
        const val TURN_TICKS = 40

        /** Half the sector swept round the threat's bearing, 70 degrees. */
        const val SECTOR = Math.PI * 7 / 18

        const val LOOK_DISTANCE = 16.0
    }
}
