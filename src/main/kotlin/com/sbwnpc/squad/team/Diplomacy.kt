package com.sbwnpc.squad.team

import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.PlayerFactionRegistry
import com.sbwnpc.squad.team.AllianceRegistry.Companion.FACTIONS
import com.sbwnpc.squad.team.AllianceRegistry.Companion.bit
import com.sbwnpc.squad.team.AllianceRegistry.Companion.membersOf
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * Alliances between factions and the votes that make and break them.
 *
 * - An alliance has two to four factions.
 * - It is made when more than half of each would-be member's players agree, and a faction leaves
 *   when at least half of its own players want out.
 * - Only players online count. A faction nobody is playing right now agrees on its own — otherwise
 *   one absent player could hold every decision up.
 * - A faction that leaves stays at truce with its former allies for [TRUCE_TICKS]: nobody shoots
 *   yet, and both sides have time to pull their troops apart.
 *
 * Allies are not hostile to each other ([SquadTeams.isHostile]), share what they have spotted
 * (`TeamAwareness`) and get treated by each other's medics. A truce only stops the shooting.
 */
object Diplomacy {
    /** Three minutes. */
    const val TRUCE_TICKS = 3L * 60 * 20
    /** An offer nobody acts on lapses after five minutes. */
    const val PROPOSAL_TICKS = 5L * 60 * 20
    const val MAX_MEMBERS = 4
    private const val RESOLVE_INTERVAL = 20L

    @Volatile private var registry: AllianceRegistry? = null
    @Volatile private var now = 0L

    fun attach(server: MinecraftServer) {
        registry = AllianceRegistry.get(server)
        now = server.overworld().gameTime
    }

    fun detach() {
        registry = null
    }

    fun tick(server: MinecraftServer) {
        val reg = registry ?: return
        now = server.overworld().gameTime
        if (now % RESOLVE_INTERVAL == 0L) resolve(server, reg)
    }

    // --- Queries ---

    fun allied(a: SquadFaction, b: SquadFaction): Boolean {
        if (a == b) return true
        val reg = registry ?: return false
        return reg.blocOf[a.ordinal] and bit(b) != 0
    }

    /** Not to be shot at: the same side, an ally, or a truce still running. */
    fun friendly(a: SquadFaction, b: SquadFaction): Boolean {
        if (a == b) return true
        val reg = registry ?: return false
        if (reg.blocOf[a.ordinal] and bit(b) != 0) return true
        return reg.truceUntil[a.ordinal * FACTIONS + b.ordinal] > now
    }

    /** [faction] and everyone allied with it. */
    fun alliesOf(faction: SquadFaction): List<SquadFaction> {
        val mask = registry?.blocOf?.get(faction.ordinal) ?: 0
        return if (mask == 0) listOf(faction) else membersOf(mask)
    }

    fun truceTicksLeft(a: SquadFaction, b: SquadFaction): Long {
        val reg = registry ?: return 0
        return maxOf(0L, reg.truceUntil[a.ordinal * FACTIONS + b.ordinal] - now)
    }

    fun proposals(): List<AllianceRegistry.Proposal> = registry?.proposals ?: emptyList()

    fun wantsOut(faction: SquadFaction, player: java.util.UUID): Boolean =
        registry?.leaveVotes?.get(faction.ordinal)?.contains(player) == true

    // --- Commands, all from a player. Each returns what to tell them. ---

    fun propose(server: MinecraftServer, player: ServerPlayer, target: SquadFaction): Component {
        val reg = registry ?: return fail("Diplomacy is not available right now.")
        val own = factionOf(server, player) ?: return fail("Pick a faction first.")
        if (own == target) return fail("That is your own faction.")
        if (allied(own, target)) return fail("${target.label} are already your allies.")
        val members = blocMask(reg, own) or blocMask(reg, target)
        if (Integer.bitCount(members) > MAX_MEMBERS) return fail("An alliance can have at most $MAX_MEMBERS factions.")
        reg.proposals.firstOrNull { it.members == members }?.let { existing ->
            return vote(server, reg, existing, own, player)
        }
        val proposal = AllianceRegistry.Proposal(reg.nextProposalId++, members, own, target, now + PROPOSAL_TICKS)
        proposal.yes.getOrPut(own.ordinal) { HashSet() } += player.uuid
        reg.proposals += proposal
        reg.setDirty()
        announce(server, members, Component.literal("${own.label} propose an alliance: ${names(members)}. ")
            .append(Component.literal("Vote in Diplomacy.").withStyle(ChatFormatting.YELLOW)))
        resolve(server, reg)
        return ok("Alliance proposed to ${target.label}.")
    }

    fun accept(server: MinecraftServer, player: ServerPlayer, proposalId: Int): Component {
        val reg = registry ?: return fail("Diplomacy is not available right now.")
        val own = factionOf(server, player) ?: return fail("Pick a faction first.")
        val proposal = reg.proposals.firstOrNull { it.id == proposalId } ?: return fail("That offer has lapsed.")
        if (proposal.members and bit(own) == 0) return fail("That offer is not to your faction.")
        return vote(server, reg, proposal, own, player)
    }

    fun voteLeave(server: MinecraftServer, player: ServerPlayer): Component {
        val reg = registry ?: return fail("Diplomacy is not available right now.")
        val own = factionOf(server, player) ?: return fail("Pick a faction first.")
        if (reg.blocOf[own.ordinal] == 0) return fail("Your faction is not in an alliance.")
        val votes = reg.leaveVotes.getOrPut(own.ordinal) { HashSet() }
        if (!votes.add(player.uuid)) {
            votes.remove(player.uuid)
            reg.setDirty()
            return ok("You withdrew your vote to leave the alliance.")
        }
        reg.setDirty()
        announce(server, bit(own), Component.literal("${player.gameProfile.name} votes to leave the alliance."))
        resolve(server, reg)
        return ok("You voted to leave the alliance.")
    }

    private fun vote(
        server: MinecraftServer, reg: AllianceRegistry, proposal: AllianceRegistry.Proposal,
        own: SquadFaction, player: ServerPlayer
    ): Component {
        if (!proposal.yes.getOrPut(own.ordinal) { HashSet() }.add(player.uuid)) return fail("You already agreed.")
        reg.setDirty()
        resolve(server, reg)
        return ok("You agreed to the alliance: ${names(proposal.members)}.")
    }

    // --- Rules ---

    private fun resolve(server: MinecraftServer, reg: AllianceRegistry) {
        val online = onlineByFaction(server)
        var changed = false

        val lapsed = reg.proposals.filter { it.expiresAt <= now }
        for (p in lapsed) announce(server, p.members, Component.literal("The alliance offer (${names(p.members)}) lapsed."))
        if (reg.proposals.removeAll(lapsed.toSet())) changed = true

        // Agreed offers. One at a time: forming an alliance can make the others impossible.
        while (true) {
            val agreed = reg.proposals.firstOrNull { p ->
                membersOf(p.members).all { f -> majority(p.yes[f.ordinal], online[f.ordinal]) }
            } ?: break
            form(server, reg, agreed.members)
            reg.proposals.remove(agreed)
            reg.proposals.removeAll { p ->
                Integer.bitCount(p.members or blocUnion(reg, p.members)) > MAX_MEMBERS ||
                    membersOf(p.members).all { allied(it, p.proposer) }
            }
            changed = true
        }

        for (faction in SquadFaction.entries) {
            val votes = reg.leaveVotes[faction.ordinal] ?: continue
            if (reg.blocOf[faction.ordinal] == 0) {
                reg.leaveVotes.remove(faction.ordinal)
                changed = true
                continue
            }
            val present = votes.count { it in online[faction.ordinal] }
            val players = online[faction.ordinal].size
            if (players > 0 && present * 2 >= players) {
                leave(server, reg, faction)
                changed = true
            }
        }
        if (changed) reg.setDirty()
    }

    /** More than half of those online, or nobody online at all. */
    private fun majority(votes: Set<java.util.UUID>?, online: Set<java.util.UUID>): Boolean {
        if (online.isEmpty()) return true
        val present = votes?.count { it in online } ?: 0
        return present * 2 > online.size
    }

    private fun form(server: MinecraftServer, reg: AllianceRegistry, members: Int) {
        val all = members or blocUnion(reg, members)
        for (f in membersOf(all)) {
            reg.blocOf[f.ordinal] = all
            reg.leaveVotes.remove(f.ordinal)
            for (g in membersOf(all)) setTruce(reg, f, g, 0L)
        }
        announce(server, all, Component.literal("Alliance formed: ${names(all)}.").withStyle(ChatFormatting.GREEN))
    }

    private fun leave(server: MinecraftServer, reg: AllianceRegistry, faction: SquadFaction) {
        val old = reg.blocOf[faction.ordinal]
        val rest = old and bit(faction).inv()
        reg.blocOf[faction.ordinal] = 0
        reg.leaveVotes.remove(faction.ordinal)
        val remaining = membersOf(rest)
        for (f in remaining) {
            reg.blocOf[f.ordinal] = if (remaining.size >= 2) rest else 0
            setTruce(reg, faction, f, now + TRUCE_TICKS)
        }
        announce(
            server, old,
            Component.literal("${faction.label} left the alliance. Truce for ${TRUCE_TICKS / 20 / 60} minutes.")
                .withStyle(ChatFormatting.GOLD)
        )
    }

    private fun setTruce(reg: AllianceRegistry, a: SquadFaction, b: SquadFaction, until: Long) {
        reg.truceUntil[a.ordinal * FACTIONS + b.ordinal] = until
        reg.truceUntil[b.ordinal * FACTIONS + a.ordinal] = until
    }

    private fun blocMask(reg: AllianceRegistry, f: SquadFaction): Int =
        reg.blocOf[f.ordinal].takeIf { it != 0 } ?: bit(f)

    /** Every alliance any of [members] already belongs to. */
    private fun blocUnion(reg: AllianceRegistry, members: Int): Int =
        membersOf(members).fold(0) { acc, f -> acc or blocMask(reg, f) }

    // --- Players ---

    private fun factionOf(server: MinecraftServer, player: ServerPlayer): SquadFaction? =
        PlayerFactionRegistry.get(server).get(player.uuid)

    private fun onlineByFaction(server: MinecraftServer): Array<MutableSet<java.util.UUID>> {
        val factions = PlayerFactionRegistry.get(server)
        val out = Array(FACTIONS) { HashSet<java.util.UUID>() as MutableSet<java.util.UUID> }
        for (p in server.playerList.players) factions.get(p.uuid)?.let { out[it.ordinal] += p.uuid }
        return out
    }

    private fun announce(server: MinecraftServer, factions: Int, message: Component) {
        val registry = PlayerFactionRegistry.get(server)
        val line = Component.literal("[Diplomacy] ").withStyle(ChatFormatting.AQUA).append(message)
        for (p in server.playerList.players) {
            val f = registry.get(p.uuid) ?: continue
            if (factions and bit(f) != 0) p.sendSystemMessage(line)
        }
    }

    private fun names(mask: Int): String = membersOf(mask).joinToString(", ") { it.label }

    private fun ok(msg: String): Component = Component.literal(msg).withStyle(ChatFormatting.GREEN)
    private fun fail(msg: String): Component = Component.literal(msg).withStyle(ChatFormatting.RED)
}
