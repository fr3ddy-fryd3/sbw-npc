package com.sbwnpc.squad.squad

import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.Heightmap
import java.util.UUID

/**
 * One order from the map for any mix of squads, turned into what each of them can actually do.
 *
 * Each squad gets the order it has the nearest equivalent of, and either its own spot in a line
 * across the direction of travel or, for a mortar firing, the clicked point itself:
 *
 * | Order   | Infantry / gunship | Tank              | Mortar                     | Transport |
 * |---------|--------------------|-------------------|----------------------------|-----------|
 * | Attack  | attack, line       | move, line        | fire at the point          | unchanged |
 * | Defend  | defend, line       | move, line        | carry the tube, defend     | defend    |
 * | Move    | move, line         | move, line        | carry the tube, defend     | move      |
 * | Retreat | retreat, line      | retreat, line     | retreat, line              | retreat   |
 * | Barrage | —                  | —                 | barrage round the point    | —         |
 *
 * Only squads going somewhere take a place in the line, [SPACING] apart and in their current
 * left-to-right order so their paths don't cross — sending them all to the one point had several
 * squads' defences or formations piling into each other.
 */
object GroupOrders {
    private const val SPACING = 16.0

    private enum class Placement { LINE, POINT }

    private enum class Kind { INFANTRY, TANK, MORTAR, GUNSHIP, TRANSPORT }

    /** What happened, one entry per squad, for the player's action bar. */
    fun apply(level: ServerLevel, mgr: SquadManager, squadIds: List<UUID>, order: SquadOrder, x: Int, z: Int): List<String> {
        val plans = squadIds.mapNotNull { id ->
            val squad = mgr.get(id) ?: return@mapNotNull null
            val (given, placement) = resolve(kindOf(mgr, squad), order) ?: return@mapNotNull squad to null
            squad to (given to placement)
        }
        val moving = plans.filter { it.second?.second == Placement.LINE }.map { it.first }
        val spots = lineSpots(level, moving, x.toDouble(), z.toDouble())
        return plans.map { (squad, plan) ->
            if (plan == null) return@map "${squad.name}: unchanged"
            val (given, placement) = plan
            val (px, pz) = if (placement == Placement.LINE) spots.getValue(squad.id) else x.toDouble() to z.toDouble()
            val bx = Math.floor(px).toInt()
            val bz = Math.floor(pz).toInt()
            val by = groundY(level, bx, bz)
            mgr.setOrder(squad.id, given)
            mgr.setObjective(level, squad.id, BlockPos(bx, by, bz))
            mgr.setFocus(squad.id, null)
            "${squad.name}: ${squad.order.name.lowercase()}"
        }
    }

    /**
     * A patrol route drawn on the map, walked by every infantry squad among [squadIds]; the rest
     * keep what they were doing. The route is saved like a recorded one — under "Map: ..." so it
     * can be told apart in the Routes screen — and the map route a squad had before is dropped once
     * no squad walks it any more, so redrawing doesn't pile them up.
     */
    fun patrol(level: ServerLevel, mgr: SquadManager, routes: RouteManager, owner: UUID, squadIds: List<UUID>, points: List<kotlin.Pair<Int, Int>>): List<String> {
        val squads = squadIds.mapNotNull { mgr.get(it) }
        val walkers = squads.filter { kindOf(mgr, it) == Kind.INFANTRY }
        if (walkers.isEmpty()) return squads.map { "${it.name}: unchanged" }
        val route = routes.create(
            owner, MAP_ROUTE_PREFIX + walkers.joinToString(", ") { it.name },
            points.map { (x, z) -> BlockPos(x, groundY(level, x, z), z) }
        )
        val previous = walkers.mapNotNull { it.routeId }.toSet()
        for (squad in walkers) {
            mgr.assignRoute(squad.id, route.id)
            mgr.setOrder(squad.id, SquadOrder.PATROL)
            mgr.setObjective(level, squad.id, route.points.first())
            mgr.setFocus(squad.id, null)
        }
        for (id in previous) {
            val old = routes.get(id) ?: continue
            if (old.name.startsWith(MAP_ROUTE_PREFIX) && mgr.all().none { it.routeId == id }) routes.delete(id)
        }
        return squads.map { if (it in walkers) "${it.name}: patrol (${route.points.size} points)" else "${it.name}: unchanged" }
    }

    private const val MAP_ROUTE_PREFIX = "Map: "

    /**
     * Ground level at a column the map pointed at. The map sends only X and Z, and the point is
     * usually far off in chunks nobody has loaded — where `Level.getHeight` answers the bottom of
     * the world. Every map order was being sent to y = -64: no path leads there, so squads stood
     * still, and one that did get close never counted as arrived. The chunk is loaded to read it.
     */
    private fun groundY(level: ServerLevel, x: Int, z: Int): Int =
        level.getChunk(x shr 4, z shr 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and 15, z and 15) + 1

    private fun kindOf(mgr: SquadManager, squad: Squad): Kind = when {
        mgr.isTankSquad(squad) -> Kind.TANK
        mgr.isMortarSquad(squad) -> Kind.MORTAR
        mgr.isGunshipSquad(squad) -> Kind.GUNSHIP
        mgr.isTransportSquad(squad) -> Kind.TRANSPORT
        else -> Kind.INFANTRY
    }

    private fun resolve(kind: Kind, order: SquadOrder): kotlin.Pair<SquadOrder, Placement>? = when (order) {
        SquadOrder.ATTACK -> when (kind) {
            Kind.TANK -> SquadOrder.MOVE to Placement.LINE
            Kind.MORTAR -> SquadOrder.ATTACK to Placement.POINT
            Kind.TRANSPORT -> null
            else -> SquadOrder.ATTACK to Placement.LINE
        }
        SquadOrder.DEFEND -> when (kind) {
            Kind.TANK -> SquadOrder.MOVE to Placement.LINE
            else -> SquadOrder.DEFEND to Placement.LINE
        }
        SquadOrder.MOVE -> when (kind) {
            Kind.MORTAR -> SquadOrder.DEFEND to Placement.LINE
            else -> SquadOrder.MOVE to Placement.LINE
        }
        SquadOrder.RETREAT -> SquadOrder.RETREAT to Placement.LINE
        SquadOrder.BARRAGE -> if (kind == Kind.MORTAR) SquadOrder.BARRAGE to Placement.POINT else null
        // Not offered on the map; anything else keeps its current order.
        SquadOrder.PATROL -> if (kind == Kind.INFANTRY) SquadOrder.PATROL to Placement.LINE else null
    }

    /** Each squad's spot on a line through the click, across the way they are heading. */
    private fun lineSpots(level: ServerLevel, squads: List<Squad>, x: Double, z: Double): Map<UUID, kotlin.Pair<Double, Double>> {
        if (squads.isEmpty()) return emptyMap()
        if (squads.size == 1) return mapOf(squads[0].id to (x to z))
        val centres = squads.associate { s ->
            val members = s.members.mapNotNull { level.getEntity(it) as? NpcEntity }
            s.id to if (members.isEmpty()) (x to z)
                else (members.sumOf { it.x } / members.size to members.sumOf { it.z } / members.size)
        }
        val cx = centres.values.sumOf { it.first } / centres.size
        val cz = centres.values.sumOf { it.second } / centres.size
        var dx = x - cx
        var dz = z - cz
        val len = Math.sqrt(dx * dx + dz * dz)
        if (len < 1.0) { dx = 0.0; dz = 1.0 } else { dx /= len; dz /= len }
        val rx = -dz
        val rz = dx
        val ordered = squads.sortedBy { s -> centres.getValue(s.id).let { (it.first - cx) * rx + (it.second - cz) * rz } }
        val n = ordered.size
        return ordered.mapIndexed { i, s ->
            val offset = (i - (n - 1) / 2.0) * SPACING
            s.id to (x + rx * offset to z + rz * offset)
        }.toMap()
    }
}
