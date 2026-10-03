package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalTerrainRulesTest {
    private val members = (0..5).map { TacticalMember(UUID(0,it.toLong()),Vec3(it*5.0,64.0,0.0),canFire=true) }
    private fun view(height: Double = 64.0) = TacticalSnapshot(0,SquadOrder.ATTACK,1,Vec3(12.5,64.0,0.0),
        Vec3(100.0,64.0,0.0),members,listOf(TacticalContact(UUID(2,1),Vec3(50.0,height,0.0),0)))
    @Test fun `an elevated enemy produces a supported side approach rather than a frontal rush`() {
        val view = view(74.0)
        assertEquals(TacticalPattern.ATTACK_HEIGHT,TacticalRules.choose(view).pattern)
        val plan = TacticalPlan(1,TacticalPattern.ATTACK_HEIGHT,view.contacts[0].position,0,1)
        TacticalManeuvers.assign(UUID(0,2),plan,view)
        assertTrue(plan.tasks.values.any { it.job == TacticalJob.WAIT && it.anchor.y > view.center.y })
        assertTrue(plan.tasks.values.filter { it.job == TacticalJob.WAIT }.all { kotlin.math.abs(it.anchor.z) >= 5.0 })
    }
    @Test fun `height advantage is retained even while defending`() {
        val view = view(54.0).copy(order=SquadOrder.DEFEND,home=Vec3(12.5,64.0,0.0))
        assertEquals(TacticalPattern.HOLD_HEIGHT,TacticalRules.choose(view).pattern)
        val plan = TacticalPlan(1,TacticalPattern.HOLD_HEIGHT,view.contacts[0].position,0,1)
        TacticalManeuvers.assign(UUID(0,2),plan,view)
        assertTrue(plan.tasks.values.all { it.anchor.y == 64.0 && it.job == TacticalJob.COVER })
    }
    @Test fun `an unopposed narrow crossing releases only the first pair`() {
        val view = view().copy(contacts=emptyList(),narrow=true,order=SquadOrder.MOVE)
        assertEquals(TacticalPattern.FILE,TacticalRules.choose(view).pattern)
        val state = SquadTacticalState()
        state.select(TacticalRules.choose(view),1,0)
        val plan=state.plan!!
        TacticalManeuvers.assign(UUID(0,2),plan,view)
        TacticalManeuvers.advance(UUID(0,2),state,plan,view)
        TacticalManeuvers.assign(UUID(0,2),plan,view)
        assertEquals(2,plan.tasks.values.count { it.job == TacticalJob.ADVANCE })
        assertEquals(4,plan.tasks.values.count { it.job == TacticalJob.WAIT })
    }
    @Test fun `explicit retreat wins over a narrow crossing or elevation plan`() {
        assertEquals(TacticalPattern.FOLLOW_ORDER,TacticalRules.choose(view(74.0).copy(order=SquadOrder.RETREAT,narrow=true)).pattern)
    }

    @Test fun `sixteen fighters use an actual firing lane to release the majority uphill`() {
        val members = (0..15).map { TacticalMember(UUID(0,it.toLong()),Vec3(it*2.0,64.0,0.0),canFire=it==15) }
        val view = view(90.0).copy(members=members,center=Vec3(15.0,64.0,0.0),peakStrength=16)
        val state = SquadTacticalState()
        state.select(TacticalRules.choose(view),1,0)
        val plan = state.plan!!
        TacticalManeuvers.assign(UUID(0,2),plan,view)
        assertEquals(TacticalJob.COVER,plan.tasks[members.last().id]!!.job)
        assertEquals(5,plan.tasks.values.count { it.job == TacticalJob.COVER })
        assertEquals(11,plan.tasks.values.count { it.job == TacticalJob.WAIT })
        plan.tasks.values.filter { it.job == TacticalJob.COVER }.forEach { it.position=it.anchor }
        TacticalManeuvers.advance(UUID(0,2),state,plan,view)
        assertEquals(TacticalStatus.EXECUTING,plan.status)
        assertEquals(11,plan.tasks.values.count { it.job == TacticalJob.ADVANCE })
    }

    @Test fun `after the uphill group arrives the rear climbs under its covering fire`() {
        val members = (0..15).map { TacticalMember(UUID(0,it.toLong()),Vec3(it*2.0,64.0,0.0),canFire=true) }
        var view = view(90.0).copy(members=members,center=Vec3(15.0,64.0,0.0),peakStrength=16)
        val state = SquadTacticalState()
        state.select(TacticalRules.choose(view),1,0)
        val plan = state.plan!!
        TacticalManeuvers.assign(UUID(0,2),plan,view)
        val originalSupport = plan.heightSupport.toSet()
        settleCover(plan)
        TacticalManeuvers.advance(UUID(0,2),state,plan,view)
        view = arrive(plan,view.copy(now=40))
        TacticalManeuvers.advance(UUID(0,2),state,plan,view)
        assertEquals(1,plan.bounds)
        assertEquals(TacticalStatus.PREPARING,plan.status)
        assertTrue(plan.heightFront!!.y > view.center.y)
        settleCover(plan)
        TacticalManeuvers.advance(UUID(0,2),state,plan,view)
        assertEquals(originalSupport,plan.tasks.filterValues { it.job == TacticalJob.ADVANCE }.keys)
        assertTrue(plan.tasks.filterValues { it.job == TacticalJob.ADVANCE }.values.all { it.anchor.y == plan.heightFront!!.y })
        assertTrue(plan.tasks.filterValues { it.job == TacticalJob.COVER }.keys.none { it in originalSupport })
        view = arrive(plan,view.copy(now=80))
        TacticalManeuvers.advance(UUID(0,2),state,plan,view)
        assertEquals(2,plan.bounds)
        settleCover(plan)
        TacticalManeuvers.advance(UUID(0,2),state,plan,view)
        assertEquals(11,plan.tasks.values.count { it.job == TacticalJob.ADVANCE })
        assertTrue(plan.tasks.filterValues { it.job == TacticalJob.ADVANCE }.values.all { it.anchor.y > plan.heightFront!!.y })
    }

    @Test fun `a late firing lane promotes its shooter from the waiting group instead of deadlocking`() {
        val members = (0..15).map { TacticalMember(UUID(0,it.toLong()),Vec3(it*2.0,64.0,0.0),canFire=false) }
        val initial = view(90.0).copy(members=members,center=Vec3(15.0,64.0,0.0),peakStrength=16)
        val state = SquadTacticalState()
        state.select(TacticalRules.choose(initial),1,0)
        val plan = state.plan!!
        TacticalManeuvers.assign(UUID(0,2),plan,initial)
        assertEquals(TacticalJob.WAIT,plan.tasks[members.last().id]!!.job)
        val ready = initial.copy(now=20,members=members.mapIndexed { index,member -> member.copy(canFire=index==15) })
        TacticalManeuvers.advance(UUID(0,2),state,plan,ready)
        assertEquals(TacticalJob.COVER,plan.tasks[members.last().id]!!.job)
        assertEquals(TacticalStatus.EXECUTING,plan.status)
        assertEquals(11,plan.tasks.values.count { it.job == TacticalJob.ADVANCE })
    }

    @Test fun `an uphill plan has time for the support group to catch up`() {
        val state = SquadTacticalState()
        val choice = TacticalRules.choose(view(90.0))
        state.select(choice,1,0)
        val plan = state.plan
        assertFalse(state.select(choice,1,300))
        assertSame(plan,state.plan)
        assertTrue(state.select(choice,1,480))
    }

    @Test fun `a ridge hiding the last seen enemy does not reduce the uphill attack to two searchers`() {
        val view = view(90.0).copy(now=40,members=members.map { it.copy(canFire=false) },
            incoming=listOf(Vec3(96.0,80.0,0.0)))
        assertTrue(view.visible.isEmpty())
        val choice = TacticalRules.choose(view)
        assertEquals(TacticalPattern.ATTACK_HEIGHT,choice.pattern)
        assertEquals(view.contacts[0].position,choice.focus)
        val state = SquadTacticalState()
        state.select(choice,1,40)
        val plan = state.plan!!
        TacticalManeuvers.assign(UUID(0,2),plan,view)
        TacticalManeuvers.advance(UUID(0,2),state,plan,view)
        assertEquals(TacticalStatus.EXECUTING,plan.status)
        assertEquals(4,plan.tasks.values.count { it.job == TacticalJob.ADVANCE })
        assertFalse(plan.tasks.values.any { it.job == TacticalJob.SEARCH })
    }

    private fun settleCover(plan: TacticalPlan) {
        plan.tasks.values.filter { it.job == TacticalJob.COVER }.forEach { it.position=it.anchor }
    }

    private fun arrive(plan: TacticalPlan,view: TacticalSnapshot): TacticalSnapshot = view.copy(members=view.members.map { member ->
        val task = plan.tasks[member.id]
        if (task?.job == TacticalJob.ADVANCE) {
            task.position=task.anchor
            member.copy(position=task.anchor)
        } else member
    })
}
