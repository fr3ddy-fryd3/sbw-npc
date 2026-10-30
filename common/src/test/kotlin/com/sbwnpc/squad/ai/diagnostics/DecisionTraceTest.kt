package com.sbwnpc.squad.ai.diagnostics

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class DecisionTraceTest {
    private val squad = UUID(1, 1)
    private val man = UUID(2, 2)

    @BeforeEach fun on() {
        DecisionTrace.clearAll()
        DecisionTrace.enabled = true
    }

    @AfterEach fun off() {
        DecisionTrace.clearAll()
        DecisionTrace.enabled = false
    }

    @Test fun `keeps the newest decisions in order and skips a repeat`() {
        repeat(DecisionTrace.LAST_DECISIONS + 5) { DecisionTrace.group(squad, it.toLong(), "squad", "plan", "choice $it") }
        DecisionTrace.group(squad, 999, "squad", "plan", "choice ${DecisionTrace.LAST_DECISIONS + 4}")
        val kept = DecisionTrace.decisionsOf(squad)
        assertEquals(DecisionTrace.LAST_DECISIONS, kept.size)
        assertEquals(5L, kept.first().tick)
        assertEquals(DecisionTrace.LAST_DECISIONS + 4L, kept.last().tick)
        repeat(DecisionTrace.LAST_PER_MAN + 2) { DecisionTrace.man(man, it.toLong(), "duty", "FIRE <- defending_fire") }
        assertEquals(DecisionTrace.LAST_PER_MAN, DecisionTrace.decisionsOf(man).size)
    }

    @Test fun `records nothing and writes nothing when off`(@TempDir dir: Path) {
        DecisionTrace.enabled = false
        DecisionTrace.group(squad, 1, "squad", "plan", "x")
        DecisionTrace.man(man, 1, "duty", "x")
        DecisionTrace.tick(1000, dir.resolve(DecisionTrace.FILE)) { error("sections built while off") }
        assertTrue(DecisionTrace.decisionsOf(squad).isEmpty())
        assertFalse(Files.exists(dir.resolve(DecisionTrace.FILE)))
    }

    @Test fun `writes every DUMP_TICKS only after something new, and stamps the tick`(@TempDir dir: Path) {
        val file = dir.resolve(DecisionTrace.FILE)
        val section = DecisionTrace.Section("Alpha", listOf("order=DEFEND"), listOf("0000 RIFLEMAN duty=FIRE <- defending_fire action=[post:FAILED(local_position_query_exhausted)]"), emptyList())
        DecisionTrace.man(man, 1, "duty", "FIRE <- defending_fire")
        DecisionTrace.tick(500, file) { listOf(section) }
        val text = Files.readString(file)
        assertTrue(text.contains("server tick 500"))
        assertTrue(text.contains("== Alpha") && text.contains("order=DEFEND") && text.contains("FAILED(local_position_query_exhausted)"))
        DecisionTrace.man(man, 2, "duty", "MOVE <- mover")
        DecisionTrace.tick(500 + DecisionTrace.DUMP_TICKS - 1L, file) { error("written before DUMP_TICKS") }
        DecisionTrace.tick(500 + DecisionTrace.DUMP_TICKS.toLong(), file) { listOf(section) }
        DecisionTrace.tick(500 + 2L * DecisionTrace.DUMP_TICKS, file) { error("written with nothing new") }
        assertTrue(Files.readString(file).contains("server tick ${500 + DecisionTrace.DUMP_TICKS}"))
    }

    @Test fun `forgets the records of what is gone`() {
        DecisionTrace.group(squad, 1, "squad", "plan", "x")
        DecisionTrace.man(man, 1, "duty", "x")
        DecisionTrace.retain(setOf(squad))
        assertEquals(1, DecisionTrace.decisionsOf(squad).size)
        assertTrue(DecisionTrace.decisionsOf(man).isEmpty())
    }
}
