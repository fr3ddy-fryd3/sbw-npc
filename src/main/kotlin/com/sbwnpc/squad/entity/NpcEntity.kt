package com.sbwnpc.squad.entity

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.ai.GrenadeThrowBehaviour
import com.sbwnpc.squad.entity.ai.IdleLookAroundGoal
import com.sbwnpc.squad.entity.ai.IdleWanderGoal
import com.sbwnpc.squad.entity.ai.InvestigateBehaviour
import com.sbwnpc.squad.entity.ai.MedicHealBehaviour
import com.sbwnpc.squad.entity.ai.MortarClaims
import com.sbwnpc.squad.entity.ai.MortarLoaderBehaviour
import com.sbwnpc.squad.entity.ai.MortarOperatorBehaviour
import com.sbwnpc.squad.entity.ai.SeekCoverBehaviour
import com.sbwnpc.squad.entity.ai.SquadOrderBehaviour
import com.sbwnpc.squad.entity.ai.VehicleCombatSupportBehaviour
import com.sbwnpc.squad.entity.ai.VehicleCrewBehaviour
import com.sbwnpc.squad.entity.ai.VehicleTransportBehaviour
import com.sbwnpc.squad.entity.ai.VehicleTransportClaims
import com.sbwnpc.squad.init.ModMemories
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.tslat.smartbrainlib.util.BrainUtils
import net.minecraft.world.level.ServerLevelAccessor

/**
 * Base squad-member entity. Role (class + rank) drives the loadout and combat tuning. Friend/foe
 * is by squad faction == vanilla scoreboard team (see [SquadTeams]); no team on either side means
 * neutral. The faction also picks the NPC's skin ([com.sbwnpc.squad.client.renderer.NpcRenderer]).
 *
 *
 * The AI is a SmartBrainLib brain (sensors and behaviours under `entity/ai`). [registerGoals] keeps
 * three small vanilla-style goals — float/swim, looking about ([IdleLookAroundGoal], which stands
 * down in combat so it doesn't fight GunAttackBehaviour for the head) and wandering.
 */
open class NpcEntity(type: EntityType<out NpcEntity>, level: Level) :
    PathfinderMob(type, level), net.tslat.smartbrainlib.api.SmartBrainOwner<NpcEntity> {

    var npcClass: NpcClass
        get() = NpcClass.byOrdinal(entityData.get(DATA_CLASS))
        set(value) = entityData.set(DATA_CLASS, value.ordinal)

    var npcRank: NpcRank
        get() = NpcRank.byOrdinal(entityData.get(DATA_RANK))
        set(value) = entityData.set(DATA_RANK, value.ordinal)

    /** Weapon spread before any target-specific adjustment: the rank's, scaled by the class. */
    val spread: Double get() = npcRank.spread * npcClass.accuracyMultiplier

    /** Faction to put the NPC on its scoreboard team; set before finalizeSpawn. null = leave unteamed. */
    var spawnFaction: SquadFaction? = null

    /** Command group this NPC belongs to, if any. Server-side; persisted. */
    var squadId: UUID? = null

    // Suppression (see SeekCoverBehaviour): a temporary "duck and hold" state triggered by taking
    // ranged damage (below, hurt()) or a nearby explosion (SuppressionEvents). Backed by a
    // SmartBrainLib TTL memory — expires on its own, and shows in the brain's memory
    // (e.g. /data get entity <e> Brain).
    fun isSuppressed(): Boolean = BrainUtils.hasMemory(this, ModMemories.SUPPRESSING_THREAT.get())
    val threatPos: Vec3? get() = BrainUtils.getMemory(this, ModMemories.SUPPRESSING_THREAT.get())

    /** Each trigger extends the timer (doesn't stack duration), capped so sustained fire doesn't
     *  grant an indefinite "immune to squad orders" state; measured against the memory's own
     *  remaining TTL. */
    fun suppress(threat: Vec3) {
        val remaining = if (isSuppressed())
            BrainUtils.getTimeUntilMemoryExpires(this, ModMemories.SUPPRESSING_THREAT.get())
        else 0L
        val ticks = maxOf(remaining, SUPPRESSION_DURATION_TICKS.toLong()).coerceAtMost(SUPPRESSION_CAP_TICKS.toLong())
        BrainUtils.setForgettableMemory(this, ModMemories.SUPPRESSING_THREAT.get(), threat, ticks.toInt())
    }

    // Alertness (see InvestigateBehaviour / Alarm): a real "heard something, go check it out"
    // reaction, distinct from actually having a target. Two sources — GunAttackBehaviour.tick()
    // raises this on nearby allies whenever it fires (heard gunfire), and die() raises it on nearby
    // squadmates when the killer can't be resolved as a direct TeamAwareness contact (see die()
    // below). Backed by a SmartBrainLib TTL memory.
    fun isAlert(): Boolean = BrainUtils.hasMemory(this, ModMemories.ALERT_POSITION.get())

    fun alert(pos: Vec3) {
        val remaining = if (isAlert())
            BrainUtils.getTimeUntilMemoryExpires(this, ModMemories.ALERT_POSITION.get())
        else 0L
        val ticks = maxOf(remaining, ALERT_DURATION_TICKS.toLong())
        BrainUtils.setForgettableMemory(this, ModMemories.ALERT_POSITION.get(), pos, ticks.toInt())
    }

    /** Called by [com.sbwnpc.squad.entity.ai.InvestigateBehaviour] once it reaches the alert
     *  position (or gives up navigating to it) — ends the investigation instead of waiting out the
     *  full timer. */
    fun clearAlert() {
        BrainUtils.clearMemory(this, ModMemories.ALERT_POSITION.get())
    }

    /** True while [com.sbwnpc.squad.entity.ai.SeekCoverBehaviour] must have the mob to itself for
     *  movement and combat goals should stand down entirely — false during its PEEKING phase, the
     *  deliberate window where the mob steps out to return fire and GunAttackBehaviour/
     *  GrenadeThrowBehaviour take back over. Backed by [ModMemories.COVER_HOLD] (see its own doc
     *  comment) instead of a hand-rolled enum with a logging setter. */
    fun combatLockedByCover(): Boolean =
        BrainUtils.hasMemory(this, ModMemories.COVER_HOLD.get()) || evadingGrenade()

    /** Running from a live grenade (GrenadeEvadeBehaviour). Part of [combatLockedByCover]; exposed
     *  on its own for the movers that don't read that lock. */
    fun evadingGrenade(): Boolean = BrainUtils.hasMemory(this, ModMemories.GRENADE_EVADE.get())

    fun combatLockedByMedic(): Boolean = BrainUtils.hasMemory(this, ModMemories.MEDIC_HEALING.get())

    // Set by GunAttackBehaviour every time it actually fires (not just "has a target" — genuinely
    // pulled the trigger this tick). Used by SeekCoverBehaviour to verify an ally is really
    // providing covering fire before digging in, rather than just inferring it from having a
    // target and line of sight. internal (not private) for the same cross-file reason isEnemy() is.
    var lastShotTick: Int = Int.MIN_VALUE / 2
        internal set

    fun firedRecently(withinTicks: Int): Boolean = tickCount - lastShotTick <= withinTicks

    // Hand grenades carried, counted rather than held as items — see applyRole() for why. Two of
    // each, per user call: the RGO when holding ground or falling back, the RGN otherwise.
    var rgnLeft = 0
    var rgoLeft = 0

    val hasGrenade: Boolean get() = rgnLeft > 0 || rgoLeft > 0

    /** Which grenade to throw now, or null with none left: the RGO while defending or falling
     *  back, the RGN otherwise — and whichever is left when that one has run out. */
    fun grenadeToThrow(): com.sbwnpc.squad.domain.port.GrenadeKind? {
        val defensive = retreatPoint() != null || currentSquad()?.order.let { it == SquadOrder.DEFEND || it == SquadOrder.RETREAT }
        val preferred = if (defensive) com.sbwnpc.squad.domain.port.GrenadeKind.DEFENSIVE else com.sbwnpc.squad.domain.port.GrenadeKind.OFFENSIVE
        val other = if (defensive) com.sbwnpc.squad.domain.port.GrenadeKind.OFFENSIVE else com.sbwnpc.squad.domain.port.GrenadeKind.DEFENSIVE
        return listOf(preferred, other).firstOrNull { grenadesLeft(it) > 0 }
    }

    fun grenadesLeft(kind: com.sbwnpc.squad.domain.port.GrenadeKind): Int =
        if (kind == com.sbwnpc.squad.domain.port.GrenadeKind.DEFENSIVE) rgoLeft else rgnLeft

    fun spendGrenade(kind: com.sbwnpc.squad.domain.port.GrenadeKind) {
        if (kind == com.sbwnpc.squad.domain.port.GrenadeKind.DEFENSIVE) rgoLeft = (rgoLeft - 1).coerceAtLeast(0)
        else rgnLeft = (rgnLeft - 1).coerceAtLeast(0)
    }

    /** Classes that carry hand grenades at all: not the crews, whose hands are on something else. */
    private fun carriesGrenades(): Boolean = npcClass != NpcClass.MORTAR_OPERATOR && npcClass != NpcClass.MORTAR_LOADER &&
        npcClass != NpcClass.TANK_CREW && npcClass != NpcClass.DRONE_OPERATOR

    // Set/cleared only by SeekCoverBehaviour (enterDugInHolding/stop) while the mob is holding a
    // foxhole it dug for itself. Read by GunAttackBehaviour to skip ALL repositioning (formation
    // advance, bounding, friendly-fire sidestep) while still aiming and firing normally — a dug-in
    // mob fights from the hole rather than leaving it, per explicit user decision (see
    // SeekCoverBehaviour.enterDugInHolding's doc comment for the fuller reasoning/history).
    var diggedIn: Boolean = false

    // Set/cleared by vehicle behaviours while the mob is seeking/boarding/driving/
    // riding a vehicle to cover long squad-transit distances. Same "hands off this mob" contract as
    // diggedIn — every other movement/role behaviour must stand down while this is true.
    var vehicleTransport: Boolean = false

    // Set/cleared only by DroneOperatorBehaviour while a DRONE_OPERATOR has a drone in the air —
    // same "hands off this mob" contract as vehicleTransport (no shooting, no squad movement, no
    // investigating), plus it keeps the AI LOD at full rate: the drone is flown from this mob's
    // brain tick, so throttling the operator would throttle the drone.
    var operatingDrone: Boolean = false

    /** At a mortar as its operator or loader. Same "hands off" contract as [operatingDrone] for
     *  the rifle and the squad's own movement: a crewman pulled toward the enemy by one behaviour
     *  and back to the tube by another spins on the spot. An enemy close enough to be a personal
     *  threat ends it (see the mortar behaviours). */
    var servingMortar: Boolean = false

    /** Tick at which this NPC last lost sight of its target, or null while it can see it. Stamped
     *  by [com.sbwnpc.squad.entity.ai.GunAttackBehaviour], read by
     *  [com.sbwnpc.squad.entity.ai.GrenadeUseBehaviour] to tell "behind cover" from "behind a tree
     *  for a moment". Combat-moment state, not persisted. */
    var blockedSightSince: Int? = null

    // Set/cleared only by AntiDroneBehaviour while this mob is dealing with a hostile drone
    // (shooting at it or running from it) — same "hands off" contract as the two above.
    var antiDroneEngaged: Boolean = false

    /** Kamikaze drones this operator still carries; refilled at a barracks (SquadManager.
     *  respawnAtBarracks). Persisted. Meaningless for other classes. */
    var dronesLeft: Int = 0

    /** True while a mortar operator has broken its mortar down and is carrying it to a new
     *  position — see [com.sbwnpc.squad.entity.ai.MortarDeployment]. Persisted, and put back on
     *  the ground if the carrier dies, so a displacing crew can never simply lose its mortar. */
    var carryingMortar: Boolean = false

    /** A machine gunner's launcher while the machine gun is in its hands, and the machine gun
     *  while the launcher is — see [com.sbwnpc.squad.combat.AntiArmourKit]. Persisted; empty for
     *  every class that carries no second weapon. */
    var antiArmourWeapon: ItemStack = ItemStack.EMPTY

    /** The gun stowed while the operator holds the drone monitor — persisted so a world save
     *  mid-flight doesn't leave the operator with a monitor and no weapon (see
     *  DroneOperatorBehaviour.holdMonitor/restoreWeapon). */
    var stowedWeapon: ItemStack = ItemStack.EMPTY

    /** The vehicle this NPC permanently crews. It remounts while the vehicle remains operational. */
    var assignedVehicleId: UUID? = null

    private var vehicleAttackerId: UUID? = null
    private var vehicleAttackerUntilTick = 0

    fun rememberVehicleAttacker(attacker: LivingEntity) {
        vehicleAttackerId = attacker.uuid
        vehicleAttackerUntilTick = tickCount + VEHICLE_ATTACKER_MEMORY_TICKS
    }

    internal fun vehicleAttacker(): LivingEntity? {
        val id = vehicleAttackerId ?: return null
        if (tickCount >= vehicleAttackerUntilTick) {
            vehicleAttackerId = null
            return null
        }
        val attacker = (level() as? ServerLevel)?.getEntity(id) as? LivingEntity
        if (attacker == null || !attacker.isAlive || !SquadTeams.isHostile(this, attacker)) {
            vehicleAttackerId = null
            return null
        }
        return attacker
    }

    /**
     * SBW puts a rider down at its seat's exit point, which only has to be clear of blocks: it is
     * routinely still inside the vehicle's own box. Stuck in there the NPC can't path anywhere, and
     * every enemy line of fire at it runs into the hull first, so they kept hunting for a firing
     * position that doesn't exist. Step out beside the hull instead.
     */
    override fun stopRiding() {
        val vehicle = this.vehicle
        if (vehicle != null && !level().isClientSide && com.sbwnpc.squad.combat.DebugFlags.LOGGING_ENABLED) {
            // Which of the many dismount paths this was — the frames above this one.
            val from = Throwable().stackTrace.drop(1).take(3).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            com.sbwnpc.squad.combat.DebugFlags.log("[dismount-debug] {} ({}) off {}: {}", uuid, npcClass, vehicle.type.descriptionId, from)
        }
        super.stopRiding()
        if (vehicle == null || this.vehicle != null) return
        if (level().isClientSide || !isAlive || !Ports.vehicles.isVehicle(vehicle)) return
        val hull = Ports.vehicles.hull(vehicle)
        if (!hull.intersects(boundingBox)) return
        clearSpotBeside(hull)?.let { teleportTo(it.x, it.y, it.z) }
    }

    /** Nearest standable, unobstructed spot just outside [hull], searched round its edge. */
    private fun clearSpotBeside(hull: net.minecraft.world.phys.AABB): Vec3? {
        val margin = bbWidth / 2.0 + DISMOUNT_CLEARANCE
        val box = hull.inflate(margin, 0.0, margin)
        val candidates = ArrayList<Vec3>()
        var t = 0.0
        while (t < 1.0) {
            candidates += Vec3(box.minX + (box.maxX - box.minX) * t, 0.0, box.minZ)
            candidates += Vec3(box.minX + (box.maxX - box.minX) * t, 0.0, box.maxZ)
            candidates += Vec3(box.minX, 0.0, box.minZ + (box.maxZ - box.minZ) * t)
            candidates += Vec3(box.maxX, 0.0, box.minZ + (box.maxZ - box.minZ) * t)
            t += DISMOUNT_SAMPLE_STEP
        }
        return candidates.sortedBy { it.distanceToSqr(x, 0.0, z) }.firstNotNullOfOrNull { c ->
            val ground = com.sbwnpc.squad.util.Terrain.standableOrNull(level(), c.x, y + 1.0, c.z) ?: return@firstNotNullOfOrNull null
            val at = Vec3(c.x, ground.y, c.z)
            val body = getDimensions(pose).makeBoundingBox(at)
            at.takeIf { !body.intersects(hull) && level().noCollision(this, body) }
        }
    }

    override fun hurt(source: DamageSource, amount: Float): Boolean {
        val result = super.hurt(source, amount)
        // Not vanilla's DamageTypeTags.IS_PROJECTILE: SBW's gunfire isn't in it, so that tag left
        // suppression coming only from explosions — see the Gear adapter.
        if (result && !level().isClientSide && Ports.gear.isBulletDamage(source)) {
            suppress(source.sourcePosition ?: position())
        }
        return result
    }

    // Resolved at most once per server tick: with SmartBrainLib's every-tick start checks, a single
    // NPC asks for its squad half a dozen times per tick (every Idle/Core behaviour's eligibility,
    // SquadOrderBehaviour.tick, SquadFormation.slotTarget/headingFor, GunAttackBehaviour.tick...).
    // A Squad object is stable for the tick — squads are only created/disbanded/re-membered from
    // network handlers and death, never mid-brain-tick — so a same-tick cache can't go stale.
    private var squadCacheTick = Int.MIN_VALUE
    private var squadCache: Squad? = null

    fun currentSquad(): Squad? {
        val id = squadId ?: return null
        if (squadCacheTick == tickCount) return squadCache?.takeIf { it.id == id }
        val lvl = level() as? ServerLevel ?: return null
        val squad = SquadManager.get(lvl).get(id)
        squadCacheTick = tickCount
        squadCache = squad
        return squad
    }

    // Formation slot = position in squad.members. indexOf is a linear UUID scan and
    // SquadFormation asks for it on every tick of every moving member; the cached index is
    // validated with one O(1) list read, so membership changes (deaths, reinforcements) are
    // picked up immediately without any invalidation hooks.
    private var slotIndexCache = -1

    fun slotIndex(squad: Squad): Int {
        val members = squad.members
        val cached = slotIndexCache
        if (cached in members.indices && members[cached] == uuid) return cached
        val index = members.indexOf(uuid)
        slotIndexCache = index
        return index
    }

    // NpcRegistry bookkeeping — see that object. NeoForge's lifecycle hooks, so this covers every
    // add/remove path (spawn, chunk load/unload, death, /kill, dimension change) without needing a
    // separate event subscriber.
    override fun onAddedToLevel() {
        super.onAddedToLevel()
        NpcRegistry.add(this)
    }

    override fun onRemovedFromLevel() {
        NpcRegistry.remove(this)
        super.onRemovedFromLevel()
    }

    /** Where this NPC "belongs" per its squad: the guarded entity, else the objective point. */
    fun homeCenter(): Vec3? {
        val squad = currentSquad() ?: return null
        // A point to get to, not an enemy to go after: no focus entity.
        if (squad.order == SquadOrder.MOVE || squad.order == SquadOrder.RETREAT) {
            return squad.objective?.let { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
        }
        squad.focusEntity?.let { fid ->
            (level() as? ServerLevel)?.getEntity(fid)?.takeIf { it.isAlive }?.let { return it.position() }
        }
        return squad.objective?.let { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
    }

    /** Where the squad is falling back to, while it is. */
    fun retreatPoint(): Vec3? =
        if (currentSquad()?.order == SquadOrder.RETREAT) homeCenter() else null

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_CLASS, NpcClass.DEFAULT.ordinal)
        builder.define(DATA_RANK, NpcRank.DEFAULT.ordinal)
    }

    /** Vehicles are not obstacles to vanilla pathfinding, so routes are plotted straight through
     *  them and the mob ends up shoved against a hull or standing on its roof. See
     *  [com.sbwnpc.squad.entity.ai.VehicleAwareNavigation]. */
    override fun createNavigation(level: Level): net.minecraft.world.entity.ai.navigation.PathNavigation =
        com.sbwnpc.squad.entity.ai.VehicleAwareNavigation(this, level)

    // Three small vanilla-style goals beside the brain — see the class doc comment.
    override fun registerGoals() {
        super.registerGoals()
        this.goalSelector.addGoal(0, FloatGoal(this))
        this.goalSelector.addGoal(6, IdleLookAroundGoal(this))
        this.goalSelector.addGoal(7, IdleWanderGoal(this, SquadOrderBehaviour.WALK_SPEED_MODIFIER))
    }

    // --- SmartBrainOwner ---
    // SmartBrainLib 1.16.11 (the published jar, whose API differs from the library's git master)
    // has no auto-wiring mixin: brainProvider()/tickBrain() are wired by hand below.
    override fun brainProvider(): net.minecraft.world.entity.ai.Brain.Provider<NpcEntity> =
        net.tslat.smartbrainlib.api.core.SmartBrainProvider(this)

    // Was a one-shot flag; widened to a few retries — see resyncEquipmentForNewlySpawnedNpc's doc
    // comment for why one resync, one tick after spawn, still wasn't always enough (reported: weapon
    // invisible even on a plain single spawn, not just batch bursts).
    private var equipmentResyncTicksRemaining = 5

    // --- AI level-of-detail ---
    // The brain (sensors + every behaviour's start check + running behaviours) is the bulk of an
    // NPC's per-tick cost, and most of it is invisible when no player is anywhere near: a patrol
    // 200 blocks away doesn't need 20 decisions a second. Re-evaluated every LOD_RECHECK_TICKS;
    // anything combat-related (target, suppressed, alert) always stays at full rate so fights over
    // the horizon still resolve at the same speed. Vanilla movement/navigation/look control keep
    // ticking every tick regardless — only the decision layer is throttled, so nothing stutters.
    // Behaviour timers are all tickCount-based and tickCount still advances every tick, so a
    // throttled brain just sees them expire on its next visit.
    private var lodRecheckTick = 0
    private var brainTickInterval = 1

    private fun inCombatState(): Boolean =
        target != null || isSuppressed() || isAlert() || operatingDrone || antiDroneEngaged

    private fun refreshAiLod() {
        brainTickInterval = when {
            inCombatState() -> 1
            else -> {
                val nearest = level().getNearestPlayer(this, -1.0)
                val distSqr = nearest?.distanceToSqr(this) ?: Double.MAX_VALUE
                when {
                    distSqr <= LOD_NEAR_RANGE * LOD_NEAR_RANGE -> 1
                    distSqr <= LOD_FAR_RANGE * LOD_FAR_RANGE -> 2
                    else -> 4
                }
            }
        }
        // Idle pathing doesn't need a perfect route — half the A* node budget is plenty for
        // walking to a patrol point; combat gets the full budget back for chasing/repositioning.
        if (inCombatState()) navigation.resetMaxVisitedNodesMultiplier()
        else navigation.setMaxVisitedNodesMultiplier(IDLE_PATH_NODE_MULTIPLIER)
    }

    private var seenOrderStamp = 0

    /** A new command beats whatever the NPC was idly busy with: an alarm it was going to
     *  investigate held the squad's orders off for up to ten seconds, and a path to the old
     *  objective kept being walked until its next repath. */
    private fun takeNewOrders() {
        val squad = currentSquad() ?: return
        val stamp = squad.orderStamp
        if (stamp == seenOrderStamp) return
        seenOrderStamp = stamp
        // Why a member does or doesn't act on it: a target, a vehicle, a tube or a drone all come
        // before a squad order.
        com.sbwnpc.squad.combat.DebugFlags.log(
            "[order-debug] {} ({}) got {}: target={} alert={} transport={} mortar={} drone={} dug={}",
            squad.name, npcClass, squad.order, target?.name?.string, isAlert(), vehicleTransport,
            servingMortar, operatingDrone, diggedIn
        )
        if (target == null) {
            clearAlert()
            navigation.stop()
        }
        brainTickInterval = 1
    }

    override fun customServerAiStep() {
        super.customServerAiStep()
        takeNewOrders()
        if (equipmentResyncTicksRemaining > 0) {
            equipmentResyncTicksRemaining--
            resyncEquipmentForNewlySpawnedNpc()
        }
        if (tickCount >= lodRecheckTick) {
            lodRecheckTick = tickCount + LOD_RECHECK_TICKS
            refreshAiLod()
        } else if (brainTickInterval > 1 && inCombatState()) {
            // Don't wait for the next recheck to react to being shot at / spotting something.
            brainTickInterval = 1
            navigation.resetMaxVisitedNodesMultiplier()
        }
        // Offset by entity id so throttled NPCs don't all take their turn on the same tick.
        if (brainTickInterval == 1 || (tickCount + id) % brainTickInterval == 0) tickBrain(this)
        updateCrouch()
        breakSoftBlockIfStuck()
    }

    // --- Stuck on a soft block ---

    private var stuckCheckPos: Vec3? = null
    private var stuckSinceTick = -1
    /** Where it last asked to go and got a path that ends where it stands, and when — see
     *  [notePathGoesNowhere]. */
    private var nowhereToward: BlockPos? = null
    private var nowhereSinceTick = -1
    private var nowhereLastTick = -1

    /**
     * Called by the navigation for every path it plans: [goesNowhere] is a path that can't reach
     * [toward] and ends where the NPC already stands. A man boxed in like that has a path that is
     * finished before it starts — the navigation reports itself done, so from outside he looks
     * like someone who has arrived, standing still with nowhere to go.
     */
    fun notePathGoesNowhere(toward: BlockPos, goesNowhere: Boolean) {
        if (!goesNowhere) {
            nowhereSinceTick = -1
            return
        }
        if (nowhereSinceTick < 0) nowhereSinceTick = tickCount
        nowhereLastTick = tickCount
        nowhereToward = toward
    }

    /**
     * Stuck with somewhere to go — either walking a path without getting anywhere, or getting paths
     * that end where it stands — and what's in the way at eye height is something a hand clears in
     * a moment: leaves, a bush, glass, sand, dirt. Knock it out. Paths are planned round such
     * blocks, but not every snag is foreseen, and a man standing pressed against a hedge until the
     * order changed looked broken. Nothing harder than [SOFT_BLOCK_HARDNESS], nothing with
     * contents, nothing at all where mob griefing is off. With eye height clear, the block over
     * its head goes instead, so it can climb the one at its feet.
     */
    private fun breakSoftBlockIfStuck() {
        if (tickCount % STUCK_CHECK_TICKS != 0 || isPassenger) return
        val here = position()
        val path = navigation.path
        val moved = stuckCheckPos?.let { it.distanceToSqr(here) > STUCK_MOVE_SQR } ?: true
        stuckCheckPos = here
        val walkingInPlace = path != null && !navigation.isDone && !moved
        if (walkingInPlace) {
            if (stuckSinceTick < 0) stuckSinceTick = tickCount
        } else {
            stuckSinceTick = -1
        }
        val boxedIn = nowhereSinceTick >= 0 && tickCount - nowhereLastTick <= NOWHERE_FRESH_TICKS && !moved
        val toward: Vec3 = when {
            walkingInPlace && tickCount - stuckSinceTick >= STUCK_BREAK_TICKS -> Vec3.atCenterOf(path!!.nextNodePos)
            boxedIn && tickCount - nowhereSinceTick >= STUCK_BREAK_TICKS -> Vec3.atCenterOf(nowhereToward ?: return)
            else -> return
        }
        val level = level() as? ServerLevel ?: return
        if (!net.neoforged.neoforge.event.EventHooks.canEntityGrief(level, this)) return
        val dx = toward.x - x
        val dz = toward.z - z
        val len = kotlin.math.sqrt(dx * dx + dz * dz)
        if (len < 1.0e-3) return
        val eye = BlockPos.containing(x + dx / len * 0.8, eyeY, z + dz / len * 0.8)
        val pos = if (solid(level, eye)) eye else eye.above()
        val state = level.getBlockState(pos)
        val hardness = state.getDestroySpeed(level, pos)
        val refusal = when {
            !solid(level, pos) -> "nothing solid there"
            !state.fluidState.isEmpty -> "fluid"
            level.getBlockEntity(pos) != null -> "has contents"
            hardness < 0f || hardness > SOFT_BLOCK_HARDNESS -> "hardness $hardness"
            state.requiresCorrectToolForDrops() -> "needs a tool"
            else -> null
        }
        // Either way, not again for another STUCK_BREAK_TICKS.
        stuckSinceTick = -1
        nowhereSinceTick = -1
        if (refusal != null) {
            com.sbwnpc.squad.combat.DebugFlags.log("[stuck-debug] {} stuck at {}, left {} at {} ({})", uuid, blockPosition(), state.block.descriptionId, pos, refusal)
            return
        }
        swing(net.minecraft.world.InteractionHand.MAIN_HAND)
        level.destroyBlock(pos, true, this)
        com.sbwnpc.squad.combat.DebugFlags.log("[stuck-debug] {} broke {} at {} to get unstuck", uuid, state.block.descriptionId, pos)
    }

    private fun solid(level: ServerLevel, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return !state.isAir && !state.getCollisionShape(level, pos).isEmpty
    }

    // --- Crouching ---

    /** Ticks this NPC has stood still in a position worth crouching in. */
    private var stillTicks = 0
    /** Crouching cost it its line of fire: stays up until this tick. */
    private var standUntilTick = 0

    /**
     * Down on one knee once settled at a position: in cover, dug in, holding a defend post, or
     * fighting from where it stands. Up again the moment it moves off. The eyes stay high enough
     * to see and shoot over a one-block wall; where crouching still hides the target — a taller
     * lip — it stands to shoot and stays up a while, rather than bobbing up and down every tick.
     */
    private fun updateCrouch() {
        if (pose != Pose.STANDING && pose != Pose.CROUCHING) return
        val moving = !navigation.isDone || deltaMovement.horizontalDistanceSqr() > STILL_SPEED_SQR
        val position = combatLockedByCover() || diggedIn || target != null || currentSquad()?.order == SquadOrder.DEFEND
        stillTicks = if (!moving && onGround() && !isPassenger && !isInWater && position) stillTicks + 1 else 0
        if (isCrouching && target != null && blockedSightSince != null && tickCount - blockedSightSince!! >= SIGHT_LOST_STAND_TICKS) {
            standUntilTick = tickCount + STAND_TO_SHOOT_TICKS
        }
        val crouch = stillTicks >= CROUCH_AFTER_TICKS && tickCount >= standUntilTick
        if (crouch && !isCrouching) {
            setPose(Pose.CROUCHING)
        } else if (!crouch && isCrouching && canStandUp()) {
            setPose(Pose.STANDING)
        }
    }

    private fun canStandUp(): Boolean =
        level().noCollision(this, getDimensions(Pose.STANDING).makeBoundingBox(position()).deflate(1.0e-7))

    override fun getDefaultDimensions(pose: Pose): EntityDimensions =
        if (pose == Pose.CROUCHING) CROUCHING_DIMENSIONS.scale(ageScale) else super.getDefaultDimensions(pose)

    // --- Equipment sync ---
    // Vanilla re-compares every equipment slot against its last-broadcast copy each tick
    // (LivingEntity.detectEquipmentUpdates → equipmentHasChanged → ItemStack.matches, a deep
    // compare of the stack's components — for an SBW gun that's its whole ~1 KB CUSTOM_DATA tag),
    // and broadcasts a ClientboundSetEquipmentPacket with the full stack to every tracker whenever
    // they differ. An SBW gun's tag differs almost every tick it's in use: ammo, heat and the
    // post-shot timers all live in it (GunData.persist rewrites CUSTOM_DATA when anything mutated).
    // None of that is visible on an NPC: third-person guns render through renderByItem — static
    // model + attachments — the animation instance that reads reload/bolt state only exists for
    // the local player's first-person view (confirmed in SBW's GeoGunRenderer /
    // simplebedrockmodel's AbstractGeoItemRendererV2). So for the held gun, only a change of item
    // or of the "Attachments" sub-tag counts as a visible change; everything else is compared and
    // synced exactly as before. Spawn-time full syncs (resyncEquipmentForNewlySpawnedNpc) are
    // unaffected — they bypass this path.
    override fun equipmentHasChanged(oldItem: ItemStack, newItem: ItemStack): Boolean {
        if (Ports.guns.isGun(oldItem) && newItem.item === oldItem.item && oldItem.count == newItem.count) {
            return Ports.guns.looks(oldItem) != Ports.guns.looks(newItem)
        }
        return super.equipmentHasChanged(oldItem, newItem)
    }

    // Vanilla runs an entity query around every mob every tick just to shove neighbours apart.
    // Formations deliberately put NPCs close together, so every one of those queries has work to
    // do. Away from players and out of combat (brain LOD > 1) it's done on alternate ticks instead
    // — the same separation, one tick later; never skipped outright so two NPCs can't end up
    // sharing a block unnoticed.
    override fun pushEntities() {
        if (brainTickInterval == 1 || (tickCount + id) % 2 == 0) super.pushEntities()
    }

    // --- Repath gate ---
    // Several behaviours call navigation.moveTo(x, y, z) every tick while approaching something.
    // Vanilla reuses the current path only while it's still in progress AND aimed at the same
    // block; the moment it finishes (arrived-but-not-close-enough, unreachable, blocked by an
    // ally) every further call is a full A* search — per tick. This funnels those callers through
    // one place that (a) does nothing while already en route to that block and (b) otherwise rate
    // limits recomputation. Returns whether a (re)path was issued this call.
    private var nextRepathTick = 0

    fun navigateTo(x: Double, y: Double, z: Double, speed: Double, repathIntervalTicks: Int = REPATH_INTERVAL_TICKS): Boolean {
        val nav = navigation
        val targetBlock = BlockPos.containing(x, y, z)
        if (!nav.isDone && nav.targetPos == targetBlock) {
            nav.setSpeedModifier(speed)
            return false
        }
        if (tickCount < nextRepathTick) return false
        nextRepathTick = tickCount + repathIntervalTicks
        nav.moveTo(x, y, z, speed)
        return true
    }

    fun navigateTo(pos: Vec3, speed: Double, repathIntervalTicks: Int = REPATH_INTERVAL_TICKS): Boolean =
        navigateTo(pos.x, pos.y, pos.z, speed, repathIntervalTicks)

    /**
     * Fix for a bug reported after in-game testing: NPCs sometimes spawn with no visibly held
     * weapon for players already nearby, even though the item is genuinely equipped (confirmed
     * functional — reload/fire/ammo all work). Originally reported on batch spawns specifically
     * (preset deploy, barracks reinforcement — several `finalizeSpawn`+`addFreshEntity` calls in the
     * same server tick); later also reported on plain single spawns, so this can no longer be a
     * one-shot fix scoped to the burst case alone — see below.
     *
     * The weapon (and now armor — HEAD/CHEST, added for per-faction kits) is set in [applyRole]
     * (called from [finalizeSpawn]), i.e. before this entity is even added to the level / starts
     * being tracked by any player — relying on the tracking-pairing path
     * (`ServerEntity.sendPairingData`, a full equipment resend to each newly-tracking player) to
     * deliver it, rather than the ordinary per-tick delta path
     * (`LivingEntity.detectEquipmentUpdates`/`ItemStack.matches` against the last-broadcast stack).
     * Re-setting the identical stack later can't force that ordinary path to fire — `matches` is a
     * value comparison, an equal-value stack is never treated as "changed" — so this bypasses both
     * paths entirely with a fresh `ClientboundSetEquipmentPacket` broadcast directly to every player
     * in the dimension.
     *
     * A SINGLE resync one tick after spawn was enough to fix the originally-reported batch case, but
     * NOT reliably enough for the single-spawn case reported later — rather than guess at the exact
     * remaining race (a slower/laggier pairing handshake for a specific client is a plausible but
     * unconfirmed cause), this retries for a handful of ticks after spawn instead of exactly once, at
     * negligible cost (a few tiny packets, once per NPC, only in the first quarter-second of its
     * life) — hedges against ANY remaining timing window rather than the one already disproven to be
     * the sole cause.
     */
    private fun resyncEquipmentForNewlySpawnedNpc() {
        val serverLevel = level() as? ServerLevel ?: return
        val slots = listOf(
            net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            net.minecraft.world.entity.EquipmentSlot.HEAD,
            net.minecraft.world.entity.EquipmentSlot.CHEST
        // .copy() — vanilla always does this before handing a stack to this exact packet
        // (LivingEntity.detectEquipmentUpdates, ServerEntity.sendPairingData) rather than the live
        // reference, since that reference can keep mutating (ammo count, durability, ...) after the
        // packet object is built but before it's actually written to the network buffer (PM finding).
        ).map { slot -> com.mojang.datafixers.util.Pair.of(slot, getItemBySlot(slot).copy()) }
            .filter { !it.second.isEmpty }
        if (slots.isEmpty()) return
        val packet = net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(id, slots)
        // Only the players actually tracking this entity — a dimension-wide broadcast sent 5
        // packets per NPC to every player on the server, including ones who couldn't see it.
        serverLevel.chunkSource.broadcast(this, packet)
    }

    // Target acquisition: replaces the old SquadFocusTargetGoal, HurtByTargetGoal,
    // SquadAwarenessTargetGoal, and NearestAttackableTargetGoal with one sensor evaluating the same
    // priority chain in one place. Bridges to mob.target via BrainUtils.setTargetOfEntity.
    override fun getSensors(): List<net.tslat.smartbrainlib.api.core.sensor.ExtendedSensor<out NpcEntity>> =
        listOf(com.sbwnpc.squad.entity.ai.SquadTargetSensor())
    // Core: always ticks regardless of the current Fight/Idle activity — matches how the goals
    // they replace ran too (door interaction and cover-seeking never competed for GoalSelector's
    // Flag.MOVE with anything, so they always ran; the mortar crew goals reserved Flag.MOVE at
    // priority 1, the highest of any goal, so they always won it too — same effective "always on"
    // outcome, just achieved differently). InteractWithDoor is a genuine upgrade over the old
    // OpenDoorGoal, not just a port — it also holds a door open for OTHER squad members mid-transit.
    override fun getCoreTasks(): net.tslat.smartbrainlib.api.core.BrainActivityGroup<NpcEntity> =
        net.tslat.smartbrainlib.api.core.BrainActivityGroup.coreTasks(
            net.tslat.smartbrainlib.api.core.behaviour.custom.move.InteractWithDoor<NpcEntity>(),
            SeekCoverBehaviour(),
            MortarOperatorBehaviour(),
            MortarLoaderBehaviour(),
            com.sbwnpc.squad.entity.ai.DroneOperatorBehaviour(),
            com.sbwnpc.squad.entity.ai.AntiDroneBehaviour(),
            VehicleCrewBehaviour(),
            com.sbwnpc.squad.entity.ai.HelicopterPilotBehaviour(),
            com.sbwnpc.squad.entity.ai.HelicopterGunnerBehaviour(),
            com.sbwnpc.squad.entity.ai.HelicopterRideBehaviour(),
            VehicleCombatSupportBehaviour(),
            MedicHealBehaviour(),
            // Last: it has to override whatever the behaviours above did with the navigation.
            com.sbwnpc.squad.entity.ai.GrenadeEvadeBehaviour()
        )
    // Idle: only relevant while there's no ATTACK_TARGET (Fight always outranks Idle). Order here
    // doesn't change behaviour — InvestigateBehaviour's and SquadOrderBehaviour's own eligibility
    // checks already exclude each other's cases (see each class's doc comment), reproducing the
    // old goal-priority order (Investigate=4 beat SquadOrder=5) by hand since Idle behaviours have
    // no automatic per-Flag exclusivity like GoalSelector did.
    // VehicleTransportBehaviour sits between them: eligible under the same base conditions as
    // SquadOrderBehaviour (no target, not alert, has a squad+home) plus "home is far enough to
    // drive instead of walk" — so it wins over SquadOrderBehaviour whenever both would otherwise
    // apply, and steps aside for SquadOrderBehaviour once close enough / after arrival.
    override fun getIdleTasks(): net.tslat.smartbrainlib.api.core.BrainActivityGroup<NpcEntity> =
        net.tslat.smartbrainlib.api.core.BrainActivityGroup.idleTasks(
            InvestigateBehaviour(), com.sbwnpc.squad.entity.ai.VehicleTransportBehaviour(), SquadOrderBehaviour()
        )
    // Fight: only while ATTACK_TARGET is set, all running side by side. AnimatableMeleeAttack is
    // SmartBrainLib's own melee behaviour and only strikes what is already in reach — it does no
    // chasing: every class carries a gun, and GunAttackBehaviour closes the distance itself.
    override fun getFightTasks(): net.tslat.smartbrainlib.api.core.BrainActivityGroup<NpcEntity> =
        net.tslat.smartbrainlib.api.core.BrainActivityGroup.fightTasks(
            com.sbwnpc.squad.entity.ai.GunAttackBehaviour(),
            // Gun empty: run from the target rather than stand facing it (GunAttack stands down).
            com.sbwnpc.squad.entity.ai.OutOfAmmoBehaviour(),
            net.tslat.smartbrainlib.api.core.behaviour.custom.attack.AnimatableMeleeAttack<NpcEntity>(20),
            GrenadeThrowBehaviour(),
            com.sbwnpc.squad.entity.ai.GrenadeUseBehaviour()
        )

    // Also used by GrenadeUseBehaviour, hence internal not private.
    internal fun isEnemy(other: LivingEntity): Boolean {
        if (other !is NpcEntity && other !is Player) return false
        if (other is Player && (other.isCreative || other.isSpectator)) return false
        return SquadTeams.isHostile(this, other)
    }

    // Squad members are always placed deliberately (spawn egg, deployer, recruitment) — never
    // ambient wildlife — so they must not despawn when the nearest player wanders off or dies and
    // respawns far away. Same as iron golems / tamed pets.
    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    // Drawn as far as the server sends them (SquadConfig.npcViewDistance, applied to tracking by
    // EntityTrackingRangeMixin).
    // Vanilla sizes this by the hitbox — 64 x the box's average side x the player's entity-distance
    // setting — which for a man-sized box came to about 70-85 blocks, so NPCs the client had been
    // sent were not drawn at all past that.
    override fun shouldRenderAtSqrDistance(distance: Double): Boolean =
        distance < com.sbwnpc.squad.config.SquadConfig.npcViewDistance().let { it.toDouble() * it }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun finalizeSpawn(
        level: ServerLevelAccessor,
        difficulty: DifficultyInstance,
        spawnType: MobSpawnType,
        spawnGroupData: SpawnGroupData?
    ): SpawnGroupData? {
        applyRole()
        spawnFaction?.let { SquadTeams.assign(this, it) }
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData)
    }

    /** (Re)applies health from rank and equips the class weapon with a loaded mag + reserve. */
    fun applyRole() {
        val healthAttr = getAttribute(Attributes.MAX_HEALTH)
        if (healthAttr != null) {
            healthAttr.baseValue = BASE_HEALTH * npcRank.healthMultiplier
            health = maxHealth
        }

        // Per-class multiplier on top of the shared base — createAttributes() only sets the raw
        // base (BASE_SPEED) since it runs once at entity-type registration, before npcClass is even
        // known; this is the real value, same pattern as MAX_HEALTH above.
        getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = BASE_SPEED * npcClass.speedMultiplier

        // One of 2 weapons per weapon category, picked once and kept for this NPC's whole life —
        // pure visual variety across NPCs of the same class, per user request ("разношерстные").
        val weaponId = npcClass.weaponPool[random.nextInt(npcClass.weaponPool.size)]
        val gun = Ports.guns.issue(weaponId, this, npcClass.startingRounds(weaponId))
        if (!gun.isEmpty) setItemInHand(InteractionHand.MAIN_HAND, gun)

        // Green (RU) kit for CREEPER/CAT/PIG/COW, sand (US) kit for the other 4 factions — per user
        // request, applies to every class without exception. spawnFaction (not
        // SquadTeams.factionOf(this)) because finalizeSpawn() calls applyRole() BEFORE assigning the
        // scoreboard team that factionOf() reads from — see finalizeSpawn().
        val faction = spawnFaction ?: SquadFaction.DEFAULT
        val (helmet, chest) = Ports.gear.uniform(green = faction in GREEN_KIT_FACTIONS)
        setItemSlot(EquipmentSlot.HEAD, helmet)
        setItemSlot(EquipmentSlot.CHEST, chest)

        // Two RGN and two RGO per fighter, crews excepted (they aren't a combat-suppression role)
        // — per user request. Counted, NOT visible offhand items — user feedback: holding a
        // physical grenade in the offhand looked wrong (both hands full). Thrown by
        // GrenadeUseBehaviour, the grenadier's GrenadeThrowBehaviour and SeekCoverBehaviour's
        // post-dig throw, all from these same counts.
        rgnLeft = if (carriesGrenades()) GRENADES_OF_EACH else 0
        rgoLeft = rgnLeft
        if (npcClass == NpcClass.DRONE_OPERATOR) dronesLeft = com.sbwnpc.squad.entity.ai.DroneOperatorBehaviour.MAX_DRONES

        // A belt-fed gun does nothing to a tank, and the machine gunner is the one member of an
        // ordinary squad with a free hand for something that does.
        if (npcClass == NpcClass.MACHINE_GUNNER) {
            antiArmourWeapon = com.sbwnpc.squad.combat.AntiArmourKit.issue(this)
        }
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        super.addAdditionalSaveData(compound)
        compound.putString("NpcClass", npcClass.name)
        compound.putString("NpcRank", npcRank.name)
        squadId?.let { compound.putUUID("SquadId", it) }
        assignedVehicleId?.let { compound.putUUID("AssignedVehicle", it) }
        compound.putInt("Rgn", rgnLeft)
        compound.putInt("Rgo", rgoLeft)
        if (carryingMortar) compound.putBoolean("CarryingMortar", true)
        compound.putInt("DronesLeft", dronesLeft)
        if (!stowedWeapon.isEmpty) compound.put("StowedWeapon", stowedWeapon.save(registryAccess()))
        if (!antiArmourWeapon.isEmpty) compound.put("AntiArmourWeapon", antiArmourWeapon.save(registryAccess()))
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        super.readAdditionalSaveData(compound)
        // Saved attributes carry the old base; NPCs from before the change swim at the new pace too.
        getAttribute(net.neoforged.neoforge.common.NeoForgeMod.SWIM_SPEED)?.baseValue = SWIM_SPEED
        runCatching { npcClass = NpcClass.valueOf(compound.getString("NpcClass")) }
        runCatching { npcRank = NpcRank.valueOf(compound.getString("NpcRank")) }
        squadId = if (compound.hasUUID("SquadId")) compound.getUUID("SquadId") else null
        assignedVehicleId = if (compound.hasUUID("AssignedVehicle")) compound.getUUID("AssignedVehicle") else null
        // Saved before grenades were counted: a full set, as for anyone recruited now.
        rgnLeft = if (compound.contains("Rgn")) compound.getInt("Rgn") else if (carriesGrenades()) GRENADES_OF_EACH else 0
        rgoLeft = if (compound.contains("Rgo")) compound.getInt("Rgo") else if (carriesGrenades()) GRENADES_OF_EACH else 0
        carryingMortar = compound.getBoolean("CarryingMortar")
        dronesLeft = if (compound.contains("DronesLeft")) compound.getInt("DronesLeft")
            else if (npcClass == NpcClass.DRONE_OPERATOR) com.sbwnpc.squad.entity.ai.DroneOperatorBehaviour.MAX_DRONES else 0
        stowedWeapon = if (compound.contains("StowedWeapon"))
            ItemStack.parseOptional(registryAccess(), compound.getCompound("StowedWeapon")) else ItemStack.EMPTY
        antiArmourWeapon = if (compound.contains("AntiArmourWeapon"))
            ItemStack.parseOptional(registryAccess(), compound.getCompound("AntiArmourWeapon")) else ItemStack.EMPTY
        // Saved mid-flight: the drone itself doesn't survive the reload as "ours" (the behaviour's
        // state is transient), so just give the gun back right away.
        if (!stowedWeapon.isEmpty) {
            setItemInHand(InteractionHand.MAIN_HAND, stowedWeapon)
            stowedWeapon = ItemStack.EMPTY
        }
    }

    override fun die(cause: net.minecraft.world.damagesource.DamageSource) {
        (level() as? ServerLevel)?.let { SquadManager.get(it).removeMemberEverywhere(uuid) }
        alertAllies(cause)
        MortarClaims.release(uuid)
        com.sbwnpc.squad.combat.FiringSpots.release(uuid)
        VehicleTransportClaims.release(uuid)
        // Carried kit goes down where its carrier did, rather than out of the world with it.
        (level() as? ServerLevel)?.let { com.sbwnpc.squad.entity.ai.MortarDeployment.dropOnDeath(it, this) }
        vehicle?.takeIf { Ports.vehicles.isVehicle(it) }?.let { ride ->
            VehicleTransportBehaviour.releaseVehicleTeamIfLastAboard(ride, this)
            // Flying it when it died: start the countdown for somebody else to take the controls.
            if (ride.firstPassenger === this) {
                (level() as? ServerLevel)?.let { com.sbwnpc.squad.vehicle.PilotlessHelicopters.pilotDown(it, ride) }
            }
        }
        // Operator down -> signal lost: its drone crashes where it is (DroneOperatorBehaviour.stop
        // would do this too once the brain notices the death, but the entity may already be gone
        // from the level by then). Drop the gun, not the monitor, as loot.
        com.sbwnpc.squad.entity.ai.DroneOperatorBehaviour.onOperatorDied(this)
        super.die(cause)
    }

    /**
     * Switches vanilla's own equipment drop off, for every NPC including ones loaded from a save
     * that predates [dropCustomDeathLoot] below — which is why this is an override rather than a
     * `setDropChance` call in [applyRole], which only ever runs at spawn.
     */
    override fun getEquipmentDropChance(slot: EquipmentSlot): Float =
        if (slot in LOOTABLE_SLOTS) 0f else super.getEquipmentDropChance(slot)

    /**
     * Loot: the weapon in hand drops with [WEAPON_DROP_CHANCE], each piece of armour with
     * [ARMOUR_DROP_CHANCE], rolled per slot, and drops exactly as it was carried — a gun keeps the
     * ammo in its magazine. Whoever or whatever did the killing: an NPC's kill leaves the same
     * loot as a player's.
     *
     * Rolled here rather than through vanilla's per-slot drop chance because vanilla damages
     * whatever it drops when the chance is below 1.0, which would hand the player a near-broken
     * rifle.
     */
    override fun dropCustomDeathLoot(level: ServerLevel, damageSource: DamageSource, recentlyHit: Boolean) {
        super.dropCustomDeathLoot(level, damageSource, recentlyHit)
        val rolls = ArrayList<String>(LOOTABLE_SLOTS.size)
        for (slot in LOOTABLE_SLOTS) {
            val stack = getItemBySlot(slot)
            val chance = if (slot == EquipmentSlot.MAINHAND) WEAPON_DROP_CHANCE else ARMOUR_DROP_CHANCE
            val roll = random.nextFloat()
            rolls += "$slot=${if (stack.isEmpty) "empty" else "%.2f/%.2f".format(roll, chance)}"
            if (stack.isEmpty || roll >= chance) continue
            val dropped = spawnAtLocation(stack.copy())
            rolls[rolls.size - 1] += if (dropped != null) " DROPPED" else " (spawn failed)"
            setItemSlot(slot, ItemStack.EMPTY)
        }
        com.sbwnpc.squad.combat.DebugFlags.log(
            "[loot-debug] {} ({}) killed by {} via {} (playerHit={}): {}",
            uuid, npcClass, damageSource.entity?.let { it as? NpcEntity }?.let { "NPC ${it.npcClass}" } ?: damageSource.entity?.name?.string,
            damageSource.msgId, recentlyHit, rolls.joinToString(", ")
        )
    }

    /** A squadmate going down is itself an "invariant" every shooter-AI convention treats as a
     *  strong signal (F.E.A.R./Half-Life-style squad escalation on a downed ally). When the killer
     *  is resolvable, this is strictly better than a vague alert — feed it into [TeamAwareness] so
     *  the infantry can act on it after the normal relay delay. Not as a sighting, though: nobody
     *  saw him, and fire support only shoots at what someone has seen. Only when the killer can't
     *  be resolved (fell, environmental, whatever) does this fall back to a plain [Alarm] at the
     *  death position. */
    private fun alertAllies(cause: net.minecraft.world.damagesource.DamageSource) {
        val faction = com.sbwnpc.squad.team.SquadTeams.factionOf(this) ?: return
        val level = level() as? ServerLevel ?: return
        val attacker = cause.entity as? LivingEntity
        if (attacker != null && attacker.isAlive && com.sbwnpc.squad.team.SquadTeams.isHostile(this, attacker)) {
            com.sbwnpc.squad.combat.TeamAwareness.reportUnseen(faction, attacker.uuid, level.gameTime)
        } else {
            com.sbwnpc.squad.combat.Alarm.raiseDeath(this, DEATH_ALARM_RADIUS)
        }
    }

    companion object {
        /** Chances that a piece of an NPC's kit survives its death — see [dropCustomDeathLoot]. */
        private const val WEAPON_DROP_CHANCE = 0.15f
        private const val ARMOUR_DROP_CHANCE = 0.05f
        private val LOOTABLE_SLOTS = listOf(EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST)
        private const val BASE_HEALTH = 20.0
        private const val GRENADES_OF_EACH = 2
        private const val SWIM_SPEED = 2.0
        private const val VEHICLE_ATTACKER_MEMORY_TICKS = 200
        // internal (not private) — MedicHealBehaviour reuses this to compute its temporary
        // "sprinting to treat someone" speed on the same BASE_SPEED*multiplier basis as applyRole(),
        // instead of hardcoding 0.25 a second time.
        internal const val BASE_SPEED = 0.25
        private const val SUPPRESSION_DURATION_TICKS = 100
        private const val SUPPRESSION_CAP_TICKS = 200
        private const val ALERT_DURATION_TICKS = 200 // ~10s to reach/abandon an investigation lead
        private const val DEATH_ALARM_RADIUS = 36.0 // detection range, x1.5 per user request (was 24)
        // How far an NPC can spot / act on relayed contacts — see the FOLLOW_RANGE note in
        // createAttributes() for why this is a constant of its own rather than that attribute.
        const val DETECTION_RANGE = 72.0

        // AI level-of-detail — see refreshAiLod(). Player distances at which the brain drops to
        // every-2nd / every-4th tick when not in combat.
        private const val LOD_RECHECK_TICKS = 20

        /** A player's crouch, scaled to the NPC's height; the eyes clear a one-block wall. */
        private val CROUCHING_DIMENSIONS: EntityDimensions = EntityDimensions.scalable(0.6f, 1.55f).withEyeHeight(1.35f)
        private const val STILL_SPEED_SQR = 0.0025
        private const val CROUCH_AFTER_TICKS = 10
        private const val SIGHT_LOST_STAND_TICKS = 10
        private const val STAND_TO_SHOOT_TICKS = 100

        private const val STUCK_CHECK_TICKS = 10
        private const val STUCK_MOVE_SQR = 0.15 * 0.15
        /** Stood still this long with a path before it clears the way. */
        private const val STUCK_BREAK_TICKS = 30
        /** A path that goes nowhere counts only while they keep coming. */
        private const val NOWHERE_FRESH_TICKS = 40
        /** Anything a hand clears quickly: leaves 0.2, glass 0.3, sand and dirt 0.5, gravel and grass
         *  0.6. Stone (1.5) and planks (2) stay. */
        private const val SOFT_BLOCK_HARDNESS = 0.6f
        private const val DISMOUNT_CLEARANCE = 0.3
        private const val DISMOUNT_SAMPLE_STEP = 0.125
        private const val LOD_NEAR_RANGE = 96.0
        private const val LOD_FAR_RANGE = 192.0
        private const val IDLE_PATH_NODE_MULTIPLIER = 0.5f
        // See navigateTo().
        const val REPATH_INTERVAL_TICKS = 10

        // Green (RU 6B47/6B43) vs sand (US PASGT/IOTV) armor kit — see applyRole(). Verified against
        // the real SBW source, not guessed: both textures inspected directly (RU = green camo, US =
        // tan/sand camo).
        private val GREEN_KIT_FACTIONS = setOf(SquadFaction.CREEPER, SquadFaction.CAT, SquadFaction.PIG, SquadFaction.COW)

        @JvmField
        val DATA_CLASS: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(NpcEntity::class.java, EntityDataSerializers.INT)

        @JvmField
        val DATA_RANK: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(NpcEntity::class.java, EntityDataSerializers.INT)

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder {
            return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, BASE_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, BASE_SPEED) // real per-class value is set in applyRole() — npcClass isn't known yet here

                .add(Attributes.ATTACK_DAMAGE, 2.0)
                .add(Attributes.ARMOR, 2.0)
                // NOT the detection range any more — that's DETECTION_RANGE (still 72, x1.5 per
                // user request), read by SquadTargetSensor directly. This attribute also sizes the
                // vanilla pathfinder at construction (maxVisitedNodes = FOLLOW_RANGE * 16, so 72 was
                // 1152 A* nodes per path search vs 768 at 48) and caps createPath's max distance;
                // neither needs to grow with detection range, and both scale every repath every
                // NPC makes. Back to vanilla-ish 48 for the pathing side only.
                .add(Attributes.FOLLOW_RANGE, 48.0)
                // Twice the usual pace in water, per user call — a squad fording a river crawled.
                .add(net.neoforged.neoforge.common.NeoForgeMod.SWIM_SPEED, SWIM_SPEED)
        }
    }
}
