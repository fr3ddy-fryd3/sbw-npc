package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.Point3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SquadAlarmsTest {
    private val objective = Point3(0.0, 64.0, 0.0)

    @Test fun `shots of one fight a few blocks apart are one spot`() {
        val alarms = SquadAlarms()
        alarms.heard(Point3(41.0, 64.0, 1.0), 0)
        val first = alarms.pending(0, objective, UnverifiedContacts.SCOUT_RADIUS)
        alarms.heard(Point3(46.0, 64.0, 6.0), 20)
        assertEquals(first, alarms.pending(20, objective, UnverifiedContacts.SCOUT_RADIUS))
    }

    @Test fun `a checked spot sends nobody back while the fight keeps re-arming it`() {
        val alarms = SquadAlarms()
        alarms.heard(Point3(40.0, 64.0, 0.0), 0)
        alarms.checked(Point3(40.0, 64.0, 0.0), 100)
        alarms.heard(Point3(48.0, 64.0, 4.0), 120)
        assertNull(alarms.pending(120, objective, UnverifiedContacts.SCOUT_RADIUS))
        alarms.heard(Point3(48.0, 64.0, 4.0), 100 + SquadAlarms.CHECKED_TICKS + 1)
        assertNotNull(alarms.pending(100 + SquadAlarms.CHECKED_TICKS + 1, objective, UnverifiedContacts.SCOUT_RADIUS))
    }

    @Test fun `a noise beyond the squad's reach of its objective sends nobody`() {
        val alarms = SquadAlarms()
        alarms.heard(Point3(70.0, 64.0, 0.0), 0)
        assertNull(alarms.pending(0, objective, UnverifiedContacts.SCOUT_RADIUS))
    }

    @Test fun `the latest spot heard is the one looked at, and an old one is forgotten`() {
        val alarms = SquadAlarms()
        alarms.heard(Point3(-40.0, 64.0, 0.0), 0)
        alarms.heard(Point3(40.0, 64.0, 0.0), 10)
        assertEquals(44.0, alarms.pending(10, objective, null)!!.x)
        assertNull(alarms.pending(10 + SquadAlarms.HEARD_TICKS + 1, objective, null))
    }

    @Test fun `once the latest is checked the earlier spot is next`() {
        val alarms = SquadAlarms()
        alarms.heard(Point3(-40.0, 64.0, 0.0), 0)
        alarms.heard(Point3(40.0, 64.0, 0.0), 10)
        alarms.checked(Point3(40.0, 64.0, 0.0), 20)
        assertEquals(-36.0, alarms.pending(20, objective, null)!!.x)
    }
}
