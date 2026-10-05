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
        TacticalCoordinator.assign(squad,plan,view)
        plan.tasks.values.filter { it.job==TacticalJob.COVER }.forEach { it.position=it.anchor }
        return state to plan
    }

    @Test fun `wide enemy front is approached from its weaker side by no more than six movers`() {
        val view=view()
        val (state,plan)=setup(view)
        val staging=plan.tasks.values.mapNotNull { it.staging }
        assertEquals(staging.size,staging.distinct().size)
        TacticalCoordinator.advance(squad,state,plan,view)
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
        TacticalCoordinator.advance(squad,state,plan,view)
        assertEquals(TacticalStatus.PREPARING,plan.status)
        val shooter=plan.tasks.entries.first { it.value.job==TacticalJob.COVER }.key
        val one=view.copy(members=view.members.map { it.copy(recentFire=it.id==shooter) })
        TacticalCoordinator.advance(squad,state,plan,one)
        assertEquals(TacticalStatus.PREPARING,plan.status)
        assertFalse(plan.tasks.values.any { it.job==TacticalJob.FLANK })
        val other=plan.tasks.entries.last { it.value.job==TacticalJob.COVER }.key
        val two=view.copy(members=view.members.map { it.copy(recentFire=it.id==shooter || it.id==other) })
        TacticalCoordinator.advance(squad,state,plan,two)
        assertEquals(TacticalStatus.EXECUTING,plan.status)
        assertEquals(6,plan.tasks.values.count { it.job==TacticalJob.FLANK })
    }

    @Test fun `arrival releases the next six and preserves covering posts including distant flankers`() {
        val view=view()
        val (state,plan)=setup(view)
        TacticalCoordinator.advance(squad,state,plan,view)
        val first=plan.tasks.filterValues { it.job==TacticalJob.FLANK }.keys.toSet()
        val cover=plan.tasks.filterValues { it.job==TacticalJob.COVER }.toMap()
        val arrived=view.copy(now=40,contacts=view.contacts.map { it.copy(seenAt=40) },members=view.members.map { npc ->
            if (npc.id in first) {
                val task=plan.tasks[npc.id]!!
                task.position=task.anchor
                npc.copy(position=task.anchor)
            } else npc
        })
        TacticalCoordinator.advance(squad,state,plan,arrived)
        assertEquals(first,(plan.behavior as FlankingState).arrived)
        assertEquals(TacticalStatus.PREPARING,plan.status)
        cover.forEach { (id,task) -> assertSame(task,plan.tasks[id]) }
        first.forEach { assertEquals(TacticalJob.COVER,plan.tasks[it]?.job) }
        TacticalCoordinator.advance(squad,state,plan,arrived)
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
        TacticalCoordinator.advance(squad,state,plan,ready)
        assertEquals(TacticalStatus.EXECUTING,plan.status)
        shooters.forEach { assertEquals(TacticalJob.COVER,plan.tasks[it]?.job) }
        assertEquals(6,plan.tasks.values.count { it.job==TacticalJob.FLANK })
    }

    @Test fun `one failed route does not block the remaining wave or release it into frontal movement`() {
        val view=view()
        val (state,plan)=setup(view)
        TacticalCoordinator.advance(squad,state,plan,view)
        val wave=plan.tasks.filterValues { it.job==TacticalJob.FLANK }.keys.toSet()
        val failed=wave.first()
        TacticalCoordinator.abandon(plan,failed)
        val arrived=view.copy(now=40,contacts=view.contacts.map { it.copy(seenAt=40) },members=view.members.map { npc ->
            val task=plan.tasks[npc.id]
            if (task?.job==TacticalJob.FLANK) {
                task.position=task.anchor
                npc.copy(position=task.anchor)
            } else npc
        })
        TacticalCoordinator.advance(squad,state,plan,arrived)
        assertEquals(wave-failed,(plan.behavior as FlankingState).arrived)
        assertNull(plan.tasks[failed])
        TacticalCoordinator.advance(squad,state,plan,arrived)
        assertEquals(6,plan.tasks.values.count { it.job==TacticalJob.FLANK })
        assertNull(plan.tasks[failed])
        state.snapshot=arrived
        assertTrue(state.holdsAfterFailure(failed,1,40))
    }

    @Test fun `new distant sector proposals preserve a running route while a new player order overrides it`() {
        val (state,plan)=setup()
        plan.tasks.values.filter { it.job == TacticalJob.COVER }.forEach { it.position = it.anchor }
        TacticalCoordinator.advance(squad,state,plan,view())
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
        TacticalCoordinator.abandon(plan,member)
        assertTrue(state.holdsAfterFailure(member,1,10))
        assertFalse(state.holdsAfterFailure(member,2,10))
        state.snapshot=view.copy(now=200,contacts=emptyList())
        assertFalse(state.holdsAfterFailure(member,1,200))
    }

    @Test fun `failure hold cannot stop the covering work of a replacement plan`() {
        val view=view()
        val (state,old)=setup(view)
        val member=old.tasks.keys.first()
        state.snapshot=view
        state.fail(20,TacticalFailure.COVER_LOST)
        assertTrue(state.holdsAfterFailure(member,1,21))
        state.select(TacticalChoice(TacticalPattern.REORIENT,focus),1,21)
        TacticalCoordinator.assign(squad,state.plan!!,view.copy(now=21))
        assertFalse(state.holdsAfterFailure(member,1,21), "a fresh local task owns movement immediately")
        TacticalCoordinator.abandon(state.plan!!,member)
        assertTrue(state.holdsAfterFailure(member,1,22), "unassigned members must not resume the frontal order")
        assertFalse(state.holdsAfterFailure(member,1,141))
    }

    @Test fun `one continuing shooter sustains a wave after two released it`() {
        val view=view()
        val (state,plan)=setup(view)
        TacticalCoordinator.advance(squad,state,plan,view)
        val shooter=plan.tasks.filterValues { it.job==TacticalJob.COVER }.keys.first()
        val sustained=view.copy(now=200,contacts=view.contacts.map { it.copy(seenAt=200) },members=view.members.map {
            it.copy(canFire=it.id==shooter,recentFire=it.id==shooter)
        })
        TacticalCoordinator.advance(squad,state,plan,sustained)
        assertEquals(TacticalStatus.EXECUTING,plan.status)
        assertEquals(200L,plan.lastCover)
        assertEquals(6,plan.tasks.values.count { it.job==TacticalJob.FLANK })
    }

    @Test fun `lost covering fire pauses and resumes the same wave without replacing its tasks`() {
        val view=view()
        val (state,plan)=setup(view)
        TacticalCoordinator.advance(squad,state,plan,view)
        val tasks=plan.tasks.toMap()
        val lost=view.copy(now=90,contacts=view.contacts.map { it.copy(seenAt=90) },members=view.members.map {
            it.copy(canFire=false,recentFire=false)
        })
        TacticalCoordinator.advance(squad,state,plan,lost)
        assertEquals(TacticalStatus.REGROUPING,plan.status)
        assertEquals(TacticalFailure.NONE,plan.failure)
        tasks.forEach { (id,task) -> assertSame(task,plan.tasks[id]) }
        assertFalse(state.select(TacticalChoice(TacticalPattern.DISLODGE,focus),1,100))
        val shooter=plan.tasks.filterValues { it.job==TacticalJob.COVER }.keys.first()
        val resumed=lost.copy(now=130,contacts=view.contacts.map { it.copy(seenAt=130) },members=lost.members.map {
            it.copy(canFire=it.id==shooter,recentFire=it.id==shooter)
        })
        TacticalCoordinator.advance(squad,state,plan,resumed)
        assertEquals(TacticalStatus.EXECUTING,plan.status)
        assertEquals(40L,plan.phaseSince, "paused ticks must not consume the movement deadline")
        tasks.forEach { (id,task) -> assertSame(task,plan.tasks[id]) }
    }

    @Test fun `blocked fire releases only two short lateral probes and leaves the assault wave waiting`() {
        val view=view().copy(members=members.map { it.copy(canFire=false,recentFire=false) })
        val (state,plan)=setup(view)
        val old=plan.tasks.toMap()
        TacticalCoordinator.advance(squad,state,plan,view.copy(now=80,contacts=view.contacts.map { it.copy(seenAt=80) }))
        assertTrue((plan.phase == TacticalPhase.OPENING_LANE))
        val probes=plan.tasks.filterValues { it.opensLane }
        assertEquals(2,probes.size)
        assertFalse(plan.tasks.values.any { it.job in setOf(TacticalJob.FLANK,TacticalJob.ADVANCE) })
        for ((id,task) in probes) {
            val from=view.members.first { it.id==id }.position
            assertEquals(12.0,from.distanceTo(task.anchor),0.01)
            assertTrue(task.anchor.distanceTo(focus)>=from.distanceTo(focus))
        }
        old.filterKeys { it !in probes }.forEach { (id,task) -> assertSame(task,plan.tasks[id]) }
        val shooters=view.copy(now=100,contacts=view.contacts.map { it.copy(seenAt=100) },members=view.members.map {
            if (it.id in probes) it.copy(position=plan.tasks[it.id]!!.anchor,canFire=true,recentFire=true) else it
        })
        TacticalCoordinator.advance(squad,state,plan,shooters)
        assertFalse((plan.phase == TacticalPhase.OPENING_LANE))
        assertEquals(TacticalStatus.PREPARING,plan.status)
        probes.keys.forEach { assertEquals(TacticalJob.COVER,plan.tasks[it]?.job) }
        TacticalCoordinator.advance(squad,state,plan,shooters)
        assertEquals(TacticalStatus.EXECUTING,plan.status)
        assertEquals(6,plan.tasks.values.count { it.job==TacticalJob.FLANK })
    }

    @Test fun `waiting fighters hold their own posts instead of retreating to the squad center`() {
        val view=view()
        val (_,plan)=setup(view)
        for (member in view.members) {
            val task=plan.tasks[member.id]!!
            if (task.job==TacticalJob.WAIT) assertEquals(member.position,task.staging)
        }
    }

    @Test fun `unproductive lane probes stop after two attempts without releasing the assault wave`() {
        var view=view().copy(members=members.map { it.copy(canFire=false,recentFire=false) })
        val (state,plan)=setup(view)
        var now=0L
        repeat(2) {
            now+=80
            view=view.copy(now=now,contacts=view.contacts.map { it.copy(seenAt=now) })
            TacticalCoordinator.advance(squad,state,plan,view)
            assertTrue((plan.phase == TacticalPhase.OPENING_LANE))
            now+=40
            view=view.copy(now=now,contacts=view.contacts.map { it.copy(seenAt=now) },members=view.members.map { npc ->
                val task=plan.tasks[npc.id]!!
                if (task.opensLane) {
                    task.position=task.anchor
                    npc.copy(position=task.anchor)
                } else npc
            })
            TacticalCoordinator.advance(squad,state,plan,view)
            assertFalse((plan.phase == TacticalPhase.OPENING_LANE))
        }
        assertEquals(2,(plan.behavior as CoveredManeuverState).laneAttempts)
        now+=110
        TacticalCoordinator.advance(squad,state,plan,view.copy(now=now,contacts=view.contacts.map { it.copy(seenAt=now) }))
        assertEquals(TacticalStatus.FAILED,plan.status)
        assertEquals(TacticalFailure.COVER_NOT_READY_TIMEOUT,plan.failure)
        assertFalse(plan.tasks.values.any { it.job==TacticalJob.FLANK })
    }

    @Test fun `a real firing peek replaces a stale covering endpoint before launching the wave`() {
        val view=view()
        val (state,plan)=setup(view)
        val cover=plan.tasks.filterValues { it.job==TacticalJob.COVER }.keys.take(2).toSet()
        val ready=view.copy(now=20,members=view.members.map {
            if (it.id in cover) it.copy(position=it.position.add(0.0,0.0,16.0)) else it.copy(canFire=false,recentFire=false)
        })
        TacticalCoordinator.advance(squad,state,plan,ready)
        assertEquals(TacticalStatus.EXECUTING,plan.status)
        for (id in cover) assertEquals(ready.members.first { it.id==id }.position,plan.tasks[id]?.position)
    }

    @Test fun `a distant covering fighter is still assigned instead of falling back to individual assault`() {
        val view=view().copy(members=members.mapIndexed { index,it ->
            if (index==0) it.copy(position=Vec3(-70.0,64.0,0.0)) else it
        })
        val (_,plan)=setup(view)
        assertNotNull(plan.tasks[view.members[0].id])
    }

    @Test fun `opening a firing lane cannot route forward through the enemy`() {
        val from=Vec3(0.0,64.0,0.0)
        assertTrue(TacticalRoutes.safeLaneProbe(from,Vec3(0.0,64.0,12.0),focus))
        assertFalse(TacticalRoutes.safeLaneProbe(from,Vec3(12.0,64.0,0.0),focus))
        assertFalse(TacticalRoutes.safeLaneProbe(from,Vec3(0.0,64.0,20.0),focus))
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
