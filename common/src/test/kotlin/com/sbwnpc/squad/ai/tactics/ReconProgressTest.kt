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

    @Test fun `the fall-back point lies 64 blocks out even when the squad stands nearer`() {
        val point = ReconProgress.along(Point3(0.0, 64.0, 0.0), Point3(0.0, 64.0, 40.0), ReconProgress.FALL_BACK_DISTANCE)
        assertEquals(64.0, point.z, 1e-9)
        assertEquals(0.0, point.x, 1e-9)
    }

    @Test fun `the post counts new enemies every 10 s while it watches`() {
        val progress = ReconProgress(1)
        val a = java.util.UUID.randomUUID()
        val b = java.util.UUID.randomUUID()
        assertEquals(null, progress.tally(setOf(a), 0))
        progress.update(95.0, 0)
        progress.update(70.0, 10)
        assertEquals(null, progress.tally(setOf(a), 10))
        assertEquals(1 to 1, progress.tally(setOf(a), 10 + ReconProgress.UPDATE_TICKS))
        assertEquals(null, progress.tally(setOf(a, b), 100 + ReconProgress.UPDATE_TICKS))
        assertEquals(1 to 2, progress.tally(setOf(a, b), 10 + 2 * ReconProgress.UPDATE_TICKS))
    }

    @Test fun `reported and holding its post it keeps counting and stays where it watched from`() {
        val o = Point3(0.0, 64.0, 0.0)
        val at = Point3(0.0, 64.0, 70.0)
        val a = java.util.UUID.randomUUID()
        val held = ReconProgress(1)
        held.update(95.0, 0)
        held.update(70.0, 0, at)
        assertEquals(at, held.point(o, Point3(0.0, 64.0, 60.0)))
        held.update(70.0, ReconProgress.WATCH_TICKS)
        held.holdsPost = true
        assertEquals(at, held.point(o, Point3(0.0, 64.0, 60.0)))
        assertEquals(1 to 1, held.tally(setOf(a), ReconProgress.WATCH_TICKS))
        held.holdsPost = false
        assertEquals(null, held.tally(setOf(a), ReconProgress.WATCH_TICKS + ReconProgress.UPDATE_TICKS))
    }

    @Test fun `the first report naming an enemy releases the operation, seeing nobody it takes three`() {
        val found = ReconProgress(1)
        found.noteReport(0)
        assertEquals(false, found.reportsDone)
        found.noteReport(2)
        assertEquals(true, found.reportsDone)
        val empty = ReconProgress(1)
        repeat(ReconProgress.REPORTS_AWAITED - 1) { empty.noteReport(0) }
        assertEquals(false, empty.reportsDone)
        empty.noteReport(0)
        assertEquals(true, empty.reportsDone)
    }

    @Test fun `in the ring it posts only where it sees the objective, making for the spot with a view`() {
        val o = Point3(0.0, 64.0, 0.0)
        val p = ReconProgress(1)
        p.update(95.0, 0)
        val view = Point3(10.0, 80.0, 60.0)
        p.searched(view)
        assertEquals(view, p.point(o, Point3(0.0, 64.0, 70.0)))
        assertEquals(ReconStage.STALK, p.update(70.0, 10, Point3(0.0, 64.0, 70.0), sees = false))
        assertEquals(ReconStage.WATCH, p.update(61.0, 20, view, sees = true))
        assertEquals(view, p.point(o, Point3(0.0, 64.0, 50.0)))
    }

    @Test fun `seeing nobody it steps nearer down to the closest post, never onto the objective`() {
        val progress = ReconProgress(1)
        progress.update(95.0, 0)
        progress.update(70.0, 0, Point3(0.0, 64.0, 70.0))
        var steps = 0
        while (progress.closer()) {
            steps++
            assertEquals(ReconStage.STALK, progress.stage)
            progress.update(progress.postDistance, 0, Point3(0.0, 64.0, progress.postDistance))
        }
        assertEquals(ReconProgress.MIN_POST_DISTANCE, progress.postDistance, 1e-9)
        assertEquals(2, steps)
        assertEquals(ReconStage.WATCH, progress.stage)
        assertEquals(0L, progress.postFrom)
    }
}
