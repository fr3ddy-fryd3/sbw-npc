package com.sbwnpc.squad.squad

import com.sbwnpc.squad.compat.SavedDataAccess
import com.sbwnpc.squad.compat.VersionedSavedData
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModEntities
import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.npc.HelicopterModel
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.Pose
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Server-wide registry of squads (command groups). Squad membership does not decide alliance —
 * that's the scoreboard team ([SquadTeams]) — but forming a squad puts every member on the
 * squad's faction team.
 */
class SquadManager : VersionedSavedData() {

    private val squads = LinkedHashMap<UUID, Squad>()

    // A deleted member can be asleep in an unloaded chunk. Keep the deletion in SavedData
    // until it loads, including across server restarts; removing its squad alone strands it.
    private val pendingDeletes = LinkedHashSet<UUID>()
    private val pendingExperience = LinkedHashMap<UUID, Double>()

    fun creditExperience(server: MinecraftServer, id: UUID, amount: Double, reason: String) {
        if (!amount.isFinite() || amount <= 0) return
        val npc = findEntity(server, id) as? NpcEntity
        if (npc != null) {
            npc.gainExperience(amount, reason)
        } else if (id !in pendingDeletes) {
            pendingExperience[id] = (pendingExperience[id] ?: 0.0) + amount
            setDirty()
        }
    }
    fun deliverExperience(npc: NpcEntity) {
        pendingExperience.remove(npc.uuid)?.let {
            npc.gainExperience(it, "deferred_assist")
            setDirty()
        }
    }

    fun all(): Collection<Squad> = squads.values
    fun get(id: UUID?): Squad? = id?.let { squads[it] }
    fun forOwner(owner: UUID): List<Squad> = squads.values.filter { it.owner == owner }

    /** Server-side authorization check — every network handler that acts on a squad by id (as
     *  opposed to picking from the caller's own [forOwner] list) must gate on this before doing
     *  anything, since a squad id in a packet is just a string a client chose to send. */
    fun ownedBy(id: UUID, player: UUID): Boolean = get(id)?.owner == player

    /** No cap on how many squads one player runs. The quick-command HUD's number keys only reach
     *  the first [HUD_SLOTS] of them ([forOwner] is in creation order, so disbanding one moves the
     *  rest up into the freed numbers); everything past that is commanded from the squad screen. */
    fun create(
        level: ServerLevel,
        owner: UUID,
        faction: SquadFaction,
        members: List<UUID>,
        originalComposition: List<NpcClass>? = null,
        rank: NpcRank? = null,
    ): Squad {
        // Captured now so a future barracks assignment knows what "full strength" means for this
        // squad — the actual classes it was formed/last topped up with, not a guess.
        val npcs = members.mapNotNull { findEntity(level.server, it) as? NpcEntity }
        val composition = originalComposition ?: npcs.map { it.npcClass }
        val squadRank = rank ?: npcs.firstOrNull()?.npcRank ?: NpcRank.DEFAULT
        val tank = composition.contains(NpcClass.TANK_CREW)
        val heli = composition.any { it == NpcClass.HELICOPTER_PILOT || it == NpcClass.HELICOPTER_GUNNER }
        val prefix = when {
            tank -> "Tank "
            heli -> "Heli "
            else -> ""
        }
        val initialOrder = when {
            // Tank crews structurally can't run anything but MOVE — setOrder forces it right back
            // the instant anyone tries to change it, so starting there avoids a one-tick mismatch.
            tank -> SquadOrder.MOVE

            else -> SquadOrder.DEFEND
        }
        val squad = Squad(
            UUID.randomUUID(), nextName(owner, prefix), faction, initialOrder, members.toMutableList(),
            null, null, owner, null, composition, squadRank,
        )
        squad.recruitmentRoster = RecruitmentRoster.migrate(
            composition,
            members.associateWith { (findEntity(level.server, it) as? NpcEntity)?.npcClass },
        )
        squads[squad.id] = squad
        members.forEach { m ->
            val e = findEntity(level.server, m)
            if (e != null) SquadTeams.assign(e, faction)
            (e as? NpcEntity)?.squadId = squad.id
        }
        setDirty()
        return squad
    }

    /**
     * Removes a squad and everything in it — members and whatever vehicles they were assigned.
     *
     * Distinct from [disband], which only dissolves the grouping and leaves the NPCs standing.
     * Shared by the squad screen's DEL button and by a Barracks being given a new garrison, so the
     * two cannot drift apart on what "delete" means.
     */
    fun deleteSquad(level: ServerLevel, id: UUID) {
        val name = get(id)?.name
        val members = beginDeletion(id)
        val removed = members.count { member -> findEntity(level.server, member)?.let(::discardDeletedEntity) == true }
        com.sbwnpc.squad.combat.DebugFlags.log(
            com.sbwnpc.squad.combat.LogGroup.ORDER,
            "[squad] event=delete squad={} name={} members={} removed={} pending={}",
            id,
            name,
            members.size,
            removed,
            members.count { it in pendingDeletes },
        )
    }

    internal fun beginDeletion(id: UUID): List<UUID> {
        val squad = squads.remove(id) ?: return emptyList()
        val members = squad.members.toList()
        pendingDeletes.addAll(members)
        setDirty()
        return members
    }

    /** Consuming a member's deletion also schedules its vehicle, even if that is unloaded. */
    internal fun takeDeletion(id: UUID, assignedVehicle: UUID?): Boolean {
        if (!pendingDeletes.remove(id)) return false
        assignedVehicle?.let { pendingDeletes.add(it) }
        setDirty()
        return true
    }

    /** Used both for loaded members and by the entity-join hook before unloaded ones reappear. */
    fun discardDeletedEntity(entity: Entity): Boolean {
        val vehicle = (entity as? NpcEntity)?.assignedVehicleId
        if (!takeDeletion(entity.uuid, vehicle)) return false
        entity.discard()
        vehicle?.let { id -> entity.server?.let { findEntity(it, id) }?.let(::discardDeletedEntity) }
        return true
    }

    fun disband(level: ServerLevel, id: UUID) {
        val squad = squads.remove(id) ?: return
        val loaded = squad.members.mapNotNull { m -> findEntity(level.server, m) as? NpcEntity }
        loaded.forEach { it.squadId = null }
        setDirty()
        com.sbwnpc.squad.combat.DebugFlags.log(
            com.sbwnpc.squad.combat.LogGroup.ORDER,
            "[squad] event=disband squad={} name={} members={} loaded={}",
            id,
            squad.name,
            squad.members.size,
            loaded.size,
        )
    }

    fun mergeProblem(source: UUID, destination: UUID, owner: UUID): String? = SquadMerging.problem(get(source), get(destination), owner)

    fun merge(level: ServerLevel, source: UUID, destination: UUID, owner: UUID): String? {
        val from = get(source) ?: return "Squad no longer exists"
        val into = get(destination) ?: return "Squad no longer exists"
        if (from.owner != owner || into.owner != owner) return "Both squads must belong to you"
        ensureRecruitmentRoster(level, from)
        ensureRecruitmentRoster(level, into)
        mergeProblem(source, destination, owner)?.let { return it }
        val moved = from.members.toList()
        mergeMembership(from, into, level.gameTime)
        into.members.forEach { id ->
            (findEntity(level.server, id) as? NpcEntity)?.let {
                it.squadId = into.id
                it.invalidateOrders()
            }
        }
        com.sbwnpc.squad.combat.DebugFlags.log(
            com.sbwnpc.squad.combat.LogGroup.ORDER,
            "[squad] event=merge source={} destination={} moved={} members={}",
            source,
            destination,
            moved.size,
            into.members.size,
        )
        return null
    }

    internal fun mergeMembership(source: Squad, destination: Squad, now: Long) {
        SquadMerging.transfer(source, destination, now)
        squads.remove(source.id)
        setDirty()
    }

    /** The saved member list is authoritative even when the old entity was unloaded at merge. */
    fun reconcileMembership(npc: NpcEntity) {
        val group = squads.values.firstOrNull { npc.uuid in it.members }
        if (group != null && npc.squadId != group.id) {
            npc.squadId = group.id
            npc.invalidateOrders()
        } else if (group == null && npc.squadId != null && get(npc.squadId) == null) {
            npc.squadId = null
        }
    }

    /** Player command, including map/group/HUD orders. Internal transitions use [setOrder]. */
    fun issueOrder(id: UUID, order: SquadOrder) {
        setOrder(id, order)
        val squad = squads[id] ?: return
        val dimension = squad.objectiveDimension?.location()?.toString() ?: squad.lastSeenDim?.location()?.toString() ?: "minecraft:overworld"
        val goal = squad.focusEntity?.let { com.sbwnpc.squad.ai.coordination.ObjectiveKey.Entity(dimension, it) }
            ?: squad.objective?.let { com.sbwnpc.squad.ai.coordination.ObjectiveKey.Area(dimension, it.x, it.y, it.z) }
        squad.command = com.sbwnpc.squad.ai.tactics.IssuedCommand(UUID.randomUUID(), squad.owner, order.tactical(), goal)
        squad.commandOutcome = com.sbwnpc.squad.ai.tactics.CommandOutcome.ACTIVE
        squad.commandReason = null
        squad.pendingCommandStamp = squad.orderStamp
        if (squad.order == SquadOrder.ATTACK && squad.attackAmmoPolicy == AttackAmmoPolicy.RETRY_AVAILABLE) {
            squad.attackAmmoPolicy = AttackAmmoPolicy.PRESS_ON
            setDirty()
            com.sbwnpc.squad.combat.DebugFlags.log(
                com.sbwnpc.squad.combat.LogGroup.ORDER,
                "[tactics] {} squad={} event=attack_retry source=player ammo_policy=PRESS_ON",
                squad.name,
                squad.id,
            )
        }
    }

    /** The ammunition failure, unlike a manual retreat, arms the next explicit attack retry. */
    fun withdrawForAmmo(id: UUID) {
        squads[id]?.attackAmmoPolicy = AttackAmmoPolicy.RETRY_AVAILABLE
        withdraw(id, "primary_ammo_exhausted")
    }

    /** Calls the squad's command off as failed for [reason] and falls back. */
    fun withdraw(id: UUID, reason: String) {
        val squad = squads[id] ?: return
        squad.commandOutcome = com.sbwnpc.squad.ai.tactics.CommandOutcome.FAILED
        squad.commandReason = reason
        setOrder(id, SquadOrder.RETREAT)
    }

    fun setOrder(id: UUID, order: SquadOrder) {
        squads[id]?.let {
            it.pendingCommandStamp = -1
            it.tacticalOperation = null
            it.tacticalObjective = null
            val available = SquadOrder.availableFor(
                isTankSquad(it),
                isMortarSquad(it),
                isGunshipSquad(it),
                isTransportSquad(it),
            )
            it.order = when {
                order in available -> order
                isTankSquad(it) -> SquadOrder.MOVE
                else -> SquadOrder.DEFEND
            }
            if (it.order != SquadOrder.ATTACK && it.attackAmmoPolicy == AttackAmmoPolicy.PRESS_ON) {
                it.attackAmmoPolicy = AttackAmmoPolicy.WITHDRAW_WHEN_EMPTY
            }
            it.orderStamp++
            setDirty()
        }
    }

    /** Complete explicit command at a point; internal mode/objective transitions keep their original intent. */
    fun issueOrderAt(level: ServerLevel, id: UUID, order: SquadOrder, pos: BlockPos, command: com.sbwnpc.squad.ai.tactics.IssuedCommand? = null) {
        require(command == null || command.principal == squads[id]?.owner) { "Foreign command principal" }
        require(command?.goal?.dimension?.let { it == level.dimension().location().toString() } != false) { "Foreign command dimension" }
        issueOrder(id, order)
        setObjective(level, id, pos)
        squads[id]?.let { squad ->
            if (command != null) squad.command = command
            squad.pendingCommandStamp = -1
        }
        setDirty()
    }

    fun issueObjective(level: ServerLevel, id: UUID, pos: BlockPos) {
        val squad = squads[id] ?: return
        val previous = squad.command
        issueOrderAt(level, id, squad.order, pos, previous?.copy(id = UUID.randomUUID(), goal = com.sbwnpc.squad.ai.coordination.ObjectiveKey.Area(level.dimension().location().toString(), pos.x, pos.y, pos.z)))
    }

    /**
     * Sends squad [id] to follow its owner [player]; false when the squad is not his or cannot
     * follow. A player has one squad following him: any other squad of his that does is put on
     * DEFEND where it stands.
     */
    fun issueFollow(level: ServerLevel, id: UUID, player: net.minecraft.world.entity.player.Player): Boolean {
        val squad = squads[id] ?: return false
        if (squad.owner != player.uuid || SquadOrder.FOLLOW !in availableOrders(squad)) return false
        squads.values.filter { it.id != id && it.order == SquadOrder.FOLLOW && it.focusEntity == player.uuid }.forEach { stopFollowing(level, it) }
        val issued = com.sbwnpc.squad.ai.tactics.IssuedCommand(
            UUID.randomUUID(),
            squad.owner,
            SquadOrder.FOLLOW.tactical(),
            com.sbwnpc.squad.ai.coordination.ObjectiveKey.Entity(level.dimension().location().toString(), player.uuid),
        )
        issueOrderAt(level, id, SquadOrder.FOLLOW, player.blockPosition(), issued)
        setFocus(id, player.uuid)
        squad.followLast = null
        squad.followHeading = Vec3.ZERO
        return true
    }

    /** Ends [squad]'s following: it defends the spot where its men stand. */
    fun stopFollowing(level: ServerLevel, squad: Squad) {
        val here = moveAssemblyPoint(level, squad) ?: squad.objective
        setFocus(squad.id, null)
        issueOrder(squad.id, SquadOrder.DEFEND)
        if (here != null) setObjective(level, squad.id, here)
    }

    private fun availableOrders(squad: Squad) = SquadOrder.availableFor(isTankSquad(squad), isMortarSquad(squad), isGunshipSquad(squad), isTransportSquad(squad))

    fun issueFocus(level: ServerLevel, id: UUID, target: UUID) {
        val squad = squads[id] ?: return
        val entity = level.getEntity(target) ?: return
        val issued = com.sbwnpc.squad.ai.tactics.IssuedCommand(
            UUID.randomUUID(),
            squad.owner,
            squad.order.tactical(),
            com.sbwnpc.squad.ai.coordination.ObjectiveKey.Entity(level.dimension().location().toString(), target),
        )
        issueOrderAt(level, id, squad.order, entity.blockPosition(), issued)
        setFocus(id, target)
    }

    fun completeCommand(id: UUID, reason: String) {
        val squad = squads[id] ?: return
        if (squad.commandOutcome != com.sbwnpc.squad.ai.tactics.CommandOutcome.ACTIVE) return
        squad.commandOutcome = com.sbwnpc.squad.ai.tactics.CommandOutcome.COMPLETED
        squad.commandReason = reason
        setDirty()
    }

    fun isTankSquad(squad: Squad): Boolean = squad.originalComposition.contains(NpcClass.TANK_CREW) || squad.name.startsWith("Tank ")

    /** A gunship squad brought a turret gunner; a transport squad is a pilot without one. */
    fun isGunshipSquad(squad: Squad): Boolean = squad.originalComposition.contains(NpcClass.HELICOPTER_GUNNER)

    fun isTransportSquad(squad: Squad): Boolean = squad.originalComposition.contains(NpcClass.HELICOPTER_PILOT) && !isGunshipSquad(squad)

    fun isMortarSquad(squad: Squad): Boolean =
        squad.originalComposition.any {
            it.canonical == NpcClass.MORTAR
        }

    /** Sets the objective; only an owner on the squad's faction sees the world particle marker. */
    fun setObjective(level: ServerLevel, id: UUID, pos: BlockPos?) {
        val squad = squads[id] ?: return
        squad.tacticalOperation = null
        squad.tacticalObjective = null
        if (squad.pendingCommandStamp == squad.orderStamp) {
            squad.command = squad.command?.copy(goal = pos?.let { com.sbwnpc.squad.ai.coordination.ObjectiveKey.Area(level.dimension().location().toString(), it.x, it.y, it.z) })
        }
        squad.pendingCommandStamp = -1
        squad.objectiveDimension = level.dimension()
        squad.objective = pos
        squad.focusEntity = null
        if (squad.order == SquadOrder.MOVE) {
            squad.moveAssembly = moveAssemblyPoint(level, squad)
            squad.moveFormationReady = false
            squad.moveRallySince = level.gameTime
        }
        squad.orderStamp++
        setDirty()
        if (pos != null) spawnObjectiveMarker(level, squad, pos)
    }

    private fun spawnObjectiveMarker(level: ServerLevel, squad: Squad, pos: BlockPos) {
        val owner = level.server.playerList.getPlayer(squad.owner) ?: return
        if (PlayerFactionRegistry.get(level).get(owner.uuid) != squad.faction) return
        val color = Vec3.fromRGB24(squad.faction.rgb).toVector3f()
        val options = DustParticleOptions(color, 1.5f)
        val center = pos.center
        level.sendParticles(owner, options, true, center.x, center.y + 0.6, center.z, 40, 0.3, 0.7, 0.3, 0.02)
    }

    fun setFocus(id: UUID, entity: UUID?) {
        squads[id]?.takeUnless(::isTankSquad)?.let {
            it.tacticalOperation = null
            it.tacticalObjective = null
            it.focusEntity = entity
            setDirty()
        }
    }

    private fun moveAssemblyPoint(level: ServerLevel, squad: Squad): BlockPos? {
        val members = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }
        if (members.isEmpty()) return null
        return BlockPos.containing(
            members.sumOf { it.x } / members.size,
            members.sumOf { it.y } / members.size,
            members.sumOf { it.z } / members.size,
        )
    }

    fun rename(id: UUID, name: String) {
        squads[id]?.let {
            it.name = name.take(MAX_NAME_LENGTH).ifBlank { it.name }
            setDirty()
        }
    }

    fun removeMemberEverywhere(entity: UUID) {
        var changed = false
        squads.values.forEach {
            if (it.members.remove(entity)) {
                it.recruitmentRoster?.release(entity)
                it.recruitmentRoster?.let { roster -> it.originalComposition = roster.slots.map { slot -> slot.role } }
                changed = true
            }
        }
        if (pruneEmptySquads()) changed = true
        if (changed) setDirty()
    }

    private fun pruneEmptySquads(): Boolean =
        squads.entries.removeIf {
            val empty = it.value.members.isEmpty() && it.value.barracks == null
            empty
        }

    fun squadsAtBarracks(barracks: BarracksRef): List<Squad> = squads.values.filter { it.barracks == barracks }

    fun assignBarracks(id: UUID, barracks: BarracksRef?) {
        val squad = squads[id] ?: return
        squad.barracks = barracks
        pruneEmptySquads()
        setDirty()
    }

    fun clearBarracks(barracks: BarracksRef) {
        squadsAtBarracks(barracks).forEach { it.barracks = null }
        pruneEmptySquads()
        setDirty()
    }

    fun assignRoute(id: UUID, routeId: UUID?) {
        squads[id]?.let {
            it.routeId = routeId
            setDirty()
        }
    }

    /** Called (see [com.sbwnpc.squad.combat.SquadFocusCleanup]) the instant anything a squad was
     *  focused on (ATTACK target or DEFEND ward) dies. `Squad.focusEntity` is a live UUID, not a
     *  fixed point — left stale, `NpcEntity.homeCenter()` resolves it to nothing (dead entity, no
     *  separate objective ever set for a pure focus-based order) and returns null FOREVER for that
     *  squad, silently disabling `SquadOrderBehaviour` for good: members just coast to a stop wherever
     *  their last queued path was heading and never reposition or reform again. Reported in-game as
     *  "after killing the enemy they walk right up to its last spot and pile up there." Promoting
     *  the death position to a fixed objective gives the squad a real home to reform around. */
    fun clearDeadFocus(deadEntity: UUID, deathPos: BlockPos) {
        // Fires for EVERY living death in the world (cows, zombies...) — the common case is that no
        // squad is focused on anything, so answer that without touching the entries below.
        if (squads.values.none { it.focusEntity != null }) return
        var changed = false
        squads.values.forEach { squad ->
            if (squad.focusEntity == deadEntity) {
                squad.focusEntity = null
                squad.objective = deathPos
                changed = true
            }
        }
        if (changed) setDirty()
    }

    /** An empty, persistent squad is created before its first recruit, so losing everyone is safe. */
    fun createAtBarracks(
        level: ServerLevel,
        barracks: BarracksRef,
        owner: UUID,
        cfg: SquadToolItem.Config,
    ): Squad {
        val composition = SquadDeployment.composition(cfg)
        val squad = create(level, owner, cfg.faction, emptyList())
        squad.barracks = barracks
        squad.originalComposition = composition
        val prefix = when {
            composition.contains(NpcClass.TANK_CREW) -> "Tank "
            composition.contains(NpcClass.HELICOPTER_PILOT) -> "Heli "
            else -> ""
        }
        if (prefix.isNotEmpty()) squad.name = nextName(owner, prefix)
        squad.rank = cfg.rank
        squad.recruitmentRoster = RecruitmentRoster.empty(composition)
        squad.order = if (cfg.preset.grid || composition.contains(NpcClass.TANK_CREW)) SquadOrder.MOVE else SquadOrder.DEFEND
        squad.objective = barracks.pos.above()
        setDirty()
        return squad
    }

    fun ensureRecruitmentRoster(level: ServerLevel, squad: Squad): RecruitmentRoster? {
        squad.recruitmentRoster?.let { return it }
        val members = squad.members.associateWith { (findEntity(level.server, it) as? NpcEntity)?.npcClass }
        val roster = RecruitmentRoster.migrate(squad.originalComposition, members) ?: return null
        squad.recruitmentRoster = roster
        setDirty()
        return roster
    }

    /** Plan the complete initial group before publishing entities; a blocked deploy stays pending. */
    fun deployInitialAtBarracks(
        level: ServerLevel,
        barracks: BarracksRef,
        squadId: UUID,
        cfg: com.sbwnpc.squad.item.SquadToolItem.Config,
        yaw: Float,
    ): String? {
        val squad = get(squadId) ?: return "Squad removed"
        if (squad.barracks != barracks) return "Squad moved to another barracks"
        val slots = squad.recruitmentRoster?.slots?.filter { it.active } ?: return "Recruitment roster unavailable"
        data class Planned(val slot: RecruitmentRoster.Slot, val npc: NpcEntity, val position: Vec3)
        val planned = mutableListOf<Planned>()
        val radians = Math.toRadians(yaw.toDouble())
        val rightX = kotlin.math.cos(radians)
        val rightZ = kotlin.math.sin(radians)
        val forwardX = -rightZ
        val forwardZ = rightX
        for ((index, slot) in slots.withIndex()) {
            if (slot.occupant != null || slot.recruited) continue
            val npc = ModEntities.NPC.get().create(level) ?: return "NPC unavailable"
            val offset = if (cfg.preset.grid) {
                com.sbwnpc.squad.combat.SquadFormation.gridOffset(index, slots.size)
            } else {
                Vec3((index - (slots.size - 1) / 2.0) * cfg.preset.spacing, 0.0, 0.0)
            }
            val dimensions = npc.getDimensions(Pose.STANDING)
            val spot = SafeSpawn.findStandingSpot(
                level,
                barracks.pos.x + 0.5 + rightX * offset.x + forwardX * (offset.z + 2.0),
                barracks.pos.z + 0.5 + rightZ * offset.x + forwardZ * (offset.z + 2.0),
                barracks.pos.y,
                dimensions,
                requireSolidFloor = true,
                acceptsPosition = { candidate ->
                    val bounds = dimensions.makeBoundingBox(candidate)
                    planned.none { it.npc.getDimensions(Pose.STANDING).makeBoundingBox(it.position).intersects(bounds) }
                },
            ) ?: return "Not enough safe space for the full garrison"
            planned += Planned(slot, npc, spot)
        }
        if (planned.isEmpty()) return null
        for (recruit in planned) {
            if (!addRecruit(level, squad, recruit.slot, recruit.npc, recruit.position, yaw)) {
                planned.forEach {
                    squad.members.remove(it.npc.uuid)
                    it.slot.occupant = null
                    it.slot.recruited = false
                    it.npc.discard()
                }
                setDirty()
                return "Garrison spawn rejected"
            }
        }
        com.sbwnpc.squad.combat.DebugFlags.log(com.sbwnpc.squad.combat.LogGroup.ORDER, "Barracks {} deployed {} new NPCs into {} ({})", barracks.pos, planned.size, squad.name, squad.faction)
        return null
    }

    /** Returns a reason when the head of the shared queue cannot spawn; never uses an unsafe fallback. */
    fun recruitAtBarracks(
        level: ServerLevel,
        barracks: BarracksRef,
        request: BarracksRecruitmentQueue.Request,
    ): String? {
        val squad = get(request.squad) ?: return "Squad removed"
        if (squad.barracks != barracks) return "Squad moved to another barracks"
        val slot = squad.recruitmentRoster?.slots?.firstOrNull { it.id == request.slot && it.active && it.occupant == null }
            ?: return "Place no longer vacant"
        val npc = ModEntities.NPC.get().create(level) ?: return "NPC unavailable"
        val angle = level.random.nextDouble() * Math.PI * 2
        val radius = SPAWN_MIN_RADIUS + level.random.nextDouble() * (SPAWN_MAX_RADIUS - SPAWN_MIN_RADIUS)
        val spot = SafeSpawn.findStandingSpot(
            level,
            barracks.pos.x + 0.5 + Math.cos(angle) * radius,
            barracks.pos.z + 0.5 + Math.sin(angle) * radius,
            barracks.pos.y + 1,
            npc.getDimensions(Pose.STANDING),
            requireSolidFloor = true,
        ) ?: return "No safe space near the barracks"
        if (!addRecruit(level, squad, slot, npc, spot, level.random.nextFloat() * 360f)) return "NPC spawn rejected"
        val composition = squad.originalComposition.sorted()
        HelicopterModel.entries.firstOrNull { it.crew.sorted() == composition }?.let { model ->
            val crew = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive }
            SquadDeployment.armHeavyGunner(model, crew, fresh = listOf(npc))
        }
        com.sbwnpc.squad.combat.DebugFlags.log(com.sbwnpc.squad.combat.LogGroup.ORDER, "Barracks {} replaced {} in {} ({}) with {}", barracks.pos, slot.role, squad.name, squad.faction, npc.uuid)
        return null
    }

    private fun addRecruit(level: ServerLevel, squad: Squad, slot: RecruitmentRoster.Slot, npc: NpcEntity, spot: Vec3, yaw: Float): Boolean {
        npc.moveTo(spot.x, spot.y, spot.z, yaw, 0f)
        npc.npcClass = slot.role
        npc.npcRank = NpcRank.RECRUIT
        npc.spawnFaction = squad.faction
        npc.squadId = squad.id
        npc.spawnNpc(level, level.getCurrentDifficultyAt(npc.blockPosition()))
        if (!level.addFreshEntity(npc)) return false
        slot.occupant = npc.uuid
        slot.recruited = true
        squad.members.add(npc.uuid)
        setDirty()
        return true
    }

    /** Resupply is independent of the production clock and never creates entities. */
    fun resupplyAtBarracks(level: ServerLevel, barracks: BarracksRef) {
        val pos = Vec3.atCenterOf(barracks.pos)
        squadsAtBarracks(barracks).forEach { squad ->
            squad.members.forEach { member ->
                val npc = level.getEntity(member) as? NpcEntity
                if (npc != null && npc.npcClass == NpcClass.DRONE_OPERATOR && npc.isAlive &&
                    npc.position().distanceToSqr(pos) <= RESUPPLY_RADIUS * RESUPPLY_RADIUS
                ) {
                    npc.dronesLeft = com.sbwnpc.squad.entity.ai.DroneOperatorBehaviour.MAX_DRONES
                }
            }
        }
    }

    private fun nextName(owner: UUID, prefix: String = ""): String = firstFreeName(forOwner(owner).map { it.name }.toSet(), prefix)

    override fun write(tag: CompoundTag): CompoundTag = saveState(tag)

    internal fun saveState(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        squads.values.forEach { list.add(it.save()) }
        tag.put("Squads", list)
        val deleted = ListTag()
        pendingDeletes.forEach { deleted.add(NbtUtils.createUUID(it)) }
        tag.put("PendingDeletes", deleted)
        val xp = CompoundTag()
        pendingExperience.forEach { (id, amount) -> xp.putDouble(id.toString(), amount) }
        tag.put("PendingExperience", xp)
        return tag
    }

    companion object {
        private const val FILE = "sbwnpc_squads"
        private const val SPAWN_MIN_RADIUS = 2.0
        private const val SPAWN_MAX_RADIUS = 5.0

        /** How many squads the 1-9 quick-command keys can address — a keyboard limit, not a cap
         *  on how many a player may have. */
        const val HUD_SLOTS = 9
        private const val RESUPPLY_RADIUS = 16.0
        const val MAX_NAME_LENGTH = 24
        private val NAMES = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel")

        /**
         * The name a new squad gets, given the names already in use.
         *
         * Past the phonetic names it counts up to the first free number rather than off the squad
         * count — with no cap on squads, deleting one from the middle otherwise hands the next squad
         * a name that is already taken.
         */
        internal fun firstFreeName(used: Set<String>, prefix: String): String {
            NAMES.firstOrNull { prefix + it !in used }?.let { return prefix + it }
            var n = NAMES.size + 1
            while ("${prefix}Squad $n" in used) n++
            return "${prefix}Squad $n"
        }

        internal fun load(tag: CompoundTag): SquadManager {
            val mgr = SquadManager()
            tag.getList("Squads", Tag.TAG_COMPOUND.toInt()).forEach { e ->
                val squad = Squad.load(e as CompoundTag)
                mgr.squads[squad.id] = squad
            }
            tag.getList("PendingDeletes", Tag.TAG_INT_ARRAY.toInt()).forEach { e ->
                mgr.pendingDeletes.add(NbtUtils.loadUUID(e))
            }
            val xp = tag.getCompound("PendingExperience")
            xp.allKeys.forEach { key ->
                runCatching { UUID.fromString(key) }.getOrNull()?.let { id ->
                    val amount = xp.getDouble(key)
                    if (amount.isFinite() && amount > 0) mgr.pendingExperience[id] = amount
                }
            }
            return mgr
        }

        // The SavedData instance is stable for the whole server run (DimensionDataStorage caches it
        // by name), but `computeIfAbsent` still allocates a fresh Factory + two lambdas and does a
        // map lookup on every call — and this is called several times per NPC per tick (every
        // currentSquad()/homeCenter() from every behaviour's start-check). Cached here per server;
        // ServerLifecycle drops it on ServerStopped so a new/reloaded world never sees the old one.
        private var cached: SquadManager? = null
        private var cachedServer: MinecraftServer? = null

        fun get(server: MinecraftServer): SquadManager {
            cached?.let { if (cachedServer === server) return it }
            val mgr = SavedDataAccess.fetch(server.overworld().dataStorage, FILE, ::SquadManager, ::load)
            cached = mgr
            cachedServer = server
            return mgr
        }

        fun clearCache() {
            cached = null
            cachedServer = null
        }

        fun get(level: ServerLevel): SquadManager = get(level.server)

        /** Composition minus classes of resolved members. Unloaded members reserve one missing slot. */
        fun missingClasses(composition: List<NpcClass>, present: List<NpcClass?>): List<NpcClass> {
            val remaining = present.filterNotNull().toMutableList()
            val missing = composition.filter { !remaining.remove(it) }
            return missing.drop(present.count { it == null })
        }

        /** The next recruit across all squads served by one barracks. */
        internal fun nextReinforcement(
            squads: List<Squad>,
            present: (UUID) -> NpcClass?,
        ): Pair<Squad, NpcClass>? {
            for (squad in squads) {
                val cls = missingClasses(squad.originalComposition, squad.members.map(present)).firstOrNull()
                    ?: continue
                return squad to cls
            }
            return null
        }

        fun findEntity(server: MinecraftServer, uuid: UUID): Entity? {
            for (level in server.allLevels) level.getEntity(uuid)?.let { return it }
            return null
        }
    }
}
