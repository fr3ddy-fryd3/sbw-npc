package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalSituationsTest {
    private val members = (0..5).map { TacticalMember(UUID(0,it.toLong()),Vec3(0.0,64.0,it*4.0-10.0),canFire=true) }
    private fun enemy(x: Double = 45.0,z: Double = 0.0,velocity: Vec3 = Vec3.ZERO,armour: Boolean = false,priority: Double = 1.0) =
        TacticalContact(UUID.randomUUID(),Vec3(x,64.0,z),0,velocity,armour,priority)
    private fun view(contacts: List<TacticalContact> = listOf(enemy())) = TacticalSnapshot(0,SquadOrder.ATTACK,1,
        Vec3(0.0,64.0,0.0),Vec3(50.0,64.0,0.0),members,contacts)

    @Test fun `wide enemy frontage focuses on a limited sector`() {
        val view=view(listOf(enemy(z=-45.0),enemy(),enemy(z=45.0)))
        val choice=TacticalRules.choose(view)
        assertEquals(TacticalPattern.FOCUS_SECTOR,choice.pattern)
        assertTrue(view.contacts.any { it.position == choice.focus })
    }
    @Test fun `a dangerous isolated shooter warrants a supported flank`() {
        assertEquals(TacticalPattern.FLANK,TacticalRules.choose(view(listOf(enemy(priority=2.0)))).pattern)
    }
    @Test fun `encirclement requires superiority and provides distinct angles`() {
        val view=view().copy(members=members+(6..7).map { TacticalMember(UUID(0,it.toLong()),Vec3(0.0,64.0,it*4.0),canFire=true) })
        assertEquals(TacticalPattern.ENCIRCLE,TacticalRules.choose(view).pattern)
        assertNotEquals(TacticalPattern.ENCIRCLE,TacticalRules.choose(view.copy(members=members)).pattern)
        val plan=TacticalPlan(1,TacticalPattern.ENCIRCLE,view.contacts[0].position,0,1,TacticalStatus.EXECUTING)
        TacticalManeuvers.assign(UUID(0,2),plan,view)
        val destinations=plan.tasks.values.filter { it.job == TacticalJob.FLANK }.map { it.anchor }
        assertTrue(destinations.size >= 4)
        assertEquals(destinations.size,destinations.distinct().size)
    }
    @Test fun `new rear contacts stop the frontal maneuver`() {
        assertEquals(TacticalPattern.REORIENT,TacticalRules.choose(view(listOf(enemy(),enemy(x=-45.0)))).pattern)
        val pinned=view(listOf(enemy(),enemy(x=-45.0))).copy(members=members.map { it.copy(suppressed=true) })
        assertEquals(TacticalPattern.BREAK_CONTACT,TacticalRules.choose(pinned).pattern)
    }
    @Test fun `advancing close enemies trigger repelling rather than pursuit`() {
        assertEquals(TacticalPattern.REPEL,TacticalRules.choose(view(listOf(enemy(x=18.0,velocity=Vec3(-0.2,0.0,0.0))))).pattern)
    }
    @Test fun `pursuit is bounded and forbidden for defenders`() {
        val view=view(listOf(enemy(velocity=Vec3(0.2,0.0,0.0))))
        assertEquals(TacticalPattern.PURSUE,TacticalRules.choose(view).pattern)
        assertNotEquals(TacticalPattern.PURSUE,TacticalRules.choose(view.copy(order=SquadOrder.DEFEND)).pattern)
        val plan=TacticalPlan(1,TacticalPattern.PURSUE,Vec3(150.0,64.0,0.0),0,1,TacticalStatus.EXECUTING)
        plan.origin=Vec3.ZERO.add(0.0,64.0,0.0)
        TacticalManeuvers.assign(UUID(0,2),plan,view.copy(center=Vec3(30.0,64.0,0.0)))
        assertTrue(plan.tasks.values.filter { it.job == TacticalJob.ADVANCE }.all { it.anchor.distanceTo(plan.origin!!) <= 32.01 })
    }
    @Test fun `losing capability cancels ambitious maneuvers`() {
        val depleted=view().copy(members=members.mapIndexed { index,m -> m.copy(ready=index<2) })
        assertEquals(TacticalPattern.REORGANIZE,TacticalRules.choose(depleted).pattern)
        assertEquals(TacticalPattern.REORGANIZE,TacticalRules.choose(view().copy(members=members.take(2),peakStrength=6)).pattern)
    }
    @Test fun `armour is assigned to a loaded launcher and others cover infantry`() {
        val view=view(listOf(enemy(armour=true),enemy(z=10.0))).copy(members=members.mapIndexed { i,m -> m.copy(rockets=i==0) })
        assertEquals(TacticalPattern.ANTI_ARMOUR,TacticalRules.choose(view).pattern)
        assertEquals(TacticalPattern.AVOID_ARMOUR,TacticalRules.choose(view.copy(members=members)).pattern)
        val plan=TacticalPlan(1,TacticalPattern.ANTI_ARMOUR,view.contacts[0].position,0,1)
        TacticalManeuvers.assign(UUID(0,2),plan,view)
        assertEquals(TacticalJob.ANTI_ARMOUR,plan.tasks[members[0].id]!!.job)
        assertTrue(plan.tasks.filterKeys { it != members[0].id }.values.all { it.focus == view.contacts[1].position })
    }
    @Test fun `a lost contact is searched at its recorded position and eventually forgotten`() {
        val old=enemy().copy(seenAt=0)
        assertEquals(TacticalPattern.SEARCH,TacticalRules.choose(view(listOf(old)).copy(now=60)).pattern)
        assertEquals(TacticalPattern.FOLLOW_ORDER,TacticalRules.choose(view().copy(now=240,contacts=emptyList())).pattern)
    }
    @Test fun `grenades interrupt immediately and the former tactic resumes without a dwell delay`() {
        val state=SquadTacticalState()
        state.select(TacticalRules.choose(view()),1,0)
        assertTrue(state.select(TacticalRules.choose(view().copy(grenade=true)),1,10))
        assertEquals(TacticalPattern.EVADE,state.plan!!.pattern)
        assertTrue(state.select(TacticalRules.choose(view()),1,20))
        assertNotEquals(TacticalPattern.EVADE,state.plan!!.pattern)
    }
    @Test fun `a failed flank changes side once while a committed plan stays stable`() {
        val state=SquadTacticalState();state.flankSide=1.0
        state.select(TacticalChoice(TacticalPattern.FLANK,Vec3(40.0,64.0,0.0)),1,0)
        state.fail(100)
        assertEquals(-1.0,state.flankSide)
        assertEquals(220L,state.blocked[TacticalPattern.FLANK])
        state.select(TacticalChoice(TacticalPattern.REORGANIZE,Vec3(40.0,64.0,0.0)),1,110)
        state.fail(200)
        assertTrue(state.blocked.containsKey(TacticalPattern.FLANK))
        assertTrue(state.blocked.containsKey(TacticalPattern.REORGANIZE))
    }
    @Test fun `rear flanking uses an arc instead of crossing the enemy position`() {
        val focus=Vec3(0.0,64.0,0.0)
        val task=TacticalTask(TacticalJob.FLANK,Vec3(18.0,64.0,0.0),focus,1)
        val first=TacticalRoutes.leg(Vec3(-36.0,64.0,0.0),task)
        assertTrue(kotlin.math.abs(first.z)>12.0)
        assertTrue(first.distanceTo(focus)>=18.0)
        assertNotEquals(task.anchor,first)
    }
    @Test fun `position searches defer on budget exhaustion and resume on the next tick`() {
        assertTrue((0..3).all { TacticalBudget.path(42) })
        assertFalse(TacticalBudget.path(42))
        assertTrue(TacticalBudget.path(43))
    }
    @Test fun `the first contact interrupts marching immediately`() {
        val state=SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.FOLLOW_ORDER,null),1,0)
        assertTrue(state.select(TacticalRules.choose(view()),1,10))
        assertNotEquals(TacticalPattern.FOLLOW_ORDER,state.plan!!.pattern)
    }
    @Test fun `infantry does not attempt to climb to an aircraft`() {
        val aircraft=enemy().copy(position=Vec3(45.0,90.0,0.0),airborne=true)
        assertEquals(TacticalPattern.REORIENT,TacticalRules.choose(view(listOf(aircraft))).pattern)
    }
    @Test fun `a solitary survivor does not wait for a nonexistent covering group`() {
        val solo=view().copy(members=members.take(1),peakStrength=1)
        assertEquals(TacticalPattern.FOLLOW_ORDER,TacticalRules.choose(solo).pattern)
    }
}
