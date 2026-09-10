package com.sbwnpc.squad.squad

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/**
 * Server-wide registry of squads (command groups). Squad membership does not change alliance —
 * that's the scoreboard team ([SquadTeams]) — but forming a squad puts every member on the
 * squad's colour team.
 */
class SquadManager : SavedData() {

    private val squads = LinkedHashMap<UUID, Squad>()

    fun all(): Collection<Squad> = squads.values
    fun get(id: UUID?): Squad? = id?.let { squads[it] }
    fun forOwner(owner: UUID): List<Squad> = squads.values.filter { it.owner == owner }
    fun squadOf(entity: UUID): Squad? = squads.values.firstOrNull { entity in it.members }

    fun create(level: ServerLevel, owner: UUID, color: ChatFormatting, members: List<UUID>, commander: UUID?): Squad {
        val name = nextName(owner)
        val squad = Squad(UUID.randomUUID(), name, color, SquadOrder.FREE, commander ?: members.firstOrNull(),
            members.toMutableList(), null, owner)
        squads[squad.id] = squad
        members.forEach { m ->
            level.getEntity(m)?.let { SquadTeams.assign(it, color) }
            (level.getEntity(m) as? NpcEntity)?.squadId = squad.id
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

    fun setObjective(id: UUID, pos: BlockPos?) {
        squads[id]?.let { it.objective = pos; setDirty() }
    }

    fun removeMemberEverywhere(entity: UUID) {
        squads.values.forEach { it.members.remove(entity); if (it.commander == entity) it.commander = it.members.firstOrNull() }
        squads.entries.removeIf { it.value.members.isEmpty() }
        setDirty()
    }

    private fun nextName(owner: UUID): String {
        val used = forOwner(owner).map { it.name }.toSet()
        return NAMES.firstOrNull { it !in used } ?: ("Squad " + (forOwner(owner).size + 1))
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        tag.put("Squads", ListTag().apply { squads.values.forEach { add(it.save()) } })
        return tag
    }

    companion object {
        private const val FILE = "sbwnpc_squads"
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
