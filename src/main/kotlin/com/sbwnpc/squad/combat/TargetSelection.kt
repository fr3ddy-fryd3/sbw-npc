package com.sbwnpc.squad.combat

internal object TargetSelection {
    /** Once a visible candidate is found, farther candidates cannot win and need no raycast. */
    inline fun <T> nearestVisible(candidates: Iterable<T>, distanceSqr: (T) -> Double, canSee: (T) -> Boolean): T? {
        var nearest: T? = null
        var nearestDistance = Double.POSITIVE_INFINITY
        for (candidate in candidates) {
            val distance = distanceSqr(candidate)
            if (distance < nearestDistance && canSee(candidate)) {
                nearest = candidate
                nearestDistance = distance
            }
        }
        return nearest
    }
}
