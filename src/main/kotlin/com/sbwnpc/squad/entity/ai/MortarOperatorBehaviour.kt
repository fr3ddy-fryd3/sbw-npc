package com.sbwnpc.squad.entity.ai

import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.phys.AABB
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/**
 * SmartBrain migration (finishing the plan's "full migration, not partial" decision) — direct port
 * of the old `MortarOperatorGoal` onto `ExtendedBehaviour`, placed in `NpcEntity.getCoreTasks()`
 * (like `SeekCoverBehaviour`/`InteractWithDoor`): a mortar crew member mans its post regardless of
 * whether the Fight/Idle activity is currently active — the old goal ran the same way (priority 1,
 * `Flag.MOVE`, only ever stepping aside for a genuine personal threat, which it checks itself below).
 *
 * Requires the mortar to actually have shells loaded (normally kept topped up by a squadmate
 * running [MortarLoaderBehaviour]). Two ways to get a fire mission:
 *  - commanded: squad order ATTACK with an objective/focus set (always wins).
 *  - autonomous: nearest hostile within the rank-scaled detection radius, even under
 *    DEFEND/PATROL/MOVE — a mortar crew doesn't just sit idle while enemies close in.
 *
 * Minimum range / friendly-safety-radius are heuristics, not a faithful read of the mortar's own
 * internal aim-solver state — needs in-game tuning.
 */
class MortarOperatorBehaviour : ExtendedBehaviour<NpcEntity>() {

    // ExtendedBehaviour defaults to a 60-tick runtime cap (confirmed via javap — see
    // SeekCoverBehaviour's doc comment for the full story); manning a mortar post is meant to be
    // indefinite, not force-interrupted and immediately re-evaluated every 3 seconds.
    init {
        noTimeout()
    }

    private var mortar: Entity? = null
    private var nextAimTick = 0
    private var nextScanTick = 0
    private var nextBarrageShiftTick = 0
    private var barrageAim: BlockPos? = null
    private var nextFireTick = 0
    private var lastScanResult: BlockPos? = null
    /** Consecutive ticks the fire mission has been out of reach — see [outOfReach]. */
    private var unreachableTicks = 0

    companion object {
        private const val SEARCH_RANGE = 30.0
        private const val MIN_RANGE_SQR = 25.0 * 25.0
        private const val SAFE_RADIUS = 10.0
        private const val SELF_DEFENSE_RANGE_SQR = 6.0 * 6.0
        /** Radius of the area a BARRAGE order shells, per the command's description. */
        private const val BARRAGE_RADIUS = 40.0
        /** How long one aim point inside that area is held before walking the fire elsewhere. */
        private const val BARRAGE_SHIFT_TICKS = 100
        private const val MIN_DETECTION = 80.0
        private const val MAX_DETECTION = 160.0
        private const val MIN_SCATTER = 5.0
        private const val MAX_SCATTER = 10.0
        private const val FIRE_COOLDOWN_TICKS = 50
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
        /** How long the mission has to stay out of reach before breaking the mortar down. Long
         *  enough that a target dipping behind a hill for a moment isn't reason to move. */
        private const val DISPLACE_AFTER_TICKS = 100
        private const val DISPLACE_SPEED = 1.0
    }

    private var nextMortarSearchTick = 0
    private var friendlyCheckTick = Int.MIN_VALUE / 2 // not MIN_VALUE: `tickCount - MIN_VALUE` overflows
    private var friendlyCheckTarget: BlockPos? = null
    private var friendlyCheckResult = false

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    private fun eligible(entity: NpcEntity): Boolean {
        if (entity.npcClass != NpcClass.MORTAR_OPERATOR) return false
        // A dug-in operator (badly hurt, took cover) must stay put like everything else that
        // respects NpcEntity.diggedIn (PM review finding — this was one of two Core tasks that
        // still didn't) — manning the mortar can wait until it's healed/no longer holding.
        if (entity.diggedIn) return false
        if (entity.vehicleTransport) return false
        // Genuine personal danger only (an enemy right on top of the operator) — NOT just "some
        // sensor set ATTACK_TARGET", which also happens from the generic squad-target sensor every
        // NpcEntity has regardless of class. Bailing out on ANY target used to silently disable the
        // whole mortar fire-mission/TeamAwareness path in ordinary combat conditions, not just real
        // self-defense.
        val personalThreat = entity.target?.takeIf { it.isAlive && entity.distanceToSqr(it) <= SELF_DEFENSE_RANGE_SQR }
        if (personalThreat != null) return false
        // Mid-displacement there is no mortar to find or claim — the crew is the mortar. Checked
        // before the fire mission, because a crew whose target has just died still has to put the
        // tube down somewhere; bailing out here would leave it carrying it forever.
        if (entity.carryingMortar) return true
        if (fireTarget(entity) == null) return false

        val current = mortar
        if (current != null && Ports.vehicles.isOperational(current) && !MortarClaims.isOperatorClaimedByOther(current.uuid, entity.uuid)) return true

        val level = entity.level() as? ServerLevel ?: return false
        if (entity.tickCount < nextMortarSearchTick) return false
        nextMortarSearchTick = entity.tickCount + MORTAR_SEARCH_INTERVAL_TICKS
        val found = Ports.mortars.within(
            level, AABB.ofSize(entity.position(), SEARCH_RANGE * 2, SEARCH_RANGE * 2, SEARCH_RANGE * 2)
        ).firstOrNull {
            Ports.vehicles.isOperational(it) && entity.distanceToSqr(it) <= SEARCH_RANGE * SEARCH_RANGE &&
                !MortarClaims.isOperatorClaimedByOther(it.uuid, entity.uuid)
        } ?: return false

        MortarClaims.claimOperator(found.uuid, entity.uuid)
        mortar = found
        // See MortarLoaderBehaviour: a loaded shell must not fire by itself, even if we claim the
        // mortar before a loader ever does.
        Ports.mortars.takeControl(found)
        return true
    }

    private val startCheck = StartCheckThrottle(START_CHECK_INTERVAL_TICKS)
    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean =
        startCheck.check(entity) { eligible(entity) }
    override fun shouldKeepRunning(entity: NpcEntity): Boolean = eligible(entity)

    override fun stop(entity: NpcEntity) {
        startCheck.reset()
        MortarClaims.releaseOperator(entity.uuid)
        mortar = null
        entity.servingMortar = false
    }

    override fun tick(entity: NpcEntity) {
        val level = entity.level() as? ServerLevel ?: return
        if (entity.carryingMortar) {
            entity.servingMortar = false
            tickDisplacing(entity, level)
            return
        }
        val m = mortar ?: return
        val target = fireTarget(entity) ?: return

        val dist = entity.position().distanceTo(m.position())
        if (dist > 2.5) {
            entity.servingMortar = false
            entity.navigateTo(m.x, m.y, m.z, 1.0)
            return
        }
        entity.servingMortar = true
        entity.navigation.stop()

        // Can't reach the fire mission from here: pick the tube up and walk it closer rather than
        // standing over it lobbing shells that fall short.
        if (outOfReach(m, target)) {
            if (++unreachableTicks >= DISPLACE_AFTER_TICKS) {
                unreachableTicks = 0
                MortarDeployment.pack(m, entity)
                mortar = null
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
        if (entity.currentSquad() != null && friendlyNearCached(level, entity, target)) return

        if (entity.tickCount >= nextAimTick) {
            Ports.mortars.lay(m, target, scatterRadius(entity), entity)
            nextAimTick = entity.tickCount + 20
        }
        if (entity.tickCount >= nextFireTick) {
            Ports.mortars.fire(m, entity)
            nextFireTick = entity.tickCount + FIRE_COOLDOWN_TICKS
        }
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
     * Carrying the tube towards the fire mission. Sets it back up once close enough — or straight
     * away if there is no longer anything to shoot at, so a crew never ends up wandering with a
     * mortar on its back.
     */
    private fun tickDisplacing(entity: NpcEntity, level: ServerLevel) {
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
            val angle = entity.random.nextDouble() * Math.PI * 2
            // Square-rooted so the points spread evenly over the disc instead of clustering in
            // the middle.
            val radius = Math.sqrt(entity.random.nextDouble()) * BARRAGE_RADIUS
            val x = objective.x + Math.round(Math.cos(angle) * radius).toInt()
            val z = objective.z + Math.round(Math.sin(angle) * radius).toInt()
            val level = entity.level() as? ServerLevel
            val y = level?.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z)
                ?: objective.y
            barrageAim = BlockPos(x, y, z)
        }
        return barrageAim ?: objective
    }

    private fun commandedTarget(entity: NpcEntity, squad: Squad): BlockPos? {
        squad.focusEntity?.let { fid ->
            (entity.level() as? ServerLevel)?.getEntity(fid)?.takeIf { it.isAlive }?.let { return BlockPos.containing(it.position()) }
        }
        return squad.objective
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
        // Was a LivingEntity query over a box up to 320 blocks on a side (thousands of chunk
        // sections), then a raycast against EVERY hostile in it. Now: hostiles from the NPC
        // registry + player list, nearest first, and at most MAX_LOS_CHECKS raycasts — the nearest
        // visible one is the fire target either way; the rest only fed TeamAwareness, which the
        // infantry actually engaging them already does.
        val candidates = ArrayList<LivingEntity>()
        NpcRegistry.forEachWithin(level, entity.position(), radius, exclude = entity) {
            if (it.isAlive && SquadTeams.isHostile(entity, it)) candidates += it
        }
        val r2 = radius * radius
        for (player in level.players()) {
            if (player.isAlive && player.distanceToSqr(entity) <= r2 && SquadTeams.isHostile(entity, player)) candidates += player
        }
        candidates.sortBy { entity.distanceToSqr(it) }

        var selfSpotted: LivingEntity? = null
        var losChecks = 0
        for (c in candidates) {
            if (losChecks++ >= MAX_LOS_CHECKS) break
            if (!entity.sensing.hasLineOfSight(c)) continue
            if (faction != null) TeamAwareness.report(faction, c.uuid, tick)
            if (selfSpotted == null) selfSpotted = c
        }
        if (selfSpotted != null) {
            lastScanResult = BlockPos.containing(selfSpotted.position())
            return lastScanResult
        }

        val relayed = faction?.let { TeamAwareness.relayedContacts(it, tick) } ?: emptyList()
        val target = relayed.asSequence().mapNotNull { level.getEntity(it) as? LivingEntity }.firstOrNull { it.isAlive }
        lastScanResult = target?.let { BlockPos.containing(it.position()) }
        return lastScanResult
    }

    private fun detectionRadius(entity: NpcEntity): Double {
        val t = entity.npcRank.ordinal / (com.sbwnpc.squad.npc.NpcRank.entries.size - 1).toDouble()
        return MIN_DETECTION + t * (MAX_DETECTION - MIN_DETECTION)
    }

    /** Impact-point scatter radius (blocks), fed straight into SBW's own `ArtilleryEntity`
     *  dispersion (`targetPos.center.randomPos(radius)`). Recruits scatter widest, elites
     *  land almost dead-on. */
    private fun scatterRadius(entity: NpcEntity): Int {
        val t = entity.npcRank.ordinal / (com.sbwnpc.squad.npc.NpcRank.entries.size - 1).toDouble()
        return Math.round(MAX_SCATTER - t * (MAX_SCATTER - MIN_SCATTER)).toInt()
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
    private fun friendlyNear(level: ServerLevel, entity: NpcEntity, target: BlockPos): Boolean {
        val center = target.center
        NpcRegistry.forEachWithin(level, center, SAFE_RADIUS, exclude = entity) { other ->
            if (other.squadId != null && !SquadTeams.isHostile(entity, other)) return true
        }
        val r2 = SAFE_RADIUS * SAFE_RADIUS
        for (player in level.players()) {
            if (player.distanceToSqr(center) <= r2 && !SquadTeams.isHostile(entity, player)) return true
        }
        return false
    }
}
