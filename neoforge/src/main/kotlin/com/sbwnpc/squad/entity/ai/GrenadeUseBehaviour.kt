package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.GrenadeThrower
import com.sbwnpc.squad.combat.VehicleTargeting
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import java.util.UUID

/**
 * The one reserve grenade every fighter carries, and the two situations it is for.
 *
 * It used to have exactly one outlet: a 20% roll taken *after* digging in, and digging in itself
 * needs the NPC to be badly hurt with a squadmate covering. The combination almost never came up,
 * so in practice the grenades were never thrown at all — which is what prompted this.
 *
 * Two rules now, both of them things a grenade is actually for:
 *  - **Flushing.** The target is close, its position is known, and it has been behind something
 *    for long enough that shooting at it is going nowhere.
 *  - **Breaking suppression.** The NPC is pinned by fire it cannot answer, and knows where from.
 *  - **A bunch.** Several enemies close together within throwing range, at least one of them in
 *    sight — one grenade does more there than a magazine.
 *
 * Throws are rationed per squad, not per NPC: without that, a squad that walks into an ambush
 * answers with eight grenades in the same second.
 */
class GrenadeUseBehaviour : ExtendedBehaviour<NpcEntity>() {

    private var aimPoint: Vec3? = null
    private var kind: com.sbwnpc.squad.domain.port.GrenadeKind? = null

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean {
        // The grenadier throws his by his own rules (GrenadeThrowBehaviour), from the same count.
        if (entity.npcClass == NpcClass.GRENADIER) return false
        if (!entity.hasGrenade) return false
        if (entity.busyWithRole()) return false
        if (entity.tickCount < nextThrowTick) return false
        if (!squadMayThrow(entity)) return false

        val point = clusterPoint(entity, level) ?: flushPoint(entity) ?: suppressionPoint(entity) ?: return false
        if (!inRange(entity, point)) return false
        val grenade = GrenadeThrower.pick(entity, level, point) ?: return false
        aimPoint = point
        kind = grenade
        return true
    }

    override fun shouldKeepRunning(entity: NpcEntity): Boolean = false

    override fun start(entity: NpcEntity) {
        val level = entity.level() as? ServerLevel ?: return
        val point = aimPoint ?: return
        val grenade = kind ?: return
        aimPoint = null
        kind = null
        if (!GrenadeThrower.throwAt(entity, level, point, grenade)) return
        nextThrowTick = entity.tickCount + SELF_COOLDOWN_TICKS
        entity.currentSquad()?.let { lastSquadThrow[it.id] = level.gameTime }
    }

    /**
     * A target that is known but not shootable. [NpcEntity.blockedSightSince] is stamped by
     * `GunAttackBehaviour` every tick it has a target it cannot see, so this asks the shooter what
     * it already knows rather than raycasting again.
     */
    private fun flushPoint(entity: NpcEntity): Vec3? {
        val target = entity.target ?: return null
        if (!target.isAlive) return null
        // Out of sight up in a helicopter is not something a grenade flushes out.
        if (VehicleTargeting.isAircrew(target)) return null
        val since = entity.blockedSightSince ?: return null
        if (entity.tickCount - since < BLOCKED_TICKS) return null
        return target.position()
    }

    /**
     * The middle of [CLUSTER_SIZE] or more enemies within [CLUSTER_RADIUS] of one another, one of
     * them in sight and within throwing range — the biggest such bunch. Looked for once every
     * [CLUSTER_SCAN_TICKS]: it is an area query and some line-of-sight checks, per NPC.
     */
    private fun clusterPoint(entity: NpcEntity, level: ServerLevel): Vec3? {
        if (entity.tickCount < nextClusterScanTick) return null
        nextClusterScanTick = entity.tickCount + CLUSTER_SCAN_TICKS
        val reach = GrenadeThrower.MAX_RANGE + CLUSTER_RADIUS
        val enemies = level.getEntitiesOfClass(
            net.minecraft.world.entity.LivingEntity::class.java, entity.boundingBox.inflate(reach, 6.0, reach)
        ) { it.isAlive && it !== entity && entity.isEnemy(it) && !VehicleTargeting.isAircrew(it) }
        if (enemies.size < CLUSTER_SIZE) return null
        val radiusSqr = CLUSTER_RADIUS * CLUSTER_RADIUS
        var best: List<net.minecraft.world.entity.LivingEntity>? = null
        for (centre in enemies) {
            val bunch = enemies.filter { it.distanceToSqr(centre) <= radiusSqr }
            if (bunch.size < CLUSTER_SIZE || bunch.size <= (best?.size ?: 0)) continue
            val middle = Vec3(bunch.sumOf { it.x } / bunch.size, bunch.sumOf { it.y } / bunch.size, bunch.sumOf { it.z } / bunch.size)
            if (!inRange(entity, middle)) continue
            // Seen, not merely known of: one in the bunch has to be in view.
            if (bunch.none { com.sbwnpc.squad.combat.DetectionSightline.canSee(entity, it) }) continue
            best = bunch
        }
        val bunch = best ?: return null
        return Vec3(bunch.sumOf { it.x } / bunch.size, bunch.sumOf { it.y } / bunch.size, bunch.sumOf { it.z } / bunch.size)
    }

    private var nextClusterScanTick = 0

    /** Pinned down, and the memory of where the fire is coming from is still live. */
    private fun suppressionPoint(entity: NpcEntity): Vec3? {
        if (!entity.isSuppressed()) return null
        // Pinned down from the air: the fire comes from somewhere a grenade can't go.
        val from = entity.threatPos ?: return null
        if (from.y - entity.y > AIR_THREAT_HEIGHT) return null
        entity.target?.takeIf { VehicleTargeting.isAircrew(it) }?.let { return null }
        return from
    }

    private fun inRange(entity: NpcEntity, point: Vec3): Boolean {
        val dist = entity.position().distanceTo(point)
        return dist in GrenadeThrower.MIN_RANGE..GrenadeThrower.MAX_RANGE
    }

    /** One grenade per squad per [SQUAD_COOLDOWN_TICKS], so an ambush draws an answer, not a volley. */
    private fun squadMayThrow(entity: NpcEntity): Boolean {
        val squad = entity.currentSquad() ?: return true
        val level = entity.level() as? ServerLevel ?: return false
        val last = lastSquadThrow[squad.id] ?: return true
        return level.gameTime - last >= SQUAD_COOLDOWN_TICKS
    }

    private var nextThrowTick = 0

    companion object {
        /** How long a target has to stay out of sight before it counts as dug in rather than
         *  momentarily behind a tree. */
        private const val BLOCKED_TICKS = 60
        private const val SELF_COOLDOWN_TICKS = 200
        /** Enemies that make a bunch worth a grenade, and how close together they have to be. */
        private const val CLUSTER_SIZE = 3
        private const val CLUSTER_RADIUS = 3.5
        private const val CLUSTER_SCAN_TICKS = 20
        private const val SQUAD_COOLDOWN_TICKS = 120L
        /** Fire from this far overhead is coming from the air. */
        private const val AIR_THREAT_HEIGHT = 8.0

        /** Keyed by squad, not by NPC — see [squadMayThrow]. Game time, so it is cleared with the
         *  server: kept into another world with an earlier clock, every squad would have waited
         *  out the difference before throwing again. */
        private val lastSquadThrow = HashMap<UUID, Long>()

        fun clearAll() = lastSquadThrow.clear()
    }
}
