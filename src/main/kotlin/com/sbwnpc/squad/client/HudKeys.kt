package com.sbwnpc.squad.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.settings.KeyConflictContext
import org.lwjgl.glfw.GLFW

/**
 * The quick-command HUD's keys: one toggle, plus nine number-row slot keys (squad picker, then
 * reused for the order picker). The slot keys physically collide with vanilla's hotbar-select
 * keys — [com.sbwnpc.squad.mixin.KeyMappingMixin] suppresses the hotbar side of that collision
 * while [HudOverlayState] is open, so both can be bound to 1-9 at once with no real conflict.
 */
@EventBusSubscriber(Dist.CLIENT)
object HudKeys {
    const val CATEGORY = "key.categories.sbwnpc"
    private val KEYS = mutableListOf<KeyMapping>()

    @JvmField
    val TOGGLE = registerKey("hud_toggle", GLFW.GLFW_KEY_B)

    @JvmField
    val SLOTS: List<KeyMapping> = (1..9).map { registerKey("hud_slot_$it", GLFW.GLFW_KEY_1 + (it - 1)) }

    private fun registerKey(name: String, code: Int): KeyMapping {
        val key = KeyMapping("key.sbwnpc.$name", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, code, CATEGORY)
        KEYS.add(key)
        return key
    }

    @SubscribeEvent
    fun registerKeyMappings(event: RegisterKeyMappingsEvent) {
        KEYS.forEach { event.register(it) }
    }
}
