package com.sbwnpc.squad.domain.port

import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer

/** One weapon a kit can be taken with, and everything that comes with it, as item ids and counts. */
class KitOption(val weapon: ResourceLocation, val items: List<Pair<ResourceLocation, Int>>)

/** A player's kit at a Supply, kitted out the way the NPC class of the same name is. */
class Kit(val name: String, val options: List<KitOption>)

/** What a Supply hands a player: kits. */
interface PlayerSupply {
    val kits: List<Kit>

    /** Gives the player whatever of kit [kit] with weapon [option] they don't already have — up to
     *  the kit's count of each item, not on top of it, and the helmet and vest of [faction] into
     *  empty armour slots only, so a Supply can't be milked by clicking. True when anything was
     *  handed over. */
    fun issueKit(player: ServerPlayer, kit: Int, option: Int, faction: SquadFaction?): Boolean
}
