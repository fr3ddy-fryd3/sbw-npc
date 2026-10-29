package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.FiringCone
import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.TickBudget
import com.sbwnpc.squad.combat.WeaponReach
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import java.nio.file.Files
import java.nio.file.Path

/** Samples everyone, including actors that never enter GunAttackBehaviour.tickShooting. */
internal class CombatParticipationTrace(private val level: ServerLevel, path: Path) : AutoCloseable {
    private val writer = Files.newBufferedWriter(path)
    private var sampleNanos = 0L
    private var samples = 0

    init {
        writer.appendLine("tick,npc,squad,order,role,alive,x,y,z,target,distance,activity,job,plan,phase,primaryRounds,handRounds,sidearm,lastShotAge,pathDone,grounded,pose,postX,postY,postZ,conditions,rayBlock,rayX,rayY,rayZ,searchId,probeIndex,probes,scored,nextSearch,rayBudget,pathAttempts,routeNode,routeAge,allyBlocker,allyDistance,allyForward,allySameSquad,coveredCandidates,ownSquadBlockers,otherSquadBlockers,aimTicks,aimRequired,postTerrainClear")
    }

    fun sample(tick: Long, npc: NpcEntity) {
        val started = System.nanoTime()
        val squad = npc.currentSquad()
        val task = com.sbwnpc.squad.ai.integration.NativeTactics.assignment(npc)
        val target = npc.target
        val rayBudget = TickBudget.hasRaycasts(level)
        val gun = Ports.guns.inHand(npc)
        val conditions = mutableListOf<String>()
        if (!npc.isAlive) conditions += "dead"
        if (npc.busyWithRole()) conditions += "role_lock"
        if (npc.holds(NpcActivity.RESUPPLY)) conditions += "resupply"
        if (npc.holdsAny(NpcActivity.COVER, NpcActivity.EVADE)) conditions += "cover_lock"
        if (npc.holds(NpcActivity.HEAL)) conditions += "medic_lock"
        if (gun == null) {
            conditions += "no_gun"
        } else if (!gun.hasAmmo()) {
            conditions += "no_hand_ammo"
        }
        var hit: net.minecraft.world.phys.BlockHitResult? = null
        var ally: NpcEntity? = null
        var ownSquadBlockers = 0
        var otherSquadBlockers = 0
        if (target == null) {
            conditions += "no_target"
        } else {
            if (!target.isAlive) conditions += "dead_target"
            if (!WeaponReach.inRange(npc, npc.eyePosition, target.eyePosition)) conditions += "out_of_range"
            val visibility = DetectionSightline.trace(npc, target)
            if (visibility != DetectionSightline.Visibility.VISIBLE) conditions += "detection_${visibility.name.lowercase()}"
            // Diagnostic collider queries do not consume the AI search budget; samples are staggered.
            hit = level.clip(ClipContext(npc.eyePosition, target.eyePosition, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc))
            if (hit.type == HitResult.Type.BLOCK) conditions += "terrain"
            if (!FriendlyFireGuard.hasClearLineOfFire(npc, target.eyePosition, npc.spread)) {
                conditions += "friendly_lane"
                val cone = FiringCone(npc.eyePosition, target.eyePosition, npc.spread)
                val flight = kotlin.math.ceil(npc.eyePosition.distanceTo(target.eyePosition) / (gun?.muzzleVelocity?.coerceAtLeast(1.0) ?: 20.0)) + 1
                val blockers = NpcRegistry.all(level).filter {
                    it !== npc && !SquadTeams.isHostile(npc, it) && Ports.guns.canHitEntity(npc, it) &&
                        cone.intersects(Ports.guns.firingSafetyBounds(it, flight))
                }
                ally = blockers.minByOrNull { it.distanceToSqr(npc) }
                ownSquadBlockers = blockers.count { npc.squadId != null && it.squadId == npc.squadId }
                otherSquadBlockers = blockers.size - ownSquadBlockers
            }
            if (!SquadTactics.permitsFire(npc, target)) conditions += "tactical_fire_permission"
            if (gun?.canShoot() == false) conditions += "weapon_cycle"
        }
        val post = task?.position
        val blockHit = hit?.takeIf { it.type == HitResult.Type.BLOCK }
        val fields = listOf(
            tick, npc.uuid, squad?.name, squad?.order, npc.npcClass, npc.isAlive,
            npc.x, npc.y, npc.z, target?.uuid, target?.let { npc.distanceTo(it) },
            npc.brain.activeNonCoreActivity.orElse(null), task?.job, "htn", npc.let(com.sbwnpc.squad.ai.integration.NativeTactics::attackPhase),
            Ports.guns.roundsLeft(npc.primaryGun()), Ports.guns.roundsLeft(npc.mainHandItem), npc.usingSidearm,
            if (npc.lastShotTick >= 0) npc.tickCount - npc.lastShotTick else -1,
            npc.navigation.isDone, npc.onGround(), npc.pose, post?.x, post?.y, post?.z,
            conditions.joinToString(";"), blockHit?.let { BuiltInRegistries.BLOCK.getKey(level.getBlockState(it.blockPos).block) },
            blockHit?.blockPos?.x, blockHit?.blockPos?.y, blockHit?.blockPos?.z,
            null, null, null, null, null, rayBudget, null, null, null,
            ally?.uuid, ally?.distanceTo(npc), ally?.position()?.subtract(npc.position())?.dot(target!!.position().subtract(npc.position()).normalize()),
            ally?.let { it.squadId == npc.squadId }, null,
            ownSquadBlockers, otherSquadBlockers, npc.debugAimTicks, npc.npcRank.aimTimeTicks,
            if (post != null && target != null) level.clip(ClipContext(post.add(0.0, npc.eyeHeight.toDouble(), 0.0), target.eyePosition, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc)).type == HitResult.Type.MISS else null,
        )
        writer.appendLine(fields.joinToString(",") { it?.toString() ?: "" })
        sampleNanos += System.nanoTime() - started
        samples++
    }

    fun flush() = writer.flush()
    fun averageMillis() = if (samples == 0) 0.0 else sampleNanos / samples / 1_000_000.0
    override fun close() = writer.close()
}
