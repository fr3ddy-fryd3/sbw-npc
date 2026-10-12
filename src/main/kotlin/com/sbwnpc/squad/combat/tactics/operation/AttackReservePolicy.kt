package com.sbwnpc.squad.combat.tactics.operation

import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Use arrived reserves to replace a persistently empty front, rather than counting distant recruits. */
internal class AttackReservePolicy {
    data class Activation(val reserve: UUID, val role: AttackRole, val relieved: UUID?, val reason: String)
    private val sparseSince = HashMap<UUID, Long>()
    private var nextActivation = Long.MIN_VALUE

    fun choose(lanes: Collection<AttackLane>, forces: Map<UUID, AttackForce>, focus: Vec3, now: Long): Activation? {
        val active = lanes.filter { it.role != AttackRole.RESERVE }
        sparseSince.keys.retainAll(active.mapTo(HashSet()) { it.squad })
        val local = active.associate { lane -> lane.squad to strength(lane, forces[lane.squad], focus) }
        for (lane in active) {
            val minimum = maxOf(3, (forces[lane.squad]?.fighters?.size ?: 0) / 4)
            if ((!lane.deployed && !lane.blocked) || local.getValue(lane.squad) >= minimum) {
                sparseSince.remove(lane.squad)
            } else {
                sparseSince.putIfAbsent(lane.squad, now)
            }
        }
        if (now < nextActivation) return null
        val reserve = lanes.filter { it.role == AttackRole.RESERVE }.mapNotNull { lane ->
            val force = forces[lane.squad] ?: return@mapNotNull null
            val ready = strength(lane, force, focus)
            lane.takeIf { ready >= minOf(4, force.fighters.size) && ready > 0 && ready * 4 >= force.fighters.size * 3 }?.let { it to ready }
        }.sortedWith(compareByDescending<Pair<AttackLane, Int>> { it.second }.thenBy { it.first.squad }).firstOrNull() ?: return null
        val vacant = listOf(AttackRole.LEFT, AttackRole.RIGHT).firstOrNull { role -> active.none { it.role == role } }
        val weak = active.sortedWith(compareBy<AttackLane> { if (it.role == AttackRole.CENTER) 0 else 1 }.thenBy { local.getValue(it.squad) })
            .firstOrNull { now - (sparseSince[it.squad] ?: now) >= GRACE && reserve.second >= maxOf(4, local.getValue(it.squad) * 2) }
        val activation = when {
            vacant != null -> Activation(reserve.first.squad, vacant, null, "vacant_sector")
            weak != null -> Activation(reserve.first.squad, weak.role, weak.squad, "persistently_understaffed_sector")
            else -> return null
        }
        nextActivation = now + GRACE
        return activation
    }

    fun strength(lane: AttackLane, force: AttackForce?, focus: Vec3): Int {
        if (force == null) return 0
        val point = combatSector(lane)
        val facing = focus.subtract(point).multiply(1.0, 0.0, 1.0).normalize()
        val side = Vec3(-facing.z, 0.0, facing.x)
        return force.fighters.count { member ->
            val post = if (lane.role == AttackRole.RESERVE) point else point.add(side.scale(lane.slots[member.id] ?: 0.0))
            member.position.subtract(post).horizontalDistance() <= (if (lane.role == AttackRole.RESERVE) 48.0 else 24.0) && kotlin.math.abs(member.position.y - post.y) <= 16.0
        }
    }

    fun combatSector(lane: AttackLane): Vec3 = if (lane.blocked && lane.route.size >= 2) lane.route[lane.route.lastIndex - 1] else lane.sector

    companion object {
        const val GRACE = 200L
    }
}
