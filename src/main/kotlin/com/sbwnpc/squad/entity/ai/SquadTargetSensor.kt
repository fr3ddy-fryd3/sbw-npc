package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.FireAllocation
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.combat.Vision
import com.sbwnpc.squad.combat.TankWeaponSelection
import com.sbwnpc.squad.combat.VehicleTargeting
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
 * Priority, highest first:
 * 1. Whatever vehicle is attacking it ([NpcEntity.vehicleAttacker]).
 * 2. At a vehicle's weapon: the crew of a hostile vehicle, then hostile aircrew.
 * 3. The squad's focus (ATTACK: hunt it; DEFEND: whoever last hurt the guarded focus) — not for
 *    mortar crew, who have their own solver (see MortarOperatorBehaviour).
 * 4. Whoever last hurt this NPC.
 * 5. Falling back: only what it can shoot from where it stands — its own sight, then what its
 *    squadmates are shooting at, then the side's contacts.
 * 6. Otherwise the side's relayed contacts ([TeamAwareness]; not for mortar crew), then the
 *    nearest hostile it can see.
 *
 * [BrainUtils.setTargetOfEntity] sets both the ATTACK_TARGET memory and `mob.target`, which the
 * rest of the code reads.
 */
class SquadTargetSensor : ExtendedSensor<NpcEntity>() {

    // ExtendedSensor's default is a flat 20 ticks with NO initial offset (nextTickTime starts at 0
    // — confirmed via javap), so every NPC deployed in the same tick (a whole preset squad, a
    // barracks reinforcement wave) scans on the very same tick forever after: N entity queries +
    // N raycasts in one tick, nothing on the other 19. Randomising the interval per scan
    // decorrelates them within a few cycles. Average is still ~20 ticks.
    init {
        setScanRate { entity -> SCAN_RATE_MIN + entity.random.nextInt(SCAN_RATE_JITTER) }
    }

    override fun type(): SensorType<out ExtendedSensor<*>> = ModSensors.SQUAD_TARGET.get()

    override fun memoriesUsed(): List<MemoryModuleType<*>> = MEMORIES

    override fun doTick(level: ServerLevel, entity: NpcEntity) {
        val target = computeDesired(entity, level)
        BrainUtils.setTargetOfEntity(entity, target)
        if (target != null && DetectionSightline.canSeeWithin(entity,target,NpcEntity.DETECTION_RANGE)) {
            entity.rememberVisible(target)
            SquadTactics.observe(entity,target)
        }
        if (target != null) TankWeaponSelection.update(entity, target)
    }

    private fun computeDesired(mob: NpcEntity, level: ServerLevel): LivingEntity? {
        mob.vehicleAttacker()?.let { return it }
        val isMortarCrew = mob.npcClass == NpcClass.MORTAR_OPERATOR || mob.npcClass == NpcClass.MORTAR_LOADER

        // SBW aims at the vehicle when its passenger is the gunner's target. Give armed vehicle
        // crews that passenger first, so armour is engaged before nearby dismounted infantry.
        if (mob.vehicle?.let { Ports.vehicles.hasWeaponAt(it, mob) } == true) {
            VehicleTargeting.closestVisibleHostileVehicleOccupant(mob, level, NpcEntity.DETECTION_RANGE)?.let { return it }
            VehicleTargeting.closestVisibleHostileAircrew(mob, level, NpcEntity.DETECTION_RANGE)?.let { return it }
        }

        if (!isMortarCrew) {
            squadFocusTarget(mob, level)?.let { return it }
        }

        mob.lastHurtByMob?.takeIf { it.isAlive && SquadTeams.isHostile(mob, it) }?.let { return it }

        // Falling back, a target has to be one this man can shoot from where he stands: the
        // covering half is there to fire, and a relayed contact behind a hill gives it nothing to
        // do. Facing away from the enemy, it would see nothing through its own eyes either — so
        // what the squad is already shooting at comes next, whichever way he faces.
        if (!isMortarCrew && mob.retreatPoint() != null) {
            nearestDirectTarget(mob, level)?.let { return it }
            squadmateTarget(mob, level)?.let { return it }
            return relayedTarget(mob, level)
        }

        if (!isMortarCrew) {
            com.sbwnpc.squad.combat.tactics.SquadTactics.preferredTarget(mob, level)?.let { return it }
            relayedTarget(mob, level)?.let { return it }
        }

        return nearestDirectTarget(mob, level)
    }

    private fun squadFocusTarget(mob: NpcEntity, level: ServerLevel): LivingEntity? {
        val squad = mob.currentSquad() ?: return null
        val fid = squad.focusEntity ?: return null
        val focus = level.getEntity(fid) as? LivingEntity ?: return null
        if (!focus.isAlive) return null
        return when (squad.order) {
            SquadOrder.ATTACK -> focus
            SquadOrder.DEFEND -> focus.lastHurtByMob?.takeIf {
                it.isAlive && it !== mob && SquadTeams.isHostile(mob, it)
            }
            else -> null
        }
    }

    /** A target a squadmate is engaging that this member can see too. Squadmates call out what
     *  they shoot at, so this skips the vision cone the way a relayed contact does. */
    private fun squadmateTarget(mob: NpcEntity, level: ServerLevel): LivingEntity? {
        val squad = mob.currentSquad() ?: return null
        val rangeSqr = NpcEntity.DETECTION_RANGE * NpcEntity.DETECTION_RANGE
        val seen = HashSet<LivingEntity>()
        for (id in squad.members) {
            if (id == mob.uuid) continue
            val t = (level.getEntity(id) as? NpcEntity)?.target ?: continue
            if (t.isAlive && SquadTeams.isHostile(mob, t) && mob.distanceToSqr(t) <= rangeSqr) seen += t
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
                it.isAlive && it !== mob && SquadTeams.isHostile(mob, it) && mob.distanceToSqr(it) <= range * range
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
        val air = VehicleTargeting.closestVisibleHostileAircrew(mob, level, NpcEntity.DETECTION_RANGE)
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
            candidate !== mob && candidate.isAlive && SquadTeams.isHostile(mob, candidate)
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
                SquadTactics.observe(mob,candidate)
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
