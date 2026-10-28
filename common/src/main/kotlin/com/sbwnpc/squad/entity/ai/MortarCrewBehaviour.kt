package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.DarkAim
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.combat.Hostiles
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.Noticing
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.squad.SquadReports
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/** One persistent soldier class; the tube assigns operator/loader posts at runtime. */
class MortarCrewBehaviour : ExtendedBehaviour<NpcEntity>() {

    // ExtendedBehaviour defaults to a 60-tick runtime cap (confirmed via javap — see
    // SeekCoverBehaviour's doc comment for the full story); manning a mortar post is meant to be
    // indefinite, not force-interrupted and immediately re-evaluated every 3 seconds.
    init {
        noTimeout()
    }

    private var mortar: Entity? = null
    private var carrier: NpcEntity? = null
    private var postOf: kotlin.Pair<Entity, Vec3>? = null
    private var nextAimTick = 0
    private var nextScanTick = 0
    private var nextBarrageShiftTick = 0
    private var barrageAim: BlockPos? = null
    private var lastScanResult: BlockPos? = null

    /** How far the crew has got at noticing each enemy its own scan looks at. */
    private val noticing = Noticing("mortar", LogGroup.FIRE_SUPPORT)

    /** Consecutive ticks the fire mission has been out of reach — see [outOfReach]. */
    private var unreachableTicks = 0

    companion object {
        private const val SEARCH_RANGE = 30.0
        private const val MIN_RANGE_SQR = 25.0 * 25.0

        /** A shell's blast reach (SBW MortarShellEntity's default explosion radius). */
        private const val SHELL_BLAST_RADIUS = 8.0

        /** Room for friendlies walking towards the impact while the shell is in the air. */
        private const val IN_FLIGHT_MARGIN = 4.0

        /** Barrage points tried before firing at one with friendlies near is left to the fire gate. */
        private const val BARRAGE_PICKS = 6
        private const val SELF_DEFENSE_RANGE_SQR = 6.0 * 6.0

        /** Radius of the area a BARRAGE order shells, per the command's description. */
        const val BARRAGE_RADIUS = 40.0

        /** How long one aim point inside that area is held before walking the fire elsewhere. */
        private const val BARRAGE_SHIFT_TICKS = 100
        private const val MIN_DETECTION = 80.0

        // An elite mortarman's sight in the brightest light: 128 x 1.5 (SpottingTime, 21.10).
        private const val MAX_DETECTION = 192.0
        private const val MIN_SCATTER = 5.0
        private const val MAX_SCATTER = 10.0

        // With no mortar in range, eligible() used to run the mortar box query every single
        // tick for the rest of the operator's life (SmartBrainLib re-checks stopped behaviours'
        // start conditions each tick). A mortar doesn't appear faster than this.
        private const val MORTAR_SEARCH_INTERVAL_TICKS = 40

        // The friendly-near-target check ran every tick while manning the mortar; the aim itself is
        // only refreshed every 20 ticks, so checking at the same cadence loses nothing.
        private const val FRIENDLY_CHECK_INTERVAL_TICKS = 20
        private const val MAX_LOS_CHECKS = 8
        private const val START_CHECK_INTERVAL_TICKS = 5

        /** Past this, a commanded fire mission counts as out of reach whatever the ballistics say
         *  — a crew shelling from the far end of the map is not supporting anybody. */
        private const val MAX_ENGAGE_RANGE = 300.0

        /** Close enough to set the tube back up. Well inside [MAX_ENGAGE_RANGE] so a target that
         *  drifts a little doesn't have the crew packing up again the moment they arrive. */
        private const val REDEPLOY_RANGE = 200.0

        /** How close to the point it was sent to the crew sets the tube up. */
        private const val RELOCATE_RANGE = 6.0

        /** How long the mission has to stay out of reach before breaking the mortar down. Long
         *  enough that a target dipping behind a hill for a moment isn't reason to move. */
        private const val DISPLACE_AFTER_TICKS = 100

        /** At a run, like a man falling in: a crew displacing its tube has fire to get back to. */
        private const val DISPLACE_SPEED = 1.3

        /** Farther than this from the tube, he runs to it. */
        private const val RUN_TO_TUBE_DISTANCE = 8.0
    }

    private var nextMortarSearchTick = 0
    private var friendlyCheckTick = Int.MIN_VALUE / 2 // not MIN_VALUE: `tickCount - MIN_VALUE` overflows
    private var friendlyCheckTarget: BlockPos? = null
    private var friendlyCheckResult = false

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    private fun eligible(entity: NpcEntity): Boolean {
        if (entity.npcClass != NpcClass.MORTAR || !MortarClaims.available(entity)) return false
        if (entity.carryingMortar) return true
        val level = entity.level() as? ServerLevel ?: return false
        MortarClaims.refresh(level)
        carrier = NpcRegistry.all(level).firstOrNull {
            it !== entity && it.isAlive && it.carryingMortar &&
                (
                    (entity.assignedMortarId != null && it.assignedMortarId == entity.assignedMortarId) ||
                        (entity.assignedMortarId == null && entity.squadId != null && it.squadId == entity.squadId)
                    )
        }
        if (carrier != null) return true
        val assigned = entity.assignedMortarId?.let(level::getEntity)
        val current = assigned ?: mortar
        if (current != null && Ports.vehicles.isOperational(current) &&
            !SquadTeams.isHostile(entity, current) && MortarClaims.assign(current, entity) != null
        ) {
            mortar = current
            return true
        }
        if (entity.tickCount < nextMortarSearchTick) return false
        nextMortarSearchTick = entity.tickCount + MORTAR_SEARCH_INTERVAL_TICKS
        val found = Ports.mortars.within(level, AABB.ofSize(entity.position(), SEARCH_RANGE * 2, SEARCH_RANGE * 2, SEARCH_RANGE * 2)) {
            Ports.vehicles.isOperational(it) && !SquadTeams.isHostile(entity, it) &&
                entity.distanceToSqr(it) <= SEARCH_RANGE * SEARCH_RANGE && MortarClaims.hasPlace(it.uuid, entity.uuid)
        }.minByOrNull { entity.distanceToSqr(it) } ?: return false
        if (MortarClaims.assign(found, entity) == null) return false
        mortar = found
        Ports.mortars.takeControl(found)
        return true
    }

    private val startCheck = StartCheckThrottle(START_CHECK_INTERVAL_TICKS)
    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean = startCheck.check(entity) { eligible(entity) }
    override fun shouldKeepRunning(entity: NpcEntity): Boolean = eligible(entity)

    override fun stop(entity: NpcEntity) {
        startCheck.reset()
        MortarClaims.release(entity.uuid)
        mortar = null
        carrier = null
        postOf = null
        entity.release(NpcActivity.MORTAR_CREW, this)
        entity.release(NpcActivity.MORTAR_ERRAND, this)
    }

    override fun tick(entity: NpcEntity) {
        val level = entity.level() as? ServerLevel ?: return
        entity.release(NpcActivity.MORTAR_ERRAND, this)
        if (entity.carryingMortar) {
            entity.release(NpcActivity.MORTAR_CREW, this)
            tickDisplacing(entity, level)
            return
        }
        carrier?.takeIf { it.isAlive && it.carryingMortar }?.let { with ->
            entity.claim(NpcActivity.MORTAR_CREW, this)
            entity.claim(NpcActivity.MORTAR_ERRAND, this)
            if (entity.distanceToSqr(with) > 25.0) entity.navigateTo(with.position(), DISPLACE_SPEED) else entity.navigation.stop()
            return
        }
        val m = mortar ?: return
        val role = MortarClaims.assign(m, entity) ?: return
        if (role == MortarClaims.Role.OPERATOR) {
            relocationPoint(entity)?.let { point ->
                if (m.position().distanceTo(point) > RELOCATE_RANGE) {
                    packForRelocation(entity, m)
                    return
                }
            }
        }
        if (entity.mortarShellsLeft <= 0) {
            entity.nearestSupply()?.let { supply ->
                MortarClaims.handOver(m, entity)
                entity.claim(NpcActivity.MORTAR_CREW, this)
                entity.claim(NpcActivity.MORTAR_ERRAND, this)
                if (entity.position().closerThan(supply, com.sbwnpc.squad.block.entity.SupplyBlockEntity.RADIUS - 3.0)) {
                    entity.navigation.stop()
                } else {
                    entity.navigateTo(supply, DISPLACE_SPEED)
                }
                return
            }
        }
        val post = if (role == MortarClaims.Role.OPERATOR) m.position() else loadingPost(entity, m)
        val dist = entity.distanceTo(m)
        if (dist > 2.5) {
            entity.claim(NpcActivity.MORTAR_CREW, this)
            entity.claim(NpcActivity.MORTAR_ERRAND, this)
            val speed = if (dist > RUN_TO_TUBE_DISTANCE) DISPLACE_SPEED else 1.0
            if (!com.sbwnpc.squad.combat.FiringPostArrival.steer(entity, post, speed)) entity.navigateTo(post, speed)
            return
        }
        entity.claim(NpcActivity.MORTAR_CREW, this)
        entity.navigation.stop()
        entity.lookControl.setLookAt(m.x, m.y + 0.5, m.z)
        if (!Ports.mortars.hasShell(m) && !Ports.mortars.isFiring(m) && entity.mortarShellsLeft > 0) {
            Ports.mortars.loadShell(m)
            entity.mortarShellsLeft--
        }
        if (role != MortarClaims.Role.OPERATOR) return
        val target = fireTarget(entity) ?: return

        // Can't reach the fire mission from here: pick the tube up and walk it closer rather than
        // standing over it lobbing shells that fall short.
        if (outOfReach(m, target)) {
            if (++unreachableTicks >= DISPLACE_AFTER_TICKS) {
                unreachableTicks = 0
                if (MortarDeployment.pack(m, entity)) mortar = null
            }
            return
        }
        unreachableTicks = 0

        if (target.distSqr(BlockPos.containing(m.position())) < MIN_RANGE_SQR) return
        // The mortar's own aim solver fails silently (keeps its previous/default aim) when a
        // target is out of ballistic range or beyond the turret's pitch limits. We used to fire
        // regardless, launching shells at whatever stale aim was left over — looked like firing
        // at max range into nothing. Ask the same solver ourselves first and just don't shoot
        // this tick if it can't actually hit the point.
        if (!canHitTarget(m, target)) return
        if (entity.currentSquad() != null && friendlyNearCached(level, entity, target)) {
            DebugFlags.log(LogGroup.FIRE_SUPPORT, "mortar {} holds fire: friendlies within {} of {}", entity.uuid, dangerRadius(entity, target), target.toShortString())
            return
        }

        if (entity.tickCount >= nextAimTick) {
            Ports.mortars.lay(m, target, scatterRadius(entity, target), entity)
            nextAimTick = entity.tickCount + 20
        }
        MortarCycle.fireIfReady(m, entity)
    }

    /**
     * Whether the mortar simply cannot serve this fire mission from where it stands — either
     * further out than a mortar has any business shooting, or a point its own solver rejects
     * (out of ballistic range, or past the pitch the tube can be laid to).
     */
    private fun outOfReach(m: Entity, target: BlockPos): Boolean {
        if (m.position().distanceTo(target.center) > MAX_ENGAGE_RANGE) return true
        // A target too CLOSE is a different problem with a different answer (hold fire, handled by
        // MIN_RANGE_SQR) — walking towards it would only make that worse.
        if (target.distSqr(BlockPos.containing(m.position())) < MIN_RANGE_SQR) return false
        return !canHitTarget(m, target)
    }

    /**
     * Where the crew has been told to set up, if that is somewhere other than a fire mission: the
     * rally point of a retreat, or the point it was ordered to defend. An ATTACK or BARRAGE
     * objective is where the shells go, not where the tube goes, so it never counts.
     */
    private fun relocationPoint(entity: NpcEntity): Vec3? {
        val squad = entity.currentSquad() ?: return null
        return when (squad.order) {
            SquadOrder.RETREAT -> entity.retreatPoint()
            SquadOrder.DEFEND -> squad.objective?.let { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
            else -> null
        }
    }

    /** Walk to the tube and break it down, so the crew can carry it to where it was sent. */
    private fun packForRelocation(entity: NpcEntity, m: Entity) {
        if (entity.position().distanceTo(m.position()) > 2.5) {
            entity.release(NpcActivity.MORTAR_CREW, this)
            entity.claim(NpcActivity.MORTAR_ERRAND, this)
            entity.navigateTo(m.x, m.y, m.z, DISPLACE_SPEED)
            return
        }
        if (MortarDeployment.pack(m, entity)) mortar = null
    }

    private fun loadingPost(entity: NpcEntity, m: Entity): Vec3 {
        postOf?.takeIf { it.first === m }?.let { return it.second }
        val yaw = Math.toRadians(m.yRot.toDouble())
        val post = com.sbwnpc.squad.util.Terrain.standableOrNull(entity.level(), m.x + Math.cos(yaw) * 1.8, m.y + 1.0, m.z + Math.sin(yaw) * 1.8) ?: m.position()
        postOf = kotlin.Pair(m, post)
        return post
    }

    private fun tickDisplacing(entity: NpcEntity, level: ServerLevel) {
        // Sent somewhere: the tube goes there, and is set up once the crew arrives.
        relocationPoint(entity)?.let { point ->
            if (entity.position().distanceTo(point) <= RELOCATE_RANGE - 2.0) {
                entity.navigation.stop()
                if (MortarDeployment.deploy(level, entity, fireTarget(entity)?.center) != null) return
            }
            entity.navigateTo(point.x, point.y, point.z, DISPLACE_SPEED)
            return
        }
        val target = fireTarget(entity)
        if (target == null) {
            MortarDeployment.deploy(level, entity, null)
            return
        }
        val aim = target.center
        if (entity.position().distanceTo(aim) <= REDEPLOY_RANGE) {
            entity.navigation.stop()
            if (MortarDeployment.deploy(level, entity, aim) != null) return
            // Nowhere to set up on this spot — keep walking and try again further on.
        }
        entity.navigateTo(aim.x, aim.y, aim.z, DISPLACE_SPEED)
    }

    private fun canHitTarget(m: Entity, target: BlockPos): Boolean = Ports.mortars.canReach(m, target)

    /** Squad-commanded target first, else the nearest hostile within detection radius. */
    private fun fireTarget(entity: NpcEntity): BlockPos? {
        val squad = entity.currentSquad()
        if (squad != null && squad.order == SquadOrder.BARRAGE) {
            squad.objective?.let { return barrageAimPoint(entity, it) }
        }
        if (squad != null && squad.order == SquadOrder.ATTACK) {
            // Sent with infantry: shell the area round the objective until the infantry gets there.
            val level = entity.level() as? ServerLevel
            if (level != null && com.sbwnpc.squad.squad.FireSupport.infantryApproaching(level, squad)) {
                squad.objective?.let { return barrageAimPoint(entity, it) }
            }
            commandedTarget(entity, squad)?.let { return it }
        }
        return scanForEnemy(entity)
    }

    /**
     * A point somewhere inside the beaten zone around the objective, held for a few shots before
     * moving on. Re-rolling it every tick would never let the aim solver settle, and walking it
     * shot by shot is what makes a barrage read as shelling an area rather than as bad aim — the
     * per-shell scatter from [scatterRadius] still applies on top of wherever this lands.
     */
    private fun barrageAimPoint(entity: NpcEntity, objective: BlockPos): BlockPos {
        if (entity.tickCount >= nextBarrageShiftTick || barrageAim == null) {
            nextBarrageShiftTick = entity.tickCount + BARRAGE_SHIFT_TICKS
            val level = entity.level() as? ServerLevel
            // Infantry walking into the area takes part of it; shell the rest instead of holding
            // fire on a point next to it until the next shift.
            val picks = (1..BARRAGE_PICKS).map { barragePoint(entity, level, objective) }
            barrageAim = picks.firstOrNull { level == null || entity.currentSquad() == null || !friendlyNear(level, entity, it) } ?: picks.first()
            DebugFlags.log(LogGroup.FIRE_SUPPORT, "mortar {} barrage point {} around {}", entity.uuid, barrageAim?.toShortString(), objective.toShortString())
        }
        return barrageAim ?: objective
    }

    private fun barragePoint(entity: NpcEntity, level: ServerLevel?, objective: BlockPos): BlockPos {
        val angle = entity.random.nextDouble() * Math.PI * 2
        // Square-rooted so the points spread evenly over the disc instead of clustering in
        // the middle.
        val radius = Math.sqrt(entity.random.nextDouble()) * BARRAGE_RADIUS
        val x = objective.x + Math.round(Math.cos(angle) * radius).toInt()
        val z = objective.z + Math.round(Math.sin(angle) * radius).toInt()
        val y = level?.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z)
            ?: objective.y
        return BlockPos(x, y, z)
    }

    private fun commandedTarget(entity: NpcEntity, squad: Squad): BlockPos? {
        squad.focusEntity?.let { fid ->
            (entity.level() as? ServerLevel)?.getEntity(fid)?.takeIf { it.isAlive }?.let { return BlockPos.containing(it.position()) }
        }
        return squad.objective
    }

    /**
     * The nearest of [candidates] this crew notices itself, reporting each one it notices to
     * [faction]. Noticed, not just in view (#114). All round, as through binoculars: serving the
     * tube turns his head onto it, so his cone points nowhere in particular, and the whole sweep is
     * as slow as the edge of a cone.
     */
    private fun ownSighting(entity: NpcEntity, level: ServerLevel, candidates: List<LivingEntity>, faction: SquadFaction?): LivingEntity? {
        val tick = level.gameTime
        var selfSpotted: LivingEntity? = null
        var losChecks = 0
        for (c in candidates) {
            if (losChecks++ >= MAX_LOS_CHECKS) break
            if (DetectionSightline.sight(entity, c).visibility != DetectionSightline.Visibility.VISIBLE) continue
            if (!noticing.noticed(entity, level, c, offAxis = 1.0)) continue
            if (faction != null) TeamAwareness.report(faction, c.uuid, c.position(), tick, "mortar ${entity.uuid.toString().take(8)}")
            if (selfSpotted == null) selfSpotted = c
        }
        noticing.endScan(tick)
        return selfSpotted
    }

    /** No more blind radius scanning: a target is only usable if THIS mortar's own operator can
     *  personally see it right now (also reports it — he's a spotter too, with binoculars, not
     *  just a passive report recipient), OR the rest of the faction has relayed a fresh sighting
     *  via [TeamAwareness] — never a target nobody has actually spotted (hiding in a building/
     *  trench stays safe from indirect fire, as it should). */
    private fun scanForEnemy(entity: NpcEntity): BlockPos? {
        if (entity.tickCount < nextScanTick) return lastScanResult
        nextScanTick = entity.tickCount + 20
        val level = entity.level() as? ServerLevel ?: return null
        val tick = level.gameTime
        val faction = SquadTeams.factionOf(entity)
        val radius = detectionRadius(entity)
        // Nearest first, and at most MAX_LOS_CHECKS raycasts — the nearest visible one is the fire
        // target either way; the rest would only feed TeamAwareness, which the infantry actually
        // engaging them already does.
        val candidates = Hostiles.within(level, entity, radius)

        val selfSpotted = ownSighting(entity, level, candidates, faction)
        if (selfSpotted != null) {
            lastScanResult = BlockPos.containing(selfSpotted.position())
            SquadReports.contact(entity, selfSpotted)
            DebugFlags.log(LogGroup.FIRE_SUPPORT, "mortar {} -> {} seen by itself at {}", entity.uuid, selfSpotted.uuid, lastScanResult)
            return lastScanResult
        }

        // Where the side last saw it, not where it is now: the shell goes where the call said.
        val sighting = faction?.let { TeamAwareness.sightings(it, tick) }?.firstOrNull { s ->
            (level.getEntity(s.target) as? LivingEntity)?.isAlive == true
        }
        lastScanResult = sighting?.let { BlockPos.containing(it.pos) }
        if (sighting != null) {
            DebugFlags.log(
                LogGroup.FIRE_SUPPORT,
                "mortar {} -> {} seen by {} {} ticks ago at {}",
                entity.uuid,
                sighting.target,
                sighting.by,
                tick - sighting.tick,
                lastScanResult,
            )
        }
        return lastScanResult
    }

    private fun detectionRadius(entity: NpcEntity): Double {
        val t = entity.npcRank.ordinal / (com.sbwnpc.squad.npc.NpcRank.entries.size - 1).toDouble()
        return MIN_DETECTION + t * (MAX_DETECTION - MIN_DETECTION)
    }

    /** Impact-point scatter radius (blocks), fed straight into SBW's own `ArtilleryEntity`
     *  dispersion (`targetPos.center.randomPos(radius)`). Recruits scatter widest, elites
     *  land almost dead-on. A point in the dark widens it (#155), see [DarkAim]. */
    private fun dangerRadius(entity: NpcEntity, target: BlockPos): Double = scatterRadius(entity, target) + SHELL_BLAST_RADIUS + IN_FLIGHT_MARGIN

    private fun scatterRadius(entity: NpcEntity, target: BlockPos): Int {
        val t = entity.npcRank.ordinal / (com.sbwnpc.squad.npc.NpcRank.entries.size - 1).toDouble()
        return Math.round((MAX_SCATTER - t * (MAX_SCATTER - MIN_SCATTER)) * DarkAim.spreadFactor(entity.level(), target)).toInt()
    }

    private fun friendlyNearCached(level: ServerLevel, entity: NpcEntity, target: BlockPos): Boolean {
        if (target == friendlyCheckTarget && entity.tickCount - friendlyCheckTick < FRIENDLY_CHECK_INTERVAL_TICKS) {
            return friendlyCheckResult
        }
        friendlyCheckTick = entity.tickCount
        friendlyCheckTarget = target
        friendlyCheckResult = friendlyNear(level, entity, target)
        return friendlyCheckResult
    }

    // Squad NPCs from the registry + players from the level list — same population the old
    // LivingEntity box query filtered down to, minus the chunk-section walk.
    //
    // The shell lands anywhere within the crew's scatter of the aim and kills within its blast
    // beyond that, so the check covers both: a fixed 10 blocks let a rookie's shells land on men
    // up to 18 blocks from the aim.
    private fun friendlyNear(level: ServerLevel, entity: NpcEntity, target: BlockPos): Boolean {
        val center = target.center
        val danger = dangerRadius(entity, target)
        NpcRegistry.forEachWithin(level, center, danger, exclude = entity) { other ->
            if (other.squadId != null && !SquadTeams.isHostile(entity, other)) return true
        }
        val r2 = danger * danger
        for (player in level.players()) {
            if (player.distanceToSqr(center) <= r2 && !SquadTeams.isHostile(entity, player)) return true
        }
        return false
    }
}
