package com.sbwnpc.squad.combat

/** Brief clear glimpses do not erase a repeatedly obstructed post; a current clear view can aim. */
class FiringObstruction {
    var ticks = 0
        private set
    private var clear = true
    val requiresRecovery get() = !clear && ticks >= 80

    fun observe(clearShot: Boolean) {
        clear = clearShot
        ticks = (ticks + if (clearShot) -1 else 1).coerceIn(0, 160)
    }

    fun reset() {
        ticks = 0
        clear = true
    }
}
