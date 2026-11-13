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

    @Test fun `the reports release the operation once one finds nobody new after a sighting, or after three`() {
        val (a, b) = java.util.UUID(1, 1) to java.util.UUID(1, 2)
        val found = ReconProgress(1)
        found.noteReport(1, found.record(setOf(a)))
        assertEquals(false, found.reportsDone, "The first sighting released the operation")
        found.noteReport(2, found.record(setOf(a, b)))
        assertEquals(false, found.reportsDone, "A report with a new enemy released the operation")
        found.noteReport(2, found.record(setOf(a, b)))
        assertEquals(true, found.reportsDone)
        val busy = ReconProgress(1)
        (1L..ReconProgress.REPORTS_AWAITED).forEach { busy.noteReport(1, busy.record(setOf(java.util.UUID(2, it)))) }
        assertEquals(true, busy.reportsDone, "New enemies in every report kept the operation waiting past three")
        val empty = ReconProgress(1)
        repeat(ReconProgress.REPORTS_AWAITED - 1) { empty.noteReport(0, empty.record(emptySet())) }
        assertEquals(false, empty.reportsDone)
        empty.noteReport(0, empty.record(emptySet()))
        assertEquals(true, empty.reportsDone)
    }

    @Test fun `an enemy gone out of view after a sighting is no news and settles the picture`() {
        val progress = ReconProgress(1)
        progress.noteReport(1, progress.record(setOf(java.util.UUID(1, 1))))
        progress.noteReport(0, progress.record(emptySet()))
        assertEquals(true, progress.reportsDone)
    }

    @Test fun `the post's running count and the report itself share what was reported`() {
        val a = java.util.UUID(1, 1)
        val counted = ReconProgress(1)
        counted.noteReport(1, counted.record(setOf(a)))
        counted.noteReport(1, counted.record(setOf(a)))
        assertEquals(true, counted.reportsDone, "The report named again the enemy the running count had")
        val more = ReconProgress(1)
        more.noteReport(1, more.record(setOf(a)))
        more.noteReport(2, more.record(setOf(a, java.util.UUID(1, 2))))
        assertEquals(false, more.reportsDone)
    }

    @Test fun `moving closer counts towards three reports but never settles the picture`() {
        val progress = ReconProgress(1)
        progress.noteReport(1, progress.record(setOf(java.util.UUID(1, 1))))
        progress.noteCloser()
        assertEquals(false, progress.reportsDone, "Moving closer after a sighting released the operation")
        progress.noteCloser()
        assertEquals(true, progress.reportsDone, "Three reports did not release the operation")
    }

    @Test fun `in the ring it posts only where it sees the objective, making for the spot with a view`() {
        val o = Point3(0.0, 64.0, 0.0)
        val p = ReconProgress(1)
        p.update(95.0, 0)
        val view = Point3(10.0, 80.0, 60.0)
        p.searched(view, 0)
        assertEquals(view, p.point(o, Point3(0.0, 64.0, 70.0)))
        assertEquals(ReconStage.STALK, p.update(70.0, 10, Point3(0.0, 64.0, 70.0), sees = false))
        assertEquals(ReconStage.WATCH, p.update(61.0, 20, view, sees = true))
        assertEquals(view, p.point(o, Point3(0.0, 64.0, 50.0)))
    }

    @Test fun `seeing the enemy on its way in it posts where it stands`() {
        val p = ReconProgress(1)
        p.update(95.0, 0)
        val here = Point3(0.0, 64.0, 80.0)
        assertEquals(ReconStage.WATCH, p.update(80.0, 10, here, sees = false, enemySeen = true))
        assertEquals(here, p.point(Point3(0.0, 64.0, 0.0), here))
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

    @Test fun `fitted to the conditions it stages beyond its first post and steps in to the floor`() {
        val progress = ReconProgress(1)
        progress.reach = com.sbwnpc.squad.combat.ReconReach(start = 128.0, floor = 102.0, step = 25.0)
        assertEquals(ReconStage.STAGING, progress.update(160.0, 0))
        assertEquals(144.0, progress.stagingDistance, 1e-9)
        assertEquals(ReconStage.STALK, progress.update(150.0, 10))
        assertEquals(ReconStage.WATCH, progress.update(126.0, 20, Point3(0.0, 64.0, 126.0)))
        assertEquals(true, progress.closer())
        assertEquals(103.0, progress.postDistance, 1e-9)
        progress.update(103.0, 30, Point3(0.0, 64.0, 103.0))
        assertEquals(true, progress.closer())
        assertEquals(102.0, progress.postDistance, 1e-9)
        progress.update(102.0, 40, Point3(0.0, 64.0, 102.0))
        assertEquals(false, progress.closer())
    }

    @Test fun `it is fitted once and only while staging`() {
        val progress = ReconProgress(1)
        progress.update(85.0, 0)
        progress.reach = com.sbwnpc.squad.combat.ReconReach(start = 128.0, floor = 102.0, step = 25.0)
        assertEquals(ReconProgress.POST_DISTANCE, progress.postDistance, 1e-9)
        assertEquals(null, progress.reach)
    }
}
