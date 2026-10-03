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
        assertTrue(plan.tasks.values.filter { it.job == TacticalJob.WAIT }.all { kotlin.math.abs(it.anchor.z) >= 10.0 })
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
}
