package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.AgentLevel
import com.sbwnpc.squad.ai.planning.FactSnapshot
import com.sbwnpc.squad.ai.planning.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class OperationManoeuvresTest {
    private val facts = FactSnapshot(AgentId(AgentLevel.OPERATION, UUID(0, 1)), 0)
    private fun allowed(option: OperationManoeuvre, input: ManoeuvreAssessment?) = OperationManoeuvres.eligible(option, input).evaluate(facts) == Verdict.Allowed

    // The allowed option scoring highest; the envelop wins ties, as the planner tries equal methods by name.
    private fun best(input: ManoeuvreAssessment?) = OperationManoeuvre.entries.filter { allowed(it, input) }.sortedBy { OperationManoeuvres.methodId(it) }.maxBy { OperationManoeuvres.score(it, input).value }

    @Test fun `without two groups or with sides unknown the envelop stands, as today`() {
        assertEquals(OperationManoeuvre.ENVELOP, best(null))
        assertEquals(OperationManoeuvre.ENVELOP, best(ManoeuvreAssessment(ManoeuvreAssessment.Side(20.0, null), ManoeuvreAssessment.Side(20.0, null))))
    }

    @Test fun `a short walk to high ground takes that flank, a long way round does not`() {
        val left = ManoeuvreAssessment.Side(30.0, 10.0)
        val right = ManoeuvreAssessment.Side(30.0, 0.0)
        assertEquals(OperationManoeuvre.FLANK_LEFT, best(ManoeuvreAssessment(left, right)))
        assertEquals(OperationManoeuvre.ENVELOP, best(ManoeuvreAssessment(left.copy(route = 200.0), right)))
        assertTrue(OperationManoeuvres.score(OperationManoeuvre.FLANK_LEFT, ManoeuvreAssessment(left.copy(route = 200.0), right)).value < OperationManoeuvres.score(OperationManoeuvre.ENVELOP, null).value)
    }

    @Test fun `a group high off to a side near the defence attacks from its height`() {
        val stand = ManoeuvreAssessment.HeightStand(offset = 1.2, rise = 10.0, overAllies = 8.0, defence = 30.0)
        val flat = ManoeuvreAssessment.Side(30.0, 0.0)
        assertEquals(OperationManoeuvre.FROM_HEIGHT, best(ManoeuvreAssessment(flat, flat, stand)))
        assertEquals(1.2, OperationManoeuvres.offset(OperationManoeuvre.FROM_HEIGHT, ManoeuvreAssessment(flat, flat, stand)))
        assertTrue(!allowed(OperationManoeuvre.FROM_HEIGHT, ManoeuvreAssessment(flat, flat)))
        assertTrue(OperationManoeuvres.flankStand(1.2) && !OperationManoeuvres.flankStand(0.2) && !OperationManoeuvres.flankStand(2.0))
    }
}
