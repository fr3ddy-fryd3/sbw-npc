package com.sbwnpc.squad.entity

import com.sbwnpc.squad.domain.port.Ports

/**
 * Every loaded piloted drone (SBW's own and the Drone Warfare addon's alike) per level.
 * `AntiDroneBehaviour` asks "any hostile drone near me?" from every NPC every few ticks; walking
 * this beats a 96-block entity box query per NPC.
 */
object DroneRegistry : EntityIndex({ Ports.drones.isPiloted(it) })
