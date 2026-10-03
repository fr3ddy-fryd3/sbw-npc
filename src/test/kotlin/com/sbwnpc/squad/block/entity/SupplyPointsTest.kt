package com.sbwnpc.squad.block.entity

import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.stream.Stream

class SupplyPointsTest {
    private val here = Vec3(0.5, 64.5, 0.5)
    private val own = SquadFaction.PIG
    private val enemy = SquadFaction.COW
    private val near = BlockPos(32, 64, 0)
    private val far = BlockPos(1000, 64, 0)
    private val registries = HolderLookup.Provider.create(Stream.empty())

    private fun reload(points: SupplyPoints): SupplyPoints =
        SupplyPoints.load(points.save(CompoundTag(), registries))

    @Test
    fun `ordinary resupply stays within 96 blocks but an empty NPC can find supply a kilometre away`() {
        val points = SupplyPoints().apply { remember(far, own) }
        assertNull(points.nearestServing(here, own, 96.0))
        assertEquals(far, points.nearestServing(here, own, Double.POSITIVE_INFINITY))
        points.remember(near, own)
        assertEquals(near, points.nearestServing(here, own, Double.POSITIVE_INFINITY))
        assertEquals(near, points.nearestServing(here, own, 96.0))
    }

    @Test
    fun `the search ignores enemy supplies even when they are closer`() {
        val points = SupplyPoints().apply {
            remember(near, enemy)
            remember(far, own)
            remember(BlockPos(2000, 64, 0), own)
        }
        assertEquals(far, points.nearestServing(here, own, Double.POSITIVE_INFINITY))
        assertNull(points.nearestServing(here, own, 96.0))
    }

    @Test
    fun `supplies remain discoverable after saving and reloading with no block entities loaded`() {
        val public = BlockPos(-1200, 70, -100)
        val points = reload(SupplyPoints().apply {
            remember(near, enemy)
            remember(far, own)
            remember(public, null)
        })
        assertEquals(far, points.nearestServing(here, own, Double.POSITIVE_INFINITY))
        assertEquals(near, points.nearestServing(here, enemy, Double.POSITIVE_INFINITY))
        assertEquals(public, points.nearestServing(here, null, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `placement updates an initially public point to its actual faction`() {
        val points = SupplyPoints().apply { remember(near, null) }
        assertEquals(near, points.nearestServing(here, enemy, 96.0))
        points.remember(near, own)
        assertNull(points.nearestServing(here, enemy, 96.0))
        assertNull(reload(points).nearestServing(here, null, 96.0))
        assertEquals(near, reload(points).nearestServing(here, own, 96.0))
    }

    @Test
    fun `breaking even a public supply removes it from the saved index`() {
        val points = SupplyPoints().apply {
            remember(near, null)
            remember(far, own)
        }
        points.remove(near)
        val reloaded = reload(points)
        assertEquals(far, reloaded.nearestServing(here, own, Double.POSITIVE_INFINITY))
        assertNull(reloaded.nearestServing(here, null, Double.POSITIVE_INFINITY))
        reloaded.remove(far)
        assertNull(reload(reloaded).nearestServing(here, own, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `positions passed by mutable world traversal are saved as immutable coordinates`() {
        val pos = BlockPos.MutableBlockPos(near.x, near.y, near.z)
        val points = SupplyPoints().apply { remember(pos, own) }
        pos.set(far.x, far.y, far.z)
        assertEquals(near, reload(points).nearestServing(here, own, 96.0))
    }

    @Test
    fun `a malformed saved faction never makes an enemy supply public`() {
        val list = ListTag().apply {
            add(CompoundTag().apply {
                put("Pos", NbtUtils.writeBlockPos(near))
                putString("Faction", "UNKNOWN")
            })
        }
        val points = SupplyPoints.load(CompoundTag().apply { put("Points", list) })
        assertNull(points.nearestServing(here, own, Double.POSITIVE_INFINITY))
        assertNull(points.nearestServing(here, null, Double.POSITIVE_INFINITY))
    }
}
