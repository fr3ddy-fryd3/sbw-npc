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
    private val home = Vec3(0.5, 64.0, 0.5)
    private val members = listOf(NpcClass.SNIPER, NpcClass.MACHINE_GUNNER, NpcClass.RIFLEMAN).mapIndexed { i, role ->
        TacticalMember(UUID(0, i.toLong()), home, role)
    }
    private fun view(order: SquadOrder = SquadOrder.DEFEND) = TacticalSnapshot(0, order, 1, home, home, members, emptyList())
    private class World(val blocks: Map<BlockPos, BlockState>) : BlockGetter {
        override fun getBlockState(pos: BlockPos) = blocks[pos] ?: Blocks.AIR.defaultBlockState()
        override fun getFluidState(pos: BlockPos): FluidState = getBlockState(pos).fluidState
        override fun getBlockEntity(pos: BlockPos): BlockEntity? = null
        override fun getHeight() = 384
        override fun getMinBuildHeight() = -64
        fun blocked(from: Vec3, to: Vec3) =
            clip(
                ClipContext(
                    from,
                    to,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    CollisionContext.empty(),
                ),
            ).type == HitResult.Type.BLOCK
    }
    private fun wall(y: Int, height: Int = 1) =
        World(
            (y until y + height).flatMap { row ->
                (-1..1).map { z -> BlockPos(1, row, z) to Blocks.STONE.defaultBlockState() }
            }.toMap(),
        )

    @Test fun `only snipers and machine gunners take high posts while defending`() {
        val plan = TacticalPlan(1, TacticalPattern.CONSOLIDATE, null, 0, 1)
        TacticalCoordinator.assign(UUID(0, 2), plan, view())
        assertEquals(TacticalJob.OVERWATCH, plan.tasks[members[0].id]!!.job)
        assertEquals(TacticalJob.OVERWATCH, plan.tasks[members[1].id]!!.job)
        assertEquals(TacticalJob.OBSERVE, plan.tasks[members[2].id]!!.job)
        assertFalse(DefensiveOverwatch.enabled(view(SquadOrder.ATTACK), members[0], TacticalPattern.HOLD_HEIGHT))
        assertFalse(DefensiveOverwatch.enabled(view(), members[0], TacticalPattern.EVADE))
    }

    @Test fun `free patrol holds support posts while riflemen and routed patrol remain mobile`() {
        val events = mutableListOf<TacticalEvent>()
        val state = SquadTacticalState(TacticalEventSink(events::add))
        val patrol = view(SquadOrder.PATROL)
        state.assess(UUID(0, 2), patrol)
        val plan = state.plan!!
        assertEquals(TacticalPattern.FOLLOW_ORDER, plan.pattern)
        assertEquals(2, plan.tasks.size)
        assertTrue(plan.tasks.values.all { it.job == TacticalJob.OVERWATCH })
        assertFalse(members[2].id in plan.tasks)
        assertFalse(events.filterIsInstance<TacticalEvent.TaskAssigned>().any { it.member == members[2].id }, "Free patrol must never issue temporary cover commands to riflemen")
        val task = plan.tasks[members[0].id]!!
        task.position = task.anchor.add(4.0, 10.0, 0.0)
        state.assess(UUID(0, 2), patrol.copy(now = 1300, narrow = true))
        assertSame(task, plan.tasks[members[0].id], "Quiet assessments must preserve reservations and ascent progress")
        state.assess(UUID(0, 2), patrol.copy(now = 1310, hasPatrolRoute = true))
        assertTrue(plan.tasks.isEmpty(), "A route must not leave its support NPCs in the old tower")
        assertFalse(DefensiveOverwatch.enabled(patrol.copy(hasPatrolRoute = true), members[0], TacticalPattern.HOLD_HEIGHT))
    }

    @Test fun `only area orders assign support posts and unavailable specialists relinquish them`() {
        val state = SquadTacticalState()
        val patrol = view(SquadOrder.PATROL)
        state.assess(UUID(0, 2), patrol)
        state.assess(UUID(0, 2), patrol.copy(now = 10, members = members.map { it.copy(ready = false) }))
        assertTrue(state.plan!!.tasks.isEmpty())
        state.assess(UUID(0, 2), patrol.copy(now = 20, order = SquadOrder.MOVE, stamp = 2))
        assertTrue(state.plan!!.tasks.isEmpty())
    }

    @Test fun `a low parapet protects the body and leaves an actual firing lane above it`() {
        val focus = Vec3(20.5, 64.0, 0.5)
        assertTrue(DefensiveOverwatch.protected(home, 1.62, focus, wall(64)::blocked))
        assertTrue(DefensiveOverwatch.protected(home, 1.62, null, wall(64)::blocked))
        assertFalse(DefensiveOverwatch.protected(home, 1.62, focus, wall(64, 2)::blocked))
        assertFalse(DefensiveOverwatch.protected(home, 1.62, null, World(emptyMap())::blocked))
    }

    @Test fun `empty primaries release high posts even though backup pistols remain ready`() {
        for (order in listOf(SquadOrder.DEFEND, SquadOrder.PATROL)) {
            val state = SquadTacticalState()
            val initial = view(order)
            state.assess(UUID(0, 2), initial)
            val old = state.plan!!.tasks[members[0].id]!!
            old.position = old.anchor.add(0.0, 12.0, 0.0)
            val empty = initial.copy(now = 10, members = members.map { it.copy(primaryAmmo = false, sidearmOnly = true, canFire = false) })
            state.assess(UUID(0, 2), empty)
            assertTrue(state.plan!!.tasks.values.none { it.job == TacticalJob.OVERWATCH })
            assertFalse(DefensiveOverwatch.enabled(empty, empty.members[0], TacticalPattern.REORIENT))
            state.assess(UUID(0, 2), initial.copy(now = 20))
            assertEquals(TacticalJob.OVERWATCH, state.plan!!.tasks[members[0].id]!!.job)
        }
    }

    @Test fun `switching to pistol cancels a committed assault assignment without borrowing its front`() {
        val view = view(SquadOrder.ATTACK).copy(
            members = (0..7).map { TacticalMember(UUID(0, it.toLong()), home.add(0.0, 0.0, it * 4.0)) },
            contacts = listOf(TacticalContact(UUID(1, 0), home.add(100.0, 0.0, 0.0), 0)),
        )
        val plan = TacticalPlan(1, TacticalPattern.BOUND, view.contacts.first().position, 0, view.stamp)
        val context = TacticalContext(UUID(0, 2), plan, view)
        plan.behavior.enter(context)
        val member = view.members.first()
        val assault = TacticalTask(TacticalJob.ADVANCE, home.add(20.0, 0.0, 0.0), plan.focus, plan.id)
        assault.frontage = TacticalFrontPost(assault.anchor, Vec3(1.0, 0.0, 0.0))
        plan.assignments.put(member.id, assault, 0, "control_assault")
        plan.tick(
            UUID(0, 2),
            SquadTacticalState(),
            view.copy(
                now = 10,
                members = view.members.map {
                    if (it.id == member.id) it.copy(primaryAmmo = false, sidearmOnly = true) else it
                },
            ),
        )
        val pistol = plan.tasks[member.id]!!
        assertEquals(TacticalJob.COVER, pistol.job)
        assertEquals(member.position, pistol.anchor)
        assertNull(pistol.frontage)
    }

    @Test fun `foliage and a distant wall do not make an exposed high position protected`() {
        val grass = World((-1..1).associate { BlockPos(1, 64, it) to Blocks.TALL_GRASS.defaultBlockState() })
        assertFalse(DefensiveOverwatch.protected(home, 1.62, null, grass::blocked))
        val farWall = World(mapOf(BlockPos(10, 64, 0) to Blocks.STONE.defaultBlockState()))
        assertFalse(DefensiveOverwatch.protected(home, 1.62, Vec3(20.5, 64.0, 0.5), farWall::blocked))
    }

    @Test fun `nearby cover distinguishes a real one block parapet from distant occlusion`() {
        val enemyEye = Vec3(20.5, 65.5, 0.5)
        assertTrue(DefensiveOverwatch.nearbyCover(home, enemyEye, wall(64)::blocked))
        assertFalse(wall(64).blocked(home.add(0.0, 1.62, 0.0), enemyEye))
        assertFalse(DefensiveOverwatch.nearbyCover(home.add(-2.0, 0.0, 0.0), enemyEye, wall(64)::blocked))
        val hill = World(mapOf(BlockPos(10, 64, 0) to Blocks.STONE.defaultBlockState()))
        assertFalse(DefensiveOverwatch.nearbyCover(home, enemyEye, hill::blocked))
    }

    @Test fun `a real parapet may adjust depth inside the owned lane but open ground and neighboring slots may not`() {
        val front = TacticalFrontPost(home.add(-6.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))
        val enemyEye = Vec3(20.5, 65.5, 0.5)
        val sheltered = DefensiveOverwatch.nearbyCover(home, enemyEye, wall(64)::blocked)
        assertTrue(sheltered)
        assertFalse(front.contains(home))
        assertTrue(front.probes(recovery = true).contains(home))
        assertTrue(front.permitsFiringPost(home, sheltered, recovery = false))
        assertFalse(front.permitsFiringPost(home, covered = false, recovery = false))
        assertFalse(front.permitsFiringPost(home.add(0.0, 0.0, 6.0), covered = true, recovery = false))
    }

    @Test fun `height takes priority among protected posts within the 32 block assignment radius`() {
        val high = home.add(20.0, 12.0, 0.0)
        assertTrue(DefensiveOverwatch.within(home, high))
        assertTrue(DefensiveOverwatch.score(home, home, high, 3) < DefensiveOverwatch.score(home, home, home, 0))
        assertFalse(DefensiveOverwatch.within(home, home.add(32.1, 0.0, 0.0)))
        assertFalse(DefensiveOverwatch.within(home, home.add(0.0, 32.1, 0.0)))
        assertTrue(DefensiveOverwatch.probes(home).all { DefensiveOverwatch.within(home, it) })
        assertEquals(DefensiveOverwatch.probes(home).distinct().size, DefensiveOverwatch.probes(home).size)
    }

    @Test fun `a changed contact preserves the original defensive assignment and selected roof`() {
        val original = TacticalPlan(1, TacticalPattern.CONSOLIDATE, null, 0, 1)
        TacticalCoordinator.assign(UUID(0, 2), original, view())
        val old = original.tasks[members[0].id]!!
        val roof = old.anchor.add(20.0, 10.0, 0.0)
        old.position = roof
        val next = TacticalPlan(2, TacticalPattern.REORIENT, Vec3(30.0, 64.0, 0.0), 100, 1)
        TacticalCoordinator.assign(
            UUID(0, 2),
            next,
            view().copy(
                now = 100,
                members = members.map {
                    if (it.id == members[0].id) it.copy(position = roof) else it
                },
            ),
        )
        DefensiveOverwatch.preservePosts(next, original.tasks, view().copy(now = 100))
        assertEquals(old.anchor, next.tasks[members[0].id]!!.anchor)
        assertEquals(roof, next.tasks[members[0].id]!!.position)
        assertEquals(next.focus, next.tasks[members[0].id]!!.focus)
        assertEquals(2, next.tasks[members[0].id]!!.plan)
    }
}
