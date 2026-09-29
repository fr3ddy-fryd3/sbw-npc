package com.sbwnpc.squad.domain.port

import net.minecraft.server.level.ServerPlayer

/** What a Supply hands a player: kits. */
interface PlayerSupply {
    /** Kit names, in the order [issueKit] takes them. */
    val kits: List<String>

    /** What kit [index] holds, one line per item, for the screen. */
    fun kitContents(index: Int): List<String>

    /** Gives the player whatever of kit [index] they don't already carry — up to the kit's count of
     *  each item, not on top of it, so a Supply can't be milked by clicking. True when anything was
     *  handed over. */
    fun issueKit(player: ServerPlayer, index: Int): Boolean
}
