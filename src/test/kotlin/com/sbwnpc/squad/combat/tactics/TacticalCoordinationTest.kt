package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalCoordinationTest {
    private val squad = UUID(0, 2)
    private val members = (0..5).map { TacticalMember(UUID(0,it.toLong()), Vec3(it*5.0,64.0,0.0), canFire = true) }
    private fun view(now: Long = 0, order: SquadOrder = SquadOrder.ATTACK) = TacticalSnapshot(now, order, 1,
        Vec3(12.5,64.0,0.0), Vec3(12.5,64.0,0.0), members,
        listOf(TacticalContact(UUID(2,1),Vec3(50.0,64.0,0.0),now), TacticalContact(UUID(2,2),Vec3(53.0,64.0,1.0),now)))

    @Test fun `changing a threat sector turns the shooter without replacing its path or cover post`() {
        val state=SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.REORIENT,Vec3(50.0,64.0,0.0),true),1,0)
        val plan=state.plan!!
        TacticalManeuvers.assign(squad,plan,view())
        val task=plan.tasks[members[0].id]!!
        task.position=members[0].position
        assertFalse(state.select(TacticalChoice(TacticalPattern.REORIENT,Vec3(-50.0,64.0,0.0),true),1,10))
        val threat=TacticalContact(UUID(9,9),Vec3(-50.0,64.0,0.0),10)
        TacticalManeuvers.refreshSectors(plan,view(10).copy(contacts=listOf(threat)))
        assertSame(task,plan.tasks[members[0].id])
        assertEquals(members[0].position,task.position)
        assertEquals(threat.position,task.focus)
        assertTrue(state.select(TacticalChoice(TacticalPattern.FOLLOW_ORDER,null),2,11))
    }

    @Test fun `brief contact loss and stalled sight cannot cancel an executing flank`() {
        val state=SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.FLANK,Vec3(50.0,64.0,0.0)),1,0)
        val plan=state.plan!!
        plan.status=TacticalStatus.EXECUTING
        assertFalse(state.select(TacticalChoice(TacticalPattern.SEARCH,plan.focus),1,120))
        assertFalse(state.select(TacticalChoice(TacticalPattern.RETURN_FIRE,Vec3(100.0,64.0,0.0)),1,130))
        assertFalse(state.select(TacticalChoice(TacticalPattern.DISLODGE,plan.focus),1,140))
        assertSame(plan,state.plan)
        assertTrue(state.select(TacticalChoice(TacticalPattern.REPEL,plan.focus,true),1,150))
    }

    @Test fun `a late ready shooter can release the waiting flank group`() {
        val state=SquadTacticalState()
        val initial=view().copy(members=members.map { it.copy(canFire=false) })
        state.select(TacticalRules.choose(initial),1,0)
        val plan=state.plan!!
        TacticalManeuvers.assign(squad,plan,initial)
        val shooter=plan.tasks.entries.last { it.value.job == TacticalJob.WAIT }.key
        val ready=initial.copy(now=20,members=initial.members.map { it.copy(canFire=it.id==shooter) })
        TacticalManeuvers.advance(squad,state,plan,ready)
        assertEquals(TacticalStatus.EXECUTING,plan.status)
        assertEquals(TacticalJob.COVER,plan.tasks[shooter]!!.job)
        assertEquals(4,plan.tasks.values.count { it.job == TacticalJob.FLANK })
        val support=plan.tasks.filterValues { it.job == TacticalJob.COVER }.keys.toSet()
        TacticalManeuvers.assign(squad,plan,ready.copy(members=members.reversed()))
        assertEquals(support,plan.tasks.filterValues { it.job == TacticalJob.COVER }.keys)
    }

    @Test fun `an unready covering group cannot release the flankers`() {
        val state = SquadTacticalState()
        val view = view()
        val unready=view.copy(members=members.map { it.copy(canFire=false,recentFire=false) })
        state.select(TacticalRules.choose(view),1,0)
        val plan = state.plan!!
        TacticalManeuvers.assign(squad,plan,unready)
        assertTrue(plan.tasks.values.any { it.job == TacticalJob.WAIT })
        TacticalManeuvers.advance(squad,state,plan,unready)
        assertEquals(TacticalStatus.PREPARING,plan.status)
        assertFalse(plan.tasks.values.any { it.job == TacticalJob.FLANK })
        plan.tasks.values.filter { it.job == TacticalJob.COVER }.forEach { it.position = it.anchor }
        TacticalManeuvers.advance(squad,state,plan,view)
        assertEquals(TacticalStatus.EXECUTING,plan.status)
        assertTrue(plan.tasks.values.any { it.job == TacticalJob.FLANK })
    }
    @Test fun `covering fire preserves its chosen positions during a maneuver`() {
        val state = SquadTacticalState(); val view = view()
        state.select(TacticalRules.choose(view),1,0)
        val plan = state.plan!!
        TacticalManeuvers.assign(squad,plan,view)
        val cover = plan.tasks.filterValues { it.job == TacticalJob.COVER }
        cover.values.forEach { it.position = it.anchor }
        TacticalManeuvers.advance(squad,state,plan,view)
        cover.forEach { (id,task) -> assertSame(task,plan.tasks[id]) }
    }
    @Test fun `a pinned group unable to open a firing lane fails without releasing a charge`() {
        val state = SquadTacticalState()
        val initial = view().copy(members=members.map { it.copy(canFire=false,recentFire=false,suppressed=true) })
        state.select(TacticalRules.choose(initial),1,0)
        val plan = state.plan!!
        TacticalManeuvers.assign(squad,plan,initial)
        TacticalManeuvers.advance(squad,state,plan,initial.copy(now=110,contacts=initial.contacts.map { it.copy(seenAt=110) }))
        assertEquals(TacticalStatus.FAILED,plan.status)
        assertEquals(TacticalPattern.FLANK,state.blockedPattern)
        assertTrue(state.blockedUntil > 110)
    }
    @Test fun `small score changes preserve the plan but a fresh player order replaces it immediately`() {
        val state = SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.FLANK,Vec3(50.0,64.0,0.0)),1,0)
        val old = state.plan
        assertFalse(state.select(TacticalChoice(TacticalPattern.BOUND,Vec3(51.0,64.0,0.0)),1,20))
        assertSame(old,state.plan)
        assertTrue(state.select(TacticalChoice(TacticalPattern.FOLLOW_ORDER,null),2,21))
        assertNotSame(old,state.plan)
    }
    @Test fun `unknown shooters do not produce a precise flanking contact`() {
        val view = view().copy(contacts = emptyList(), incoming = listOf(Vec3(80.0,64.0,0.0)))
        assertEquals(TacticalPattern.RETURN_FIRE,TacticalRules.choose(view).pattern)
    }
    @Test fun `a defender does not pursue an unseen target or stop a distant straggler`() {
        val view = view(order = SquadOrder.DEFEND).copy(contacts = emptyList(), members =
            listOf(members[0].copy(position = Vec3(200.0,64.0,0.0))))
        val choice = TacticalRules.choose(view)
        assertEquals(TacticalPattern.CONSOLIDATE,choice.pattern)
        val plan = TacticalPlan(1,choice.pattern,choice.focus,0,1)
        TacticalManeuvers.assign(squad,plan,view)
        assertTrue(plan.tasks.isEmpty())
        assertFalse(TacticalRules.withinOrder(view,Vec3(100.0,64.0,0.0),24.0))
    }
    @Test fun `medics remain behind the maneuver group`() {
        val view = view().copy(members = members + TacticalMember(UUID(0,9),Vec3(10.0,64.0,0.0),NpcClass.MEDIC))
        val plan = TacticalPlan(1,TacticalPattern.FLANK,Vec3(50.0,64.0,0.0),0,1,TacticalStatus.EXECUTING)
        TacticalManeuvers.assign(squad,plan,view)
        assertEquals(TacticalJob.RESERVE,plan.tasks[UUID(0,9)]!!.job)
        assertTrue(plan.tasks[UUID(0,9)]!!.anchor.x < view.center.x)
    }
    @Test fun `firing in another direction does not count as covering the maneuver`() {
        val state=SquadTacticalState()
        val view=view().copy(members=members.map { it.copy(firingAt=Vec3(-100.0,64.0,0.0)) })
        state.select(TacticalRules.choose(view),1,0)
        val plan=state.plan!!
        TacticalManeuvers.assign(squad,plan,view)
        plan.tasks.values.filter { it.job == TacticalJob.COVER }.forEach { it.position=it.anchor }
        TacticalManeuvers.advance(squad,state,plan,view)
        assertEquals(TacticalStatus.PREPARING,plan.status)
    }

    @Test fun `one unreachable covering member does not cancel the other members maneuver`() {
        val state = SquadTacticalState()
        val view = view()
        state.select(TacticalRules.choose(view),1,0)
        val plan = state.plan!!
        TacticalManeuvers.assign(squad,plan,view)
        val failed = plan.tasks.entries.first { it.value.job == TacticalJob.COVER }.key
        TacticalManeuvers.abandon(plan,failed)
        plan.tasks.values.filter { it.job == TacticalJob.COVER }.forEach { it.position=it.anchor }
        TacticalManeuvers.advance(squad,state,plan,view)
        assertEquals(TacticalStatus.EXECUTING,plan.status)
        assertNull(plan.tasks[failed])
        assertTrue(plan.tasks.values.any { it.job == TacticalJob.FLANK })
        assertNull(state.blockedPattern)
        TacticalManeuvers.assign(squad,plan,view)
        assertNull(plan.tasks[failed], "a phase change must not reclaim the failed member from individual AI")
    }

    @Test fun `waiting for cover does not consume the covering fire timeout before movement starts`() {
        val state = SquadTacticalState()
        val initial = view()
        state.select(TacticalRules.choose(initial),1,0)
        val plan = state.plan!!
        TacticalManeuvers.assign(squad,plan,initial)
        plan.tasks.values.filter { it.job == TacticalJob.COVER }.forEach { it.position=it.anchor }
        TacticalManeuvers.advance(squad,state,plan,view(80))
        assertEquals(TacticalStatus.EXECUTING,plan.status)
        assertEquals(80L,plan.lastCover)
        TacticalManeuvers.advance(squad,state,plan,view(90).copy(members=members.map { it.copy(canFire=false) }))
        assertEquals(TacticalStatus.EXECUTING,plan.status)
    }
}
