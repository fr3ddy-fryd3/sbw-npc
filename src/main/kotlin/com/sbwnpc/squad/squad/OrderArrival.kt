package com.sbwnpc.squad.squad

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.MinecraftServer
import net.minecraft.world.phys.Vec3

/**
 * Turns a squad that has taken its point into one holding it: ATTACK and RETREAT become DEFEND
 * once three in four of the squad are there.
 *
 * The only place that makes this switch, for the squad as a whole, once a second, through
 * [SquadManager.setOrder] so every member takes it as a new order and it is saved. It used to be
 * made in three places by three different rules — by each member (every last man within a few
 * blocks, and only while it had nobody to shoot at), by the withdrawal, and here — and the first
 * two set the order behind the manager's back: the members never took it as new orders, and the
 * world saved the squad still attacking or retreating.
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
            val was = squad.order
            mgr.setOrder(squad.id, SquadOrder.DEFEND)
            SquadReports.holding(server, squad, was)
            com.sbwnpc.squad.combat.Withdrawal.forget(squad.id)
        }
    }
}
