package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class DefensiveOverwatchTest {
    private val home=Vec3(0.5,64.0,0.5)
    private val members=listOf(NpcClass.SNIPER,NpcClass.MACHINE_GUNNER,NpcClass.RIFLEMAN).mapIndexed { i,role ->
        TacticalMember(UUID(0,i.toLong()),home,role)
    }
    private fun view(order: SquadOrder=SquadOrder.DEFEND)=TacticalSnapshot(0,order,1,home,home,members,emptyList())
    private class World(val blocks: Map<BlockPos,BlockState>): BlockGetter {
        override fun getBlockState(pos: BlockPos)=blocks[pos] ?: Blocks.AIR.defaultBlockState()
        override fun getFluidState(pos: BlockPos): FluidState=getBlockState(pos).fluidState
        override fun getBlockEntity(pos: BlockPos): BlockEntity?=null
        override fun getHeight()=384
        override fun getMinBuildHeight()=-64
        fun blocked(from: Vec3,to: Vec3)=clip(ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,
            CollisionContext.empty())).type==HitResult.Type.BLOCK
    }
    private fun wall(y: Int,height: Int=1)=World((y until y+height).flatMap { row ->
        (-1..1).map { z -> BlockPos(1,row,z) to Blocks.STONE.defaultBlockState() }
    }.toMap())

    @Test fun `only snipers and machine gunners take high posts while defending`() {
        val plan=TacticalPlan(1,TacticalPattern.CONSOLIDATE,null,0,1)
        TacticalCoordinator.assign(UUID(0,2),plan,view())
        assertEquals(TacticalJob.OVERWATCH,plan.tasks[members[0].id]!!.job)
        assertEquals(TacticalJob.OVERWATCH,plan.tasks[members[1].id]!!.job)
        assertEquals(TacticalJob.OBSERVE,plan.tasks[members[2].id]!!.job)
        assertFalse(DefensiveOverwatch.enabled(view(SquadOrder.ATTACK),members[0],TacticalPattern.HOLD_HEIGHT))
        assertFalse(DefensiveOverwatch.enabled(view(),members[0],TacticalPattern.EVADE))
    }

    @Test fun `a low parapet protects the body and leaves an actual firing lane above it`() {
        val focus=Vec3(20.5,64.0,0.5)
        assertTrue(DefensiveOverwatch.protected(home,1.62,focus,wall(64)::blocked))
        assertTrue(DefensiveOverwatch.protected(home,1.62,null,wall(64)::blocked))
        assertFalse(DefensiveOverwatch.protected(home,1.62,focus,wall(64,2)::blocked))
        assertFalse(DefensiveOverwatch.protected(home,1.62,null,World(emptyMap())::blocked))
    }

    @Test fun `foliage and a distant wall do not make an exposed high position protected`() {
        val grass=World((-1..1).associate { BlockPos(1,64,it) to Blocks.TALL_GRASS.defaultBlockState() })
        assertFalse(DefensiveOverwatch.protected(home,1.62,null,grass::blocked))
        val farWall=World(mapOf(BlockPos(10,64,0) to Blocks.STONE.defaultBlockState()))
        assertFalse(DefensiveOverwatch.protected(home,1.62,Vec3(20.5,64.0,0.5),farWall::blocked))
    }

    @Test fun `height takes priority among protected posts within the 32 block assignment radius`() {
        val high=home.add(20.0,12.0,0.0)
        assertTrue(DefensiveOverwatch.within(home,high))
        assertTrue(DefensiveOverwatch.score(home,home,high,3)<DefensiveOverwatch.score(home,home,home,0))
        assertFalse(DefensiveOverwatch.within(home,home.add(32.1,0.0,0.0)))
        assertFalse(DefensiveOverwatch.within(home,home.add(0.0,32.1,0.0)))
        assertTrue(DefensiveOverwatch.probes(home).all { DefensiveOverwatch.within(home,it) })
        assertEquals(DefensiveOverwatch.probes(home).distinct().size,DefensiveOverwatch.probes(home).size)
    }

    @Test fun `a changed contact preserves the original defensive assignment and selected roof`() {
        val original=TacticalPlan(1,TacticalPattern.CONSOLIDATE,null,0,1)
        TacticalCoordinator.assign(UUID(0,2),original,view())
        val old=original.tasks[members[0].id]!!
        val roof=old.anchor.add(20.0,10.0,0.0)
        old.position=roof
        val next=TacticalPlan(2,TacticalPattern.REORIENT,Vec3(30.0,64.0,0.0),100,1)
        TacticalCoordinator.assign(UUID(0,2),next,view().copy(now=100,members=members.map {
            if (it.id==members[0].id) it.copy(position=roof) else it
        }))
        DefensiveOverwatch.preservePosts(next,original.tasks,view().copy(now=100))
        assertEquals(old.anchor,next.tasks[members[0].id]!!.anchor)
        assertEquals(roof,next.tasks[members[0].id]!!.position)
        assertEquals(next.focus,next.tasks[members[0].id]!!.focus)
        assertEquals(2,next.tasks[members[0].id]!!.plan)
    }
}
