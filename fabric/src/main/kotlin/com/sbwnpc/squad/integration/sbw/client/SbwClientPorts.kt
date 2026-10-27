package com.sbwnpc.squad.integration.sbw.client

import com.sbwnpc.squad.domain.port.ClientPorts

/** NPCs hold an SBW gun in the pose SBW gives it and draw its static LOD model, as on NeoForge. */
object SbwClientPorts {
    fun install() {
        ClientPorts.gunPoses = SbwGunPoses
        SbwStaticGunRendering.install()
    }
}
