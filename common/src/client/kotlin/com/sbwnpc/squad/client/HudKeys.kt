package com.sbwnpc.squad.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW

/**
 * The quick-command HUD's keys: one toggle, nine number-row slot keys (squad picker, then reused
 * for the order picker), and `0` to act on every squad the player owns at once. The slot keys
 * physically collide with vanilla's hotbar-select keys — [com.sbwnpc.squad.mixin.KeyMappingMixin]
 * suppresses the hotbar side of that collision while [HudOverlayState] is open, so both can be
 * bound to 1-9 at once with no real conflict. `0` has no vanilla hotbar binding, so it needs no
 * such interception.
 */
object HudKeys {
    const val CATEGORY = "key.categories.sbwnpc"

    /** Every key of the HUD, for the loader to register. */
    val KEYS = mutableListOf<KeyMapping>()

    // Z: under the left hand on WASD, and the one such key nothing else in the pack binds — B is
    // JourneyMap's waypoint key, G opens Curios, and X/C/V are taken several times over.
    @JvmField
    val TOGGLE = registerKey("hud_toggle", GLFW.GLFW_KEY_Z)

    @JvmField
    val SLOTS: List<KeyMapping> = (1..9).map { registerKey("hud_slot_$it", GLFW.GLFW_KEY_1 + (it - 1)) }

    @JvmField
    val SELECT_ALL = registerKey("hud_select_all", GLFW.GLFW_KEY_0)

    private fun registerKey(name: String, code: Int): KeyMapping {
        val key = KeyMapping("key.sbwnpc.$name", InputConstants.Type.KEYSYM, code, CATEGORY)
        KEYS.add(key)
        return key
    }
}
