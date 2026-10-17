package com.sbwnpc.squad.domain.port

/** Client-only SBW contracts, initialized during client setup. */
object ClientPorts {
    lateinit var gunPoses: GunPoses
    lateinit var gunRendering: GunRendering
}
