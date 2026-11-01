package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.coordination.Point3
import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.AgentLevel
import com.sbwnpc.squad.ai.planning.FactSnapshot
import com.sbwnpc.squad.ai.tactics.CrewDuty
import com.sbwnpc.squad.ai.tactics.CrewFacts
import com.sbwnpc.squad.ai.tactics.FireSupport
import com.sbwnpc.squad.ai.tactics.FireSupportTargets
import com.sbwnpc.squad.ai.tactics.FireTarget
import com.sbwnpc.squad.ai.tactics.SupportCrew
import com.sbwnpc.squad.ai.tactics.TubeState
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.Noticing
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.entity.ai.MortarClaims
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * One crew as [CrewRoster] found it this update: its [crew] for the plan, the [squad] it fires for
 * (null for a squadless man), and the [tube] its men are assigned to, null for none.
 */
internal class CrewUnit(val crew: SupportCrew, val squad: UUID?, val tube: UUID?)

/** A crew of [men]; keyed by its men, as a tube packed and set up again is a new entity, but the same crew. */
private fun crewUnit(men: List<NpcEntity>, squad: UUID?, tube: UUID?): CrewUnit {
    val key = men.map { it.uuid.toString() }.sorted().joinToString(",")
    return CrewUnit(SupportCrew(AgentId(AgentLevel.TEAM, UUID.nameUUIDFromBytes("crew:$key".toByteArray())), men.map { AgentId(AgentLevel.NPC, it.uuid) }), squad, tube)
}

/**
 * What a crew's facts keep between updates (#258): the scan's progress at noticing each enemy, its
 * last own sighting, the target it chose, and how long that target has been out of the tube's reach.
 */
internal class CrewState(val noticing: Noticing) {
    var nextScan = Long.MIN_VALUE
    var ownSighting: UUID? = null
    var ownSeenAt = Long.MIN_VALUE
    var ownSeenPos: Vec3? = null
    var target: FireTarget? = null

    /** Where the side last saw a sighted [target], the point its shells go. */
    var sightingPos: Vec3? = null

    /** Where to carry the tube for a target out of reach, set once it has been out of reach long enough. */
    var reachPoint: Point3? = null
    var lastTube: TubeState? = null
    private var reachKey: String? = null
    private var unreachableSince: Long? = null

    /**
     * The operator laid at [aim] from [tube] for [target], [reachable] or not. Out of reach for
     * [FireSupport.DISPLACE_AFTER_TICKS], [reachPoint] is set, and the crew's fact flips once.
     */
    fun report(target: FireTarget, tube: Vec3, aim: Vec3, reachable: Boolean, now: Long) {
        if (reachKey != target.key) {
            clearReach()
            reachKey = target.key
        }
        if (reachable) {
            unreachableSince = null
            return
        }
        val since = unreachableSince ?: now.also { unreachableSince = it }
        if (reachPoint != null || now - since < FireSupport.DISPLACE_AFTER_TICKS) return
        // Short of the target by the range a tube redeploys at, or a quarter of the way nearer for
        // a target already that close the solver still rejects.
        val away = tube.subtract(aim).multiply(1.0, 0.0, 1.0)
        val distance = away.length()
        val keep = minOf(FireSupportWorld.REDEPLOY_RANGE, distance * NEARER_FRACTION)
        reachPoint = (if (distance < MIN_DISTANCE) tube else aim.add(away.normalize().scale(keep))).point()
        DebugFlags.log(LogGroup.FIRE_SUPPORT, "event=out_of_reach target={} carry_to={}", target.key, reachPoint)
    }

    /** Forgets the target's reach: a new target, or a tube set up again where it was carried. */
    fun clearReach() {
        unreachableSince = null
        reachPoint = null
        reachKey = null
    }

    private companion object {
        const val NEARER_FRACTION = 0.75
        const val MIN_DISTANCE = 1e-3
    }
}

/**
 * Which mortarmen form which crew this update (#258): the men assigned to one tube, two at most,
 * and each man left over (no tube, or a third at one) as a crew of his own while his squad has a
 * tube or a free one is in reach; a mortarman with neither gets no crew plan.
 */
internal class CrewRoster(private val level: ServerLevel) {
    private var units: Map<AgentId, CrewUnit> = emptyMap()
    private var byMan: Map<UUID, CrewUnit> = emptyMap()

    /** Each tubeless man's free tube, and when it was last looked for. */
    private val freeTubes = HashMap<UUID, Pair<Long, UUID?>>()

    fun collect(now: Long) {
        val men = NpcRegistry.all(level).filter { it.npcClass == NpcClass.MORTAR && it.isAlive && !it.isRemoved && it.vehicle == null }
        val carried = men.filter { it.holds(NpcActivity.CARRYING_MORTAR) }.mapNotNullTo(HashSet()) { it.assignedMortarId }
        val tubeOf = { man: NpcEntity -> man.assignedMortarId?.takeIf { id -> id in carried || level.getEntity(id)?.let { usable(man, it) } == true } }
        val found = ArrayList<CrewUnit>()
        val tubed = men.filter { tubeOf(it) != null }.mapNotNullTo(HashSet()) { it.squadId }
        men.groupBy { it.squadId to tubeOf(it) }.forEach { (key, group) ->
            val (squad, tube) = key
            // The posted men first, then by id: the order of a crew's men never changes with who holds
            // which post (that is `crew.operator`), or a shell errand's swap would replan the operation.
            val ordered = if (tube == null) group else group.sortedWith(compareBy({ MortarClaims.role(tube, it.uuid) == null && !it.holds(NpcActivity.CARRYING_MORTAR) }, { it.uuid }))
            val posted = if (tube == null) emptyList() else ordered.take(MortarClaims.POSTS)
            if (posted.isNotEmpty()) found += crewUnit(posted.sortedBy { it.uuid }, squad, tube)
            // A man left over has a crew's work only with a tube to stand by or take; with none he
            // fights as any man of his squad does, through his engage (#251).
            found += (ordered - posted.toSet()).filter { (squad != null && squad in tubed) || freeTube(it, now) != null }.map { crewUnit(listOf(it), squad, null) }
        }
        units = found.associateBy { it.crew.agent }
        byMan = found.flatMap { unit -> unit.crew.members.map { it.id to unit } }.toMap()
        freeTubes.keys.removeIf { it !in byMan && men.none { man -> man.uuid == it } }
    }

    /** A tube [man] may serve: set up, working and not the enemy's. */
    fun usable(man: NpcEntity, tube: Entity) = tube.isAlive && Ports.vehicles.isOperational(tube) && !SquadTeams.isHostile(man, tube)

    /** The friendly tube with a free post within [FireSupportWorld.SEARCH_RANGE] of [man], looked for every [FREE_TUBE_INTERVAL_TICKS]. */
    fun freeTube(man: NpcEntity, now: Long): UUID? {
        freeTubes[man.uuid]?.takeIf { now - it.first < FREE_TUBE_INTERVAL_TICKS }?.let { return it.second }
        val range = FireSupportWorld.SEARCH_RANGE
        val found = Ports.mortars.within(level, AABB.ofSize(man.position(), range * 2, range * 2, range * 2)) {
            usable(man, it) && man.distanceToSqr(it) <= range * range && MortarClaims.hasPlace(it.uuid, man.uuid)
        }.minByOrNull { man.distanceToSqr(it) }?.uuid
        freeTubes[man.uuid] = now to found
        return found
    }

    /** [squad]'s crews this update; with null, the squadless men's, each its own root. */
    fun crews(squad: UUID?): List<SupportCrew> = units.values.filter { it.squad == squad }.map { it.crew }.sortedBy { it.agent.id }

    /** The squads with crews this update. */
    fun squads(): Set<UUID> = units.values.mapNotNullTo(HashSet()) { it.squad }

    fun unit(team: AgentId): CrewUnit? = units[team]

    /** [man]'s crew this update. */
    fun unitOf(man: UUID): CrewUnit? = byMan[man]

    /** The squad a crew's decisions are filed under, its first man for a squadless one. */
    fun groupOf(team: AgentId): UUID? = units[team]?.let { it.squad ?: it.crew.members.first().id }

    /** Where [squad]'s crewmen stand on average, for a squad with no ground men to be joined at. */
    fun center(squad: UUID): Vec3? {
        val men = units.values.filter { it.squad == squad }.flatMap { it.crew.members }.mapNotNull { level.getEntity(it.id) }
        if (men.isEmpty()) return null
        return Vec3(men.map { it.x }.average(), men.map { it.y }.average(), men.map { it.z }.average())
    }

    fun clear() {
        units = emptyMap()
        byMan = emptyMap()
        freeTubes.clear()
    }

    private companion object {
        // A tube does not appear faster than this.
        const val FREE_TUBE_INTERVAL_TICKS = 40L
    }
}

/**
 * The mortar crews of one level (#258): the facts their team and NPC agents plan on, and their
 * men's duties. Ground squads stay [NativeTactics]'; crews join a squad's plan only as its
 * [com.sbwnpc.squad.ai.tactics.SquadMission.support].
 */
internal class FireSupportWorld(private val level: ServerLevel) {
    val roster = CrewRoster(level)
    private val spotter = CrewSpotter(level)
    private val states = HashMap<AgentId, CrewState>()

    /** Each man's execution state, kept across his duties. */
    private val hands = HashMap<UUID, CrewHand>()

    /** The team facts of crew [empty.owner], see [CrewFacts]. */
    fun teamFacts(empty: FactSnapshot): FactSnapshot {
        val unit = roster.unit(empty.owner) ?: return empty
        val now = empty.now
        val men = unit.crew.members.mapNotNull { level.getEntity(it.id) as? NpcEntity }
        if (men.isEmpty()) return empty
        val state = states.getOrPut(empty.owner) { CrewState(Noticing("mortar", LogGroup.FIRE_SUPPORT)) }
        val carrier = men.firstOrNull { it.holds(NpcActivity.CARRYING_MORTAR) }
        val deployed = unit.tube?.let(level::getEntity)?.takeIf { carrier == null && roster.usable(men.first(), it) }
        val tube = tubeState(carrier, deployed, state)
        val squad = unit.squad?.let { SquadManager.get(level).get(it) }
        var result = empty.observe(CrewFacts.TUBE, tube).observe(CrewFacts.FREE_TUBE, tube == TubeState.NONE && men.any { roster.freeTube(it, now) != null })
        result = placeFacts(result, (carrier ?: deployed)?.position(), relocationPoint(squad, men.first()), carried = carrier != null)
        if (now >= state.nextScan) {
            state.nextScan = now + FireSupportTargets.SCAN_INTERVAL_TICKS
            spotter.update(unit, squad, men, state, now)
        }
        result = result.observe(CrewFacts.OUT_OF_REACH, state.reachPoint != null).observe(CrewFacts.SHELLS_OUT, shellsOut(unit, men))
        state.reachPoint?.let { result = result.observe(CrewFacts.REACH_POINT, it) }
        state.target?.let { result = result.observe(CrewFacts.FIRE, it) }
        unit.tube?.let(MortarClaims::operator)?.let { id -> AgentId(AgentLevel.NPC, id).takeIf { it in unit.crew.members } }?.let { result = result.observe(CrewFacts.OPERATOR, it) }
        return result
    }

    /** Where the tube is; a tube carried for a target out of reach is set up again with that target's reach unknown. */
    private fun tubeState(carrier: NpcEntity?, deployed: Entity?, state: CrewState): TubeState {
        val tube = when {
            carrier != null -> TubeState.CARRIED
            deployed != null -> TubeState.DEPLOYED
            else -> TubeState.NONE
        }
        if (state.lastTube == TubeState.CARRIED && tube != TubeState.CARRIED) state.clearReach()
        state.lastTube = tube
        return tube
    }

    /**
     * Whether the order sends the tube from [at] to [point], farther than [RELOCATE_RANGE]. A
     * [carried] tube keeps its destination until its carrier is near enough to set it up
     * ([DEPLOY_RANGE]): dropped at the edge of [RELOCATE_RANGE], the tube landed past it beside him
     * and was packed again.
     */
    private fun placeFacts(facts: FactSnapshot, at: Vec3?, point: Vec3?, carried: Boolean): FactSnapshot {
        val range = if (carried) DEPLOY_RANGE else RELOCATE_RANGE
        val relocation = point?.takeIf { at != null && at.distanceTo(it) > range }
        val result = facts.observe(CrewFacts.RELOCATE, relocation != null)
        return relocation?.let { result.observe(CrewFacts.RELOCATION, it.point()) } ?: result
    }

    /** [npc]'s own crew facts: whether he is free to serve, and whether an enemy is at arm's length. */
    fun manFacts(facts: FactSnapshot, npc: NpcEntity): FactSnapshot = facts.observe(CrewFacts.FREE, MortarClaims.free(npc)).observe(CrewFacts.THREATENED, MortarClaims.threatened(npc))

    /** The state of [man]'s crew, read by his duty for where the target stands and to report reach. */
    fun stateOf(man: UUID): CrewState? = roster.unitOf(man)?.let { states[it.crew.agent] }

    /**
     * Where the order sets the tube up, if somewhere other than a fire mission: the rally point of a
     * retreat, or the point it holds. An ATTACK or BARRAGE objective is where the shells go.
     */
    private fun relocationPoint(squad: Squad?, man: NpcEntity): Vec3? =
        when (squad?.order) {
            SquadOrder.RETREAT -> man.retreatPoint()
            SquadOrder.DEFEND -> squad.objective?.let { Vec3(it.x + HALF, it.y.toDouble(), it.z + HALF) }
            else -> null
        }

    /**
     * The man at the operator's post has no shells and no man at his post has any to take over
     * with, and there is supply to fetch them from (#258 v2.1): read off the crew, so the swap that
     * sends him for shells does not flip it.
     */
    private fun shellsOut(unit: CrewUnit, men: List<NpcEntity>): Boolean {
        val tube = unit.tube?.let(level::getEntity)
        val operator = tube?.let { MortarClaims.operator(it.uuid) }?.let { id -> men.firstOrNull { it.uuid == id } } ?: men.first()
        if (operator.mortarShellsLeft > 0) return false
        if (tube != null && men.filter { it !== operator && it.mortarShellsLeft > 0 }.any { MortarClaims.atPost(it, tube) }) return false
        return operator.nearestSupply() != null
    }

    /** Drops the crews and men no agent plans for any more; a man leaving keeps no post or claim. */
    fun sweep(desired: Set<AgentId>) {
        states.keys.removeIf { it !in desired }
        hands.entries.removeIf { (id, hand) ->
            (AgentId(AgentLevel.NPC, id) !in desired).also { gone ->
                if (gone) {
                    hand.release()
                    MortarClaims.release(id)
                }
            }
        }
    }

    fun clear() {
        hands.values.forEach(CrewHand::release)
        hands.clear()
        states.clear()
        roster.clear()
    }

    /** The action carrying out [duty] for [npc], on his own [CrewHand]. */
    fun action(duty: CrewDuty, npc: NpcEntity) = CrewDutyAction(duty, npc, hands.getOrPut(npc.uuid) { CrewHand(npc) }, this)

    companion object {
        /** How far a man looks for a free tube, as before. */
        const val SEARCH_RANGE = 30.0

        /** Closer than this to where the order sends it, the tube stays. */
        const val RELOCATE_RANGE = 6.0

        /** Within this of where it was sent, the carrier sets the tube up, well inside [RELOCATE_RANGE]. */
        const val DEPLOY_RANGE = RELOCATE_RANGE - 2.0

        /** A tube carried for a target out of reach is set up again this far short of it. */
        const val REDEPLOY_RANGE = 200.0
        private const val HALF = 0.5
    }
}
