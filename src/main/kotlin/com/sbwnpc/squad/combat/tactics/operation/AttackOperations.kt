package com.sbwnpc.squad.combat.tactics.operation

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.SidearmKit
import com.sbwnpc.squad.combat.tactics.TacticalMember
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.WeakHashMap

/** Local cooperation on explicit ATTACK orders. No spawning, order changes or global enemy queries. */
object AttackOperations {
    private class World {
        var nextTick = Long.MIN_VALUE
        var serial = 0L
        var forces = emptyList<AttackForce>()
        val operations = mutableListOf<AttackOperation>()
        val recovery = AttackRouteRecovery()
    }
    private val worlds = WeakHashMap<ServerLevel, World>()
    private val infantry = setOf(NpcClass.RIFLEMAN, NpcClass.SHOTGUNNER, NpcClass.MACHINE_GUNNER, NpcClass.SNIPER, NpcClass.GRENADIER, NpcClass.MEDIC, NpcClass.DRONE_OPERATOR)

    fun assignment(level: ServerLevel, squad: Squad): AttackAssignment? {
        if (squad.order != SquadOrder.ATTACK) return null
        val world = worlds.getOrPut(level) { World() }
        if (level.gameTime >= world.nextTick) update(level, world)
        val operation = world.operations.firstOrNull { squad.id in it.lanes } ?: return null
        // Cached assessment must never survive a player's fresh command or faction change.
        val force = world.forces.firstOrNull { it.id == squad.id } ?: return null
        if (force.stamp != squad.orderStamp || force.faction != squad.faction) return null
        return operation.assignment(squad.id, world.forces)
    }

    fun lane(level: ServerLevel, squad: UUID): AttackLane? = worlds[level]?.operations?.firstNotNullOfOrNull { it.lanes[squad] }

    /** Useful when unloading an isolated test world; ordinary worlds are weakly held. */
    fun clear(level: ServerLevel) {
        worlds.remove(level)
    }
    fun clearAll() = worlds.clear()

    private fun update(level: ServerLevel, world: World) {
        val now = level.gameTime
        world.nextTick = now + 10
        val squads = SquadManager.get(level).all().filter { it.order == SquadOrder.ATTACK }
        world.forces = squads.mapNotNull { describe(level, it) }
        val byId = world.forces.associateBy { it.id }
        for (operation in world.operations) {
            val central = operation.lanes.values.firstOrNull { it.role == AttackRole.CENTER }?.squad?.let(byId::get)
            val compatible = world.forces.filter { it.id in operation.lanes && central != null && compatible(central, it) }
            val contacts = squads.filter { it.id in operation.lanes }.flatMap { it.tactics.contacts.values }
                .filter { contact -> now - contact.seenAt <= 200 && level.getEntity(contact.id)?.isAlive == true }
            val moved = contacts.isNotEmpty() && contacts.all { it.position.subtract(operation.focus).horizontalDistance() > 48.0 }
            when {
                contacts.isEmpty() -> operation.transition(FinishedAttack(), now, "contact_memory_expired")
                moved -> operation.transition(FinishedAttack(), now, "known_enemy_sector_moved")
                else -> operation.tick(compatible, now)
            }
        }
        world.operations.removeIf { it.state.stage == AttackStage.COMPLETED }
        world.recovery.update(level, world.operations, world.forces, squads.associateBy { it.id })
        val assigned = world.operations.flatMap { it.lanes.keys }.toMutableSet()
        for (force in world.forces.filter { it.id !in assigned }) {
            val operation = world.operations.firstOrNull { operation ->
                operation.lanes.values.firstOrNull { it.role == AttackRole.CENTER }?.squad?.let(byId::get)?.let { compatible(it, force) } == true
            } ?: continue
            val central = operation.lanes.values.first { it.role == AttackRole.CENTER }.squad.let(byId::get)
            if (central != null && force.fighters.size > central.fighters.size && operation.state.stage == AttackStage.DEPLOYING && now - operation.phaseSince < 80) {
                operation.transition(FinishedAttack(), now, "larger_force_joined_initial_deployment")
                assigned.removeAll(operation.lanes.keys)
                world.operations.remove(operation)
                continue
            }
            operation.join(force, world.forces, now)
            assigned += force.id
        }
        for (seed in world.forces.sortedWith(compareByDescending<AttackForce> { it.fighters.size }.thenBy { it.id })) {
            if (seed.id in assigned) continue
            val candidates = world.forces.filter { it.id !in assigned && compatible(seed, it) }
            if (candidates.size < 2) continue
            val candidateSquads = squads.filter { it.id in candidates.map { force -> force.id } }
            // Plans begin only with a real locally known contact, not knowledge of the entire map.
            val contacts = candidateSquads.flatMap { it.tactics.contacts.values }.filter { now - it.seenAt < 80 && level.getEntity(it.id)?.isAlive == true }
            val focus = contacts.maxWithOrNull(compareBy<com.sbwnpc.squad.combat.tactics.TacticalContact> { it.priority }.thenBy { it.seenAt })?.position ?: continue
            val operation = AttackOperation(
                ++world.serial,
                focus,
                candidates,
                now,
                events = AttackOperationEvents { event ->
                    DebugFlags.log(LogGroup.ORDER, "[operation] id={} tick={} event={} squad={} role={} reason={} point={}", event.operation, event.tick, event.action, event.squad, event.role, event.reason, event.point)
                },
            )
            world.operations += operation
            assigned += operation.lanes.keys
        }
        world.operations.forEach { operation ->
            operation.lanes.values.filter { !it.blocked && !it.deployed }.forEach { lane ->
                surfaceWaypoint(level, lane, byId[lane.squad])
            }
        }
    }

    /** Geometric sectors inherit an old altitude; route scoring needs the actual walking surface. */
    private fun surfaceWaypoint(level: ServerLevel, lane: AttackLane, force: AttackForce?) {
        val point = lane.waypoint
        val block = BlockPos.containing(point)
        if (!level.chunkSource.hasChunk(block.x shr 4, block.z shr 4)) return
        val actor = force?.fighters?.firstNotNullOfOrNull { level.getEntity(it.id) as? NpcEntity } ?: return
        val surface = Terrain.feetAt(level, point, 12) { level.noCollision(actor, actor.getDimensions(actor.pose).makeBoundingBox(it)) } ?: return
        if (kotlin.math.abs(surface.y - point.y) < 0.01) return
        lane.route[lane.next] = Vec3(point.x, surface.y, point.z)
        lane.revision++
        DebugFlags.log(LogGroup.ORDER, "[operation] squad={} event=waypoint_surface_adjusted revision={} from={} to={}", lane.squad, lane.revision, point, lane.waypoint)
    }

    private fun compatible(a: AttackForce, b: AttackForce): Boolean =
        a.faction == b.faction && a.targetId == b.targetId &&
            a.objective.subtract(b.objective).horizontalDistance() <= 48.0 && a.center.subtract(b.center).horizontalDistance() <= 256.0

    private fun describe(level: ServerLevel, squad: Squad): AttackForce? {
        val members = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive && it.vehicle == null && it.npcClass in infantry }
        if (members.isEmpty()) return null
        val center = Vec3(members.map { it.x }.sorted()[members.size / 2], members.map { it.y }.sorted()[members.size / 2], members.map { it.z }.sorted()[members.size / 2])
        val objective = squad.objective?.let(Vec3::atBottomCenterOf) ?: squad.focusEntity?.let { level.getEntity(it)?.position() } ?: return null
        val described = members.map { npc ->
            TacticalMember(
                npc.uuid,
                npc.position(),
                npc.npcClass,
                (npc.health / npc.maxHealth).toDouble(),
                ready = !npc.busyWithRole() && !npc.resupplying && !npc.combatLockedByMedic() && !npc.evadingGrenade(),
                suppressed = npc.isSuppressed(),
                primaryAmmo = SidearmKit.hasPrimaryAmmo(npc),
                sidearmOnly = npc.usingSidearm,
            )
        }
        if (described.none { it.ready && it.primaryAmmo && !it.sidearmOnly && it.role != NpcClass.MEDIC }) return null
        val known = squad.tactics.contacts.values.filter { level.gameTime - it.seenAt < 80 }
        val covering = members.count { npc ->
            npc.firedRecently(40) && npc.onGround() && !npc.resupplying && npc.lastFireAt?.let { at -> known.any { at.distanceTo(it.position) <= 18.0 } } == true
        }
        // Read the gun behaviour's existing sustained sight-loss stamp. Aiming, burst pauses,
        // healing and foliage reply fire must not be mistaken for an unusable sector.
        val eligible = described.filter { it.ready && it.health >= 0.3 && it.primaryAmmo && !it.sidearmOnly && it.role != NpcClass.MEDIC }.mapTo(HashSet()) { it.id }
        val blocked = members.count { npc ->
            npc.uuid in eligible && !npc.firedRecently(40) && npc.target?.isAlive == true &&
                npc.blockedSightSince?.let { npc.tickCount - it >= 80 } == true
        }
        // A lane can have no individual target while a neighboring squad maintains the
        // operation's contact. It still needs to approach sight; do not invent a gun target.
        val unlocated = members.count { npc -> npc.uuid in eligible && !npc.firedRecently(40) && npc.target?.isAlive != true }
        val underFire = members.any { it.isSuppressed() || it.incomingFire.recentPoint(level.gameTime) != null }
        return AttackForce(squad.id, squad.orderStamp, squad.faction, objective, center, described, covering, squad.tactics.plan?.failedMembers?.size ?: 0, squad.focusEntity, blocked, underFire, unlocated)
    }
}
