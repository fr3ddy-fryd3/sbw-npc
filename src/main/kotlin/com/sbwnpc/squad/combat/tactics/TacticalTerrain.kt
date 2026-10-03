package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.TickBudget
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/** Bounded probes of the immediate route; no whole-map scan or chunk loading. */
object TacticalTerrain {
    data class Assessment(val narrow: Boolean = false, val open: Boolean = false)
    fun assess(level: ServerLevel, members: List<NpcEntity>, destination: Vec3?, threat: Vec3?): Assessment {
        if (destination == null || members.isEmpty()) return Assessment()
        val lead = members.minBy { it.position().distanceToSqr(destination) }
        val forward = destination.subtract(lead.position()).multiply(1.0,0.0,1.0).normalize()
        if (forward.lengthSqr() < 0.01) return Assessment()
        val side = Vec3(-forward.z,0.0,forward.x)
        val ahead = lead.navigation.path?.takeIf { !it.isDone }?.getNextEntityPos(lead)
            ?: lead.position().add(forward.scale(4.0))
        fun walkable(raw: Vec3): Boolean {
            val pos = BlockPos.containing(raw)
            if (!level.chunkSource.hasChunk(pos.x shr 4,pos.z shr 4)) return false
            val feet = Terrain.standableOrNull(level,raw.x,raw.y,raw.z,4) ?: return false
            if (kotlin.math.abs(feet.y-ahead.y) > 2.0 || !level.getFluidState(BlockPos.containing(feet)).isEmpty) return false
            return level.noCollision(lead,lead.getDimensions(lead.pose).makeBoundingBox(feet))
        }
        val narrow = walkable(ahead) && !walkable(ahead.add(side.scale(2.0))) && !walkable(ahead.subtract(side.scale(2.0)))
        var exposed = 0
        if (threat != null) for (npc in members.take(3)) {
            if (!TickBudget.hasRaycasts(level)) break
            if (!Sightline.blocked(level,threat.add(0.0,1.5,0.0),npc.position().add(0.0,1.0,0.0),npc)) exposed++
        }
        return Assessment(narrow, exposed >= minOf(2,members.size))
    }
}
