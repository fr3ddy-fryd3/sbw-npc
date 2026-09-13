package com.sbwnpc.squad.entity

import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.sbwnpc.squad.entity.ai.GrenadeThrowBehaviour
import com.sbwnpc.squad.entity.ai.IdleLookAroundGoal
import com.sbwnpc.squad.entity.ai.InvestigateBehaviour
import com.sbwnpc.squad.entity.ai.MedicHealBehaviour
import com.sbwnpc.squad.entity.ai.MortarClaims
import com.sbwnpc.squad.entity.ai.MortarLoaderBehaviour
import com.sbwnpc.squad.entity.ai.MortarOperatorBehaviour
import com.sbwnpc.squad.entity.ai.SeekCoverBehaviour
import com.sbwnpc.squad.entity.ai.SquadOrderBehaviour
import com.sbwnpc.squad.init.ModMemories
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
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.tslat.smartbrainlib.util.BrainUtils
import net.minecraft.world.level.ServerLevelAccessor

/**
 * Base squad-member entity. Role (class + rank) drives the loadout and combat tuning. Friend/foe
 * is by squad faction == vanilla scoreboard team (see [SquadTeams]); no team on either side means
 * neutral. The faction also picks the NPC's skin ([com.sbwnpc.squad.client.renderer.NpcRenderer]).
 *
 * MIGRATION TO SmartBrainLib — complete. [registerGoals] only registers three trivial, NPC-agnostic
 * vanilla utility goals now (float/swim, random look, random wander), none of which were part of the
 * migration's task list — no risk in leaving those as ordinary Goals indefinitely. Every subsystem
 * that reads/writes combat state (targeting, gun combat, melee, grenades, mortar, cover/suppression,
 * alarm/investigate, squad formations/patrol) is Brain-side. One exception: the random-look goal is
 * [IdleLookAroundGoal], not vanilla's bare `RandomLookAroundGoal` — see that class's doc comment for
 * why it DOES need to check combat state (`mob.target`) despite the above, to fix a real bug
 * (rendered head direction fighting `GunAttackBehaviour` for control mid-combat).
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

    // Suppression (see SeekCoverBehaviour): a temporary "duck and hold" state triggered by taking
    // ranged damage (below, hurt()) or a nearby explosion (SuppressionEvents). Backed by a
    // SmartBrainLib TTL memory (SmartBrain migration step 6) instead of a hand-rolled
    // suppressedUntilTick/threatPos pair — expires on its own, no manual tick comparison, and
    // visible via the brain's own memory (e.g. /data get entity <e> Brain) instead of debug logs.
    fun isSuppressed(): Boolean = BrainUtils.hasMemory(this, ModMemories.SUPPRESSING_THREAT.get())
    val threatPos: Vec3? get() = BrainUtils.getMemory(this, ModMemories.SUPPRESSING_THREAT.get())

    /** Each trigger extends the timer (doesn't stack duration), capped so sustained fire doesn't
     *  grant an indefinite "immune to squad orders" state — same cap logic as before, just computed
     *  against the memory's own remaining TTL instead of a local field. */
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
    // below). Backed by a SmartBrainLib TTL memory (SmartBrain migration step 7) instead of a
    // hand-rolled alertUntilTick/alertPos pair.
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
    fun combatLockedByCover(): Boolean = BrainUtils.hasMemory(this, ModMemories.COVER_HOLD.get())

    fun combatLockedByMedic(): Boolean = BrainUtils.hasMemory(this, ModMemories.MEDIC_HEALING.get())

    // Set by GunAttackBehaviour every time it actually fires (not just "has a target" — genuinely
    // pulled the trigger this tick). Used by SeekCoverBehaviour to verify an ally is really
    // providing covering fire before digging in, rather than just inferring it from having a
    // target and line of sight. internal (not private) for the same cross-file reason isEnemy() is.
    var lastShotTick: Int = Int.MIN_VALUE / 2
        internal set

    fun firedRecently(withinTicks: Int): Boolean = tickCount - lastShotTick <= withinTicks

    // Single-use "parting shot" grenade, tracked as a plain flag rather than a visible held item —
    // see applyRole() for why. Consumed (set false) by SeekCoverBehaviour.maybeThrowGrenadeOnceDugIn.
    var hasReserveGrenade: Boolean = false

    // Set/cleared only by SeekCoverBehaviour (enterDugInHolding/stop) while the mob is holding a
    // foxhole it dug for itself. Read by GunAttackBehaviour to skip ALL repositioning (formation
    // advance, bounding, friendly-fire sidestep) while still aiming and firing normally — a dug-in
    // mob fights from the hole rather than leaving it, per explicit user decision (see
    // SeekCoverBehaviour.enterDugInHolding's doc comment for the fuller reasoning/history).
    var diggedIn: Boolean = false

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

    // Only trivial, NPC-agnostic vanilla utility goals left — none of them ever touched
    // `mob.target`/ATTACK_TARGET or any custom AI state, so there's no Goal/Brain interop risk in
    // leaving them here indefinitely (see the class doc comment above).
    override fun registerGoals() {
        super.registerGoals()
        this.goalSelector.addGoal(0, FloatGoal(this))
        this.goalSelector.addGoal(6, IdleLookAroundGoal(this))
        this.goalSelector.addGoal(7, WaterAvoidingRandomStrollGoal(this, 0.8))
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

    // Was a one-shot flag; widened to a few retries — see resyncEquipmentForNewlySpawnedNpc's doc
    // comment for why one resync, one tick after spawn, still wasn't always enough (reported: weapon
    // invisible even on a plain single spawn, not just batch bursts).
    private var equipmentResyncTicksRemaining = 5

    override fun customServerAiStep() {
        super.customServerAiStep()
        if (equipmentResyncTicksRemaining > 0) {
            equipmentResyncTicksRemaining--
            resyncEquipmentForNewlySpawnedNpc()
        }
        tickBrain(this)
    }

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
        serverLevel.server.playerList.broadcastAll(packet, serverLevel.dimension())
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
            MedicHealBehaviour()
        )
    // Idle: only relevant while there's no ATTACK_TARGET (Fight always outranks Idle). Order here
    // doesn't change behaviour — InvestigateBehaviour's and SquadOrderBehaviour's own eligibility
    // checks already exclude each other's cases (see each class's doc comment), reproducing the
    // old goal-priority order (Investigate=4 beat SquadOrder=5) by hand since Idle behaviours have
    // no automatic per-Flag exclusivity like GoalSelector did.
    override fun getIdleTasks(): net.tslat.smartbrainlib.api.core.BrainActivityGroup<NpcEntity> =
        net.tslat.smartbrainlib.api.core.BrainActivityGroup.idleTasks(InvestigateBehaviour(), SquadOrderBehaviour())
    // Fight: only while ATTACK_TARGET is set. GunAttackBehaviour is a direct port of the old
    // NpcGunAttackGoal; AnimatableMeleeAttack is SmartBrainLib's own ready-made melee behaviour
    // (only attacks when already within melee range + LOS — it does no chasing of its own, same as
    // before: GunAttackBehaviour's own advance-to-shootDistance already closes the gap for every
    // class, since every NpcClass carries a gun, so melee only ever needed to cover the
    // already-adjacent case); GrenadeThrowBehaviour is a direct port of GrenadeThrowGoal. All three
    // ran concurrently as unflagged Goals before — same here, just as Behaviours in one Activity.
    override fun getFightTasks(): net.tslat.smartbrainlib.api.core.BrainActivityGroup<NpcEntity> =
        net.tslat.smartbrainlib.api.core.BrainActivityGroup.fightTasks(
            com.sbwnpc.squad.entity.ai.GunAttackBehaviour(),
            net.tslat.smartbrainlib.api.core.behaviour.custom.attack.AnimatableMeleeAttack<NpcEntity>(20),
            GrenadeThrowBehaviour()
        )

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

        // Per-class multiplier on top of the shared base — createAttributes() only sets the raw
        // base (BASE_SPEED) since it runs once at entity-type registration, before npcClass is even
        // known; this is the real value, same pattern as MAX_HEALTH above.
        getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = BASE_SPEED * npcClass.speedMultiplier

        // One of 2 weapons per weapon category, picked once and kept for this NPC's whole life —
        // pure visual variety across NPCs of the same class, per user request ("разношерстные").
        val weaponId = npcClass.weaponPool[random.nextInt(npcClass.weaponPool.size)]
        val gunItem = BuiltInRegistries.ITEM.getOptional(weaponId).orElse(Items.AIR)
        if (gunItem is GunItem) {
            val gunData = GunData.from(ItemStack(gunItem))
            gunData.virtualAmmo.set(120)
            gunData.reloadAmmo(this)
            gunData.save()
            setItemInHand(InteractionHand.MAIN_HAND, gunData.stack)
        }

        // Green (RU) kit for CREEPER/CAT/PIG/COW, sand (US) kit for the other 4 factions — per user
        // request, applies to every class without exception. spawnFaction (not
        // SquadTeams.factionOf(this)) because finalizeSpawn() calls applyRole() BEFORE assigning the
        // scoreboard team that factionOf() reads from — see finalizeSpawn().
        val faction = spawnFaction ?: SquadFaction.DEFAULT
        val (helmet, chest) = if (faction in GREEN_KIT_FACTIONS) {
            ModItems.RU_HELMET_6B47.get() to ModItems.RU_CHEST_6B43.get()
        } else {
            ModItems.US_HELMET_PASGT.get() to ModItems.US_CHEST_IOTV.get()
        }
        setItemSlot(EquipmentSlot.HEAD, ItemStack(helmet))
        setItemSlot(EquipmentSlot.CHEST, ItemStack(chest))

        // One reserve grenade per fighter, mortar crew excepted (they aren't a combat-suppression
        // role) — per user request. Tracked as a plain flag, NOT a visible offhand item — user
        // feedback: holding a physical grenade in the offhand looked wrong (both hands full), and
        // GRENADIER's own unlimited GrenadeThrowBehaviour already throws without ever visibly
        // holding a grenade either (it just spawns HandGrenadeEntity directly), so there's no
        // established precedent here for a held item in the first place. Consumed by
        // SeekCoverBehaviour's occasional POST-dig throw (see maybeThrowGrenadeOnceDugIn — thrown
        // once already dug in, not before); entirely separate from GRENADIER's own mechanic.
        hasReserveGrenade = npcClass != NpcClass.MORTAR_OPERATOR && npcClass != NpcClass.MORTAR_LOADER
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
        private const val BASE_SPEED = 0.25
        private const val SUPPRESSION_DURATION_TICKS = 100
        private const val SUPPRESSION_CAP_TICKS = 200
        private const val ALERT_DURATION_TICKS = 200 // ~10s to reach/abandon an investigation lead
        private const val DEATH_ALARM_RADIUS = 36.0 // detection range, x1.5 per user request (was 24)

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
                .add(Attributes.FOLLOW_RANGE, 72.0) // detection range, x1.5 per user request (was 48)
        }
    }
}
