package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.tactics.FireOptions
import com.sbwnpc.squad.ai.tactics.FireSupportTargets
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.Hostiles
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.MortarClaims
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.squad.SquadReports
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import com.sbwnpc.squad.squad.FireSupport as InfantrySupport

/**
 * A crew's eyes and its target choice (#258), run at the crew's scan rate whatever its plan does:
 * the operator scans for enemies, reports what he notices, and [FireSupportTargets] picks the
 * target in today's priority. The plan reads the result as `crew.fire`.
 */
internal class CrewSpotter(private val level: ServerLevel, private val preparation: PreparationLedger) {
    fun update(unit: CrewUnit, squad: Squad?, men: List<NpcEntity>, state: CrewState, now: Long) {
        val eyes = unit.tube?.let(MortarClaims::operator)?.let { id -> men.firstOrNull { it.uuid == id } } ?: men.first()
        scan(eyes, state, now)
        val faction = SquadTeams.factionOf(eyes)
        val reported = faction?.let { TeamAwareness.sightings(it, now) }?.firstOrNull { (level.getEntity(it.target) as? LivingEntity)?.isAlive == true }
        val own = state.ownSighting?.takeIf { now - state.ownSeenAt <= FireSupportTargets.OWN_SIGHTING_HOLD_TICKS }
        val sighted = faction?.let { TeamAwareness.sightings(it, now) }.orEmpty().mapTo(HashSet()) { it.target.toString() } + listOfNotNull(own?.toString())
        val options = (squad?.let(::ordered) ?: FireOptions()).copy(ownSighting = own, factionSighting = reported?.target, sighted = sighted)
        val chosen = FireSupportTargets.choose(options, state.target)
        if (chosen != state.target) DebugFlags.log(LogGroup.FIRE_SUPPORT, "crew={} event=target from={} to={}", unit.crew.agent.id, state.target, chosen)
        state.target = chosen
        // Where the side last saw it, not where it is now: the shell goes where the call said.
        state.sightingPos = when {
            chosen == null || !chosen.sighting -> null
            chosen.key == own?.toString() -> state.ownSeenPos
            else -> faction?.let { TeamAwareness.sightings(it, now) }?.firstOrNull { it.target.toString() == chosen.key }?.pos ?: state.ownSeenPos
        }
    }

    /**
     * What [squad]'s order gives the crew to fire at. Attacking with other squads, it fires
     * [InfantrySupport.PREPARATION_SALVOS] at the objective's area, then only at a live focus or
     * an enemy its side has seen. Alone, it shells the area while infantry approaches and then the
     * objective, as before.
     */
    private fun ordered(squad: Squad): FireOptions {
        val key = "${squad.id}:${squad.orderStamp}"
        val attack = squad.order == SquadOrder.ATTACK && squad.objective != null
        val partnered = attack && preparation.partnered(squad.id, key) { InfantrySupport.partnered(level, squad) }
        return FireOptions(
            barrage = squad.order == SquadOrder.BARRAGE && squad.objective != null,
            preparation = attack &&
                if (partnered) preparation.shots(squad.id, key) < InfantrySupport.PREPARATION_SALVOS else InfantrySupport.infantryApproaching(level, squad),
            commanded = squad.takeIf { it.order == SquadOrder.ATTACK }?.let { commanded(it, objective = !partnered) },
            orderKey = key,
        )
    }

    /** The ATTACK order's target: its focus while alive, else its [objective] point when allowed. */
    private fun commanded(squad: Squad, objective: Boolean): String? =
        squad.focusEntity?.takeIf { (level.getEntity(it) as? LivingEntity)?.isAlive == true }?.toString()
            ?: squad.objective?.takeIf { objective }?.let { "objective:${it.x},${it.y},${it.z}" }

    /**
     * The nearest enemy the crew notices itself, reported to its faction. Noticed, not just in view
     * (#114); all round, as through binoculars, since serving the tube turns his head onto it.
     * Never blind radius scanning: a man in a building nobody has seen stays safe from the tube.
     */
    private fun scan(eyes: NpcEntity, state: CrewState, now: Long) {
        val faction = SquadTeams.factionOf(eyes)
        // Nearest first and few raycasts: the nearest one seen is the target either way, and the
        // infantry fighting the rest already reports them.
        var spotted: LivingEntity? = null
        var kept: LivingEntity? = null
        for ((checks, candidate) in Hostiles.within(level, eyes, detectionRadius(eyes)).withIndex()) {
            if (checks >= MAX_LOS_CHECKS) break
            if (DetectionSightline.sight(eyes, candidate).visibility != DetectionSightline.Visibility.VISIBLE) continue
            if (!state.noticing.noticed(eyes, level, candidate, offAxis = 1.0)) continue
            if (faction != null) TeamAwareness.report(faction, candidate.uuid, candidate.position(), now, "mortar ${eyes.uuid.toString().take(SHORT_ID)}")
            if (spotted == null) spotted = candidate
            if (candidate.uuid == state.ownSighting) kept = candidate
        }
        state.noticing.endScan(now)
        // The one he watches while he still notices him, not whichever is a step nearer this scan.
        kept?.let { spotted = it }
        if (spotted == null) return
        if (state.ownSighting != spotted.uuid) SquadReports.contact(eyes, spotted)
        state.ownSighting = spotted.uuid
        state.ownSeenAt = now
        state.ownSeenPos = spotted.position()
    }

    private fun detectionRadius(man: NpcEntity): Double {
        val t = man.npcRank.ordinal / (NpcRank.entries.size - 1).toDouble()
        return MIN_DETECTION + t * (MAX_DETECTION - MIN_DETECTION)
    }

    private companion object {
        const val MAX_LOS_CHECKS = 8
        const val MIN_DETECTION = 80.0

        // An elite mortarman's sight in the brightest light: 128 x 1.5 (SpottingTime, 21.10).
        const val MAX_DETECTION = 192.0
        const val SHORT_ID = 8
    }
}
