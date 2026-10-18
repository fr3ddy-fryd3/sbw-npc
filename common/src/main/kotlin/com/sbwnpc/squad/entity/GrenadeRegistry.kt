package com.sbwnpc.squad.entity

import com.sbwnpc.squad.domain.port.Ports

/**
 * Every live hand grenade per level ([EntityIndex]). Every NPC asks "is a
 * grenade about to go off next to me?" every tick, and the answer is almost always "there are no
 * grenades at all", which this makes a single empty-map lookup.
 *
 * Only grenades on a timed fuse: they lie there long enough to run from. Contact-fuzed rounds go
 * off before anyone could react.
 */
object GrenadeRegistry : EntityIndex({ Ports.grenades.isTimedGrenade(it) })
