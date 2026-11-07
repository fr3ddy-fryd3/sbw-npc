package com.sbwnpc.squad.squad

import com.sbwnpc.squad.compat.VersionCompat
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import java.util.UUID

/** What a squad was attacking when it went to refill first: the point, or the entity it was focused on. */
data class ResupplyAttack(val objective: BlockPos?, val focus: UUID?) {
    fun save(): CompoundTag =
        CompoundTag().also { tag ->
            objective?.let { tag.put("Objective", NbtUtils.writeBlockPos(it)) }
            focus?.let { tag.putUUID("Focus", it) }
        }

    companion object {
        fun load(tag: CompoundTag) =
            ResupplyAttack(
                if (tag.contains("Objective")) VersionCompat.readBlockPos(tag, "Objective") else null,
                if (tag.hasUUID("Focus")) tag.getUUID("Focus") else null,
            )
    }
}

/** A barracks needs both its position and dimension: the same coordinates exist in every world. */
data class BarracksRef(val dimension: ResourceKey<Level>, val pos: BlockPos)

class Squad(
    val id: UUID,
    var name: String,
    var faction: SquadFaction,
    var order: SquadOrder,
    val members: MutableList<UUID>,
    var objective: BlockPos?,
    /** Entity the squad is focused on: attack it (ATTACK) or guard it (DEFEND). */
    var focusEntity: UUID?,
    val owner: UUID,
    /** Barracks this squad resupplies from, if any. */
    var barracks: BarracksRef? = null,
    /** Classes the squad was formed/last topped up with. */
    var originalComposition: List<NpcClass> = emptyList(),
    /** Legacy recruitment setting retained in saved data; new soldiers start as RECRUIT. */
    var rank: NpcRank = NpcRank.DEFAULT,
    /** Patrol route this squad walks when on PATROL order, if any — see `Route`/`SquadOrderBehaviour`. */
    var routeId: UUID? = null,
    /** The fixed rally point for the current MOVE command. */
    var moveAssembly: BlockPos? = null,
    /** True once every member has reached its slot at [moveAssembly]. */
    var moveFormationReady: Boolean = false,
) {
    /** Bumped on every new order or objective, so members can tell a fresh command from the one
     *  they're already carrying out. Not saved: a reload is a fresh start anyway. */
    val tactics = com.sbwnpc.squad.combat.tactics.SquadTacticalState()

    var orderStamp: Int = 0

    /** Explicit common command identity and objective; execution plans/cursors are not persisted. */
    var command: com.sbwnpc.squad.ai.tactics.IssuedCommand? = null
        internal set
    var commandOutcome = com.sbwnpc.squad.ai.tactics.CommandOutcome.ACTIVE
        internal set
    var commandReason: String? = null
        internal set
    internal var pendingCommandStamp = -1
    var objectiveDimension: ResourceKey<Level>? = null
    var tacticalOperation: UUID? = null
    var tacticalObjective: BlockPos? = null

    /** Which way the followed player is going and where he was last seen, for FOLLOW. Not saved. */
    var followHeading: net.minecraft.world.phys.Vec3 = net.minecraft.world.phys.Vec3.ZERO
    var followLast: net.minecraft.world.phys.Vec3? = null

    /** Game time the current MOVE rally started — see `SquadOrderBehaviour.tickMove`. Not saved. */
    var moveRallySince: Long = 0L

    /** Where the squad's middle was the last time its men were loaded — what the map shows and
     *  where `SquadChunkLoader` wakes it, while they're out of loaded range. */
    var lastSeen: BlockPos? = null
    var lastSeenDim: ResourceKey<Level>? = null

    /** Null only for a pre-1.0 save whose member roles have not been resolved yet. */
    var recruitmentRoster: RecruitmentRoster? = null

    /**
     * The attack's strength when it started ([com.sbwnpc.squad.combat.tactics.CombatStrength]): null
     * while the squad is not attacking, or until the attack has been measured. Saved, so a reload
     * mid-attack does not measure it again from the men left by then.
     */
    var attackBase: Int? = null

    /** Sent into this attack as the remnant of a squad: it fights to the end and is never withdrawn on strength. */
    var attackRemnant = false

    /** This attack has already gone to a Supply once: dry again, it attacks rather than going back. */
    var attackResupplied = false

    /** The attack waiting while the squad refills at a Supply, resumed once it has. */
    var resupplyAttack: ResupplyAttack? = null

    /** Loaded attacking from a save older than [attackBase]: measured as it stands, not classified. */
    internal var attackUnmeasuredOnLoad = false

    fun save(): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID("Id", id)
        tag.putString("Name", name)
        tag.putString("Faction", faction.name)
        tag.putString("Order", order.name)
        command?.let { tag.put("IssuedCommand", com.sbwnpc.squad.ai.integration.IssuedCommandCodec.save(it)) }
        tag.putString("CommandOutcome", commandOutcome.name)
        commandReason?.let { tag.putString("CommandReason", it) }
        objectiveDimension?.let { tag.putString("ObjectiveDimension", it.location().toString()) }
        tacticalOperation?.let { tag.putUUID("TacticalOperation", it) }
        tacticalObjective?.let { tag.put("TacticalObjective", NbtUtils.writeBlockPos(it)) }
        attackBase?.let { tag.putInt("AttackBase", it) }
        tag.putBoolean("AttackRemnant", attackRemnant)
        tag.putBoolean("AttackResupplied", attackResupplied)
        resupplyAttack?.let { tag.put("ResupplyAttack", it.save()) }
        tag.putString("Rank", rank.name)
        val list = ListTag()
        members.forEach { list.add(NbtUtils.createUUID(it)) }
        tag.put("Members", list)
        objective?.let { tag.put("Objective", NbtUtils.writeBlockPos(it)) }
        focusEntity?.let { tag.putUUID("Focus", it) }
        tag.putUUID("Owner", owner)
        barracks?.let {
            tag.put("BarracksPos", NbtUtils.writeBlockPos(it.pos))
            tag.putString("BarracksDim", it.dimension.location().toString())
        }
        val comp = ListTag()
        originalComposition.forEach { comp.add(StringTag.valueOf(it.name)) }
        tag.put("OriginalComposition", comp)
        recruitmentRoster?.let { tag.put("RecruitmentRoster", it.save()) }
        routeId?.let { tag.putUUID("RouteId", it) }
        moveAssembly?.let { tag.put("MoveAssembly", NbtUtils.writeBlockPos(it)) }
        tag.putBoolean("MoveFormationReady", moveFormationReady)
        lastSeen?.let { tag.put("LastSeen", NbtUtils.writeBlockPos(it)) }
        lastSeenDim?.let { tag.putString("LastSeenDim", it.location().toString()) }
        return tag
    }

    companion object {
        fun load(tag: CompoundTag): Squad {
            val members = mutableListOf<UUID>()
            tag.getList("Members", Tag.TAG_INT_ARRAY.toInt()).forEach { members.add(NbtUtils.loadUUID(it)) }
            val faction = runCatching { SquadFaction.valueOf(tag.getString("Faction")) }.getOrNull() ?: SquadFaction.DEFAULT
            val barracks = if (tag.contains("BarracksPos")) {
                VersionCompat.readBlockPos(tag, "BarracksPos")?.let { pos ->
                    val dimension = if (tag.contains("BarracksDim", Tag.TAG_STRING.toInt())) {
                        ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString("BarracksDim")))
                    } else {
                        Level.OVERWORLD
                    }
                    BarracksRef(dimension, pos)
                }
            } else {
                null
            }
            val squad = Squad(
                id = tag.getUUID("Id"),
                name = tag.getString("Name"),
                faction = faction,
                order = loadOrder(tag),
                members = members,
                objective = if (tag.contains("Objective")) VersionCompat.readBlockPos(tag, "Objective") else null,
                focusEntity = if (tag.hasUUID("Focus")) tag.getUUID("Focus") else null,
                owner = tag.getUUID("Owner"),
                barracks = barracks,
                originalComposition = loadComposition(tag),
                rank = runCatching { NpcRank.valueOf(tag.getString("Rank")) }.getOrDefault(NpcRank.DEFAULT),
                routeId = if (tag.hasUUID("RouteId")) tag.getUUID("RouteId") else null,
                moveAssembly = if (tag.contains("MoveAssembly")) VersionCompat.readBlockPos(tag, "MoveAssembly") else null,
                moveFormationReady = tag.getBoolean("MoveFormationReady"),
            )
            if (tag.contains("IssuedCommand")) squad.command = com.sbwnpc.squad.ai.integration.IssuedCommandCodec.load(tag.getCompound("IssuedCommand"))?.takeIf { it.principal == squad.owner }
            squad.commandOutcome = com.sbwnpc.squad.ai.tactics.CommandOutcome.entries.firstOrNull { it.name == tag.getString("CommandOutcome") } ?: com.sbwnpc.squad.ai.tactics.CommandOutcome.ACTIVE
            squad.commandReason = tag.getString("CommandReason").takeIf { it.isNotBlank() }
            if (tag.contains("ObjectiveDimension")) squad.objectiveDimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString("ObjectiveDimension")))
            if (tag.hasUUID("TacticalOperation")) squad.tacticalOperation = tag.getUUID("TacticalOperation")
            if (tag.contains("TacticalObjective")) squad.tacticalObjective = VersionCompat.readBlockPos(tag, "TacticalObjective")
            if (tag.contains("LastSeen")) squad.lastSeen = VersionCompat.readBlockPos(tag, "LastSeen")
            if (tag.contains("LastSeenDim", Tag.TAG_STRING.toInt())) {
                squad.lastSeenDim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString("LastSeenDim")))
            }
            if (tag.contains("RecruitmentRoster", Tag.TAG_LIST.toInt())) {
                squad.recruitmentRoster = RecruitmentRoster.load(tag.getList("RecruitmentRoster", Tag.TAG_COMPOUND.toInt()))
            }
            if (tag.contains("AttackBase", Tag.TAG_INT.toInt())) squad.attackBase = tag.getInt("AttackBase")
            squad.attackRemnant = tag.getBoolean("AttackRemnant")
            squad.attackResupplied = tag.getBoolean("AttackResupplied")
            if (tag.contains("ResupplyAttack", Tag.TAG_COMPOUND.toInt())) squad.resupplyAttack = ResupplyAttack.load(tag.getCompound("ResupplyAttack"))
            squad.attackUnmeasuredOnLoad = squad.order == SquadOrder.ATTACK && squad.attackBase == null && !tag.contains("AttackRemnant")
            return squad
        }

        private fun loadOrder(tag: CompoundTag): SquadOrder =
            when {
                tag.contains("Order", Tag.TAG_STRING.toInt()) ->
                    runCatching { SquadOrder.valueOf(tag.getString("Order")) }.getOrDefault(SquadOrder.MOVE)

                tag.contains("Order", Tag.TAG_INT.toInt()) -> SquadOrder.byOrdinal(tag.getInt("Order"))

                else -> SquadOrder.MOVE
            }

        private fun loadComposition(tag: CompoundTag): List<NpcClass> {
            val names = tag.getList("OriginalComposition", Tag.TAG_STRING.toInt())
            if (names.isNotEmpty()) return names.mapNotNull { runCatching { NpcClass.byName(it.asString) }.getOrNull() }
            return tag.getList("OriginalComposition", Tag.TAG_INT.toInt()).map { NpcClass.byOrdinal((it as IntTag).asInt) }
        }
    }
}
