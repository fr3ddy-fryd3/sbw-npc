package com.sbwnpc.squad.entity

import com.sbwnpc.squad.domain.port.Ports

/** Every live piece of a smoke grenade's cloud per level ([EntityIndex]), for [com.sbwnpc.squad.combat.SmokeClouds]. */
object SmokeCloudRegistry : EntityIndex({ Ports.grenades.isSmokeCloud(it) })
