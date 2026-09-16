package com.sbwnpc.squad.network

import com.sbwnpc.squad.SquadMod
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** Client -> server: overwrite the held squad tool's config. */
class ConfigureToolPayload(val cls: Int, val rank: Int, val faction: Int, val preset: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ConfigureToolPayload>(SquadMod.loc("configure_tool"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ConfigureToolPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ConfigureToolPayload::cls,
            ByteBufCodecs.VAR_INT, ConfigureToolPayload::rank,
            ByteBufCodecs.VAR_INT, ConfigureToolPayload::faction,
            ByteBufCodecs.VAR_INT, ConfigureToolPayload::preset,
            ::ConfigureToolPayload
        )
    }
}

/** Client -> server: a command-mode squad action. */
class SquadCmdPayload(val action: Int, val squad: String, val value: Int, val text: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        const val CREATE = 0
        const val DISBAND = 1
        const val SET_ORDER = 2
        const val RENAME = 3
        const val ARM_OBJECTIVE = 4
        const val SELECT = 5
        const val ARM_FOCUS = 6
        const val DELETE_SQUAD = 7

        val TYPE = CustomPacketPayload.Type<SquadCmdPayload>(SquadMod.loc("squad_cmd"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, SquadCmdPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SquadCmdPayload::action,
            ByteBufCodecs.STRING_UTF8, SquadCmdPayload::squad,
            ByteBufCodecs.VAR_INT, SquadCmdPayload::value,
            ByteBufCodecs.STRING_UTF8, SquadCmdPayload::text,
            ::SquadCmdPayload
        )
    }
}

/** Server -> client: open the command screen, carrying a snapshot of the player's squads. */
class OpenCommandScreenPayload(val data: CompoundTag) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<OpenCommandScreenPayload>(SquadMod.loc("open_command_screen"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, OpenCommandScreenPayload> = StreamCodec.composite(
            ByteBufCodecs.COMPOUND_TAG, OpenCommandScreenPayload::data,
            ::OpenCommandScreenPayload
        )
    }
}

/** Client -> server: the quick-command HUD was opened, please send a fresh squad list.
 *  A singleton (StreamCodec.unit requires encoding the exact same instance every time, not just
 *  an equal one — a `class` re-instantiated per send fails that identity check and blows up the
 *  connection). */
object RequestHudPayload : CustomPacketPayload {
    override fun type() = TYPE

    val TYPE = CustomPacketPayload.Type<RequestHudPayload>(SquadMod.loc("request_hud"))
    val CODEC: StreamCodec<RegistryFriendlyByteBuf, RequestHudPayload> = StreamCodec.unit(this)
}

/** Server -> client: (re)populate the quick-command HUD's squad list. */
class OpenHudPayload(val data: CompoundTag) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<OpenHudPayload>(SquadMod.loc("open_hud"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, OpenHudPayload> = StreamCodec.composite(
            ByteBufCodecs.COMPOUND_TAG, OpenHudPayload::data,
            ::OpenHudPayload
        )
    }
}

/** Client -> server: quick-command HUD order pick — set [squad]'s order and, in the same action,
 *  its objective to wherever the player is currently looking (raycast happens server-side, using
 *  the player's synced look direction at the moment this is processed). */
class HudOrderPayload(val squad: String, val order: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<HudOrderPayload>(SquadMod.loc("hud_order"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, HudOrderPayload> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, HudOrderPayload::squad,
            ByteBufCodecs.VAR_INT, HudOrderPayload::order,
            ::HudOrderPayload
        )
    }
}

/** Server -> client: this player hasn't picked their faction yet — force the mandatory picker.
 *  Sent whenever a server-side entry point that would assign a faction finds no entry in
 *  [com.sbwnpc.squad.squad.PlayerFactionRegistry] for the sender. A singleton for the same reason
 *  [RequestHudPayload] is one. */
object OpenFactionPickPayload : CustomPacketPayload {
    override fun type() = TYPE

    val TYPE = CustomPacketPayload.Type<OpenFactionPickPayload>(SquadMod.loc("open_faction_pick"))
    val CODEC: StreamCodec<RegistryFriendlyByteBuf, OpenFactionPickPayload> = StreamCodec.unit(this)
}

/** Client -> server: the player's one-time faction pick. */
class ChooseFactionPayload(val faction: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ChooseFactionPayload>(SquadMod.loc("choose_faction"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ChooseFactionPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ChooseFactionPayload::faction,
            ::ChooseFactionPayload
        )
    }
}

/** Server -> client: permission granted, open the Recruit GUI using the tool currently in hand.
 *  Recruit-mode air-click now round-trips through the server (same pattern Command mode already
 *  used) purely so the faction-lock check has somewhere server-side to happen before the GUI
 *  opens — the GUI content itself still comes entirely from the client's own held item stack. */
object OpenRecruitScreenPayload : CustomPacketPayload {
    override fun type() = TYPE

    val TYPE = CustomPacketPayload.Type<OpenRecruitScreenPayload>(SquadMod.loc("open_recruit_screen"))
    val CODEC: StreamCodec<RegistryFriendlyByteBuf, OpenRecruitScreenPayload> = StreamCodec.unit(this)
}

/** Client -> server: quick-command HUD order pick for EVERY squad the sender owns at once (the
 *  `0` key) — same order and the same single look-direction raycast applied to each of them. No
 *  squad id needed: the server resolves "every squad I own" itself, which also means ownership
 *  needs no separate check here (unlike [HudOrderPayload]/[SquadCmdPayload]) — the owner filter
 *  IS the selection. */
class HudOrderAllPayload(val order: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<HudOrderAllPayload>(SquadMod.loc("hud_order_all"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, HudOrderAllPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, HudOrderAllPayload::order,
            ::HudOrderAllPayload
        )
    }
}

/** Client -> server: a route-related action (Phase 5.6 waypoints). */
class RouteCmdPayload(val action: Int, val route: String, val text: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        const val START_RECORDING = 0
        const val FINISH = 1       // text = new route's name
        const val CANCEL = 2
        const val ASSIGN = 3       // route = route id, text = squad id
        const val DELETE = 4       // route = route id
        const val REQUEST_LIST = 5

        val TYPE = CustomPacketPayload.Type<RouteCmdPayload>(SquadMod.loc("route_cmd"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, RouteCmdPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RouteCmdPayload::action,
            ByteBufCodecs.STRING_UTF8, RouteCmdPayload::route,
            ByteBufCodecs.STRING_UTF8, RouteCmdPayload::text,
            ::RouteCmdPayload
        )
    }
}

/** Server -> client: open/refresh the routes screen, carrying the player's routes and squads. */
class OpenRoutesScreenPayload(val data: CompoundTag) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<OpenRoutesScreenPayload>(SquadMod.loc("open_routes_screen"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, OpenRoutesScreenPayload> = StreamCodec.composite(
            ByteBufCodecs.COMPOUND_TAG, OpenRoutesScreenPayload::data,
            ::OpenRoutesScreenPayload
        )
    }
}

/** Server -> client: player air-clicked while recording a route — show the Finish/Cancel prompt
 *  instead of the normal GUI (same "tool behavior changes by armed state" pattern as
 *  ARM_OBJECTIVE/ARM_FOCUS). */
class OpenFinishRoutePayload(val pointCount: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<OpenFinishRoutePayload>(SquadMod.loc("open_finish_route"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, OpenFinishRoutePayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenFinishRoutePayload::pointCount,
            ::OpenFinishRoutePayload
        )
    }
}
