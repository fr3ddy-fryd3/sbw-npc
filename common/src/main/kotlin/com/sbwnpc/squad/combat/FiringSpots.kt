package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Who has already claimed which firing position.
 *
 * Every shooter scores candidate positions the same way against the same target, so without this
 * a squad that spots an enemy together converges on the same two or three "best" spots and stands
 * in a heap — reported in-game as NPCs bunching up the moment contact is made. A heap is also what
 * makes them unable to shoot: each one then has a squadmate on its own firing line.
 *
 * Claims are global rather than per-squad on purpose: two squads fighting side by side should not
 * pile into the same doorway either.
 *
 * Entries are transient. They are released when a shooter stops fighting or dies, and a stale one
 * costs nothing worse than a few blocks of ground being avoided for a moment.
 */
object FiringSpots {
    /** How far apart two shooters' chosen positions are kept. */
    const val MIN_SPACING = 3.0

    /**
     * The spacing between men on a height post. At [MIN_SPACING] a 5x5 lookout held one man to a
     * side; up there two may share a parapet, and the tower is what they would otherwise lose.
     */
    const val HEIGHT_POST_SPACING = 2.0

    /**
     * The spacing for a man standing at feet height [y] whose overwatch post was searched round an
     * anchor at [overwatchAnchorY] (null: no overwatch duty). One rule for the post search, the
     * crowding fact and the held-spot check, so none of them moves a man the others placed.
     */
    fun spacing(y: Double, overwatchAnchorY: Double?): Double = if (overwatchAnchorY != null && y > overwatchAnchorY + 2.0) HEIGHT_POST_SPACING else MIN_SPACING

    private val owners = HashMap<UUID, UUID>()
    private val claims = HashMap<UUID, Vec3>()

    fun claim(shooter: UUID, pos: Vec3, owner: UUID? = null) {
        claims[shooter] = pos
        if (owner == null) owners.remove(shooter) else owners[shooter] = owner
    }

    fun release(shooter: UUID) {
        claims.remove(shooter)
        owners.remove(shooter)
    }

    fun release(shooter: UUID, owner: UUID) {
        if (owners[shooter] == owner) release(shooter)
    }

    fun clearAll() {
        claims.clear()
        owners.clear()
    }

    /**
     * Claims close enough to [origin] to matter, everyone else's.
     *
     * Collected once per position search rather than per candidate: the map is walked a single
     * time here, and the handful of results is what the candidates are then tested against.
     */
    fun nearby(origin: Vec3, radius: Double, except: UUID): List<Vec3> {
        if (claims.isEmpty()) return emptyList()
        val reach = radius + MIN_SPACING
        val reachSqr = reach * reach
        val result = ArrayList<Vec3>(4)
        for ((id, pos) in claims) {
            if (id == except) continue
            if (pos.distanceToSqr(origin) <= reachSqr) result += pos
        }
        return result
    }

    /**
     * [nearby] plus where the other friendly NPCs around are actually standing. A claim only
     * exists for a shooter that has picked a firing spot; a squadmate in cover, one still walking
     * up or one that just decided to stay put has none, and the spot next to it looked free.
     */
    fun nearbyWithBodies(level: ServerLevel, shooter: NpcEntity, radius: Double): List<Vec3> {
        val result = ArrayList(nearby(shooter.position(), radius, shooter.uuid))
        NpcRegistry.forEachWithin(level, shooter.position(), radius + MIN_SPACING, exclude = shooter) {
            // A destination reservation does not vacate the NPC's current standing position.
            if (it.isAlive && !SquadTeams.isHostile(shooter, it)) result += it.position()
        }
        return result
    }

    /** Whether [pos] is too close to something already claimed. */
    fun crowded(pos: Vec3, taken: List<Vec3>, spacing: Double = MIN_SPACING): Boolean {
        if (taken.isEmpty()) return false
        val minSqr = spacing * spacing
        return taken.any { it.distanceToSqr(pos) < minSqr }
    }
}
