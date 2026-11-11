package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.planning.ActionEvidence
import com.sbwnpc.squad.ai.planning.ActionExecution
import com.sbwnpc.squad.ai.planning.ActionUpdate
import com.sbwnpc.squad.ai.planning.FactSnapshot
import com.sbwnpc.squad.ai.tactics.SelfCareFacts
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.MandatoryRule
import com.sbwnpc.squad.entity.ai.MandatoryRules
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.server.level.ServerLevel
import org.joml.Vector3f
import java.util.UUID

/**
 * A man's own dressing (#189): whether it is due, observed for the plan as [SelfCareFacts.DUE], and
 * the dressing the plan chose. The gates are the old self-treat behaviour's.
 *
 * Being in cover, his hole or at a patient is latched: choosing the dressing stops the cover or
 * treatment executor, which drops the claim the gate reads, and without the latch the fact would
 * flip back before the dressing ran and the plan would return to cover and choose it again, forever.
 * A dressing done waits [COOLDOWN_TICKS] before the next, as the old behaviour's start check did, so
 * a kit that leaves him below the threshold does not drain the rest in a row.
 */
internal class SelfTreatCare(private val level: ServerLevel) {
    private val latched = HashSet<UUID>()
    private val nextAt = HashMap<UUID, Long>()

    /** [npc]'s own care fact at [now]. */
    fun observe(facts: FactSnapshot, npc: NpcEntity, now: Long): FactSnapshot {
        val wounded = mayTreat(npc) && now >= (nextAt[npc.uuid] ?: 0L)
        val sheltered = npc.holdsAny(NpcActivity.COVER, NpcActivity.HEAL) || npc.holds(NpcActivity.DUG_IN)
        if (!wounded) {
            latched -= npc.uuid
        } else if (sheltered) {
            latched += npc.uuid
        }
        return facts.observe(SelfCareFacts.DUE, wounded && npc.uuid in latched)
    }

    /** The dressing of [npc], run in its first tick. */
    fun action(npc: NpcEntity): ActionExecution = Dressing(npc)

    fun keep(alive: (UUID) -> Boolean) {
        latched.removeIf { !alive(it) }
        nextAt.keys.removeIf { !alive(it) }
    }

    fun clear() {
        latched.clear()
        nextAt.clear()
    }

    private fun mayTreat(npc: NpcEntity) = npc.medkitsLeft > 0 && !npc.isPassenger && MandatoryRules.may(MandatoryRule.SELF_TREAT, npc) && npc.health < npc.maxHealth * WOUNDED_FRACTION

    private inner class Dressing(private val npc: NpcEntity) : ActionExecution {
        override fun start(now: Long): ActionUpdate {
            latched -= npc.uuid
            nextAt[npc.uuid] = now + COOLDOWN_TICKS
            // A grenade or drone that arrived since the plan looked: the next look decides again.
            if (!mayTreat(npc)) return ActionUpdate.Completed(listOf(ActionEvidence("not_needed", now, "${npc.uuid}")))
            Ports.gear.treat(npc)
            npc.medkitsLeft--
            level.sendParticles(DustParticleOptions(HEAL_COLOR, PARTICLE_SCALE), npc.x, npc.y + 1.0, npc.z, PARTICLES, PARTICLE_SPREAD, PARTICLE_RISE, PARTICLE_SPREAD, 0.0)
            DebugFlags.log(LogGroup.MEDIC, "[htn] actor={} event=self_treated class={} hp={} kitsLeft={}", npc.uuid, npc.npcClass, npc.health, npc.medkitsLeft)
            return ActionUpdate.Completed(listOf(ActionEvidence("self_treated", now, "${npc.uuid}")))
        }

        override fun tick(now: Long) = start(now)

        override fun suspend(now: Long, reason: String) = Unit

        override fun resume(now: Long) = Unit

        override fun cancel(now: Long, reason: String) = Unit
    }

    private companion object {
        const val WOUNDED_FRACTION = 0.5f
        const val COOLDOWN_TICKS = 10L
        const val PARTICLES = 12
        const val PARTICLE_SCALE = 1.5f
        const val PARTICLE_SPREAD = 0.3
        const val PARTICLE_RISE = 0.5

        /** Same colour as a medic's treatment. */
        val HEAL_COLOR = Vector3f(1.0f, 0.45f, 0.45f)
    }
}
