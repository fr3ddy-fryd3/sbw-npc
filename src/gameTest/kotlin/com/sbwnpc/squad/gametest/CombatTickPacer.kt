package com.sbwnpc.squad.gametest

import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.concurrent.locks.LockSupport
import java.util.function.Consumer

/** GameTest skips normal tick waiting, whereas the production firing timer uses wall-clock time. */
class CombatTickPacer : Consumer<ServerTickEvent.Pre> {
    private var previous = System.nanoTime()

    override fun accept(event: ServerTickEvent.Pre) {
        val deadline = previous + 50_000_000L
        while (System.nanoTime() < deadline) LockSupport.parkNanos(deadline - System.nanoTime())
        previous = System.nanoTime()
    }
}
