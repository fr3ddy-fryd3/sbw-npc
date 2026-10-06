package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.CombatPosition
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.FireAllocation
import com.sbwnpc.squad.combat.FiringOpportunity
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.TankWeaponSelection
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.combat.VehicleTargeting
import com.sbwnpc.squad.combat.Vision
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.combat.tactics.TacticalTargetSelection
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModSensors
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.sensing.SensorType
import net.tslat.smartbrainlib.api.core.sensor.ExtendedSensor
import net.tslat.smartbrainlib.util.BrainUtils

/**
 * Picks every NPC's target, all in one place — one priority chain rather than several target
 * selectors each keeping its own idea of who the enemy is (which once had a mortar crew arguing
 * with itself over its target).
 *
 * Priority, highest first (computeDesired is the executable order):
 * 1. The vehicle attacker, then hostile vehicle occupants / aircrew for an armed seat.
 * 2. A nearby visible threat for dismounted non-mortar members.
 * 3. The ordered focus for non-mortar members, then whoever last hurt this NPC.
 * 4. A visible current target retained by a holding order.
 * 5. During withdrawal: direct sight, squadmate callout, then faction relay.
 * 6. Otherwise: tactical assignment, faction relay, then nearest direct target.
 * Mortar crew skip ordinary focus, close-threat, withdrawal and tactical/relay selection.
 *
 * [BrainUtils.setTargetOfEntity] sets both the ATTACK_TARGET memory and `mob.target`, which the
 * rest of the code reads.
 */
class SquadTargetSensor : ExtendedSensor<NpcEntity>() {
    private var tacticalProbeCursor = 0

    // ExtendedSensor's default is a flat 20 ticks with NO initial offset (nextTickTime starts at 0
    // — confirmed via javap), so every NPC deployed in the same tick (a whole preset squad, a
    // barracks reinforcement wave) scans on the very same tick forever after: N entity queries +
    // N raycasts in one tick, nothing on the other 19. Randomising the interval per scan
    // decorrelates them within a few cycles. Average is still ~20 ticks.
    init {
        setScanRate { entity ->
            if (entity.target?.let { entity.distanceToSqr(it) <= 36.0 } == true) {
                4
            } else {
                SCAN_RATE_MIN + entity.random.nextInt(SCAN_RATE_JITTER)
            }
        }
    }

    override fun type(): SensorType<out ExtendedSensor<*>> = ModSensors.SQUAD_TARGET.get()

    override fun memoriesUsed(): List<MemoryModuleType<*>> = MEMORIES

    override fun doTick(level: ServerLevel, entity: NpcEntity) {
        val previous = entity.target
        // Observation continues while engaging a reported/ordered target; target priority cannot blind the squad.
        val directTarget = nearestDirectTarget(entity, level)
        val target = computeDesired(entity, level, directTarget)
        BrainUtils.setTargetOfEntity(entity, target)
        if (DebugFlags.on(LogGroup.ORDER) && previous?.uuid != target?.uuid) {
            val squad = entity.currentSquad()
            DebugFlags.log(
                LogGroup.ORDER,
                "[tactics] {} squad={} plan={} event=target_changed previous={} target={} distance={} sharedContact={}",
                entity.uuid, squad?.id, squad?.tactics?.plan?.id, previous?.uuid, target?.uuid,
                target?.let { entity.distanceTo(it) }, target?.uuid in squad?.tactics?.contacts.orEmpty(),
            )
        }
        if (target != null && DetectionSightline.canSeeWithin(entity, target, NpcEntity.DETECTION_RANGE)) {
            entity.rememberVisible(target)
            SquadTactics.observe(entity, target)
        }
        if (target != null) TankWeaponSelection.update(entity, target)
    }

    /** Priority is explicit here: later queries run only when earlier sources have no target. */
    private fun computeDesired(mob: NpcEntity, level: ServerLevel, directTarget: LivingEntity?): LivingEntity? {
        val vehicleAttacker = mob.vehicleAttacker()
        if (vehicleAttacker != null && allowed(mob)) return vehicleAttacker
        val mortarCrew = mob.npcClass in setOf(NpcClass.MORTAR_OPERATOR, NpcClass.MORTAR_LOADER)

        val mountedTarget = armedVehicleTarget(mob, level)
        if (mountedTarget != null) return mountedTarget
        if (!mortarCrew && mob.vehicle == null) {
            val closeEnemy = closeThreat(mob, level)
            if (closeEnemy != null) return closeEnemy
        }
        if (!mortarCrew) {
            val orderedTarget = squadFocusTarget(mob, level)
            if (orderedTarget != null && allowed(mob)) return orderedTarget
        }
        val attacker = mob.lastHurtByMob
        val hostileAttacker = attacker?.takeIf { it.isAlive && SquadTeams.isHostile(mob, it) && allowed(mob) }
        if (hostileAttacker != null && FiringOpportunity.assess(mob, hostileAttacker) == TacticalTargetSelection.Opportunity.SHOOTABLE) return hostileAttacker

        val retainedTarget = retainedDefensiveTarget(mob)
        if (retainedTarget != null) return retainedTarget
        if (!mortarCrew && mob.retreatPoint() != null) return withdrawalTarget(mob, level, directTarget)
        if (!mortarCrew) {
            val tacticalTarget = SquadTactics.preferredTarget(mob, level, tacticalProbeCursor)
            tacticalProbeCursor = (tacticalProbeCursor + TacticalTargetSelection.PROBES_PER_SCAN) and Int.MAX_VALUE
            if (tacticalTarget != null) return tacticalTarget
            val relayedTarget = relayedTarget(mob, level)
            if (relayedTarget != null) return relayedTarget
        }
        return directTarget ?: hostileAttacker
    }

    private fun armedVehicleTarget(mob: NpcEntity, level: ServerLevel): LivingEntity? {
        val armedSeat = mob.vehicle?.let { Ports.vehicles.hasWeaponAt(it, mob) } == true
        if (!armedSeat) return null
        // SBW aims at the vehicle when its passenger is the gunner's target.
        return VehicleTargeting.closestVisibleHostileVehicleOccupant(mob, level, NpcEntity.DETECTION_RANGE)
            ?: VehicleTargeting.closestVisibleHostileAircrew(mob, level, NpcEntity.DETECTION_RANGE)
    }

    private fun retainedDefensiveTarget(mob: NpcEntity): LivingEntity? {
        if (!CombatPosition.holdsPosition(mob.currentSquad()?.order)) return null
        // A holding order may retain a visible target beyond the ordinary detection radius.
        return mob.target?.takeIf { target ->
            target.isAlive && SquadTeams.isHostile(mob, target) && allowed(mob) &&
                FiringOpportunity.assess(mob, target) == TacticalTargetSelection.Opportunity.SHOOTABLE
        }
    }

    private fun withdrawalTarget(mob: NpcEntity, level: ServerLevel, directTarget: LivingEntity?): LivingEntity? {
        // Covering members need a target visible from their position, including squad callouts.
        return directTarget ?: squadmateTarget(mob, level) ?: relayedTarget(mob, level)
    }

    private fun allowed(mob: NpcEntity): Boolean {
        if (mob.vehicle != null) return true
        val squad = mob.currentSquad()
        val home = mob.homeCenter()
        return squad?.order != SquadOrder.DEFEND || home == null ||
            CombatPosition.withinArea(home, mob.position(), CombatPosition.defendRadius(mob.npcClass, squad.members.size))
    }

    private fun squadFocusTarget(mob: NpcEntity, level: ServerLevel): LivingEntity? {
        val squad = mob.currentSquad() ?: return null
        val focusId = squad.focusEntity ?: return null
        val focus = level.getEntity(focusId) as? LivingEntity ?: return null
        if (!focus.isAlive) return null
        return when (squad.order) {
            SquadOrder.ATTACK -> focus

            SquadOrder.DEFEND -> focus.lastHurtByMob?.takeIf {
                it.isAlive && it !== mob && SquadTeams.isHostile(mob, it)
            }

            else -> null
        }
    }

    /** A local visible threat outranks a distant ordered/relayed target, without seeing through cover. */
    private fun closeThreat(mob: NpcEntity, level: ServerLevel): LivingEntity? {
        val candidates = level.getEntitiesOfClass(LivingEntity::class.java, mob.boundingBox.inflate(6.0)) {
            it !== mob && it.isAlive && SquadTeams.isHostile(mob, it) && Vision.noticesClose(mob.position(), mob.yHeadRot, it.position())
        }.sortedBy { mob.distanceToSqr(it) }
        for (candidate in candidates) {
            if (DetectionSightline.canSee(mob, candidate)) {
                SquadTactics.observe(mob, candidate)
                return candidate
            }
        }
        return null
    }

    /** A target a squadmate is engaging that this member can see too. Squadmates call out what
     *  they shoot at, so this skips the vision cone the way a relayed contact does. */
    private fun squadmateTarget(mob: NpcEntity, level: ServerLevel): LivingEntity? {
        val squad = mob.currentSquad() ?: return null
        val seen = HashSet<LivingEntity>()
        for (id in squad.members) {
            if (id == mob.uuid) continue
            val target = (level.getEntity(id) as? NpcEntity)?.target ?: continue
            if (target.isAlive && SquadTeams.isHostile(mob, target) && allowed(mob)) seen += target
        }
        val visible = seen.sortedBy { mob.distanceToSqr(it) }.filter { DetectionSightline.canSee(mob, it) }
        return FireAllocation.pick(mob, level, visible)
    }

    private fun relayedTarget(mob: NpcEntity, level: ServerLevel): LivingEntity? {
        val faction = SquadTeams.factionOf(mob) ?: return null
        return TeamAwareness.relayedContacts(faction, level.gameTime, TeamAwareness.MEMORY_TICKS)
            .asSequence()
            .mapNotNull { level.getEntity(it) as? LivingEntity }
            .filter {
                val range = VehicleTargeting.rangeFor(it, NpcEntity.DETECTION_RANGE)
                it.isAlive && it !== mob && SquadTeams.isHostile(mob, it) && mob.distanceToSqr(it) <= range * range && allowed(mob)
            }
            .sortedBy { mob.distanceToSqr(it) }
            .toList()
            .let { FireAllocation.pick(mob, level, it) }
    }

    // Two cost fixes versus the naive "filter by LOS, then take the nearest" version:
    //  - the box is DETECTION_RANGE wide but only DETECTION_HEIGHT tall — infantry lives on the
    //    ground, and a full 144-block-tall column meant walking ~10x the chunk sections for nothing;
    //  - hostiles are sorted by distance FIRST and raycast in that order, stopping at the first one
    //    visible — the result is identical (nearest visible hostile) but it's typically 1-3
    //    raycasts instead of one per hostile in range (dozens, in a big fight, per NPC per scan).
    private fun nearestDirectTarget(mob: NpcEntity, level: ServerLevel): LivingEntity? {
        val ground = nearestGroundTarget(mob, level)
        val air = VehicleTargeting.closestVisibleHostileAircrew(mob, level, NpcEntity.DETECTION_RANGE)?.takeIf { allowed(mob) }
        if (air != null) SquadTactics.observe(mob, air)
        if (ground == null || air == null) return ground ?: air
        return if (mob.distanceToSqr(air.vehicle ?: air) < mob.distanceToSqr(ground)) air else ground
    }

    private fun nearestGroundTarget(mob: NpcEntity, level: ServerLevel): LivingEntity? {
        // Riding a helicopter, the ground is sighted the way the ground sights a helicopter: twice
        // as far, a hundred blocks up or down, all round (the airframe turns, not the man in it),
        // and not through vanilla's line-of-sight check, which gives up at 128 blocks.
        val airborne = VehicleTargeting.isAircrew(mob)
        val range = if (airborne) NpcEntity.DETECTION_RANGE * VehicleTargeting.AIR_RANGE_FACTOR else NpcEntity.DETECTION_RANGE
        val height = if (airborne) VehicleTargeting.AIR_SEARCH_HEIGHT else DETECTION_HEIGHT
        // At a vehicle's gun it's the mount that turns: the whole circle, like aircrew. Held to
        // its head's cone, a gunner saw only the arc its last target had been in, missed the next
        // enemy coming from the side, and climbed out believing the fight was over.
        val allRound = airborne || mob.vehicle?.let { Ports.vehicles.hasWeaponAt(it, mob) } == true
        val box = mob.boundingBox.inflate(range, height, range)
        val hostiles = level.getEntitiesOfClass(LivingEntity::class.java, box) { candidate ->
            candidate !== mob && candidate.isAlive && SquadTeams.isHostile(mob, candidate) && allowed(mob)
        }
        if (hostiles.isEmpty()) return null
        val rangeSqr = range * range
        hostiles.sortBy { mob.distanceToSqr(it) }
        // The nearest few in sight, not just the nearest: squadmates may already have it covered
        // (see FireAllocation). Still stops raycasting early — a few candidates is plenty.
        val visible = ArrayList<LivingEntity>(MAX_SPREAD_CANDIDATES)
        for (candidate in hostiles) {
            if (mob.distanceToSqr(candidate) > rangeSqr) break // box corners reach past the sphere
            // Cheaper than the raycast and rejects more, so it goes first. Head rotation, not body
            // yaw: an NPC scanning around while it walks is looking where its head points.
            if (!allRound && !Vision.inCone(mob.position(), mob.yHeadRot, candidate.position())) continue
            val sees = DetectionSightline.canSee(mob, candidate)
            if (sees) {
                SquadTactics.observe(mob, candidate)
                visible += candidate
                if (visible.size >= MAX_SPREAD_CANDIDATES || mob.currentSquad() == null) break
            }
        }
        return FireAllocation.pick(mob, level, visible)
    }

    companion object {
        private const val SCAN_RATE_MIN = 15
        private const val SCAN_RATE_JITTER = 11 // 15..25 ticks, mean 20 — same average as before
        private const val DETECTION_HEIGHT = 24.0

        /** How many visible enemies a scan considers when spreading the squad's fire. */
        private const val MAX_SPREAD_CANDIDATES = 4

        private val MEMORIES: List<MemoryModuleType<*>> = listOf(MemoryModuleType.ATTACK_TARGET)
    }
}
