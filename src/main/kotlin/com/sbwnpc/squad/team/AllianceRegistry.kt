package com.sbwnpc.squad.team

import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/**
 * Who is allied with whom, per world. Only the data and its persistence — the rules that change it
 * live in [Diplomacy].
 *
 * Factions are few and fixed, so everything is kept as bit masks and flat arrays indexed by
 * ordinal: [Diplomacy.friendly] runs inside every hostility check, which is to say constantly.
 */
class AllianceRegistry : SavedData() {
    /** Each faction's alliance as a mask of its members, itself included; 0 when in none. */
    val blocOf = IntArray(FACTIONS)

    /** Game time a pair's truce runs until, at `[a * FACTIONS + b]`, kept symmetric. */
    val truceUntil = LongArray(FACTIONS * FACTIONS)

    val proposals = ArrayList<Proposal>()

    /** Players of a faction who want it out of its alliance, by faction ordinal. */
    val leaveVotes = HashMap<Int, MutableSet<UUID>>()

    var nextProposalId = 1

    /**
     * An offer to join two sides into one alliance. [members] is everyone who would end up in it:
     * both factions plus whatever alliances they already belong to.
     */
    class Proposal(
        val id: Int,
        val members: Int,
        val proposer: SquadFaction,
        val target: SquadFaction,
        val expiresAt: Long,
        /** Players in favour, by faction ordinal. */
        val yes: HashMap<Int, MutableSet<UUID>> = HashMap()
    )

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        tag.putIntArray("Blocs", blocOf)
        tag.putLongArray("Truces", truceUntil)
        tag.putInt("NextProposal", nextProposalId)
        val list = ListTag()
        for (p in proposals) {
            val pt = CompoundTag()
            pt.putInt("Id", p.id)
            pt.putInt("Members", p.members)
            pt.putString("Proposer", p.proposer.name)
            pt.putString("Target", p.target.name)
            pt.putLong("Expires", p.expiresAt)
            pt.put("Yes", votesTag(p.yes))
            list.add(pt)
        }
        tag.put("Proposals", list)
        tag.put("Leave", votesTag(leaveVotes))
        return tag
    }

    private fun votesTag(votes: Map<Int, Set<UUID>>): CompoundTag {
        val out = CompoundTag()
        for ((faction, players) in votes) {
            val ids = ListTag()
            players.forEach { ids.add(net.minecraft.nbt.StringTag.valueOf(it.toString())) }
            out.put(faction.toString(), ids)
        }
        return out
    }

    companion object {
        val FACTIONS = SquadFaction.entries.size
        private const val FILE = "sbwnpc_alliances"

        fun bit(faction: SquadFaction): Int = 1 shl faction.ordinal

        fun membersOf(mask: Int): List<SquadFaction> = SquadFaction.entries.filter { mask and bit(it) != 0 }

        private fun readVotes(tag: CompoundTag): HashMap<Int, MutableSet<UUID>> {
            val out = HashMap<Int, MutableSet<UUID>>()
            for (key in tag.allKeys) {
                val faction = key.toIntOrNull() ?: continue
                val ids = tag.getList(key, Tag.TAG_STRING.toInt())
                val set = HashSet<UUID>()
                for (i in 0 until ids.size) runCatching { UUID.fromString(ids.getString(i)) }.getOrNull()?.let(set::add)
                if (set.isNotEmpty()) out[faction] = set
            }
            return out
        }

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): AllianceRegistry {
            val reg = AllianceRegistry()
            tag.getIntArray("Blocs").copyInto(reg.blocOf, endIndex = minOf(FACTIONS, tag.getIntArray("Blocs").size))
            tag.getLongArray("Truces").let { it.copyInto(reg.truceUntil, endIndex = minOf(it.size, reg.truceUntil.size)) }
            reg.nextProposalId = maxOf(1, tag.getInt("NextProposal"))
            val list = tag.getList("Proposals", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                val pt = list.getCompound(i)
                val proposer = runCatching { SquadFaction.valueOf(pt.getString("Proposer")) }.getOrNull() ?: continue
                val target = runCatching { SquadFaction.valueOf(pt.getString("Target")) }.getOrNull() ?: continue
                reg.proposals += Proposal(
                    pt.getInt("Id"), pt.getInt("Members"), proposer, target, pt.getLong("Expires"),
                    readVotes(pt.getCompound("Yes"))
                )
            }
            reg.leaveVotes.putAll(readVotes(tag.getCompound("Leave")))
            return reg
        }

        fun get(server: MinecraftServer): AllianceRegistry =
            server.overworld().dataStorage.computeIfAbsent(
                SavedData.Factory({ AllianceRegistry() }, ::load, null), FILE
            )
    }
}
