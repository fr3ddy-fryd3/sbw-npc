package com.sbwnpc.squad.domain.port

/** Where the rest of the mod finds the outside world. Wired once, in the mod constructor. */
object Ports {
    lateinit var guns: Guns
    lateinit var vehicles: Vehicles
}
