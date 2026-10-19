package com.sbwnpc.squad

import net.minecraft.resources.ResourceLocation
import org.apache.logging.log4j.LogManager

/** The mod namespace and logger shared by gameplay and loader entrypoints. */
object ModIdentity {
    const val MODID = "sbwnpc"
    val LOGGER = LogManager.getLogger(MODID)

    fun loc(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MODID, path)
}
