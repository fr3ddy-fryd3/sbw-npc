package com.sbwnpc.squad.entity

import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.sbwnpc.squad.entity.ai.GrenadeThrowGoal
import com.sbwnpc.squad.entity.ai.InvestigateGoal
import com.sbwnpc.squad.entity.ai.MortarClaims
import com.sbwnpc.squad.entity.ai.MortarLoaderGoal
import com.sbwnpc.squad.entity.ai.MortarOperatorGoal
import com.sbwnpc.squad.entity.ai.NpcGunAttackGoal
import com.sbwnpc.squad.entity.ai.SeekCoverGoal
import com.sbwnpc.squad.entity.ai.SquadOrderGoal
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import java.util.UUID
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor

/**
 * Base squad-member entity. Role (class + rank) drives the loadout and combat tuning. Friend/foe
 * is by squad faction == vanilla scoreboard team (see [SquadTeams]); no team on either side means
 * neutral. The faction also picks the NPC's skin ([com.sbwnpc.squad.client.renderer.NpcRenderer]).
 *
 * MIGRATION IN PROGRESS to SmartBrainLib (see SMARTBRAIN_MIGRATION_PLAN.md, gitignored working
 * doc) — [SmartBrainOwner] is now implemented, but only a subset of behaviour has actually moved
 * over yet; everything not yet migrated still runs as an ordinary [net.minecraft.world.entity.ai.goal.Goal]
 * in [registerGoals], reading `mob.target` exactly as before. The new Brain-side target sensor (once
 * added) writes both stores via `BrainUtil.setTargetOfEntity` specifically so those not-yet-migrated
 * goals keep working unmodified during the transition — this is a deliberate, temporary bridge, not
 * a permanent design.
 */
open class NpcEntity(type: EntityType<out NpcEntity>, level: Level) :
    PathfinderMob(type, level), net.tslat.smartbrainlib.api.SmartBrainOwner<NpcEntity> {

    var npcClass: NpcClass
        get() = NpcClass.byOrdinal(entityData.get(DATA_CLASS))
        set(value) = entityData.set(DATA_CLASS, value.ordinal)

    var npcRank: NpcRank
        get() = NpcRank.byOrdinal(entityData.get(DATA_RANK))
        set(value) = entityData.set(DATA_RANK, value.ordinal)

    /** Faction to put the NPC on its scoreboard team; set before finalizeSpawn. null = leave unteamed. */
    var spawnFaction: SquadFaction? = null

    /** Command group this NPC belongs to, if any. Server-side; persisted. */
    var squadId: UUID? = null

    // Suppression (see SeekCoverGoal): a temporary "duck and hold" state triggered by taking
    // ranged damage (below, hurt()) or a nearby explosion (SuppressionEvents). Not persisted —
    // always fine to reset to "not suppressed" on reload, it's a momentary combat reaction.
    var suppressedUntilTick: Int = 0
        private set
    var threatPos: Vec3? = null
        private set

    fun isSuppressed(): Boolean = tickCount < suppressedUntilTick

    /** Each trigger extends the timer (doesn't stack duration), capped so sustained fire doesn't
     *  grant an indefinite "immune to squad orders" state. */
    fun suppress(threat: Vec3) {
        val extended = tickCount + SUPPRESSION_DURATION_TICKS
        val cap = tickCount + SUPPRESSION_CAP_TICKS
        suppressedUntilTick = maxOf(suppressedUntilTick, extended).coerceAtMost(cap)
        threatPos = threat
        // TEMPORARY diagnostic (see PHASE5_PLAN.md "Диагностика укрытий перед фиксом") — remove
        // once an in-game test confirms whether suppress() even fires reliably and whether
        // SeekCoverGoal actually reaches IN_COVER/PEEKING, or whether what's visible is really
        // FriendlyFireGuard.sidestepAwayFromAllies instead.
        com.sbwnpc.squad.SquadMod.LOGGER.info(
            "[cover-debug] {} suppressed at tick {} by threat near {}", uuid, tickCount, threat
        )
    }

    // Alertness (see AlertGoal / Alarm): a real "heard something, go check it out" reaction,
    // distinct from actually having a target. Two sources — NpcGunAttackGoal.tick() raises this on
    // nearby allies whenever it fires (heard gunfire), and die() raises it on nearby squadmates when
    // the killer can't be resolved as a direct TeamAwareness contact (see die() below). Not
    // persisted — momentary, like suppression.
    var alertUntilTick: Int = 0
        private set
    var alertPos: Vec3? = null
        private set

    fun isAlert(): Boolean = tickCount < alertUntilTick && alertPos != null

    fun alert(pos: Vec3) {
        alertUntilTick = maxOf(alertUntilTick, tickCount + ALERT_DURATION_TICKS)
        alertPos = pos
    }

    /** Called by [com.sbwnpc.squad.entity.ai.InvestigateGoal] once it reaches the alert position (or
     *  gives up navigating to it) — ends the investigation instead of waiting out the full timer. */
    fun clearAlert() {
        alertUntilTick = 0
    }

    /** Cover-seeking state machine driven entirely by [com.sbwnpc.squad.entity.ai.SeekCoverGoal] —
     *  lives here (like suppression above) rather than inside the goal so combat goals can read it
     *  without needing a reference to the goal instance. */
    enum class CoverPhase { NONE, MOVING_TO_COVER, IN_COVER, PEEKING, RETURNING_TO_COVER }

    var coverPhase: CoverPhase = CoverPhase.NONE
        set(value) {
            // TEMPORARY diagnostic, same reason as suppress() above — remove once confirmed.
            if (value != field) {
                com.sbwnpc.squad.SquadMod.LOGGER.info("[cover-debug] {} coverPhase {} -> {}", uuid, field, value)
            }
            field = value
        }

    /** True while SeekCoverGoal must have the mob to itself for movement and combat goals should
     *  stand down entirely — false during [CoverPhase.PEEKING], the deliberate window where the
     *  mob steps out to return fire and NpcGunAttackGoal/GrenadeThrowGoal take back over. */
    fun combatLockedByCover(): Boolean = coverPhase == CoverPhase.MOVING_TO_COVER ||
        coverPhase == CoverPhase.IN_COVER || coverPhase == CoverPhase.RETURNING_TO_COVER

    override fun hurt(source: DamageSource, amount: Float): Boolean {
        val result = super.hurt(source, amount)
        // NOT vanilla's DamageTypeTags.IS_PROJECTILE — SBW's gunfire damage types (GUN_FIRE,
        // GUN_FIRE_HEADSHOT, the ones actually dealt by every rifle/MG/sniper hit) are never
        // members of that vanilla tag. SBW tags them under its OWN
        // ModTags.DamageTypes.PROJECTILE instead — using the vanilla tag here meant this branch
        // was silently dead for ordinary gunfire and suppression only ever came from explosions.
        if (result && !level().isClientSide && source.`is`(com.atsuishio.superbwarfare.init.ModTags.DamageTypes.PROJECTILE)) {
            suppress(source.sourcePosition ?: position())
        }
        return result
    }

    fun currentSquad(): Squad? {
        val id = squadId ?: return null
        val lvl = level() as? ServerLevel ?: return null
        return SquadManager.get(lvl).get(id)
    }

    /** Where this NPC "belongs" per its squad: the guarded entity, else the objective point. */
    fun homeCenter(): Vec3? {
        val squad = currentSquad() ?: return null
        squad.focusEntity?.let { fid ->
            (level() as? ServerLevel)?.getEntity(fid)?.takeIf { it.isAlive }?.let { return it.position() }
        }
        return squad.objective?.let { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_CLASS, NpcClass.DEFAULT.ordinal)
        builder.define(DATA_RANK, NpcRank.DEFAULT.ordinal)
    }

    override fun registerGoals() {
        super.registerGoals()
        this.goalSelector.addGoal(0, FloatGoal(this))
        this.goalSelector.addGoal(1, NpcGunAttackGoal(this))
        this.goalSelector.addGoal(1, MortarOperatorGoal(this))
        this.goalSelector.addGoal(1, MortarLoaderGoal(this))
        this.goalSelector.addGoal(2, MeleeAttackGoal(this, 1.2, false))
        this.goalSelector.addGoal(2, GrenadeThrowGoal(this))
        // Door opening moved to SmartBrainLib's InteractWithDoor (see getCoreTasks()) — migration
        // step 3. It's actually a step up, not just a port: it also holds a door open for OTHER
        // squad members mid-transit, which the old vanilla-style OpenDoorGoal never did.
        // Below melee self-defense (2) — an enemy in your face still gets fought, not fled from —
        // but above squad-order positioning (4), so suppression interrupts holding/patrolling.
        this.goalSelector.addGoal(3, SeekCoverGoal(this))
        // Above squad-order positioning (5) — "go check that out" wins over routine patrol/hold
        // while there's nothing to actually shoot at yet, same as a real soldier breaking formation
        // briefly to investigate nearby gunfire or a downed squadmate.
        this.goalSelector.addGoal(4, InvestigateGoal(this))
        this.goalSelector.addGoal(5, SquadOrderGoal(this))
        this.goalSelector.addGoal(6, RandomLookAroundGoal(this))
        this.goalSelector.addGoal(7, WaterAvoidingRandomStrollGoal(this, 0.8))

        // Target acquisition moved to SquadTargetSensor (see getSensors()) — migration step 4.
        // Replaces SquadFocusTargetGoal, HurtByTargetGoal, SquadAwarenessTargetGoal, and
        // NearestAttackableTargetGoal with one sensor evaluating the same priority chain in one
        // place. It bridges to mob.target via BrainUtils.setTargetOfEntity so every not-yet-migrated
        // goal above keeps working unmodified.
    }

    // --- SmartBrainOwner: step 2 of the migration (skeleton only) ---
    // The actual published 1.16.11 jar's API does NOT match SmartBrainLib's git `master` branch
    // (confirmed by decompiling the real dependency with javap, not trusting the cloned source) —
    // no auto-wiring mixin exists in this version, so brainProvider()/tickBrain() are wired by hand
    // below. Task groups are BrainActivityGroup (not raw Lists) in this version; getSensors() is the
    // only abstract member, the three task-group getters have library defaults (empty groups) that
    // are overridden here just so tasks 3-5 have an obvious place to fill in one subsystem at a time.
    override fun brainProvider(): net.minecraft.world.entity.ai.Brain.Provider<NpcEntity> =
        net.tslat.smartbrainlib.api.core.SmartBrainProvider(this)

    override fun customServerAiStep() {
        super.customServerAiStep()
        tickBrain(this)
    }

    // Step 4: target acquisition. See registerGoals() above for what this replaces.
    override fun getSensors(): List<net.tslat.smartbrainlib.api.core.sensor.ExtendedSensor<out NpcEntity>> =
        listOf(com.sbwnpc.squad.entity.ai.SquadTargetSensor())
    // Step 3: door interaction. Real behavioural upgrade, not just a port — see registerGoals().
    override fun getCoreTasks(): net.tslat.smartbrainlib.api.core.BrainActivityGroup<NpcEntity> =
        net.tslat.smartbrainlib.api.core.BrainActivityGroup.coreTasks(
            net.tslat.smartbrainlib.api.core.behaviour.custom.move.InteractWithDoor<NpcEntity>()
        )
    override fun getIdleTasks(): net.tslat.smartbrainlib.api.core.BrainActivityGroup<NpcEntity> =
        net.tslat.smartbrainlib.api.core.BrainActivityGroup.empty()
    override fun getFightTasks(): net.tslat.smartbrainlib.api.core.BrainActivityGroup<NpcEntity> =
        net.tslat.smartbrainlib.api.core.BrainActivityGroup.empty()

    // Used by SquadTargetSensor (step 4 of the SmartBrain migration) too, hence internal not private.
    internal fun isEnemy(other: LivingEntity): Boolean {
        if (other !is NpcEntity && other !is Player) return false
        if (other is Player && (other.isCreative || other.isSpectator)) return false
        return SquadTeams.isHostile(this, other)
    }

    // Squad members are always placed deliberately (spawn egg, deployer, recruitment) — never
    // ambient wildlife — so they must not despawn when the nearest player wanders off or dies and
    // respawns far away. Same as iron golems / tamed pets.
    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

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

        val gunItem = BuiltInRegistries.ITEM.getOptional(npcClass.weaponId).orElse(Items.AIR)
        if (gunItem is GunItem) {
            val gunData = GunData.from(ItemStack(gunItem))
            gunData.virtualAmmo.set(120)
            gunData.reloadAmmo(this)
            gunData.save()
            setItemInHand(InteractionHand.MAIN_HAND, gunData.stack)
        }
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        super.addAdditionalSaveData(compound)
        compound.putString("NpcClass", npcClass.name)
        compound.putString("NpcRank", npcRank.name)
        squadId?.let { compound.putUUID("SquadId", it) }
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        super.readAdditionalSaveData(compound)
        runCatching { npcClass = NpcClass.valueOf(compound.getString("NpcClass")) }
        runCatching { npcRank = NpcRank.valueOf(compound.getString("NpcRank")) }
        squadId = if (compound.hasUUID("SquadId")) compound.getUUID("SquadId") else null
    }

    override fun die(cause: net.minecraft.world.damagesource.DamageSource) {
        (level() as? ServerLevel)?.let { SquadManager.get(it).removeMemberEverywhere(uuid) }
        alertAllies(cause)
        MortarClaims.release(uuid)
        super.die(cause)
    }

    /** A squadmate going down is itself an "invariant" every shooter-AI convention treats as a
     *  strong signal (F.E.A.R./Half-Life-style squad escalation on a downed ally). When the killer
     *  is resolvable, this is strictly better than a vague alert — feed it straight into
     *  [TeamAwareness] as if someone had just spotted it directly, so [SquadAwarenessTargetGoal]
     *  can act on it after the normal relay delay. Only when the killer can't be resolved (fell,
     *  environmental, whatever) does this fall back to a plain [Alarm] at the death position. */
    private fun alertAllies(cause: net.minecraft.world.damagesource.DamageSource) {
        val faction = com.sbwnpc.squad.team.SquadTeams.factionOf(this) ?: return
        if (level() !is ServerLevel) return
        val attacker = cause.entity as? LivingEntity
        if (attacker != null && attacker.isAlive && com.sbwnpc.squad.team.SquadTeams.isHostile(this, attacker)) {
            com.sbwnpc.squad.combat.TeamAwareness.report(faction, attacker.uuid, tickCount.toLong())
        } else {
            com.sbwnpc.squad.combat.Alarm.raise(this, position(), position(), DEATH_ALARM_RADIUS)
        }
    }

    companion object {
        private const val BASE_HEALTH = 20.0
        private const val SUPPRESSION_DURATION_TICKS = 100
        private const val SUPPRESSION_CAP_TICKS = 200
        private const val ALERT_DURATION_TICKS = 200 // ~10s to reach/abandon an investigation lead
        private const val DEATH_ALARM_RADIUS = 36.0 // detection range, x1.5 per user request (was 24)

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
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 2.0)
                .add(Attributes.ARMOR, 2.0)
                .add(Attributes.FOLLOW_RANGE, 72.0) // detection range, x1.5 per user request (was 48)
        }
    }
}
