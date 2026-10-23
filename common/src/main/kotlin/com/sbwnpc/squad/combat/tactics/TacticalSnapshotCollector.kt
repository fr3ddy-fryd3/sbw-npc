package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.AntiArmourKit
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.GrenadeHazard
import com.sbwnpc.squad.combat.SidearmKit
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.combat.WeaponReach
import com.sbwnpc.squad.compat.VersionCompat
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.RouteManager
import com.sbwnpc.squad.squad.Squad
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** World queries and observations produce an immutable input for the decision policy. */
internal object TacticalSnapshotCollector {
    fun collect(squad: Squad, level: ServerLevel, npcs: List<NpcEntity>, reference: NpcEntity): TacticalSnapshot {
        val state = squad.tactics
        val now = level.gameTime
        val center = medianCenter(npcs.map { it.position() })
        val visibleTargetIds = observeTargets(npcs)
        state.contacts.entries.removeIf { now - it.value.seenAt > CONTACT_MEMORY_TICKS }
        val members = npcs.map { describeMember(it, visibleTargetIds) }
        state.peakStrength = maxOf(state.peakStrength, members.size)
        val knownFocus = state.contacts.values.filter { now - it.seenAt < VISIBLE_CONTACT_TICKS }.maxByOrNull { it.priority }?.position
        val terrain = TacticalTerrain.assess(level, npcs, knownFocus ?: reference.homeCenter(), knownFocus)
        val engaged = npcs.filter { it.target != null && it.npcClass != NpcClass.MEDIC && !it.busyWithRole() && !it.resupplying }
        return TacticalSnapshot(
            now = now,
            order = squad.order,
            stamp = squad.orderStamp,
            center = center,
            home = reference.homeCenter(),
            members = members,
            contacts = state.contacts.values.toList(),
            incoming = npcs.mapNotNull { it.incomingFire.recentPoint(now) },
            grenade = npcs.any { it.evadingGrenade() || GrenadeHazard.threatens(level, it.position()) },
            narrow = terrain.narrow,
            open = terrain.open,
            stalled = TacticalRules.stalled(engaged.count { hasBlockedShot(it) }, engaged.size),
            peakStrength = state.peakStrength,
            hasPatrolRoute = squad.routeId?.let { RouteManager.get(level).get(it)?.points?.isNotEmpty() } == true,
        )
    }

    private fun observeTargets(members: List<NpcEntity>): Set<UUID> {
        val visibleTargetIds = HashSet<UUID>()
        for (npc in members) {
            val target = npc.target ?: continue
            if (!target.isAlive || !npc.isEnemy(target)) continue
            if (target.uuid in visibleTargetIds || !DetectionSightline.canSee(npc, target)) continue
            visibleTargetIds += target.uuid
            SquadTactics.observe(npc, target)
        }
        return visibleTargetIds
    }

    private fun describeMember(npc: NpcEntity, visibleTargetIds: Set<UUID>): TacticalMember {
        val target = npc.target
        val gun = Ports.guns.inHand(npc)
        val ready = (gun?.hasAmmo() == true || AntiArmourKit.loaded(npc)) &&
            !npc.busyWithRole() && !npc.resupplying && !npc.combatLockedByMedic() && !npc.evadingGrenade()
        val canFire = ready && !npc.combatLockedByCover() && (npc.readyToCover || npc.firedRecently(RECENT_FIRE_TICKS)) &&
            target != null && target.uuid in visibleTargetIds && WeaponReach.inRange(npc, npc.eyePosition, target.eyePosition) &&
            DetectionSightline.canSee(npc, target) && hasFiringSight(npc, target)
        return TacticalMember(
            id = npc.uuid,
            position = npc.position(),
            role = npc.npcClass,
            health = (npc.health / npc.maxHealth).toDouble(),
            ready = ready,
            canFire = canFire,
            suppressed = npc.isSuppressed(),
            rockets = AntiArmourKit.loaded(npc),
            firingAt = if (npc.readyToCover && target?.uuid in visibleTargetIds) target?.position() else npc.lastFireAt,
            recentFire = npc.firedRecently(RECENT_FIRE_TICKS),
            recentFireAt = npc.lastFireAt,
            moving = !npc.navigation.isDone || npc.deltaMovement.horizontalDistanceSqr() > 0.0025,
            grounded = npc.onGround() || VersionCompat.isInLiquid(npc),
            primaryAmmo = SidearmKit.hasPrimaryAmmo(npc),
            sidearmOnly = npc.usingSidearm,
        )
    }

    private fun hasBlockedShot(npc: NpcEntity): Boolean = !npc.firedRecently(RECENT_FIRE_TICKS) && npc.blockedSightSince?.let { npc.tickCount - it >= BLOCKED_SIGHT_TICKS } == true

    private fun hasFiringSight(npc: NpcEntity, target: net.minecraft.world.entity.LivingEntity): Boolean =
        if (npc.distanceToSqr(target) > 128.0 * 128.0) {
            !Sightline.blocked(npc.level() as ServerLevel, npc.eyePosition, target.eyePosition, npc)
        } else {
            npc.sensing.hasLineOfSight(target)
        }

    // Coordinate-wise upper median keeps distant stragglers from pulling the squad center away.
    private fun medianCenter(points: List<Vec3>): Vec3 {
        val middle = points.size / 2
        return Vec3(points.map { it.x }.sorted()[middle], points.map { it.y }.sorted()[middle], points.map { it.z }.sorted()[middle])
    }

    private const val CONTACT_MEMORY_TICKS = 200
    private const val VISIBLE_CONTACT_TICKS = 40
    private const val RECENT_FIRE_TICKS = 40
    private const val BLOCKED_SIGHT_TICKS = 80
}
