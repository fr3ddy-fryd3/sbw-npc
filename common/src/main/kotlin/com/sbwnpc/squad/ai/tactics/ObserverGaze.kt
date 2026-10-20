package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import kotlin.math.hypot

/**
 * Where an observer at his post looks when he has no target and no focus point.
 *
 * It never depends on where he already looks: a point derived from his own gaze and raised a little
 * made him raise it every tick until the head's pitch limit (#120).
 */
object ObserverGaze {
    /** Nearer than this to a point, the direction to it is too uncertain to face. */
    private const val MIN_DISTANCE = 4.0

    /**
     * What an observer's gaze can follow, most telling first: the nearest known [contact], where
     * fire came from ([threatOrigin]), the order's [objective], the squad's [centre]. An attack faces
     * its objective, where the enemy is ([attacking]); a defence holds it, so it faces outward.
     */
    data class Cues(val contact: Point3?, val threatOrigin: Point3?, val objective: Point3?, val attacking: Boolean, val centre: Point3?)

    /** The unit direction (x, z) from [post] to look along, or null when no cue says where to face. */
    fun direction(post: Point3, cues: Cues): Pair<Double, Double>? {
        toward(post, cues.contact)?.let { return it }
        toward(post, cues.threatOrigin)?.let { return it }
        cues.objective?.let { if (cues.attacking) toward(post, it) else toward(it, post) }?.let { return it }
        return cues.centre?.let { toward(it, post) }
    }

    private fun toward(from: Point3, to: Point3?): Pair<Double, Double>? {
        if (to == null) return null
        val dx = to.x - from.x
        val dz = to.z - from.z
        val length = hypot(dx, dz)
        return if (length < MIN_DISTANCE) null else dx / length to dz / length
    }
}
