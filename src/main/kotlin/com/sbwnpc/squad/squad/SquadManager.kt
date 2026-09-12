package com.sbwnpc.squad.squad

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModEntities
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
import net.minecraft.world.entity.MobSpawnType
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
        val composition = members.mapNotNull { (level.getEntity(it) as? NpcEntity)?.npcClass }
        val squad = Squad(
            UUID.randomUUID(), nextName(owner), faction, SquadOrder.FREE, members.toMutableList(),
            null, null, owner, null, composition
        )
        squads[squad.id] = squad
        members.forEach { m ->
            val e = level.getEntity(m)
            if (e != null) SquadTeams.assign(e, faction)
            (e as? NpcEntity)?.squadId = squad.id
        }
        setDirty()
        return squad
    }

    fun disband(level: ServerLevel, id: UUID) {
        val squad = squads.remove(id) ?: return
        squad.members.forEach { m -> (level.getEntity(m) as? NpcEntity)?.squadId = null }
        setDirty()
    }

    fun setOrder(id: UUID, order: SquadOrder) {
        squads[id]?.let { it.order = order; setDirty() }
    }

    /** Sets the objective and, if it's a real point, bursts a squad-coloured particle marker
     *  visible only to the squad's owner — the only person who can act on where they just
     *  pointed, and the only one who needs to see it. */
    fun setObjective(level: ServerLevel, id: UUID, pos: BlockPos?) {
        val squad = squads[id] ?: return
        squad.objective = pos
        squad.focusEntity = null
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
        squads[id]?.let { it.focusEntity = entity; setDirty() }
    }

    fun rename(id: UUID, name: String) {
        squads[id]?.let { it.name = name.take(24).ifBlank { it.name }; setDirty() }
    }

    fun removeMemberEverywhere(entity: UUID) {
        squads.values.forEach { it.members.remove(entity) }
        // A squad tied to a barracks survives at 0 members — it's waiting on resupply, not
        // abandoned. Only an unlinked empty squad gets cleaned up automatically.
        squads.entries.removeIf { it.value.members.isEmpty() && it.value.barracksPos == null }
        setDirty()
    }

    fun squadsAtBarracks(barracksPos: BlockPos): List<Squad> = squads.values.filter { it.barracksPos == barracksPos }

    fun assignBarracks(id: UUID, barracksPos: BlockPos?) {
        squads[id]?.let { it.barracksPos = barracksPos; setDirty() }
    }

    fun assignRoute(id: UUID, routeId: UUID?) {
        squads[id]?.let { it.routeId = routeId; setDirty() }
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
        val pos = Vec3(barracksPos.x + 0.5, barracksPos.y.toDouble(), barracksPos.z + 0.5)
        val difficulty = level.getCurrentDifficultyAt(barracksPos)
        var changed = false
        squadsAtBarracks(barracksPos).forEach { squad ->
            val missing = squad.originalComposition.drop(squad.members.size)
            missing.forEach { cls ->
                val npc = ModEntities.NPC.get().create(level) ?: return@forEach
                val offX = (level.random.nextDouble() - 0.5) * 3.0
                val offZ = (level.random.nextDouble() - 0.5) * 3.0
                npc.moveTo(pos.x + offX, pos.y, pos.z + offZ, level.random.nextFloat() * 360f, 0f)
                npc.npcClass = cls
                npc.npcRank = NpcRank.DEFAULT
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

    private fun nextName(owner: UUID): String {
        val used = forOwner(owner).map { it.name }.toSet()
        return NAMES.firstOrNull { it !in used } ?: ("Squad " + (forOwner(owner).size + 1))
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
        private val NAMES = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel")

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): SquadManager {
            val mgr = SquadManager()
            tag.getList("Squads", Tag.TAG_COMPOUND.toInt()).forEach { e ->
                val squad = Squad.load(e as CompoundTag)
                mgr.squads[squad.id] = squad
            }
            return mgr
        }

        fun get(server: MinecraftServer): SquadManager =
            server.overworld().dataStorage.computeIfAbsent(
                SavedData.Factory({ SquadManager() }, ::load, null), FILE
            )

        fun get(level: ServerLevel): SquadManager = get(level.server)
    }
}
