package com.sbwnpc.squad.client

import com.sbwnpc.squad.network.HudOrderAllPayload
import com.sbwnpc.squad.network.HudOrderPayload
import com.sbwnpc.squad.network.RequestHudPayload
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Client-side, non-blocking quick-command overlay state (see [HudKeys], rendered by
 * [HudClientEvents]). Toggled with [HudKeys.TOGGLE]; while open, number keys 1-9
 * ([HudKeys.SLOTS]) first pick a squad, then — reusing the same keys — pick that squad's order.
 * `0` ([HudKeys.SELECT_ALL]) picks every squad the player owns at once instead of a single one.
 * Picking an order sends it straight to the server, which also raycasts the player's current look
 * direction for the objective in the same action (see `ModNetwork.onHudOrder`/`onHudOrderAll`),
 * then the overlay drops back to the squad list so several squads can be commanded in one sitting
 * without reopening.
 */
object HudOverlayState {

    enum class Mode { SQUAD_LIST, ORDERS }

    data class Row(
        val id: String,
        val name: String,
        val faction: SquadFaction,
        val members: Int,
        val tank: Boolean,
        val mortar: Boolean,
        val gunship: Boolean,
        val transport: Boolean
    )

    @JvmStatic
    var isOpen: Boolean = false
        private set

    var mode: Mode = Mode.SQUAD_LIST
        private set
    var rows: List<Row> = emptyList()
        private set
    var selected: Row? = null
        private set
    var selectedAll: Boolean = false
        private set
    /** The player's own recorded default faction — used only to show an accurate "ALL SQUADS (N)"
     *  count for [selectAll]; the server independently applies the same filter (ModNetwork.
     *  onHudOrderAll) regardless of what the client shows. Null if not chosen yet (shouldn't
     *  happen once squads exist, but no reason to crash on it). */
    var defaultFaction: SquadFaction? = null
        private set
    private var refreshTicks = 0

    /** Rows [selectAll] actually orders — those matching the player's own faction. A test/OPFOR
     *  squad of a different faction under the same player is excluded, same as server-side. */
    val allTargetRows: List<Row> get() = rows.filter { defaultFaction == null || it.faction == defaultFaction }

    fun toggle() {
        if (isOpen) {
            close()
        } else {
            refreshTicks = REFRESH_INTERVAL_TICKS
            PacketDistributor.sendToServer(RequestHudPayload)
        }
    }

    fun close() {
        isOpen = false
        mode = Mode.SQUAD_LIST
        selected = null
        selectedAll = false
        rows = emptyList()
        refreshTicks = 0
    }

    /** Server replied to the open request with a fresh squad list. */
    fun onSnapshot(data: CompoundTag) {
        val selectedId = selected?.id
        rows = data.getList("Squads", Tag.TAG_COMPOUND.toInt()).map {
            val t = it as CompoundTag
            Row(
                t.getString("Id"), t.getString("Name"),
                runCatching { SquadFaction.valueOf(t.getString("Faction")) }.getOrDefault(SquadFaction.DEFAULT),
                t.getInt("Members"), t.getBoolean("Tank"), t.getBoolean("Mortar"),
                t.getBoolean("Gunship"), t.getBoolean("Transport")
            )
        }
        defaultFaction = if (data.contains("DefaultFaction")) SquadFaction.byOrdinal(data.getInt("DefaultFaction")) else null
        if (mode == Mode.ORDERS && !selectedAll) {
            selected = rows.firstOrNull { it.id == selectedId }
            if (selected == null) mode = Mode.SQUAD_LIST
        }
        isOpen = true
    }

    /** Refreshes roster counts and newly created/deleted squads without interrupting order selection. */
    fun refreshIfDue() {
        if (--refreshTicks > 0) return
        refreshTicks = REFRESH_INTERVAL_TICKS
        PacketDistributor.sendToServer(RequestHudPayload)
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
                val order = availableOrders().getOrNull(index) ?: return
                if (selectedAll) {
                    PacketDistributor.sendToServer(HudOrderAllPayload(order.ordinal))
                } else {
                    val row = selected ?: return
                    PacketDistributor.sendToServer(HudOrderPayload(row.id, order.ordinal))
                }
                mode = Mode.SQUAD_LIST
                selected = null
                selectedAll = false
                // The squad list shown after this was fetched when the HUD was opened and never
                // refreshed since — stale if a squad died/disbanded mid-session. Ask for a fresh
                // one now; onSnapshot() will swap `rows` in when it arrives, keeping whatever's
                // currently cached on screen in the meantime instead of flashing empty.
                PacketDistributor.sendToServer(RequestHudPayload)
            }
        }
    }

    /** `0` pressed in the squad list — pick every squad of the player's own faction at once
     *  instead of one (see [allTargetRows]). */
    fun selectAll() {
        if (mode != Mode.SQUAD_LIST || allTargetRows.isEmpty()) return
        selected = null
        selectedAll = true
        mode = Mode.ORDERS
    }

    /** Orders displayed for the selected squad. Tank crews only navigate; mortar crews hold or fire. */
    fun availableOrders(): List<SquadOrder> = when {
        // "All squads" can be any mix, so offer what suits an ordinary one; the server clamps each
        // squad to its own list anyway.
        selectedAll -> SquadOrder.availableFor(tank = false, mortar = false)
        else -> SquadOrder.availableFor(
            selected?.tank == true, selected?.mortar == true,
            selected?.gunship == true, selected?.transport == true
        )
    }

    private const val REFRESH_INTERVAL_TICKS = 40
}
