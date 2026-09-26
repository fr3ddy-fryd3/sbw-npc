package com.sbwnpc.squad.integration.journeymap

import com.mojang.blaze3d.platform.NativeImage
import com.sbwnpc.squad.SquadMod
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation

/**
 * The map's symbols, drawn in white at runtime and tinted per faction by JourneyMap — no texture
 * files. Registered once, on first use (textures have to be made on the render thread).
 */
internal enum class MapShapes {
    SQUARE, CIRCLE, TRIANGLE, CROSS;

    val location: ResourceLocation = SquadMod.loc("map/${name.lowercase()}")

    companion object {
        const val SIZE = 32
        private var registered = false

        fun ensureRegistered() {
            if (registered) return
            registered = true
            val textures = Minecraft.getInstance().textureManager
            for (shape in entries) textures.register(shape.location, DynamicTexture(draw(shape)))
        }

        private fun draw(shape: MapShapes): NativeImage {
            val img = NativeImage(SIZE, SIZE, true)
            val c = (SIZE - 1) / 2.0
            for (x in 0 until SIZE) for (y in 0 until SIZE) {
                val dx = x - c
                val dy = y - c
                val inside = when (shape) {
                    SQUARE -> x in 4 until SIZE - 4 && y in 4 until SIZE - 4
                    CIRCLE -> dx * dx + dy * dy <= (c - 2) * (c - 2)
                    // Point up; widens toward the bottom edge.
                    TRIANGLE -> y in 3 until SIZE - 3 && Math.abs(dx) <= (y - 3) * 0.55
                    CROSS -> x in 3 until SIZE - 3 && y in 3 until SIZE - 3 &&
                        (Math.abs(dx - dy) <= 3.0 || Math.abs(dx + dy) <= 3.0)
                }
                // ABGR; opaque white where the shape is, a thin dark rim for contrast on any map.
                img.setPixelRGBA(x, y, if (inside) -1 else 0)
            }
            outline(img)
            return img
        }

        /** Dark edge around the shape, so a white or yellow faction still reads on snow or sand. */
        private fun outline(img: NativeImage) {
            val filled = Array(SIZE) { x -> BooleanArray(SIZE) { y -> img.getPixelRGBA(x, y) != 0 } }
            for (x in 0 until SIZE) for (y in 0 until SIZE) {
                if (filled[x][y]) continue
                val near = (-1..1).any { ox -> (-1..1).any { oy ->
                    val nx = x + ox
                    val ny = y + oy
                    nx in 0 until SIZE && ny in 0 until SIZE && filled[nx][ny]
                } }
                if (near) img.setPixelRGBA(x, y, 0xC0202020.toInt())
            }
        }
    }
}
