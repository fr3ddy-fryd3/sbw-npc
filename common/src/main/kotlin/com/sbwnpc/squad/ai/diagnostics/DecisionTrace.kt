package com.sbwnpc.squad.ai.diagnostics

import com.sbwnpc.squad.ModIdentity
import com.sbwnpc.squad.combat.BuildFlags
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Why each NPC does what it does (#193), written to `logs/sbwnpc-decisions.txt` for playtest
 * reports and headless scenes: per squad, its order, operation and strategy, one line per man with
 * the chain down to his action or the reason he waits, and the last decisions with the options they
 * weighed.
 *
 * Before it, rebuilding why one man stood still meant matching log lines of several groups by UUID
 * and time by hand. Only development builds keep it ([BuildFlags.DEBUG_ENABLED]); every entry point
 * returns at once otherwise. Decisions are recorded where they are made, the chain is read from the
 * planner's state when the file is written, so nothing is copied every tick.
 */
object DecisionTrace {
    /** Off in release builds; a unit test switches it on. */
    @Volatile
    internal var enabled = BuildFlags.DEBUG_ENABLED

    val on get() = enabled

    /** How often the file is rewritten, in server ticks: 10 s. */
    const val DUMP_TICKS = 200

    /** Decisions kept per squad or operation: its own (strategy, plans, orders) and its men's. */
    const val LAST_DECISIONS = 50

    /** Decisions kept per man. */
    const val LAST_PER_MAN = 5

    const val FILE = "sbwnpc-decisions.txt"

    private const val BYTES_PER_KB = 1024

    /** A decision equal to one of this many latest is a repeat. */
    private const val REPEAT_WINDOW = 8

    /** Enough of an id to tell the men of one dump apart. */
    private const val SHORT_ID = 8

    fun short(id: UUID): String = id.toString().take(SHORT_ID)

    /** One decision: when, by whom, what it was about, and what it weighed and chose. */
    data class Decision(val tick: Long, val actor: String, val event: String, val detail: String) {
        override fun toString() = "$tick $actor $event $detail"
    }

    /** One squad as the file shows it: [header] lines, a chain line per man, and its decisions. */
    data class Section(val title: String, val header: List<String>, val members: List<String>, val decisions: List<Decision>)

    private val groups = HashMap<UUID, ArrayDeque<Decision>>()
    private val men = HashMap<UUID, ArrayDeque<Decision>>()
    private var dirty = false
    private var lastDump: Long? = null
    private var failedOnce = false

    /** A decision of the squad or operation [group]. */
    fun group(group: UUID, tick: Long, actor: String, event: String, detail: String) {
        if (!enabled) return
        val ring = groups.getOrPut(group) { ArrayDeque() }
        // A replan that weighed the same and chose the same adds nothing to read. A squad replans its
        // strategy and its approach in turn, so the same decision recurs a few entries apart.
        if (ring.takeLast(REPEAT_WINDOW).any { it.actor == actor && it.event == event && it.detail == detail }) return
        add(ring, Decision(tick, actor, event, detail), LAST_DECISIONS)
    }

    /** A decision about the man [npc]. */
    fun man(npc: UUID, tick: Long, event: String, detail: String) {
        if (!enabled) return
        add(men.getOrPut(npc) { ArrayDeque() }, Decision(tick, short(npc), event, detail), LAST_PER_MAN)
    }

    private fun add(ring: ArrayDeque<Decision>, decision: Decision, limit: Int) {
        ring.addLast(decision)
        while (ring.size > limit) ring.removeFirst()
        dirty = true
    }

    /** The decisions kept for a squad, an operation or a man: their ids never coincide. */
    fun decisionsOf(id: UUID): List<Decision> = (groups[id] ?: men[id]).orEmpty().toList()

    /** Drops the records of squads, operations and men that are gone, so a long session does not grow. */
    fun retain(live: Set<UUID>) {
        groups.keys.retainAll(live)
        men.keys.retainAll(live)
    }

    fun clearAll() {
        groups.clear()
        men.clear()
        dirty = false
        lastDump = null
        failedOnce = false
    }

    /**
     * Rewrites [file] with [sections] when [DUMP_TICKS] have passed since the last write and
     * something was recorded since. [sections] is only built then. One call per server tick covers
     * every level, so dimensions do not overwrite each other.
     */
    fun tick(serverTick: Long, file: Path, sections: () -> List<Section>) {
        if (!enabled || !dirty || lastDump?.let { serverTick - it < DUMP_TICKS } == true) return
        lastDump = serverTick
        dirty = false
        dump(file, serverTick, sections())
    }

    /**
     * Writes [sections] to [file] through a temporary file moved over it, so a reader never sees half
     * a dump. A failed write is logged once and never thrown into the server tick.
     */
    fun dump(file: Path, serverTick: Long, sections: List<Section>) {
        val started = System.nanoTime()
        val text = render(serverTick, sections)
        try {
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(temp, text)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            if (!failedOnce) ModIdentity.LOGGER.warn("Could not write the decision trace to {}: {}", file, e.message)
            failedOnce = true
            return
        }
        com.sbwnpc.squad.combat.DebugFlags.log(
            com.sbwnpc.squad.combat.LogGroup.PERF,
            "decision trace: {} squads, {} KB in {} ms",
            sections.size,
            text.length / BYTES_PER_KB,
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
        )
    }

    /** The file's text; stamped with the server tick, so a file no longer updated shows as stale. */
    fun render(serverTick: Long, sections: List<Section>): String =
        buildString {
            appendLine("# sbwnpc decision trace, server tick $serverTick, written ${java.time.LocalTime.now().withNano(0)}")
            appendLine("# per squad: order, operation, strategy; a line per man: duty <- rule, post, action or why he waits, what else holds him; then the last decisions, oldest first")
            for (section in sections) {
                appendLine()
                appendLine("== ${section.title}")
                section.header.forEach { appendLine("  $it") }
                section.members.forEach { appendLine("  - $it") }
                if (section.decisions.isNotEmpty()) appendLine("  decisions:")
                section.decisions.forEach { appendLine("    $it") }
            }
        }
}
