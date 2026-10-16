package com.sbwnpc.squad.client

import com.sbwnpc.squad.integration.sbw.SbwAircraftSnapshots
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AircraftSnapshotsTest {
    @Test
    fun `lightweight aircraft movement uses the same lists that vanilla entity load consumes`() {
        val tag = CompoundTag().apply { putFloat("Health", 100f) }
        SbwAircraftSnapshots.writeMotion(tag, Vec3(480.0, 95.0, -12.0), Vec3(2.5, -0.1, 0.7), 135f, -25f)
        val position = tag.getList("Pos", Tag.TAG_DOUBLE.toInt())
        val motion = tag.getList("Motion", Tag.TAG_DOUBLE.toInt())
        val rotation = tag.getList("Rotation", Tag.TAG_FLOAT.toInt())
        assertEquals(listOf(480.0, 95.0, -12.0), (0..2).map(position::getDouble))
        assertEquals(listOf(2.5, -0.1, 0.7), (0..2).map(motion::getDouble))
        assertEquals(listOf(135f, -25f), (0..1).map(rotation::getFloat))
        assertEquals(100f, tag.getFloat("Health"))
    }
}
