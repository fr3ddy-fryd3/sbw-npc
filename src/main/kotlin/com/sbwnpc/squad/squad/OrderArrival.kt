package com.sbwnpc.squad.squad

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.vehicle.Helicopters
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
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
            // AH-6 keeps orbiting for its gunners instead of finishing the attack on arrival.
            if (squad.order == SquadOrder.ATTACK && mgr.isTransportSquad(squad)) continue
            // Hunting a unit rather than taking a point: nothing to arrive at.
            if (squad.focusEntity != null) continue
            val goal = squad.objective ?: continue
            val point = Vec3(goal.x + 0.5, goal.y.toDouble(), goal.z + 0.5)
            val members = squad.members.mapNotNull { SquadManager.findEntity(server, it) as? NpcEntity }
                .filter { it.isAlive }
            if (members.isEmpty()) continue
            // Every role marches to the objective once enemy contact has ended.
            val there = members.count { npc ->
                // Formation tolerance includes height: it can accept passengers during the flare.
                // DEFEND would then cancel LANDING and launch a loiter with everyone still aboard.
                val ride = npc.vehicle
                val landed = ride == null || !Helicopters.isHelicopter(ride) || Helicopters.isGrounded(npc.level() as ServerLevel, ride)
                landed && SquadFormation.reachedPoint(npc, point, squad.members.size)
            }
            if (there * 4 < members.size * 3) continue
            // A spread attack line can satisfy the formation tolerance while still fighting.
            // Changing to DEFEND here cancels maneuvers and drags the outer shooters back inside its leash.
            val now = members.first().level().gameTime
            val engaged = members.any { npc -> npc.target?.let { it.isAlive && npc.isEnemy(it) } == true } ||
                squad.tactics.contacts.values.any { now - it.seenAt <= 200 }
            if (squad.order == SquadOrder.ATTACK && engaged) {
                DebugFlags.log(LogGroup.ORDER, "{} event=arrival_held reason=enemy_contact arrived={} members={}", squad.name, there, members.size)
                continue
            }
            DebugFlags.log(LogGroup.ORDER, "{} took its point ({}), defending", squad.name, squad.order)
            val was = squad.order
            if (was == SquadOrder.ATTACK) com.sbwnpc.squad.experience.ExperienceAwards.objective(server, squad)
            mgr.setOrder(squad.id, SquadOrder.DEFEND)
            SquadReports.holding(server, squad, was)
            com.sbwnpc.squad.combat.Withdrawal.forget(squad.id)
        }
    }
}
