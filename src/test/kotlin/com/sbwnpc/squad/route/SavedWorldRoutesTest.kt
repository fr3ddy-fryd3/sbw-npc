package com.sbwnpc.squad.route

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.File

/**
 * The long-route planner over ground taken from a saved world — the marches and drives a play
 * session logged, planned again here to see what the planner makes of them.
 *
 * Needs an export of the world's ground (height, kind and room per column, as [GroundMap] reads
 * them) named by the SBWNPC_GROUND environment variable; skipped without one. The ground isn't
 * part of the repository: it's a few megabytes of somebody's world.
 */
class SavedWorldRoutesTest {
    private class FileGround(file: File) : Ground {
        val x0: Int
        val z0: Int
        val w: Int
        val h: Int
        private val height: ShortArray
        private val kind: ByteArray
        private val room: ByteArray

        init {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val magic = ByteArray(4).also { input.readFully(it) }
                check(String(magic) == "GRND")
                x0 = input.readInt(); z0 = input.readInt(); w = input.readInt(); h = input.readInt()
                height = ShortArray(w * h); kind = ByteArray(w * h); room = ByteArray(w * h)
                for (i in 0 until w * h) {
                    height[i] = input.readShort(); kind[i] = input.readByte(); room[i] = input.readByte()
                }
            }
        }

        private fun index(x: Int, z: Int): Int =
            if (x < x0 || z < z0 || x >= x0 + w || z >= z0 + h) -1 else (x - x0) * h + (z - z0)

        override fun known(x: Int, z: Int) = index(x, z).let { it >= 0 && kind[it].toInt() != 0 }
        override fun kind(x: Int, z: Int) = index(x, z).let { if (it < 0) GroundMap.Kind.UNKNOWN else GroundMap.Kind.entries[kind[it].toInt()] }
        override fun height(x: Int, z: Int) = index(x, z).let { if (it < 0) Int.MIN_VALUE else height[it].toInt() }
        override fun room(x: Int, z: Int) = index(x, z).let { if (it < 0) 0 else room[it].toInt() }
    }

    private class Trip(val name: String, val from: Vec3, val goal: Vec3)

    private fun ground(): FileGround {
        val path = System.getenv("SBWNPC_GROUND")
        assumeTrue(path != null && File(path).exists(), "no saved-world ground to plan over")
        return FileGround(File(path!!))
    }

    private fun plan(medium: CellPlanner.Medium, trip: Trip): String {
        val started = System.nanoTime()
        val search = CellPlanner.search(medium, trip.from, trip.goal) ?: return "${trip.name}: not on known open ground"
        var ticks = 0
        while (!search.step(2_500)) ticks++
        val ms = (System.nanoTime() - started) / 1.0e6
        val route = search.result()
            ?: return "${trip.name}: NO ROUTE (${search.stoppedBy}) after ${search.expanded} units, $ticks ticks, %.0f ms".format(ms)
        val ys = route.trail.map { it.y }
        val short = Math.hypot(route.landing.x - trip.goal.x, route.landing.z - trip.goal.z)
        return ("${trip.name}: ${search.stoppedBy}, ${route.trail.size} columns, cost %.0f, ends %.0f from the goal, " +
            "lowest y %.0f highest y %.0f, ${search.expanded} units, $ticks ticks, %.0f ms").format(route.length, short, ys.min(), ys.max(), ms)
    }

    /** Only the ground within [radius] of where the traveller has been is known — as in the game,
     *  where the land loads round the squad as it goes. */
    private class Seen(private val ground: Ground, private val radius: Int) : Ground {
        val spots = ArrayList<Vec3>()
        private fun seen(x: Int, z: Int) = spots.any { Math.abs(it.x - x) <= radius && Math.abs(it.z - z) <= radius }
        override fun known(x: Int, z: Int) = seen(x, z) && ground.known(x, z)
        override fun kind(x: Int, z: Int) = if (seen(x, z)) ground.kind(x, z) else GroundMap.Kind.UNKNOWN
        override fun height(x: Int, z: Int) = if (seen(x, z)) ground.height(x, z) else Int.MIN_VALUE
        override fun room(x: Int, z: Int) = if (seen(x, z)) ground.room(x, z) else 0
    }

    /** The trip taken a known stretch at a time: planned, followed to the end of what is known,
     *  planned again from there with the land round it seen — until it gets there. */
    private fun travel(trip: Trip, radius: Int, medium: (Ground) -> CellPlanner.Medium, ground: Ground): String {
        val seen = Seen(ground, radius)
        var at = trip.from
        var legs = 0
        var lowest = Double.MAX_VALUE
        var walked = 0.0
        while (legs < 20) {
            seen.spots += at
            legs++
            val search = CellPlanner.search(medium(seen), at, trip.goal) ?: return "${trip.name}: leg $legs not on known open ground at $at"
            while (!search.step(2_500)) Unit
            val route = search.result() ?: return "${trip.name}: leg $legs NO ROUTE (${search.stoppedBy}) at $at"
            lowest = minOf(lowest, route.trail.minOf { it.y })
            // Along the way the land round it loads too.
            route.trail.forEachIndexed { i, p -> if (i % 32 == 0) seen.spots += p }
            walked += route.trail.zipWithNext { a, b -> Math.hypot(a.x - b.x, a.z - b.z) }.sum()
            at = route.landing
            if (route.complete) break
        }
        val short = Math.hypot(at.x - trip.goal.x, at.z - trip.goal.z)
        return "${trip.name}: seen $radius, $legs legs, %.0f blocks, ends %.0f from the goal, lowest y %.0f".format(walked, short, lowest)
    }

    @Test
    fun `marches and drives from the log`() {
        val ground = ground()
        val marches = listOf(
            Trip("Bravo 20:12:06", Vec3(-609.5, 108.0, 394.5), Vec3(-1159.5, 123.0, 553.5)),
            Trip("Bravo 20:12:53", Vec3(-630.5, 111.0, 406.5), Vec3(-1150.5, 123.0, 563.5)),
            Trip("Alpha 20:13:03", Vec3(-657.5, 114.0, 363.5), Vec3(-730.5, 64.0, -48.5)),
            Trip("Alpha 20:13:17", Vec3(-575.5, 107.0, 356.5), Vec3(-693.5, 64.0, -63.5)),
            Trip("Charlie 20:11:48", Vec3(-621.5, 108.0, 375.5), Vec3(-443.5, 70.0, 137.5)),
            Trip("Bravo in the canyon 20:18:57", Vec3(-1057.5, 62.0, 495.5), Vec3(-1165.5, 122.0, 534.5)),
        )
        val drives = listOf(
            Trip("LAV ea35 20:09:24", Vec3(-610.5, 108.0, 384.5), Vec3(-1158.5, 124.0, 416.5)),
            Trip("LAV 4b9c 20:09:25", Vec3(-614.5, 108.0, 399.5), Vec3(-1157.5, 125.0, 432.5)),
        )
        for (trip in marches) println("[walk] " + plan(Walking(ground), trip))
        for (trip in drives) println("[drive] " + plan(Driving(ground, 1.65, 1.5, 3.0), trip))
        for (trip in marches) println("[walk seen] " + travel(trip, 128, { Walking(it) }, ground))
        for (trip in drives) println("[drive seen] " + travel(trip, 128, { Driving(it, 1.65, 1.5, 3.0) }, ground))
    }
}
