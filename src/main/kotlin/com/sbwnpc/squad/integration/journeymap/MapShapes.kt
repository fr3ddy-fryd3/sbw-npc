package com.sbwnpc.squad.integration.journeymap

import com.sbwnpc.squad.SquadMod
import net.minecraft.resources.ResourceLocation

/**
 * The map's symbols: white shapes with a dark rim (`textures/map/`), tinted per faction by
 * JourneyMap. Real texture files, as in JourneyMap's own examples: shapes registered only in code
 * under a made-up location drew nothing at all.
 */
internal enum class MapShapes {
    SQUARE, CIRCLE, TRIANGLE, CROSS;

    val location: ResourceLocation = SquadMod.loc("textures/map/${name.lowercase()}.png")

    companion object {
        const val SIZE = 32
    }
}
