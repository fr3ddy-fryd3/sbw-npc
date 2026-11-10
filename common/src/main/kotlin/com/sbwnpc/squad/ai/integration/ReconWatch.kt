package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.squad.Squad
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * What a level's recon squads have given away and seen under their current order (#194). Latched
 * where it happens (a hit, an enemy taking a scout as target, a scout noticing an enemy) and kept per
 * order stamp, so a new order starts hidden again. Runtime only: after a reload a squad starts hidden.
 */
internal class ReconWatch {
    private val discovered = HashMap<UUID, Int>()
    private val sawEnemy = HashMap<UUID, Int>()
    private val reported = HashMap<UUID, Int>()

    /** Squads discovered since the last pass, with where the enemy who found them stood, if known. */
    val pending = HashMap<UUID, Vec3?>()

    fun discover(squad: Squad, how: String, threat: Vec3?) {
        if (discovered.put(squad.id, squad.orderStamp) == squad.orderStamp) return
        pending[squad.id] = threat
        DebugFlags.log(LogGroup.ORDER, "[recon] squad={} event=discovered how={}", squad.id, how)
    }

    fun report(squad: Squad) {
        reported[squad.id] = squad.orderStamp
    }

    fun reported(squad: Squad) = reported[squad.id] == squad.orderStamp

    fun saw(squad: Squad) {
        if (sawEnemy.put(squad.id, squad.orderStamp) != squad.orderStamp) DebugFlags.log(LogGroup.ORDER, "[recon] squad={} event=enemy_seen", squad.id)
    }

    fun discovered(squad: Squad) = discovered[squad.id] == squad.orderStamp

    fun sawEnemy(squad: Squad) = sawEnemy[squad.id] == squad.orderStamp

    fun forget(squad: UUID) {
        discovered.remove(squad)
        sawEnemy.remove(squad)
        reported.remove(squad)
        pending.remove(squad)
    }

    fun clear() {
        discovered.clear()
        sawEnemy.clear()
        reported.clear()
        pending.clear()
    }
}
