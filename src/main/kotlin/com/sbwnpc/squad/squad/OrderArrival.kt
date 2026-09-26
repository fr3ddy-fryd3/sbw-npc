package com.sbwnpc.squad.squad

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.MinecraftServer
import net.minecraft.world.phys.Vec3

/**
 * Turns a squad that has taken its point into one holding it: ATTACK and RETREAT become DEFEND
 * once most of the squad is there.
 *
 * Checked here, for the squad as a whole, once a second. It used to be each member's own job, and
 * only a member with nobody to shoot at ever did it, needing every last man within a few blocks:
 * one straggler, one man in a firefight or one who couldn't quite reach his slot kept the whole
 * squad "attacking" a point it had long since taken.
 */
object OrderArrival {
    private const val INTERVAL_TICKS = 20

    fun tick(server: MinecraftServer) {
        if (server.tickCount % INTERVAL_TICKS != 0) return
        val mgr = SquadManager.get(server)
        for (squad in mgr.all()) {
            if (squad.order != SquadOrder.ATTACK && squad.order != SquadOrder.RETREAT) continue
            // Hunting a unit rather than taking a point: nothing to arrive at.
            if (squad.focusEntity != null) continue
            val goal = squad.objective ?: continue
            val point = Vec3(goal.x + 0.5, goal.y.toDouble(), goal.z + 0.5)
            val members = squad.members.mapNotNull { SquadManager.findEntity(server, it) as? NpcEntity }.filter { it.isAlive }
            if (members.isEmpty()) continue
            // Three in four: one man pinned in a ditch shouldn't keep the rest from digging in.
            val there = members.count { SquadFormation.reachedPoint(it, point, squad.members.size) }
            if (there * 4 < members.size * 3) continue
            DebugFlags.log("[order-debug] {} took its point ({}), defending", squad.name, squad.order)
            mgr.setOrder(squad.id, SquadOrder.DEFEND)
        }
    }
}
