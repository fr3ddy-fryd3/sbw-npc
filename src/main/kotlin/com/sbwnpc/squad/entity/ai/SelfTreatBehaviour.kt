package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/**
 * In the Core tasks: a wounded NPC uses its own medical kit ([NpcEntity.medkitsLeft]) — only once
 * it is in cover, ducked or dug in (see [SeekCoverBehaviour]), never out in the open. Takes a couple
 * of seconds; leaving cover before then puts it off, the kit unspent.
 */
class SelfTreatBehaviour : ExtendedBehaviour<NpcEntity>() {

    init {
        noTimeout()
    }

    private var doneTick = 0

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    private fun inCover(entity: NpcEntity) = entity.combatLockedByCover() || entity.diggedIn

    private fun eligible(entity: NpcEntity): Boolean =
        entity.medkitsLeft > 0 && !entity.isPassenger && inCover(entity) &&
            entity.health < entity.maxHealth * WOUNDED_FRACTION

    private val startCheck = StartCheckThrottle(START_CHECK_INTERVAL_TICKS)

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean =
        startCheck.check(entity) { eligible(entity) }

    override fun shouldKeepRunning(entity: NpcEntity): Boolean =
        entity.medkitsLeft > 0 && inCover(entity) && entity.tickCount < doneTick

    override fun start(entity: NpcEntity) {
        doneTick = entity.tickCount + TREAT_TICKS
    }

    override fun stop(entity: NpcEntity) {
        if (entity.tickCount < doneTick || entity.medkitsLeft <= 0 || !entity.isAlive) return
        Ports.gear.treat(entity)
        entity.medkitsLeft--
        (entity.level() as? ServerLevel)?.sendParticles(
            net.minecraft.core.particles.DustParticleOptions(HEAL_COLOR, 1.5f),
            entity.x, entity.y + 1.0, entity.z, 12, 0.3, 0.5, 0.3, 0.0
        )
        DebugFlags.log("[medkit-debug] {} ({}) treated itself in cover, {} hp", entity.uuid, entity.npcClass, entity.health)
    }

    private companion object {
        const val WOUNDED_FRACTION = 0.5f
        const val TREAT_TICKS = 40
        const val START_CHECK_INTERVAL_TICKS = 10
        /** Same colour as a medic's treatment. */
        val HEAL_COLOR = org.joml.Vector3f(1.0f, 0.45f, 0.45f)
    }
}
