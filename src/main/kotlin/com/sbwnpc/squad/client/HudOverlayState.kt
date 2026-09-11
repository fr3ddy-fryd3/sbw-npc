package com.sbwnpc.squad.client

import com.sbwnpc.squad.network.HudOrderPayload
import com.sbwnpc.squad.network.RequestHudPayload
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.ChatFormatting
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Client-side, non-blocking quick-command overlay state (see [HudKeys], rendered by
 * [HudClientEvents]). Toggled with [HudKeys.TOGGLE]; while open, number keys 1-9
 * ([HudKeys.SLOTS]) first pick a squad, then — reusing the same keys — pick that squad's order.
 * Picking an order sends it straight to the server, which also raycasts the player's current look
 * direction for the objective in the same action (see `ModNetwork.onHudOrder`), then the overlay
 * drops back to the squad list so several squads can be commanded in one sitting without
 * reopening.
 */
object HudOverlayState {

    enum class Mode { SQUAD_LIST, ORDERS }

    data class Row(val id: String, val name: String, val color: ChatFormatting, val members: Int)

    @JvmStatic
    var isOpen: Boolean = false
        private set

    var mode: Mode = Mode.SQUAD_LIST
        private set
    var rows: List<Row> = emptyList()
        private set
    var selected: Row? = null
        private set

    fun toggle() {
        if (isOpen) close() else PacketDistributor.sendToServer(RequestHudPayload)
    }

    fun close() {
        isOpen = false
        mode = Mode.SQUAD_LIST
        selected = null
        rows = emptyList()
    }

    /** Server replied to the open request with a fresh squad list. */
    fun onSnapshot(data: CompoundTag) {
        rows = data.getList("Squads", Tag.TAG_COMPOUND.toInt()).map {
            val t = it as CompoundTag
            Row(
                t.getString("Id"), t.getString("Name"),
                ChatFormatting.getByName(t.getString("Color"))?.takeIf { c -> c.isColor } ?: ChatFormatting.WHITE,
                t.getInt("Members")
            )
        }
        mode = Mode.SQUAD_LIST
        selected = null
        isOpen = true
    }

    /** 0-indexed slot pick (slot key 1 -> index 0, ... slot key 9 -> index 8). */
    fun pickSlot(index: Int) {
        when (mode) {
            Mode.SQUAD_LIST -> {
                val row = rows.getOrNull(index) ?: return
                selected = row
                mode = Mode.ORDERS
            }
            Mode.ORDERS -> {
                val row = selected ?: return
                val order = SquadOrder.entries.getOrNull(index) ?: return
                PacketDistributor.sendToServer(HudOrderPayload(row.id, order.ordinal))
                mode = Mode.SQUAD_LIST
                selected = null
            }
        }
    }
}
