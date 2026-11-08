package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.AgentLevel
import com.sbwnpc.squad.npc.NpcClass
import java.util.UUID
import kotlin.math.ceil

/**
 * A squad's lasting fire teams (#184): a man keeps his team whatever his duty, so a role the squad
 * gives a team (support, manoeuvre) changes the team's task, not who is in it.
 *
 * A squad of four to [PAIR_UP_TO] men forms two teams, as a rifle squad forms two fire teams, and a
 * larger one a team per [SIZE] men. Machine gunners and assault men are spread over the teams so
 * each can fire and close in by itself; snipers all stay in the first team, which the squad keeps
 * on its fire line (owner, 08.11). A man keeps his team while he is in the squad's [roster], also
 * while he rides or is away for a moment, and is moved only to even out sizes that drifted
 * [DRIFT] apart, and only while the squad is [steady]: a man who changes team has his legs reset,
 * and mid-manoeuvre he would change sides.
 */
class FireTeams {
    /** One man as the teams see him. */
    data class Man(val id: UUID, val role: NpcClass, val origin: Point3)

    private class Team(val id: AgentId, val members: LinkedHashSet<UUID> = LinkedHashSet())

    private val teams = ArrayList<Team>()
    private var serial = 0L

    /**
     * The teams of [squad]'s present [men], each as the ids of its members, in a stable order. Men of
     * the [roster] not present keep their place but are not listed.
     */
    fun assign(squad: UUID, men: List<Man>, roster: Set<UUID>, steady: Boolean): Map<AgentId, List<UUID>> {
        require(men.map { it.id }.distinct().size == men.size)
        val byId = men.associateBy { it.id }
        teams.forEach { team -> team.members.retainAll(roster + byId.keys) }
        teams.removeIf { it.members.isEmpty() }
        val target = target(teams.flatMapTo(HashSet()) { it.members }.union(byId.keys).size)
        if (teams.isEmpty() || steady) {
            while (teams.size < target) teams += Team(AgentId(AgentLevel.TEAM, UUID.nameUUIDFromBytes("$squad:team:${serial++}".toByteArray())))
            // The smallest team's men join the rest one by one, as newcomers do.
            while (teams.size > target) teams.remove(teams.minBy { it.members.size })
        }
        val placed = teams.flatMapTo(HashSet()) { it.members }
        men.filter { it.id !in placed }.sortedWith(compareBy<Man> { rank(it.role) }.thenBy { it.id.toString() }).forEach { man -> place(man, byId) }
        if (steady) rebalance(byId)
        return teams.associate { team -> team.id to team.members.filter { it in byId } }.filterValues { it.isNotEmpty() }
    }

    private fun place(man: Man, byId: Map<UUID, Man>) {
        val kind = kind(man.role)
        if (man.role == NpcClass.SNIPER) {
            teams.first().members += man.id
            return
        }
        val team = teams.minWith(
            compareBy<Team> { team -> if (kind == null) 0 else team.members.count { id -> byId[id]?.let { kind(it.role) } == kind } }
                .thenBy { it.members.size }
                .thenBy { team -> distance(team, man, byId) },
        )
        team.members += man.id
    }

    // Moves the man who adds least to his team's kit, so a gun or a grenadier stays where he is.
    private fun rebalance(byId: Map<UUID, Man>) {
        while (teams.size > 1) {
            val largest = teams.maxBy { it.members.size }
            val smallest = teams.minBy { it.members.size }
            if (largest.members.size - smallest.members.size < DRIFT) return
            // Only a man present and no sniper: snipers stay on the fire line.
            val moved = largest.members.filter { byId[it]?.let { man -> man.role != NpcClass.SNIPER } == true }
                .maxWithOrNull(compareBy<UUID> { rank(byId.getValue(it).role) }.thenBy { it.toString() }) ?: return
            largest.members -= moved
            smallest.members += moved
        }
    }

    private fun distance(team: Team, man: Man, byId: Map<UUID, Man>): Double {
        val present = team.members.mapNotNull(byId::get)
        if (present.isEmpty()) return 0.0
        return present.sumOf { it.origin.horizontalDistanceSquared(man.origin) } / present.size
    }

    companion object {
        /** How far apart team sizes may drift before a man is moved. */
        const val DRIFT = 2

        /** Below this a squad is a single team: two teams of one man cover nothing. */
        const val MIN_SPLIT = 4

        /** Up to this many men a squad is two teams, as before it bounded by halves. */
        const val PAIR_UP_TO = 12

        /** A larger squad forms a team per this many men. */
        const val SIZE = 6

        fun target(size: Int) =
            when {
                size < MIN_SPLIT -> minOf(size, 1)
                size <= PAIR_UP_TO -> 2
                else -> ceil(size / SIZE.toDouble()).toInt()
            }

        // Kits spread over the teams, placed first so each team gets its share.
        private fun kind(role: NpcClass) =
            when (role) {
                NpcClass.MACHINE_GUNNER -> 0
                NpcClass.GRENADIER, NpcClass.SHOTGUNNER -> 1
                else -> null
            }

        // Placing order: guns, then assault men, then snipers, then everyone else.
        private fun rank(role: NpcClass) = if (role == NpcClass.SNIPER) SNIPER_RANK else kind(role) ?: SNIPER_RANK + 1
        private const val SNIPER_RANK = 2
    }
}
