package com.sbwnpc.squad.squad

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModEntities
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.Pose
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Server-wide registry of squads (command groups). Squad membership does not decide alliance —
 * that's the scoreboard team ([SquadTeams]) — but forming a squad puts every member on the
 * squad's faction team.
 */
class SquadManager : SavedData() {

    private val squads = LinkedHashMap<UUID, Squad>()

    fun all(): Collection<Squad> = squads.values
    fun get(id: UUID?): Squad? = id?.let { squads[it] }
    fun forOwner(owner: UUID): List<Squad> = squads.values.filter { it.owner == owner }
    fun squadOf(entity: UUID): Squad? = squads.values.firstOrNull { entity in it.members }

    /** Server-side authorization check — every network handler that acts on a squad by id (as
     *  opposed to picking from the caller's own [forOwner] list) must gate on this before doing
     *  anything, since a squad id in a packet is just a string a client chose to send. */
    fun ownedBy(id: UUID, player: UUID): Boolean = get(id)?.owner == player

    /** Null if [owner] is already at the [MAX_SQUADS_PER_OWNER] cap — chosen to match the 1-9
     *  number keys the quick-command HUD selects squads with. */
    fun create(level: ServerLevel, owner: UUID, faction: SquadFaction, members: List<UUID>): Squad? {
        if (forOwner(owner).size >= MAX_SQUADS_PER_OWNER) return null
        // Captured now so a future barracks assignment knows what "full strength" means for this
        // squad — the actual classes it was formed/last topped up with, not a guess.
        val npcs = members.mapNotNull { findEntity(level.server, it) as? NpcEntity }
        val composition = npcs.map { it.npcClass }
        val rank = npcs.firstOrNull()?.npcRank ?: NpcRank.DEFAULT
        val tank = composition.contains(NpcClass.TANK_CREW)
        val prefix = if (tank) "Tank " else ""
        val initialOrder = when {
            // Tank crews structurally can't run anything but MOVE — setOrder forces it right back
            // the instant anyone tries to change it, so starting there avoids a one-tick mismatch.
            tank -> SquadOrder.MOVE
            else -> SquadOrder.DEFEND
        }
        val squad = Squad(
            UUID.randomUUID(), nextName(owner, prefix), faction, initialOrder, members.toMutableList(),
            null, null, owner, null, composition, rank
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

    fun disband(level: ServerLevel, id: UUID) {
        val squad = squads.remove(id) ?: return
        squad.members.forEach { m -> (findEntity(level.server, m) as? NpcEntity)?.squadId = null }
        setDirty()
    }

    fun setOrder(id: UUID, order: SquadOrder) {
        squads[id]?.let {
            val available = SquadOrder.availableFor(
                isTankSquad(it), isMortarSquad(it), isGunshipSquad(it), isTransportSquad(it)
            )
            it.order = when {
                order in available -> order
                isTankSquad(it) -> SquadOrder.MOVE
                else -> SquadOrder.DEFEND
            }
            setDirty()
        }
    }

    fun isTankSquad(squad: Squad): Boolean =
        squad.originalComposition.contains(NpcClass.TANK_CREW) || squad.name.startsWith("Tank ")

    /** A gunship squad brought a turret gunner; a transport squad is a pilot without one. */
    fun isGunshipSquad(squad: Squad): Boolean =
        squad.originalComposition.contains(NpcClass.HELICOPTER_GUNNER)

    fun isTransportSquad(squad: Squad): Boolean =
        squad.originalComposition.contains(NpcClass.HELICOPTER_PILOT) && !isGunshipSquad(squad)

    fun isMortarSquad(squad: Squad): Boolean = squad.originalComposition.any {
        it == NpcClass.MORTAR_OPERATOR || it == NpcClass.MORTAR_LOADER
    }

    /** Sets the objective and, if it's a real point, bursts a squad-coloured particle marker
     *  visible only to the squad's owner — the only person who can act on where they just
     *  pointed, and the only one who needs to see it. */
    fun setObjective(level: ServerLevel, id: UUID, pos: BlockPos?) {
        val squad = squads[id] ?: return
        squad.objective = pos
        squad.focusEntity = null
        if (squad.order == SquadOrder.MOVE) {
            squad.moveAssembly = moveAssemblyPoint(level, squad)
            squad.moveFormationReady = false
        }
        setDirty()
        if (pos != null) spawnObjectiveMarker(level, squad, pos)
    }

    private fun spawnObjectiveMarker(level: ServerLevel, squad: Squad, pos: BlockPos) {
        val owner = level.server.playerList.getPlayer(squad.owner) ?: return
        val color = Vec3.fromRGB24(squad.faction.accentColor.color ?: 0xFFFFFF).toVector3f()
        val options = DustParticleOptions(color, 1.5f)
        val center = pos.center
        level.sendParticles(owner, options, true, center.x, center.y + 0.6, center.z, 40, 0.3, 0.7, 0.3, 0.02)
    }

    fun setFocus(id: UUID, entity: UUID?) {
        squads[id]?.takeUnless(::isTankSquad)?.let { it.focusEntity = entity; setDirty() }
    }

    private fun moveAssemblyPoint(level: ServerLevel, squad: Squad): BlockPos? {
        val members = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }
        if (members.isEmpty()) return null
        return BlockPos.containing(
            members.sumOf { it.x } / members.size,
            members.sumOf { it.y } / members.size,
            members.sumOf { it.z } / members.size
        )
    }

    fun rename(id: UUID, name: String) {
        squads[id]?.let { it.name = name.take(MAX_NAME_LENGTH).ifBlank { it.name }; setDirty() }
    }

    fun removeMemberEverywhere(entity: UUID) {
        var changed = false
        squads.values.forEach { if (it.members.remove(entity)) changed = true }
        if (pruneEmptySquads()) changed = true
        if (changed) setDirty()
    }

    private fun pruneEmptySquads(): Boolean = squads.entries.removeIf { it.value.members.isEmpty() && it.value.barracks == null }

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
        squads[id]?.let { it.routeId = routeId; setDirty() }
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

    /** Called periodically by BarracksBlockEntity itself (not on a separate scheduler) for every
     *  squad currently assigned to it: spawns whatever's missing versus [Squad.originalComposition]
     *  at [barracksPos], scattered a little so reinforcements don't all stack on one block.
     *
     *  Reinforcements spawn on the SQUAD's own faction, not whatever faction the barracks' owner
     *  happens to have picked — a real bug fixed here: this used to take a single `faction` param
     *  (the barracks owner's `PlayerFactionRegistry` default) and apply it to every squad linked to
     *  that barracks, regardless of what faction each squad actually was. Since squads can be any
     *  faction (free choice stays available during development), a player's own barracks could end
     *  up respawning troops for someone else's-faction squad wearing the WRONG side's skin/team. */
    fun respawnAtBarracks(level: ServerLevel, barracksPos: BlockPos) {
        val barracks = BarracksRef(level.dimension(), barracksPos)
        val pos = Vec3(barracksPos.x + 0.5, barracksPos.y.toDouble(), barracksPos.z + 0.5)
        val difficulty = level.getCurrentDifficultyAt(barracksPos)
        var changed = false
        squadsAtBarracks(barracks).forEach { squad ->
            val members = squad.members.map { findEntity(level.server, it) as? NpcEntity }
            // Resupply: drone operators standing near their barracks get their drones back.
            members.forEach { npc ->
                if (npc != null && npc.npcClass == NpcClass.DRONE_OPERATOR && npc.isAlive &&
                    npc.position().distanceToSqr(pos) <= RESUPPLY_RADIUS * RESUPPLY_RADIUS
                ) npc.dronesLeft = com.sbwnpc.squad.entity.ai.DroneOperatorBehaviour.MAX_DRONES
            }
            val present = members.map { it?.npcClass }
            val missing = missingClasses(squad.originalComposition, present)
            missing.forEach { cls ->
                val npc = ModEntities.NPC.get().create(level) ?: return@forEach
                val dimensions = npc.getDimensions(Pose.STANDING)
                // A barracks built into a slope/hillside means the ±3-block scatter can easily land
                // on a spot where the terrain has no safe footing in range at all — try a few
                // scatter offsets before falling back to the barracks' own spot, which is guaranteed
                // to stand on solid ground since the block itself is placed there.
                var spawnX = pos.x
                var spawnZ = pos.z
                var spawnY: Double? = null
                for (attempt in 0 until 5) {
                    val tryX = if (attempt == 0) pos.x else pos.x + (level.random.nextDouble() - 0.5) * 3.0
                    val tryZ = if (attempt == 0) pos.z else pos.z + (level.random.nextDouble() - 0.5) * 3.0
                    val tryY = SafeSpawn.findSafeY(level, tryX, tryZ, barracksPos.y, dimensions)
                    if (tryY != null) {
                        spawnX = tryX; spawnZ = tryZ; spawnY = tryY
                        break
                    }
                }
                npc.moveTo(spawnX, spawnY ?: (barracksPos.y + 1.0), spawnZ, level.random.nextFloat() * 360f, 0f)
                npc.npcClass = cls
                npc.npcRank = squad.rank
                npc.spawnFaction = squad.faction
                npc.finalizeSpawn(level, difficulty, MobSpawnType.SPAWN_EGG, null)
                level.addFreshEntity(npc)
                squad.members.add(npc.uuid)
                npc.squadId = squad.id
                changed = true
            }
        }
        if (changed) setDirty()
    }

    private fun nextName(owner: UUID, prefix: String = ""): String {
        val used = forOwner(owner).map { it.name }.toSet()
        return NAMES.firstOrNull { prefix + it !in used }
            ?.let { prefix + it }
            ?: (prefix + "Squad " + (forOwner(owner).size + 1))
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val list = ListTag()
        squads.values.forEach { list.add(it.save()) }
        tag.put("Squads", list)
        return tag
    }

    companion object {
        private const val FILE = "sbwnpc_squads"
        const val MAX_SQUADS_PER_OWNER = 9
        private const val RESUPPLY_RADIUS = 16.0
        const val MAX_NAME_LENGTH = 24
        private val NAMES = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel")

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): SquadManager {
            val mgr = SquadManager()
            tag.getList("Squads", Tag.TAG_COMPOUND.toInt()).forEach { e ->
                val squad = Squad.load(e as CompoundTag)
                mgr.squads[squad.id] = squad
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
            val mgr = server.overworld().dataStorage.computeIfAbsent(
                SavedData.Factory({ SquadManager() }, ::load, null), FILE
            )
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

        fun findEntity(server: MinecraftServer, uuid: UUID): Entity? {
            for (level in server.allLevels) level.getEntity(uuid)?.let { return it }
            return null
        }
    }
}
