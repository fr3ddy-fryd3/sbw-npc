package com.sbwnpc.squad.combat

import com.sbwnpc.squad.combat.tactics.TacticalBudget
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.entity.ai.VehicleAwareNavigation
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.util.Terrain
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.WeakHashMap

/** Shared local space for ground infantry; explicit orders and patrol routes stay authoritative. */
object SquadAreas {
    private class World {
        val layout = SquadAreaLayout()
        var refreshAt = Long.MIN_VALUE
        var validateAt = Long.MIN_VALUE
        var checking = false
        var members = emptyMap<UUID, List<UUID>>()
        val confirmed = HashMap<UUID, Vec3>()
        val failures = HashMap<UUID, Int>()
        val unavailable = HashMap<UUID, Vec3>()
        val origins = HashMap<UUID, SquadAreaRequest>()
        var cursor = 0
    }
    private val worlds = WeakHashMap<ServerLevel, World>()
    private val roles = setOf(NpcClass.RIFLEMAN, NpcClass.SHOTGUNNER, NpcClass.MACHINE_GUNNER, NpcClass.SNIPER, NpcClass.GRENADIER, NpcClass.MEDIC, NpcClass.DRONE_OPERATOR)

    private fun eligible(squad: Squad) =
        squad.order in setOf(SquadOrder.DEFEND, SquadOrder.PATROL, SquadOrder.RETREAT) &&
            squad.focusEntity == null && (squad.order != SquadOrder.PATROL || squad.routeId == null)

    fun home(npc: NpcEntity, requested: Vec3): Vec3 {
        val squad = npc.currentSquad() ?: return requested
        val level = npc.level() as? ServerLevel ?: return requested
        if (!eligible(squad) || npc.npcClass !in roles || npc.vehicle != null) return requested
        val world = worlds.getOrPut(level) { World() }
        if (level.gameTime >= world.refreshAt) refresh(level, world)
        if (!world.checking && level.gameTime >= world.validateAt) validate(level, world)
        return world.layout.area(squad.id)?.takeIf { it.request.point == requested && it.request.faction == squad.faction }?.center ?: requested
    }

    /** The local area [squad] was given beside its requested point, if it holds one. */
    fun assigned(level: ServerLevel, squad: UUID): SquadArea? = worlds[level]?.layout?.area(squad)

    /** Used by arrival as well as movement, so a separated retreat cannot finish at another squad. */
    fun rallySlot(npc: NpcEntity, point: Vec3): Vec3 {
        val squad = npc.currentSquad() ?: return point
        return point.add(SquadFormation.gridOffset(npc.slotIndex(squad).coerceAtLeast(0), squad.members.size))
    }

    private fun refresh(level: ServerLevel, world: World) {
        world.refreshAt = level.gameTime + 20
        val loaded = NpcRegistry.all(level).filter { it.isAlive && it.vehicle == null && it.npcClass in roles && it.squadId != null }.groupBy { it.squadId!! }
        world.members = loaded.mapValues { (_, members) -> members.map { it.uuid } }
        val requests = SquadManager.get(level).all().filter { eligible(it) }.mapNotNull { squad ->
            val members = loaded[squad.id] ?: return@mapNotNull null
            val point = members.first().orderCenter() ?: return@mapNotNull null
            val radius = if (squad.order == SquadOrder.PATROL) SquadFormation.patrolRadius(squad.members.size) + 2.0 else SquadFormation.perimeterRadius(squad.members.size) + 6.0
            SquadAreaRequest(squad.id, squad.faction, point, radius)
        }
        val before = requests.associate { it.squad to world.layout.area(it.squad)?.center }
        for (request in requests) {
            val old = world.origins.put(request.squad, request)
            if (old?.point != request.point || old?.faction != request.faction) {
                world.failures.remove(request.squad)
                world.confirmed.remove(request.squad)
                world.unavailable.remove(request.squad)
            }
        }
        world.origins.keys.retainAll(requests.mapTo(HashSet()) { it.squad })
        world.unavailable.entries.removeIf { (id, point) -> requests.none { it.squad == id && it.point == point } }
        world.layout.reconcile(requests.filter { world.unavailable[it.squad] != it.point }) { request, point -> ground(level, world, request, point) }
        world.confirmed.keys.retainAll(requests.mapTo(HashSet()) { it.squad })
        world.failures.keys.retainAll(world.confirmed.keys + requests.map { it.squad })
        for (request in requests) {
            val area = world.layout.area(request.squad) ?: continue
            if (area.center == request.point) world.confirmed[request.squad] = area.center
            if (before[request.squad] != area.center) {
                if (before[request.squad] != null) world.failures.putIfAbsent(request.squad, 0)
                DebugFlags.log(LogGroup.ORDER, "[area] squad={} event=assigned requested={} center={} radius={}", request.squad, request.point, area.center, area.request.radius)
            }
        }
    }

    private fun ground(level: ServerLevel, world: World, request: SquadAreaRequest, point: Vec3): Vec3? {
        // An unchanged solo objective retains its original semantics (including a tower floor).
        if (point == request.point) return point
        val members = world.members[request.squad].orEmpty().mapNotNull { level.getEntity(it) as? NpcEntity }
        val actor = members.firstOrNull() ?: return null
        val known = actor.currentSquad()?.tactics?.contacts?.values.orEmpty().filter { level.gameTime - it.seenAt <= 200 }
        if (known.any { point.subtract(it.position).horizontalDistance() < request.point.subtract(it.position).horizontalDistance() - 3.0 }) return null
        if (actor.currentSquad()?.order == SquadOrder.RETREAT) {
            val origin = members.fold(Vec3.ZERO) { sum, npc -> sum.add(npc.position()) }.scale(1.0 / members.size)
            val escape = request.point.subtract(origin).multiply(1.0, 0.0, 1.0).normalize()
            if (point.subtract(request.point).dot(escape) < -3.0) return null
        }
        if (!level.hasChunkAt(net.minecraft.core.BlockPos.containing(point))) return null
        return Terrain.feetAt(level, point, 6) { feet ->
            val half = actor.bbWidth / 2.0
            level.noCollision(actor, AABB(feet.x - half, feet.y + 0.001, feet.z - half, feet.x + half, feet.y + actor.bbHeight, feet.z + half))
        }
    }

    private fun validate(level: ServerLevel, world: World) {
        world.validateAt = level.gameTime + 2
        val pending = world.members.keys.mapNotNull(world.layout::area).filter {
            world.confirmed[it.request.squad] != it.center && (world.failures[it.request.squad] ?: 0) < 8
        }
        if (pending.isEmpty()) return
        val area = pending[Math.floorMod(world.cursor++, pending.size)]
        val actor = world.members[area.request.squad].orEmpty().mapNotNull { level.getEntity(it) as? NpcEntity }.filter { !it.busyWithRole() && !it.resupplying && !it.movementLockedByCover() && !it.combatLockedByMedic() }
            .minByOrNull { it.position().distanceToSqr(area.center) } ?: return
        val navigation = actor.navigation as? VehicleAwareNavigation ?: return
        if (!navigation.canPlan || !TacticalBudget.path(actor)) return
        world.validateAt = level.gameTime + 20 // At most one added native search per world per second.
        world.checking = true
        try {
            val delta = area.center.subtract(actor.position())
            val partial = delta.length() > 72.0
            // A distant area cannot require the actor to first reach the other side of a
            // wall. Probe a bounded approach leg; only reaching the full area confirms it.
            val checkpoint = if (partial) actor.position().add(delta.normalize().scale(48.0)) else area.center
            val goal = if (partial) {
                Terrain.feetAt(level, checkpoint, 6) { feet -> level.noCollision(actor, actor.getDimensions(actor.pose).makeBoundingBox(feet)) }
            } else {
                checkpoint
            }
            val path = goal?.let { TacticalBudget.measure(actor) { navigation.createPositionPath(VehicleAwareNavigation.walkingNode(it)) } }
            if (path?.canReach() == true) {
                if (partial) {
                    DebugFlags.log(LogGroup.ORDER, "[area] squad={} event=approach_reachable center={} checkpoint={} remaining={}", area.request.squad, area.center, goal, delta.length())
                } else {
                    world.confirmed[area.request.squad] = area.center
                    DebugFlags.log(LogGroup.ORDER, "[area] squad={} event=reachable center={}", area.request.squad, area.center)
                }
            } else {
                world.failures.merge(area.request.squad, 1, Int::plus)
                val next = world.layout.reject(area.request.squad) { request, point -> ground(level, world, request, point) }
                if (next == null || (world.failures[area.request.squad] ?: 0) >= 8) {
                    world.unavailable[area.request.squad] = area.request.point
                    world.refreshAt = level.gameTime
                }
                DebugFlags.log(LogGroup.ORDER, "[area] squad={} event=unreachable previous={} next={} attempts={}", area.request.squad, area.center, next?.center, world.failures[area.request.squad])
            }
        } finally {
            world.checking = false
        }
    }

    fun clearAll() = worlds.clear()
}
