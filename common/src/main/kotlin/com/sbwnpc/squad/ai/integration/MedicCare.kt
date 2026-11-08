package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.ai.planning.ActionEvidence
import com.sbwnpc.squad.ai.planning.ActionExecution
import com.sbwnpc.squad.ai.planning.ActionUpdate
import com.sbwnpc.squad.ai.planning.FactSnapshot
import com.sbwnpc.squad.ai.tactics.MedicalCare
import com.sbwnpc.squad.ai.tactics.MedicalFacts
import com.sbwnpc.squad.ai.tactics.Wounded
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.compat.VersionCompat
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.entity.ai.MandatoryRule
import com.sbwnpc.squad.entity.ai.MandatoryRules
import com.sbwnpc.squad.experience.ExperienceAwards
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.team.Diplomacy
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import org.joml.Vector3f
import java.util.UUID

/**
 * The medics of a level (#189): who each treats, observed for the plan as [MedicalFacts.PATIENT]
 * by [MedicalCare.pick], and the treatment the plan chose, walked and dressed here as
 * the old medic behaviour did before.
 *
 * A patient is reserved by the medic who has it across squads, so two medics never walk to one man;
 * a reservation not refreshed for [RESERVATION_TICKS] (its medic dead, unloaded or replanned away)
 * is free again.
 */
internal class MedicCare(private val level: ServerLevel) {
    private val hands = HashMap<UUID, Hand>()
    private val reservations = HashMap<UUID, Reservation>()

    /** [npc]'s care facts at [now]: the patient he has, if he is a medic free to treat. */
    fun observe(facts: FactSnapshot, npc: NpcEntity, now: Long): FactSnapshot {
        if (npc.npcClass != NpcClass.MEDIC) return facts
        val hand = hands.getOrPut(npc.uuid) { Hand() }
        if (now >= hand.nextScan) {
            hand.nextScan = now + SCAN_TICKS
            hand.unreachable.entries.removeIf { it.value <= now }
            val pick = if (vetoed(npc)) null else MedicalCare.pick(hand.patient, wounded(npc, hand, now), npc.target != null) { taken(it, npc.uuid, now) }
            if (pick != hand.patient) {
                hand.patient?.let(::release)
                DebugFlags.log(LogGroup.MEDIC, "[htn] actor={} event=patient_allocated from={} to={}", npc.uuid, hand.patient, pick)
            }
            hand.patient = pick
        }
        val patient = hand.patient ?: return facts
        reservations[patient] = Reservation(npc.uuid, now)
        return facts.observe(MedicalFacts.PATIENT, patient)
    }

    /** The treatment [npc]'s plan chose for [patient]. */
    fun action(npc: NpcEntity, patient: UUID): ActionExecution = Treat(npc, patient)

    /** Keeps only the men [planned] still holds an agent for. */
    fun keep(planned: (UUID) -> Boolean) {
        hands.keys.removeIf { !planned(it) }
    }

    fun clear() {
        hands.clear()
        reservations.clear()
    }

    private fun release(patient: UUID) {
        reservations.remove(patient)
    }

    private fun taken(patient: UUID, medic: UUID, now: Long): Boolean {
        val held = reservations[patient] ?: return false
        return held.medic != medic && now - held.at <= RESERVATION_TICKS && (level.getEntity(held.medic) as? LivingEntity)?.isAlive == true
    }

    // A medic in cover, peeking, dug in or riding keeps to that, as before.
    private fun vetoed(npc: NpcEntity) = npc.holdsAny(NpcActivity.COVER, NpcActivity.PEEK, NpcActivity.DUG_IN, NpcActivity.VEHICLE)

    /** The wounded of [npc]'s side within [SCAN_RADIUS], NPCs of any squad and players, as before. */
    private fun wounded(npc: NpcEntity, hand: Hand, now: Long): List<Wounded> {
        val faction = SquadTeams.factionOf(npc) ?: return emptyList()
        val result = ArrayList<Wounded>()
        fun consider(ally: LivingEntity) {
            if (!ally.isAlive || ally.uuid in hand.unreachable) return
            val fraction = ally.health / ally.maxHealth
            if (fraction < MedicalCare.WOUNDED) result += Wounded(ally.uuid, fraction, npc.distanceTo(ally).toDouble())
        }
        NpcRegistry.forEachWithin(level, npc.position(), SCAN_RADIUS, exclude = npc) { ally ->
            val side = SquadTeams.factionOf(ally)
            if (side != null && Diplomacy.allied(side, faction)) consider(ally)
        }
        // Creative players cannot be hurt and have no side; spectators neither.
        level.players().filter { !it.isSpectator && it.distanceToSqr(npc) <= SCAN_RADIUS * SCAN_RADIUS && SquadTeams.sideOf(it)?.let { side -> Diplomacy.allied(side, faction) } == true }.forEach(::consider)
        if (DebugFlags.on(LogGroup.MEDIC)) DebugFlags.log(LogGroup.MEDIC, "[htn] actor={} event=patient_scan now={} wounded={} fighting={}", npc.uuid, now, result.size, npc.target != null)
        return result
    }

    /**
     * One medic's treatment of one patient: the run to him, a dressing every [TREAT_COOLDOWN_TICKS]
     * while he still needs it. A higher mandatory rule pauses it, keeping the patient (#257). The
     * HEAL claim is held throughout, so his gun keeps silent and the other readers see him busy; as a
     * [PlanExecutor] it does not suspend the plan it carries out.
     */
    private inner class Treat(private val npc: NpcEntity, private val patient: UUID) :
        ActionExecution,
        PlanExecutor {
        private var closest = Double.MAX_VALUE
        private var progressAt = 0L
        private var nextRepath = 0L
        private var nextDressing = 0L

        override fun start(now: Long): ActionUpdate {
            progressAt = now
            npc.claim(NpcActivity.HEAL, this)
            DebugFlags.log(LogGroup.MEDIC, "[htn] actor={} event=treatment_started patient={}", npc.uuid, patient)
            return tick(now)
        }

        override fun tick(now: Long): ActionUpdate {
            val ally = (level.getEntity(patient) as? LivingEntity)?.takeIf { it.isAlive && npc.distanceToSqr(it) <= SCAN_RADIUS * SCAN_RADIUS }
                ?: return end(ActionUpdate.Failed("patient_gone"))
            if (!MedicalCare.needs(ally.health / ally.maxHealth, npc.target != null)) return end(ActionUpdate.Completed(listOf(ActionEvidence("patient_treated", now, "$patient"))))
            if (vetoed(npc)) return end(ActionUpdate.Failed("medic_held"))
            if (!MandatoryRules.may(MandatoryRule.MEDIC_HELP, npc)) return ActionUpdate.Running
            npc.claim(NpcActivity.HEAL, this)
            val distance = npc.position().distanceTo(ally.position())
            return if (distance > HEAL_RANGE) approach(ally, distance, now) else dress(ally, now)
        }

        private fun approach(ally: LivingEntity, distance: Double, now: Long): ActionUpdate {
            if (distance < closest - PROGRESS_STEP) {
                closest = distance
                progressAt = now
            }
            if (now - progressAt >= STUCK_TICKS) {
                hands[npc.uuid]?.let {
                    it.unreachable[patient] = now + STUCK_TICKS
                    it.patient = null
                    it.nextScan = now
                }
                DebugFlags.log(LogGroup.MEDIC, "[htn] actor={} event=patient_unreachable patient={} distance={}", npc.uuid, patient, distance)
                return end(ActionUpdate.Failed("patient_unreachable"))
            }
            sprint(true)
            if (now >= nextRepath) {
                nextRepath = now + REPATH_TICKS
                npc.navigation.moveTo(ally.x, ally.y, ally.z, 1.0)
            }
            return ActionUpdate.Running
        }

        private fun dress(ally: LivingEntity, now: Long): ActionUpdate {
            sprint(false)
            npc.navigation.stop()
            npc.lookAt(ally, LOOK_DEGREES, LOOK_DEGREES)
            if (now < nextDressing) return ActionUpdate.Running
            nextDressing = now + TREAT_COOLDOWN_TICKS
            val before = ally.health
            val injury = ExperienceAwards.wounds(ally)
            Ports.gear.treat(ally)
            ExperienceAwards.treated(npc, ally, before, injury)
            DebugFlags.log(LogGroup.MEDIC, "[htn] actor={} event=patient_dressed patient={} hpBefore={} hpAfter={} maxHp={}", npc.uuid, patient, before, ally.health, ally.maxHealth)
            level.sendParticles(DustParticleOptions(HEAL_COLOR, HEAL_PARTICLE_SCALE), ally.x, ally.y + 1.0, ally.z, HEAL_PARTICLES, PARTICLE_SPREAD, PARTICLE_RISE, PARTICLE_SPREAD, 0.0)
            return ActionUpdate.Running
        }

        private fun end(update: ActionUpdate): ActionUpdate {
            stop()
            if (hands[npc.uuid]?.patient == patient) hands[npc.uuid]?.let { it.nextScan = 0L }
            return update
        }

        private fun stop() {
            sprint(false)
            npc.navigation.stop()
            npc.release(NpcActivity.HEAL, this)
        }

        /** A medic hustles to a patient faster than his usual pace ([NpcClass.speedMultiplier] 1.3). */
        private fun sprint(on: Boolean) {
            val speed = npc.getAttribute(Attributes.MOVEMENT_SPEED) ?: return
            if (on) VersionCompat.setMultipliedModifier(speed, SPRINT_MODIFIER_ID, SPRINT_SPEED / npc.npcClass.speedMultiplier - 1.0) else VersionCompat.removeModifier(speed, SPRINT_MODIFIER_ID)
        }

        override fun suspend(now: Long, reason: String) = stop()

        override fun resume(now: Long) = Unit

        override fun cancel(now: Long, reason: String) = stop()
    }

    /** A medic's allocation state: his patient, the next scan, and patients he could not reach lately. */
    private class Hand {
        var patient: UUID? = null
        var nextScan = Long.MIN_VALUE
        val unreachable = HashMap<UUID, Long>()
    }

    private data class Reservation(val medic: UUID, val at: Long)

    private companion object {
        const val SCAN_RADIUS = 64.0
        const val SCAN_TICKS = 10L
        const val RESERVATION_TICKS = 40L
        const val HEAL_RANGE = 2.5
        const val REPATH_TICKS = 10L
        const val STUCK_TICKS = 100L
        const val PROGRESS_STEP = 0.5

        /** About 5 s: the Regeneration of the last dressing is still ticking. */
        const val TREAT_COOLDOWN_TICKS = 100L
        const val SPRINT_SPEED = 1.6
        const val LOOK_DEGREES = 30f
        const val HEAL_PARTICLE_SCALE = 1.5f
        const val HEAL_PARTICLES = 12
        const val PARTICLE_SPREAD = 0.3
        const val PARTICLE_RISE = 0.5
        val SPRINT_MODIFIER_ID = ModIdentity.loc("medic_sprint")

        // Light red: the player-facing sign of a dressing.
        val HEAL_COLOR = Vector3f(1.0f, 0.45f, 0.45f)
    }
}
