package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TacticalTelemetryTest {
    private val focus=Vec3(50.0,64.0,0.0)
    private val member=TacticalMember(UUID(0,1),Vec3(0.0,64.0,0.0),canFire=true,
        firingAt=focus,recentFire=false)
    private fun view(members: List<TacticalMember>)=TacticalSnapshot(20,SquadOrder.ATTACK,1,
        member.position,focus,members,listOf(TacticalContact(UUID(1,1),focus,20)))
    private fun plan(members: List<TacticalMember>)=TacticalPlan(1,TacticalPattern.FLANK,focus,0,1).apply {
        members.forEach { npc -> assignments.put(npc.id,TacticalTask(TacticalJob.COVER,npc.position,focus,id).apply {
            position=npc.position
        },20,"fixture") }
    }

    @Test fun `ready to shoot is reported separately from actual covering shots`() {
        val npcs=listOf(member,member.copy(id=UUID(0,2),canFire=false,recentFire=true,recentFireAt=focus))
        val plan=plan(npcs)
        val task=plan.tasks[member.id]
        val cover=TacticalTelemetry.cover(plan,view(npcs))
        assertEquals(TacticalTelemetry.Cover(2,1,1,1),cover)
        assertSame(task,plan.tasks[member.id])
        assertEquals(TacticalStatus.PREPARING,plan.status)
    }

    @Test fun `a new aim sector does not turn old shots in another direction into covering fire`() {
        val npc=member.copy(recentFire=true,recentFireAt=Vec3(-50.0,64.0,0.0))
        val plan=plan(listOf(npc))
        plan.tasks[npc.id]!!.position=Vec3(12.0,64.0,0.0)
        val cover=TacticalTelemetry.cover(plan,view(listOf(npc)))
        assertEquals(TacticalTelemetry.Cover(1,1,0,0),cover)
    }

    @Test fun `chosen reasons follow the winning rule and failure reasons survive plan failure`() {
        val snapshot=view(listOf(member)).copy(grenade=true,stalled=true)
        val decision=TacticalRules.choose(snapshot)
        assertEquals(TacticalPattern.EVADE,decision.pattern)
        assertEquals(TacticalReason.GRENADE_DANGER,decision.reason)
        val state=SquadTacticalState()
        state.select(decision,1,20)
        val plan=state.plan!!
        assertEquals(decision.reason,plan.reason)
        state.fail(30,TacticalFailure.COVER_LOST)
        assertSame(plan,state.plan)
        assertEquals(TacticalStatus.FAILED,plan.status)
        assertEquals(TacticalFailure.COVER_LOST,plan.failure)
    }
}
