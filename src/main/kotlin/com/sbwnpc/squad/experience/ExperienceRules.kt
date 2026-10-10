package com.sbwnpc.squad.experience

import com.sbwnpc.squad.config.SquadConfig
import com.sbwnpc.squad.npc.NpcRank
import java.util.UUID

object ExperienceRules {
    fun threshold(rank: NpcRank): Double = SquadConfig.experienceThresholds()[rank.ordinal].toDouble()
    fun rank(xp: Double): NpcRank = NpcRank.entries.lastOrNull { xp >= threshold(it) } ?: NpcRank.RECRUIT
    fun next(rank: NpcRank): Double? = NpcRank.entries.getOrNull(rank.ordinal + 1)?.let(::threshold)
    fun name(id: UUID): String {
        val first = listOf("Alex", "Sam", "Chris", "Max", "Victor", "Leon", "Daniel", "Nick", "Eli", "Mark", "Oscar", "Finn", "Robin", "James", "Anton", "Ben")
        val last = listOf("Stone", "Reed", "Cole", "Ward", "Hill", "Fox", "Brooks", "Gray", "West", "Lane", "Wood", "Hart", "Price", "Hale", "King", "Cross")
        return first[(id.mostSignificantBits and 15).toInt()] + " " + last[(id.leastSignificantBits and 15).toInt()]
    }
}
