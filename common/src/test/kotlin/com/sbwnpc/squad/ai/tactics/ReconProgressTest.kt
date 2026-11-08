package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReconProgressTest {
    @Test fun `runs to the staging line, stalks to the post, watches 20 s, then reports`() {
        val progress = ReconProgress(1)
        assertEquals(ReconStage.STAGING, progress.update(200.0, 0))
        assertEquals(ReconStage.STALK, progress.update(95.0, 100))
        assertEquals(ReconStage.STALK, progress.update(80.0, 200))
        assertEquals(ReconStage.WATCH, progress.update(70.0, 300))
        assertEquals(ReconStage.WATCH, progress.update(70.0, 300 + ReconProgress.WATCH_TICKS - 1))
        assertEquals(ReconStage.REPORTED, progress.update(70.0, 300 + ReconProgress.WATCH_TICKS))
        // Once reported it stays so, wherever the squad goes.
        assertEquals(ReconStage.REPORTED, progress.update(200.0, 2000))
    }

    @Test fun `a squad already inside the staging line stalks at once`() {
        assertEquals(ReconStage.STALK, ReconProgress(1).update(85.0, 0))
    }

    @Test fun `the staging point lies on the line from the objective towards the squad`() {
        val objective = Point3(0.0, 64.0, 0.0)
        assertEquals(Point3(90.0, 64.0, 0.0), ReconProgress.shortOf(objective, Point3(300.0, 70.0, 0.0), 90.0))
        val near = Point3(30.0, 70.0, 40.0)
        assertEquals(near, ReconProgress.shortOf(objective, near, 90.0))
    }
}
