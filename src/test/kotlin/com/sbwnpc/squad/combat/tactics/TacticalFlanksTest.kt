package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalFlanksTest {
    private val squad=UUID(1,1)
    private val focus=Vec3(80.0,64.0,0.0)
    private val members=(0..31).map { index -> TacticalMember(UUID(0,index.toLong()),
        Vec3((index%8-3.5)*4,64.0,(index/8)*4.0),canFire=true,firingAt=focus,
        recentFire=true,recentFireAt=focus) }
    private fun view()=TacticalSnapshot(0,SquadOrder.ATTACK,1,Vec3(0.0,64.0,0.0),focus,members,
        listOf(TacticalContact(UUID(2,1),focus,0),TacticalContact(UUID(2,2),focus.add(0.0,0.0,45.0),0)))
    private fun setup(view: TacticalSnapshot=view()): Pair<SquadTacticalState,TacticalPlan> {
        val state=SquadTacticalState()
        state.select(TacticalChoice(TacticalPattern.FOCUS_SECTOR,focus),1,0)
        val plan=state.plan!!
        TacticalManeuvers.assign(squad,plan,view)
        plan.tasks.values.filter { it.job==TacticalJob.COVER }.forEach { it.position=it.anchor }
        return state to plan
    }

    @Test fun `wide enemy front is approached from its weaker side by no more than six movers`() {
        val view=view()
        val (state,plan)=setup(view)
        val staging=plan.tasks.values.mapNotNull { it.staging }
        assertEquals(staging.size,staging.distinct().size)
        TacticalManeuvers.advance(squad,state,plan,view)
        val movers=plan.tasks.filterValues { it.job==TacticalJob.FLANK }
        assertEquals(6,movers.size)
        assertTrue(plan.tasks.values.any { it.job==TacticalJob.WAIT })
        assertTrue(movers.values.all { it.anchor.z<=-20.0 })
        assertEquals(movers.size,movers.values.map { it.anchor }.distinct().size)
        assertFalse(plan.tasks.values.any { it.job==TacticalJob.ADVANCE })
    }

    @Test fun `available firing lanes alone cannot launch a large wave and one shooter is insufficient`() {
        val view=view().copy(members=members.map { it.copy(recentFire=false) })
        val (state,plan)=setup(view)
        TacticalManeuvers.advance(squad,state,plan,view)
        assertEquals(TacticalStatus.PREPARING,plan.status)
        val shooter=plan.tasks.entries.first { it.value.job==TacticalJob.COVER }.key
        val one=view.copy(members=view.members.map { it.copy(recentFire=it.id==shooter) })
        TacticalManeuvers.advance(squad,state,plan,one)
        assertEquals(TacticalStatus.PREPARING,plan.status)
        assertFalse(plan.tasks.values.any { it.job==TacticalJob.FLANK })
        val other=plan.tasks.entries.last { it.value.job==TacticalJob.COVER }.key
        val two=view.copy(members=view.members.map { it.copy(recentFire=it.id==shooter || it.id==other) })
        TacticalManeuvers.advance(squad,state,plan,two)
        assertEquals(TacticalStatus.EXECUTING,plan.status)
        assertEquals(6,plan.tasks.values.count { it.job==TacticalJob.FLANK })
    }

    @Test fun `arrival releases the next six and preserves covering posts including distant flankers`() {
        val view=view()
        val (state,plan)=setup(view)
        TacticalManeuvers.advance(squad,state,plan,view)
        val first=plan.tasks.filterValues { it.job==TacticalJob.FLANK }.keys.toSet()
        val cover=plan.tasks.filterValues { it.job==TacticalJob.COVER }.toMap()
        val arrived=view.copy(now=40,contacts=view.contacts.map { it.copy(seenAt=40) },members=view.members.map { npc ->
            if (npc.id in first) {
                val task=plan.tasks[npc.id]!!
                task.position=task.anchor
                npc.copy(position=task.anchor)
            } else npc
        })
        TacticalManeuvers.advance(squad,state,plan,arrived)
        assertEquals(first,plan.flankArrived)
        assertEquals(TacticalStatus.PREPARING,plan.status)
        cover.forEach { (id,task) -> assertSame(task,plan.tasks[id]) }
        first.forEach { assertEquals(TacticalJob.COVER,plan.tasks[it]?.job) }
        TacticalManeuvers.advance(squad,state,plan,arrived)
        val next=plan.tasks.filterValues { it.job==TacticalJob.FLANK }.keys
        assertEquals(6,next.size)
        assertTrue(next.none { it in first })
        first.forEach { assertEquals(TacticalJob.COVER,plan.tasks[it]?.job) }
    }

    @Test fun `late actual shooters are promoted from waiting into the covering group`() {
        val initial=view().copy(members=members.map { it.copy(recentFire=false) })
        val (state,plan)=setup(initial)
        val shooters=plan.tasks.filterValues { it.job==TacticalJob.WAIT }.keys.toList().takeLast(2).toSet()
        val ready=initial.copy(now=20,members=initial.members.map { it.copy(recentFire=it.id in shooters) })
        TacticalManeuvers.advance(squad,state,plan,ready)
        assertEquals(TacticalStatus.EXECUTING,plan.status)
        shooters.forEach { assertEquals(TacticalJob.COVER,plan.tasks[it]?.job) }
        assertEquals(6,plan.tasks.values.count { it.job==TacticalJob.FLANK })
    }

    @Test fun `one failed route does not block the remaining wave or release it into frontal movement`() {
        val view=view()
        val (state,plan)=setup(view)
        TacticalManeuvers.advance(squad,state,plan,view)
        val wave=plan.tasks.filterValues { it.job==TacticalJob.FLANK }.keys.toSet()
        val failed=wave.first()
        TacticalManeuvers.abandon(plan,failed)
        val arrived=view.copy(now=40,contacts=view.contacts.map { it.copy(seenAt=40) },members=view.members.map { npc ->
            val task=plan.tasks[npc.id]
            if (task?.job==TacticalJob.FLANK) {
                task.position=task.anchor
                npc.copy(position=task.anchor)
            } else npc
        })
        TacticalManeuvers.advance(squad,state,plan,arrived)
        assertEquals(wave-failed,plan.flankArrived)
        assertNull(plan.tasks[failed])
        TacticalManeuvers.advance(squad,state,plan,arrived)
        assertEquals(6,plan.tasks.values.count { it.job==TacticalJob.FLANK })
        assertNull(plan.tasks[failed])
        state.snapshot=arrived
        assertTrue(state.holdsAfterFailure(failed,1,40))
    }

    @Test fun `new distant sector proposals preserve a running route while a new player order overrides it`() {
        val (state,plan)=setup()
        plan.status=TacticalStatus.EXECUTING
        val task=plan.tasks.values.first()
        assertFalse(state.select(TacticalChoice(TacticalPattern.FOCUS_SECTOR,focus.add(0.0,0.0,60.0)),1,120))
        assertSame(plan,state.plan)
        assertSame(task,plan.tasks.values.first())
        assertFalse(state.select(TacticalChoice(TacticalPattern.DISLODGE,focus.add(0.0,0.0,60.0)),1,130))
        assertSame(plan,state.plan)
        assertTrue(state.select(TacticalChoice(TacticalPattern.FOLLOW_ORDER,null),2,121))
    }

    @Test fun `failed members hold in combat instead of resuming assault and fresh orders release them`() {
        val view=view()
        val (state,plan)=setup(view)
        val member=members[0].id
        state.snapshot=view
        TacticalManeuvers.abandon(plan,member)
        assertTrue(state.holdsAfterFailure(member,1,10))
        assertFalse(state.holdsAfterFailure(member,2,10))
        state.snapshot=view.copy(now=200,contacts=emptyList())
        assertFalse(state.holdsAfterFailure(member,1,200))
    }

    @Test fun `distant rear flank legs stay short and do not cut toward the enemy`() {
        val start=Vec3(0.0,64.0,0.0)
        val task=TacticalTask(TacticalJob.FLANK,focus.add(24.0,0.0,0.0),focus,1)
        val leg=TacticalRoutes.leg(start,task)
        assertTrue(leg.distanceTo(start)<=24.01)
        assertEquals(start.distanceTo(focus),leg.distanceTo(focus),0.01)
        assertTrue(kotlin.math.abs(leg.z)>10.0)
    }
}
