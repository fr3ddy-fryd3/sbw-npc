package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.planning.AgentId
import com.sbwnpc.squad.ai.planning.Condition
import com.sbwnpc.squad.ai.planning.FactView
import com.sbwnpc.squad.ai.planning.Method
import com.sbwnpc.squad.ai.planning.TaskKey
import com.sbwnpc.squad.ai.planning.TaskNetwork

/**
 * A man's answer to fire as HTN methods (#251), built once per root's call type, so the full root
 * and the roots without a position intent (#189: a squadless man's, a lone operator's, a crewman's)
 * answer it alike; [UnderFire] holds the conditions, the cover executors the doing.
 */
object FireAnswers {
    /** The answer to fire of a root without a position intent: its cover, dig, hold or fall back. */
    val ANSWER_TASK = TaskKey("npc.answer_fire", EngageTask::class.java)

    /** The same root's hole: climb out, dig deeper or hold. */
    val HOLE_TASK = TaskKey("npc.hole", EngageTask::class.java)

    /**
     * The two methods that join another root to its man's answer to fire: the roots without a
     * position intent list them, and every other method of theirs stands aside by [UnderFire.alone],
     * so exactly one family applies.
     */
    fun <P : Any> forRoot(actor: (P) -> AgentId): List<Method<P>> =
        listOf(
            Method("under_fire", eligible = { _ -> UnderFire.firedOnAlone }, decompose = { call, _ -> TaskNetwork.sequence(ANSWER_TASK.call(EngageTask(actor(call), forward = false))) }),
            Method("in_hole", eligible = { _ -> UnderFire.inHole() }, decompose = { call, _ -> TaskNetwork.sequence(HOLE_TASK.call(EngageTask(actor(call), forward = false))) }),
        )

    /** Cover from the fire's sector, else dig in, else [holdOpen] when engaged, else fall back. */
    fun <P : Any> cover(actor: (P) -> AgentId, holdOpen: (P, FactView) -> TaskNetwork): List<Method<P>> =
        listOf(
            Method("take_cover", eligible = { _ -> UnderFire.coverLeft() }, decompose = { call, facts -> coverMove(actor(call), facts, fallBack = false) }),
            Method("dig_in", eligible = { _ -> UnderFire.noCover(dig = true) }, decompose = { call, facts -> TaskNetwork.sequence(TacticalDomain.DIG_TASK.call(DigIn(actor(call), facts.value(TacticalFacts.DIGS) ?: 0))) }),
            Method("hold_open", eligible = { _ -> Condition.all(UnderFire.noCover(dig = false), Condition.test(TacticalFacts.ENGAGED, "not_engaged") { it }) }, decompose = holdOpen),
            Method("fall_back", eligible = { _ -> Condition.all(UnderFire.noCover(dig = false), Condition.test(TacticalFacts.ENGAGED, "engaged") { !it }) }, decompose = { call, facts -> coverMove(actor(call), facts, fallBack = true) }),
        )

    /** His hole: climb out, one block deeper, or hold. */
    fun <P : Any> hole(actor: (P) -> AgentId): List<Method<P>> =
        listOf(
            Method("leave_hole", eligible = { _ -> UnderFire.leaveHole() }, decompose = { call, _ -> TaskNetwork.sequence(TacticalDomain.LEAVE_HOLE_TASK.call(LeaveHole(actor(call)))) }),
            Method("dig_deeper", eligible = { _ -> UnderFire.digDeeper() }, decompose = { call, facts -> TaskNetwork.sequence(TacticalDomain.DIG_TASK.call(DigIn(actor(call), facts.value(TacticalFacts.DIGS)!!))) }),
            Method("hold_in_hole", eligible = { _ -> UnderFire.holdHole() }, decompose = { call, facts -> TaskNetwork.sequence(TacticalDomain.HOLD_HOLE_TASK.call(HoldHole(actor(call), facts.value(TacticalFacts.DIGS)!!))) }),
        )

    private fun coverMove(actor: AgentId, facts: FactView, fallBack: Boolean): TaskNetwork {
        val sector = facts.value(TacticalFacts.FIRE_SECTOR)!!
        return TaskNetwork.sequence(
            TacticalDomain.COVER_MOVE_TASK.call(CoverMove(actor, sector, fallBack = fallBack, armour = facts.value(ArmourFacts.RELEVANT))),
            TacticalDomain.COVER_CYCLE_TASK.call(CoverCycle(actor, facts.value(TacticalFacts.COVER_CYCLES) ?: 0, facts.value(ArmourFacts.RELEVANT))),
        )
    }
}
