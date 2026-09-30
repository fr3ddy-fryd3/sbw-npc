package com.sbwnpc.squad.route

import net.minecraft.server.level.ServerLevel

/**
 * The lie of the land column by column, as the travellers over it ([Walking], [Driving]) read it:
 * in the game the remembered map of a level ([GroundMap]); in a test, ground taken from a saved
 * world.
 */
interface Ground {
    fun known(x: Int, z: Int): Boolean
    fun kind(x: Int, z: Int): GroundMap.Kind
    /** The height a man stands at over the column — see [GroundMap.height]. */
    fun height(x: Int, z: Int): Int
    /** Free blocks over the ground, up to [GroundMap.MAX_ROOM]. */
    fun room(x: Int, z: Int): Int

    companion object {
        fun of(level: ServerLevel): Ground = object : Ground {
            override fun known(x: Int, z: Int) = GroundMap.known(level, x, z)
            override fun kind(x: Int, z: Int) = GroundMap.kind(level, x, z)
            override fun height(x: Int, z: Int) = GroundMap.height(level, x, z)
            override fun room(x: Int, z: Int) = GroundMap.room(level, x, z)
        }
    }
}
