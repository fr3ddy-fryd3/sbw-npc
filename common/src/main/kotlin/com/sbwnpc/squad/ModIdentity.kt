package com.sbwnpc.squad

import net.minecraft.resources.ResourceLocation

/** The mod namespace shared by gameplay and loader entrypoints. */
object ModIdentity {
    const val MODID = "sbwnpc"

    fun loc(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MODID, path)
}
