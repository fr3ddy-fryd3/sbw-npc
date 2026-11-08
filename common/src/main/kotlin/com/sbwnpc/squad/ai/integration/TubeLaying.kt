package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.tactics.FireSource
import com.sbwnpc.squad.ai.tactics.FireTarget
import com.sbwnpc.squad.combat.DarkAim
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.entity.ai.MortarCycle
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.levelgen.Heightmap

/**
 * The operator's laying and firing (#258): where his crew's target stands now, whether the tube can
 * reach it (reported to the crew's facts), and the refusals that stay with the executor as safety:
 * friendlies near the impact, inside the minimum range, a point the solver cannot reach.
 */
internal class TubeLaying(private val npc: NpcEntity, private val hand: CrewHand, private val world: FireSupportWorld) {
    private val level = npc.level() as ServerLevel

    /** Lays [m] on [target] every [AIM_INTERVAL_TICKS] and fires when it may. */
    fun fire(m: Entity, target: FireTarget, now: Long) {
        val aim = resolve(target, m) ?: return
        val tooClose = aim.distSqr(BlockPos.containing(m.position())) < MIN_RANGE_SQR
        if (now >= hand.nextAimTick) {
            hand.nextAimTick = now + AIM_INTERVAL_TICKS
            hand.reachable = !outOfReach(m, aim)
            world.stateOf(npc.uuid)?.report(target, m.position(), aim.center, hand.reachable, now)
            // The tube's own solver keeps its old aim for a point it cannot hit: laid anyway, it
            // fired at max range into nothing.
            if (hand.reachable && !tooClose) Ports.mortars.lay(m, aim, scatterRadius(aim), npc)
        }
        if (!hand.reachable || tooClose) return
        if (npc.currentSquad() != null && friendlyNearCached(aim, now)) {
            DebugFlags.log(LogGroup.FIRE_SUPPORT, "mortar {} holds fire: friendlies within {} of {}", npc.uuid, dangerRadius(aim), aim.toShortString())
            return
        }
        if (MortarCycle.fireIfReady(m, npc) && target.source == FireSource.ATTACK_PREPARATION) world.stateOf(npc.uuid)?.preparationFired(target.key)
    }

    /**
     * Whether the tube cannot serve [target] from where it stands: farther than a mortar has any
     * business shooting, or a point its own solver rejects. A target too close is a different
     * problem with a different answer (hold fire), and walking towards it would make it worse.
     */
    private fun outOfReach(m: Entity, target: BlockPos): Boolean {
        if (m.position().distanceTo(target.center) > MAX_ENGAGE_RANGE) return true
        if (target.distSqr(BlockPos.containing(m.position())) < MIN_RANGE_SQR) return false
        return !Ports.mortars.canReach(m, target)
    }

    /** Where [target] stands now: the barrage point, the commanded focus or objective, or where the side last saw it. */
    fun resolve(target: FireTarget, tube: Entity? = null): BlockPos? {
        val squad = npc.currentSquad()
        return when (target.source) {
            FireSource.ORDER_BARRAGE, FireSource.ATTACK_PREPARATION -> squad?.objective?.let { barragePoint(it, target.key, tube) }
            FireSource.COMMANDED -> squad?.focusEntity?.takeIf { it.toString() == target.key }?.let(level::getEntity)?.takeIf { it.isAlive }?.let { BlockPos.containing(it.position()) } ?: squad?.objective
            FireSource.OWN_SIGHTING, FireSource.FACTION_SIGHTING -> world.stateOf(npc.uuid)?.sightingPos?.let(BlockPos::containing)
        }
    }

    /**
     * A point inside the beaten zone round [objective], held for [BARRAGE_SHIFT_TICKS] before the
     * fire walks on: re-rolled every tick the aim solver would never settle, and walking it is what
     * reads as shelling an area rather than bad aim. The per-shell scatter still applies on top.
     */
    private fun barragePoint(objective: BlockPos, order: String, tube: Entity?): BlockPos {
        val now = level.gameTime
        if (now >= hand.nextBarrageShift || hand.barrageAim == null || hand.barrageFor != order) {
            hand.nextBarrageShift = now + BARRAGE_SHIFT_TICKS
            hand.barrageFor = order
            // Infantry walking into the area takes part of it; shell the rest instead of holding
            // fire on a point next to it until the next shift.
            // Points the tube cannot reach are left out while it reaches the objective: at the edge of
            // its range the far side of the area read as out of reach and the crew carried the tube
            // off mid-barrage (in-game 08.11).
            val all = (1..BARRAGE_PICKS).map { pick(objective) }
            val picks = if (tube == null || outOfReach(tube, objective)) all else all.filter { !outOfReach(tube, it) }.ifEmpty { listOf(objective) }
            hand.barrageAim = picks.firstOrNull { npc.currentSquad() == null || !friendlyNear(it) } ?: picks.first()
            DebugFlags.log(LogGroup.FIRE_SUPPORT, "mortar {} barrage point {} around {}", npc.uuid, hand.barrageAim?.toShortString(), objective.toShortString())
        }
        return hand.barrageAim ?: objective
    }

    private fun pick(objective: BlockPos): BlockPos {
        val angle = npc.random.nextDouble() * Math.PI * 2
        // Square-rooted so the points spread evenly over the disc instead of clustering in the middle.
        val radius = Math.sqrt(npc.random.nextDouble()) * BARRAGE_RADIUS
        val x = objective.x + Math.round(Math.cos(angle) * radius).toInt()
        val z = objective.z + Math.round(Math.sin(angle) * radius).toInt()
        return BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z)
    }

    /** Impact scatter fed into SBW's own dispersion: recruits widest, elites almost dead-on, wider in the dark (#155). */
    private fun scatterRadius(target: BlockPos): Int {
        val t = npc.npcRank.ordinal / (NpcRank.entries.size - 1).toDouble()
        return Math.round((MAX_SCATTER - t * (MAX_SCATTER - MIN_SCATTER)) * DarkAim.spreadFactor(level, target)).toInt()
    }

    private fun dangerRadius(target: BlockPos): Double = scatterRadius(target) + SHELL_BLAST_RADIUS + IN_FLIGHT_MARGIN

    private fun friendlyNearCached(target: BlockPos, now: Long): Boolean {
        if (target == hand.friendlyTarget && now - hand.friendlyAt < AIM_INTERVAL_TICKS) return hand.friendlyResult
        hand.friendlyAt = now
        hand.friendlyTarget = target
        hand.friendlyResult = friendlyNear(target)
        return hand.friendlyResult
    }

    // The shell lands anywhere within the crew's scatter of the aim and kills within its blast
    // beyond that, so the check covers both: a fixed 10 blocks let a rookie's shells land on men
    // up to 18 blocks from the aim.
    private fun friendlyNear(target: BlockPos): Boolean {
        val center = target.center
        val danger = dangerRadius(target)
        NpcRegistry.forEachWithin(level, center, danger, exclude = npc) { other ->
            if (other.squadId != null && !SquadTeams.isHostile(npc, other)) return true
        }
        val r2 = danger * danger
        return level.players().any { it.distanceToSqr(center) <= r2 && !SquadTeams.isHostile(npc, it) }
    }

    companion object {
        /** Radius of the area a BARRAGE order shells, per the command's description. */
        const val BARRAGE_RADIUS = 40.0
        private const val MIN_RANGE_SQR = 25.0 * 25.0

        /** A shell's blast reach (SBW MortarShellEntity's default explosion radius). */
        private const val SHELL_BLAST_RADIUS = 8.0

        /** Room for friendlies walking towards the impact while the shell is in the air. */
        private const val IN_FLIGHT_MARGIN = 4.0

        /** Barrage points tried before firing at one with friendlies near is left to the fire gate. */
        private const val BARRAGE_PICKS = 6

        /** How long one aim point inside that area is held before walking the fire elsewhere. */
        private const val BARRAGE_SHIFT_TICKS = 100L
        private const val AIM_INTERVAL_TICKS = 20L
        private const val MIN_SCATTER = 5.0
        private const val MAX_SCATTER = 10.0

        /**
         * Past this a fire mission is out of reach whatever the ballistics say, and the crew carries
         * the tube nearer: 200 blocks (owner, 08.11).
         */
        private const val MAX_ENGAGE_RANGE = 200.0
    }
}
