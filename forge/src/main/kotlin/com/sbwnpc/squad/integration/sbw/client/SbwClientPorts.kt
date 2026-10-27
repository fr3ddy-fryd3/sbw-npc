package com.sbwnpc.squad.integration.sbw.client

import com.sbwnpc.squad.domain.port.ClientPorts
import net.minecraftforge.eventbus.api.IEventBus

/**
 * The client ports on Forge: NPCs hold an SBW gun in the arm pose SBW gives it ([SbwGunPoses]) and
 * draw its static model ([SbwStaticGunRendering]) without GeckoLib, as the NeoForge module does.
 */
object SbwClientPorts {
    fun install(modBus: IEventBus) {
        ClientPorts.gunPoses = SbwGunPoses
        SbwStaticGunRendering.install(modBus)
    }
}
