package com.sbwnpc.squad.experience

/**
 * A value that holds only while a block runs, restored to the outer one afterwards, also when the
 * block throws: one drone's blast that sets off another operator's must not wipe the first blame.
 * Server thread only.
 */
internal class Scoped<T : Any> {
    var current: T? = null
        private set

    fun <R> with(value: T, block: () -> R): R {
        val outer = current
        current = value
        try {
            return block()
        } finally {
            current = outer
        }
    }
}
