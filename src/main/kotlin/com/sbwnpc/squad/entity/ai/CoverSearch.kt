package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.GrenadeHazard
import com.sbwnpc.squad.combat.Hostiles
import com.sbwnpc.squad.combat.Sightline
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Where a suppressed NPC can get out of sight: [SeekCoverBehaviour]'s search for real cover, kept
 * apart from its phases because it keeps no state of its own.
 */
object CoverSearch {
    /** Two candidate sources, both filtered down to points hidden from EVERY nearby hostile (not
     *  just the specific threat that triggered suppression — see [nearbyThreats]) — the raycast in
     *  [isHiddenFrom] is the real gate, this is just about generating candidates likely to pass it:
     *   - a coarse grid scan biased toward spots with a solid block right next to them (an actual
     *     wall/rock/building corner) — genuine physical cover, not just "some open spot that
     *     happens to be hidden by a terrain bump".
     *   - random angle/distance samples (same as before) — still useful in open/rubble terrain
     *     where there's no single obvious wall to hug.
     *  Grid-scan-only would miss legitimate cover in uneven open terrain; random-only was what
     *  actually shipped before and, per user feedback in-game, mostly just resulted in NPCs backing
     *  straight away from the threat (`SeekCoverBehaviour`'s fallback retreat) instead of finding real cover — because
     *  purely random points rarely land next to a wall by chance within a small sample. */
    fun find(entity: NpcEntity, level: ServerLevel, threat: Vec3,excluded: Set<BlockPos> = emptySet()): BlockPos? {
        val origin = entity.blockPosition()
        val candidates = ArrayList<BlockPos>(64)

        var dx = -MAX_RADIUS.toInt()
        while (dx <= MAX_RADIUS.toInt()) {
            var dz = -MAX_RADIUS.toInt()
            while (dz <= MAX_RADIUS.toInt()) {
                val distSq = (dx * dx + dz * dz).toDouble()
                if (distSq in (MIN_RADIUS * MIN_RADIUS)..(MAX_RADIUS * MAX_RADIUS)) {
                    val ground = Terrain.groundAt(level, origin.offset(dx, 0, dz))
                    if (hasAdjacentSolidWall(level, ground)) candidates += ground
                }
                dz += WALL_SCAN_STEP
            }
            dx += WALL_SCAN_STEP
        }

        repeat(SAMPLE_COUNT) {
            val angle = entity.random.nextDouble() * Math.PI * 2
            val dist = MIN_RADIUS + entity.random.nextDouble() * (MAX_RADIUS - MIN_RADIUS)
            candidates += Terrain.groundAt(level, origin.offset(Math.round(Math.cos(angle) * dist).toInt(), 0, Math.round(Math.sin(angle) * dist).toInt()))
        }

        val threats = nearbyThreats(entity, level, threat)
        // One query for the whole search: an APC is cover in every practical sense, and the grid
        // above only ever proposes candidates next to a solid *block*, so without this the mob
        // would never recognise the one piece of hard cover actually standing next to it.
        val hulls = Sightline.vehicleHulls(
            level, entity.boundingBox.inflate(MAX_RADIUS + 4.0), entity, null
        )
        // Nearest cover that nobody else is already behind — the nearest cover, full stop, put a
        // whole suppressed squad behind the same wall.
        val taken = FiringSpots.nearbyWithBodies(level, entity, MAX_RADIUS)
        return candidates.asSequence()
            .mapNotNull { Terrain.feetAt(level,it.bottomCenter) { feet ->
                level.noCollision(entity,entity.getDimensions(entity.pose).makeBoundingBox(feet))
            }?.let(BlockPos::containing) }
            .distinct()
            .filterNot { it in excluded }
            .filterNot { GrenadeHazard.threatens(level, it) }
            .filterNot { FiringSpots.crowded(it.bottomCenter, taken) }
            .sortedBy { it.distSqr(origin) }
            .firstOrNull { isHiddenFrom(level, entity, threats, it, hulls) }
    }

    /** Every currently-known hostile near [entity] (within [THREAT_SCAN_RADIUS]), as eye-height
     *  points — not just [primary] (the specific enemy whose fire triggered suppression). Reported
     *  in-game: the mob would duck out of view of that one attacker while stepping straight into
     *  full view of the rest of the enemy squad standing right next to it. [primary] is always
     *  included in ADDITION to the scan, never replaced by it — the attacker who actually triggered
     *  suppression might be a sniper well outside [THREAT_SCAN_RADIUS], and dropping it just because
     *  some other, closer hostile happened to be in range would silently un-hide the mob from the
     *  one threat it's certain is real. Approximated at the same "+1.5 eye height" heuristic the old
     *  single-threat check always used (threatPos is a stored position, not a live entity to read an
     *  exact eyePosition from). Same hostile-detection idiom as
     *  `MortarOperatorBehaviour.scanForEnemy` (NpcEntity/Player, [SquadTeams.isHostile], alive). */
    private fun nearbyThreats(entity: NpcEntity, level: ServerLevel, primary: Vec3): List<Vec3> {
        val threats = Hostiles.within(level, entity, THREAT_SCAN_RADIUS).mapTo(ArrayList()) { it.eyePosition }
        threats += primary.add(0.0, 1.5, 0.0)
        return threats
    }

    /** Cheap proxy for "there's a wall/corner here", biased two ways rather than just one — per
     *  user request, a candidate standing AT the base of a rise counts (a solid block right beside
     *  it at body/head height, but
     *  so does a candidate sitting IN a natural depression/pit whose own rim is higher than the
     *  candidate itself — a dip's edge blocks a ground-level threat's sightline just as well as a
     *  standing wall does, even with nothing solid immediately at the candidate's own body height.
     *  The depression check scans out to [DEPRESSION_CHECK_DISTANCES] blocks, not just the
     *  immediate neighbor — a real dip/trench is normally wider than 1 block, so its actual rim
     *  sits further out than the candidate's own floor, which is still low ground at distance 1 and
     *  never trips the immediate-neighbor check on its own (this was reported in-game as
     *  depressions still not being recognized after the first attempt at this fix). Doesn't need to
     *  know which side the threat is on: [isHiddenFrom]'s raycast is what actually decides whether
     *  this particular wall/rim blocks THIS particular threat. */
    private fun hasAdjacentSolidWall(level: ServerLevel, pos: BlockPos): Boolean {
        val elevatedWallNearby = NEIGHBOR_OFFSETS.any { (nx, nz) ->
            val side = pos.offset(nx, 0, nz)
            hasCollision(level, side) || hasCollision(level, side.above())
        }
        if (elevatedWallNearby) return true
        return DEPRESSION_CHECK_DISTANCES.any { dist ->
            NEIGHBOR_OFFSETS.any { (nx, nz) -> Terrain.groundAt(level, pos.offset(nx * dist, 0, nz * dist)).y > pos.y }
        }
    }

    private fun hasCollision(level: ServerLevel, pos: BlockPos): Boolean =
        !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty

    /** [candidate] only counts as real cover if it's blocked from EVERY entry in [threats] — one
     *  attacker with a clear line to it is enough to make it not-cover, regardless of how many
     *  others it's hidden from. */
    private fun isHiddenFrom(
        level: ServerLevel,
        entity: NpcEntity,
        threats: List<Vec3>,
        candidate: BlockPos,
        hulls: List<AABB>
    ): Boolean {
        val to = Vec3(candidate.x + 0.5, candidate.y + 1.5, candidate.z + 0.5)
        // A vehicle has collision, so walking at one rides the mob up onto it. Somewhere inside a
        // hull is the roof of an APC, not a piece of cover.
        val feet = Vec3(candidate.x + 0.5, candidate.y + 0.5, candidate.z + 0.5)
        if (hulls.any { it.contains(feet) }) return false
        return threats.all { threat -> Sightline.blockedBy(level, threat, to, entity, hulls) }
    }

    private val NEIGHBOR_OFFSETS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        private const val SAMPLE_COUNT = 20
        private const val MIN_RADIUS = 5.0
        private const val MAX_RADIUS = 14.0        // reverted back from 28 per user request
        private const val WALL_SCAN_STEP = 4       // 8x8 candidate grid across the 28-block search diameter
        // How far out to look for OTHER hostiles a candidate cover point must also stay hidden from
        // — see [find]. Roughly the rifleman/machine-gunner engagement range
        // (BASE_SHOOT_DISTANCE 24 * up to 1.5-2x class multiplier, GunAttackBehaviour) rather than
        // the sniper's full 3x/72 — a pragmatic bound, not "hidden from literally everything that
        // could ever see this spot", to keep the per-candidate raycast cost from scaling unbounded.
        private const val THREAT_SCAN_RADIUS = 40.0
        // How far out (in block-widths, along each NEIGHBOR_OFFSETS direction) to look for a
        // depression's actual rim — see hasAdjacentSolidWall's doc comment. 1..3 only ever found
        // depressions narrower than ~6 blocks total (rim within 3 of any interior point); a real
        // depression/valley is often wider than that, so a candidate deep inside one never saw its
        // own rim (reported in-game as depression-cover only ever working "right up close"). Widened
        // to 1..7 — this only feeds isHiddenFrom() more CANDIDATES to try, it doesn't grant cover on
        // its own, so widening it further has no downside beyond a bit more scanning work.
        private val DEPRESSION_CHECK_DISTANCES = 1..7
}
