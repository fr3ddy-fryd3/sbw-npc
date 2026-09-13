package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.item.misc.MedicalKitItem
import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModMemories
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.AABB
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import net.tslat.smartbrainlib.util.BrainUtils
import java.util.UUID

/**
 * Phase 5.8 — a MEDIC's shoot-vs-heal decision. Placed in `NpcEntity.getCoreTasks()` (like
 * `SeekCoverBehaviour`/`MortarOperatorBehaviour`) so it can act whether or not the medic currently
 * has a combat target of its own — a real Fight-activity behaviour would only ever run alongside
 * `ATTACK_TARGET`, which would rule out the "no target, heal opportunistically" case entirely.
 *
 * Per explicit user decision (confirmed during planning): a medic heals a CRITICALLY wounded
 * squadmate (health below [CRITICAL_HEALTH_FRACTION]) even while it has a live target and is
 * actively engaged — for that case it locks [GunAttackBehaviour] out via
 * [ModMemories.MEDIC_HEALING] (same "hands off, I own the mob right now" idiom as
 * `SeekCoverBehaviour`'s `COVER_HOLD`) for as long as treatment takes. A merely wounded ally (below
 * [NEEDS_HEAL_FRACTION] but not critical) is only tended opportunistically, when the medic itself
 * has no target — `GunAttackBehaviour` is already inactive without one, so no lock is needed there
 * (matches this codebase's existing level of rigor: `SeekCoverBehaviour` itself doesn't lock out
 * Idle behaviours either, only the Fight one).
 *
 * The actual heal reuses SuperbWarfare's real `MedicalKitItem.treat()` (heal amount/percentage +
 * Regeneration, from that mod's own `MiscConfig`) instead of reinventing the healing math — called
 * directly on the ally via the already-registered [ModItems.MEDICAL_KIT] instance, with no item
 * ever placed in the medic's own hand (same "flag/direct call, not a visible item" precedent as
 * [NpcEntity.hasReserveGrenade] — a medic's hands are already full with its SMG).
 */
class MedicHealBehaviour : ExtendedBehaviour<NpcEntity>() {

    // ExtendedBehaviour defaults to a 60-tick runtime cap (confirmed via javap — see
    // SeekCoverBehaviour's doc comment for the full story); approaching/treating a critical ally must
    // not get force-interrupted and re-evaluated every 3 seconds regardless of progress.
    init {
        noTimeout()
    }

    private var healTargetId: UUID? = null
    private var nextTreatTick = 0

    companion object {
        private const val SCAN_RADIUS = 16.0
        private const val CRITICAL_HEALTH_FRACTION = 0.3f
        private const val NEEDS_HEAL_FRACTION = 0.7f
        private const val HEAL_RANGE = 2.5
        private const val TREAT_COOLDOWN_TICKS = 100 // ~5s — don't immediately re-treat while Regeneration is still ticking

        // Light red — debug visual for a treat() call, see markHeal().
        private val HEAL_COLOR = org.joml.Vector3f(1.0f, 0.45f, 0.45f)
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    private fun needsHealing(entity: NpcEntity, ally: NpcEntity): Boolean {
        val fraction = ally.health / ally.maxHealth
        return fraction < CRITICAL_HEALTH_FRACTION || (entity.target == null && fraction < NEEDS_HEAL_FRACTION)
    }

    /** Most-wounded eligible squadmate within range — same scan pattern as
     *  `SeekCoverBehaviour.hasCoveringAlly`. Deliberately re-run each time (not just checked against
     *  the sticky [healTargetId]) so eligibility always reflects the CURRENT most urgent case. */
    private fun candidate(entity: NpcEntity): NpcEntity? {
        if (entity.npcClass != NpcClass.MEDIC) return null
        val squad = entity.currentSquad() ?: return null
        val level = entity.level() as? ServerLevel ?: return null
        val box = AABB.ofSize(entity.position(), SCAN_RADIUS * 2, SCAN_RADIUS * 2, SCAN_RADIUS * 2)
        return level.getEntitiesOfClass(NpcEntity::class.java, box)
            .filter { ally -> ally !== entity && ally.isAlive && squad.members.contains(ally.uuid) && needsHealing(entity, ally) }
            .minByOrNull { it.health / it.maxHealth }
    }

    private fun eligible(entity: NpcEntity): Boolean = candidate(entity) != null

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean = eligible(entity)
    override fun shouldKeepRunning(entity: NpcEntity): Boolean = eligible(entity)

    override fun start(entity: NpcEntity) {
        healTargetId = candidate(entity)?.uuid
    }

    override fun stop(entity: NpcEntity) {
        healTargetId = null
        entity.navigation.stop()
        BrainUtils.clearMemory(entity, ModMemories.MEDIC_HEALING.get())
    }

    override fun tick(entity: NpcEntity) {
        val level = entity.level() as? ServerLevel ?: return
        // Keep tending the same ally as long as it still genuinely needs it — re-picking a fresh
        // candidate every tick (even one that's merely alive but already healed up) would abandon a
        // near-finished treatment for whichever squadmate happens to sort first.
        val sticky = healTargetId?.let { level.getEntity(it) as? NpcEntity }?.takeIf { it.isAlive && needsHealing(entity, it) }
        val ally = sticky ?: candidate(entity)?.also { healTargetId = it.uuid } ?: return

        if (ally.health / ally.maxHealth < CRITICAL_HEALTH_FRACTION) {
            BrainUtils.setMemory(entity, ModMemories.MEDIC_HEALING.get(), true)
        } else {
            BrainUtils.clearMemory(entity, ModMemories.MEDIC_HEALING.get())
        }

        val dist = entity.position().distanceTo(ally.position())
        if (dist > HEAL_RANGE) {
            entity.navigation.moveTo(ally.x, ally.y, ally.z, 1.0)
            return
        }
        entity.navigation.stop()
        entity.lookAt(ally, 30f, 30f)

        if (entity.tickCount < nextTreatTick) return
        nextTreatTick = entity.tickCount + TREAT_COOLDOWN_TICKS
        (ModItems.MEDICAL_KIT.get() as MedicalKitItem).treat(ally)
        markHeal(level, ally)
    }

    /** Debug visual, per user request — same idiom as SeekCoverBehaviour.markCoverChoice: a short
     *  particle burst at the treated ally, so healing is visible without watching health numbers. */
    private fun markHeal(level: ServerLevel, ally: NpcEntity) {
        level.sendParticles(
            net.minecraft.core.particles.DustParticleOptions(HEAL_COLOR, 1.5f),
            ally.x, ally.y + 1.0, ally.z, 12, 0.3, 0.5, 0.3, 0.0
        )
    }
}
