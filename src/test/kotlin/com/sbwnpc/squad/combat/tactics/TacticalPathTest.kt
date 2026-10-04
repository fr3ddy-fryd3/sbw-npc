package com.sbwnpc.squad.combat.tactics

import net.minecraft.core.BlockPos
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TacticalPathTest {
    @Test fun `a reachable waypoint is not arrival at the requested defensive post`() {
        val goal=Vec3(30.5,70.0,0.5)
        val waypoint=Path(listOf(Node(10,64,0)),BlockPos(10,64,0),true)
        assertFalse(TacticalPositions.reaches(waypoint,goal))
        val arrival=Path(listOf(Node(30,70,0)),BlockPos(30,70,0),true)
        assertTrue(TacticalPositions.reaches(arrival,goal))
        assertFalse(TacticalPositions.reaches(Path(listOf(Node(30,70,0)),BlockPos(30,70,0),false),goal))
    }
}
