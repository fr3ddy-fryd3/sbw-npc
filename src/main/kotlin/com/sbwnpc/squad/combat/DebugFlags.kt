package com.sbwnpc.squad.combat

/**
 * Central switch for in-game debug particle markers (cover-search choice in
 * [com.sbwnpc.squad.entity.ai.SeekCoverBehaviour], firing-position choice in
 * [com.sbwnpc.squad.entity.ai.GunAttackBehaviour]) — per user request: these get toggled off
 * temporarily fairly often (e.g. playing with a friend, not wanting the particle spam), so flip
 * [MARKERS_ENABLED] here rather than commenting out individual `sendParticles` call sites scattered
 * across files.
 */
object DebugFlags {
    var MARKERS_ENABLED = true
}
