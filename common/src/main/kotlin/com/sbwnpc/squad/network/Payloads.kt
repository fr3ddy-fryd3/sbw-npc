package com.sbwnpc.squad.network

import com.sbwnpc.squad.ModIdentity
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag

/** Client -> server: ctrl+air-click on the squad tool — cycle RECRUIT/COMMAND mode. Triggered from
 *  [com.sbwnpc.squad.client.ToolInputEvents] (a raw ctrl-modifier isn't part of synced player input
 *  the way sneaking is, so the client has to detect it and tell the server what happened) rather
 *  than from [com.sbwnpc.squad.item.SquadToolItem.use] itself. A singleton for the same reason
 *  [RequestHudPayload] is one. */
object ToggleToolModePayload : PacketPayload {
    override fun type() = TYPE

    val TYPE = PayloadType<ToggleToolModePayload>(ModIdentity.loc("toggle_tool_mode"))
    val CODEC: PayloadCodec<ToggleToolModePayload> = Codecs.unit(this)
}

/** Client -> server: overwrite the held squad tool's config. */
class ConfigureToolPayload(
    val cls: Int,
    val rank: Int,
    val faction: Int,
    val preset: Int,
    val vehicle: Boolean,
    val vehicleModel: Int,
    val tankModel: Int,
    val heliModel: Int,
) : PacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = PayloadType<ConfigureToolPayload>(ModIdentity.loc("configure_tool"))

        // Past StreamCodec.composite's max arity of 6, so this is wired by hand instead of the
        // usual composite(...) call.
        val CODEC: PayloadCodec<ConfigureToolPayload> = Codecs.of(
            { buf, p ->
                buf.writeVarInt(p.cls)
                buf.writeVarInt(p.rank)
                buf.writeVarInt(p.faction)
                buf.writeVarInt(p.preset)
                buf.writeBoolean(p.vehicle)
                buf.writeVarInt(p.vehicleModel)
                buf.writeVarInt(p.tankModel)
                buf.writeVarInt(p.heliModel)
            },
            { buf ->
                ConfigureToolPayload(
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                )
            },
        )
    }
}

/** Client -> server: a command-mode squad action. */
class SquadCmdPayload(val action: Int, val squad: String, val value: Int, val text: String) : PacketPayload {
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

        /** From the map: squad = comma-separated squad ids, value = order, text = "x z" of the
         *  point clicked. Each squad gets its own version of it — see `GroupOrders`. */
        const val MAP_ORDER = 8

        /** Radio calls from all the sender's squads on or off. */
        const val TOGGLE_REPORTS = 9
        const val REQUEST_DETAILS = 10
        const val MERGE = 11
        const val REQUEST_LIST = 12

        val TYPE = PayloadType<SquadCmdPayload>(ModIdentity.loc("squad_cmd"))
        val CODEC: PayloadCodec<SquadCmdPayload> = Codecs.composite(
            Codecs.VAR_INT, SquadCmdPayload::action,
            Codecs.STRING, SquadCmdPayload::squad,
            Codecs.VAR_INT, SquadCmdPayload::value,
            Codecs.STRING, SquadCmdPayload::text,
            ::SquadCmdPayload,
        )
    }
}

/** Server -> client: open the command screen, carrying a snapshot of the player's squads. */
class OpenCommandScreenPayload(val data: CompoundTag) : PacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = PayloadType<OpenCommandScreenPayload>(ModIdentity.loc("open_command_screen"))
        val CODEC: PayloadCodec<OpenCommandScreenPayload> = Codecs.composite(
            Codecs.COMPOUND_TAG,
            OpenCommandScreenPayload::data,
            ::OpenCommandScreenPayload,
        )
    }
}

/** Server -> client: one owned squad, its loaded soldiers and legal merge destinations. */
class SquadDetailsPayload(val data: CompoundTag) : PacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = PayloadType<SquadDetailsPayload>(ModIdentity.loc("squad_details"))
        val CODEC: PayloadCodec<SquadDetailsPayload> = Codecs.composite(
            Codecs.COMPOUND_TAG,
            SquadDetailsPayload::data,
            ::SquadDetailsPayload,
        )
    }
}

/** Client -> server: the quick-command HUD was opened, please send a fresh squad list.
 *  A singleton (StreamCodec.unit requires encoding the exact same instance every time, not just
 *  an equal one — a `class` re-instantiated per send fails that identity check and blows up the
 *  connection). */
object RequestHudPayload : PacketPayload {
    override fun type() = TYPE

    val TYPE = PayloadType<RequestHudPayload>(ModIdentity.loc("request_hud"))
    val CODEC: PayloadCodec<RequestHudPayload> = Codecs.unit(this)
}

/** Server -> client: (re)populate the quick-command HUD's squad list. */
class OpenHudPayload(val data: CompoundTag) : PacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = PayloadType<OpenHudPayload>(ModIdentity.loc("open_hud"))
        val CODEC: PayloadCodec<OpenHudPayload> = Codecs.composite(
            Codecs.COMPOUND_TAG,
            OpenHudPayload::data,
            ::OpenHudPayload,
        )
    }
}

/** Client -> server: quick-command HUD order pick — set [squad]'s order and, in the same action,
 *  its objective to wherever the player is currently looking (raycast happens server-side, using
 *  the player's synced look direction at the moment this is processed). */
class HudOrderPayload(val squad: String, val order: Int) : PacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = PayloadType<HudOrderPayload>(ModIdentity.loc("hud_order"))
        val CODEC: PayloadCodec<HudOrderPayload> = Codecs.composite(
            Codecs.STRING,
            HudOrderPayload::squad,
            Codecs.VAR_INT,
            HudOrderPayload::order,
            ::HudOrderPayload,
        )
    }
}

/** Server -> client: this player hasn't picked their faction yet — force the mandatory picker.
 *  Sent whenever a server-side entry point that would assign a faction finds no entry in
 *  [com.sbwnpc.squad.squad.PlayerFactionRegistry] for the sender. A singleton for the same reason
 *  [RequestHudPayload] is one. */
object OpenFactionPickPayload : PacketPayload {
    override fun type() = TYPE

    val TYPE = PayloadType<OpenFactionPickPayload>(ModIdentity.loc("open_faction_pick"))
    val CODEC: PayloadCodec<OpenFactionPickPayload> = Codecs.unit(this)
}

/** Server -> client: the player's own faction, or -1 before they've picked one — for the banner
 *  over the screen while the squad tool is in hand. Sent on login and when they pick. */
class PlayerFactionPayload(val faction: Int) : PacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = PayloadType<PlayerFactionPayload>(ModIdentity.loc("player_faction"))
        val CODEC: PayloadCodec<PlayerFactionPayload> = Codecs.composite(
            Codecs.VAR_INT,
            PlayerFactionPayload::faction,
            ::PlayerFactionPayload,
        )
    }
}

/** Client -> server: the player's one-time faction pick. */
class ChooseFactionPayload(val faction: Int) : PacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = PayloadType<ChooseFactionPayload>(ModIdentity.loc("choose_faction"))
        val CODEC: PayloadCodec<ChooseFactionPayload> = Codecs.composite(
            Codecs.VAR_INT,
            ChooseFactionPayload::faction,
            ::ChooseFactionPayload,
        )
    }
}

/** Server -> client: permission granted, open the Recruit GUI using the tool currently in hand.
 *  Recruit-mode air-click now round-trips through the server (same pattern Command mode already
 *  used) purely so the faction-lock check has somewhere server-side to happen before the GUI
 *  opens — the GUI content itself still comes entirely from the client's own held item stack. */
object OpenRecruitScreenPayload : PacketPayload {
    override fun type() = TYPE

    val TYPE = PayloadType<OpenRecruitScreenPayload>(ModIdentity.loc("open_recruit_screen"))
    val CODEC: PayloadCodec<OpenRecruitScreenPayload> = Codecs.unit(this)
}

/** Client -> server: quick-command HUD order pick for EVERY squad the sender owns at once (the
 *  `0` key) — same order and the same single look-direction raycast applied to each of them. No
 *  squad id needed: the server resolves "every squad I own" itself, which also means ownership
 *  needs no separate check here (unlike [HudOrderPayload]/[SquadCmdPayload]) — the owner filter
 *  IS the selection. */
class HudOrderAllPayload(val order: Int) : PacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = PayloadType<HudOrderAllPayload>(ModIdentity.loc("hud_order_all"))
        val CODEC: PayloadCodec<HudOrderAllPayload> = Codecs.composite(
            Codecs.VAR_INT,
            HudOrderAllPayload::order,
            ::HudOrderAllPayload,
        )
    }
}

/** Client -> server: a patrol-route action. */
class RouteCmdPayload(val action: Int, val route: String, val text: String) : PacketPayload {
    override fun type() = TYPE

    companion object {
        const val START_RECORDING = 0
        const val FINISH = 1 // text = new route's name
        const val CANCEL = 2
        const val ASSIGN = 3 // route = route id, text = squad id
        const val DELETE = 4 // route = route id
        const val REQUEST_LIST = 5
        const val MAP_ROUTE = 6 // route = squad ids, comma-separated; text = "x z x z ..." drawn on the map

        val TYPE = PayloadType<RouteCmdPayload>(ModIdentity.loc("route_cmd"))
        val CODEC: PayloadCodec<RouteCmdPayload> = Codecs.composite(
            Codecs.VAR_INT,
            RouteCmdPayload::action,
            Codecs.STRING,
            RouteCmdPayload::route,
            Codecs.STRING,
            RouteCmdPayload::text,
            ::RouteCmdPayload,
        )
    }
}

/** Server -> client: open/refresh the routes screen, carrying the player's routes and squads. */
class OpenRoutesScreenPayload(val data: CompoundTag) : PacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = PayloadType<OpenRoutesScreenPayload>(ModIdentity.loc("open_routes_screen"))
        val CODEC: PayloadCodec<OpenRoutesScreenPayload> = Codecs.composite(
            Codecs.COMPOUND_TAG,
            OpenRoutesScreenPayload::data,
            ::OpenRoutesScreenPayload,
        )
    }
}

/** Server -> client: player air-clicked while recording a route — show the Finish/Cancel prompt
 *  instead of the normal GUI (same "tool behavior changes by armed state" pattern as
 *  ARM_OBJECTIVE/ARM_FOCUS). */
class OpenFinishRoutePayload(val pointCount: Int) : PacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = PayloadType<OpenFinishRoutePayload>(ModIdentity.loc("open_finish_route"))
        val CODEC: PayloadCodec<OpenFinishRoutePayload> = Codecs.composite(
            Codecs.VAR_INT,
            OpenFinishRoutePayload::pointCount,
            ::OpenFinishRoutePayload,
        )
    }
}

/** Server -> client: open the Barracks' garrison config, carrying what it is currently set to.
 *  The same [com.sbwnpc.squad.client.screen.RecruitScreen] the squad tool uses — a Barracks
 *  describes a deployment exactly the way the tool does. */
class OpenBarracksScreenPayload(val pos: BlockPos, val config: CompoundTag) : PacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = PayloadType<OpenBarracksScreenPayload>(ModIdentity.loc("open_barracks_screen"))
        val CODEC: PayloadCodec<OpenBarracksScreenPayload> = Codecs.composite(
            Codecs.BLOCK_POS,
            OpenBarracksScreenPayload::pos,
            Codecs.COMPOUND_TAG,
            OpenBarracksScreenPayload::config,
            ::OpenBarracksScreenPayload,
        )
    }
}

/** Client -> server: set what the Barracks at [pos] garrisons. */
class ConfigureBarracksPayload(val pos: BlockPos, val config: CompoundTag) : PacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = PayloadType<ConfigureBarracksPayload>(ModIdentity.loc("configure_barracks"))
        val CODEC: PayloadCodec<ConfigureBarracksPayload> = Codecs.composite(
            Codecs.BLOCK_POS,
            ConfigureBarracksPayload::pos,
            Codecs.COMPOUND_TAG,
            ConfigureBarracksPayload::config,
            ::ConfigureBarracksPayload,
        )
    }
}

/** Client -> server: refresh a nearby owned barracks' production queue. */
class RequestBarracksQueuePayload(val pos: BlockPos) : PacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = PayloadType<RequestBarracksQueuePayload>(ModIdentity.loc("request_barracks_queue"))
        val CODEC: PayloadCodec<RequestBarracksQueuePayload> = Codecs.composite(
            Codecs.BLOCK_POS,
            RequestBarracksQueuePayload::pos,
            ::RequestBarracksQueuePayload,
        )
    }
}

/** Server -> client: queue state only; never overwrites unsubmitted configuration edits. */
class BarracksQueuePayload(val pos: BlockPos, val data: CompoundTag) : PacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = PayloadType<BarracksQueuePayload>(ModIdentity.loc("barracks_queue"))
        val CODEC: PayloadCodec<BarracksQueuePayload> = Codecs.composite(
            Codecs.BLOCK_POS,
            BarracksQueuePayload::pos,
            Codecs.COMPOUND_TAG,
            BarracksQueuePayload::data,
            ::BarracksQueuePayload,
        )
    }
}

/** Server -> client: open/refresh the screen of the Supply at [pos]. */
class OpenSupplyScreenPayload(val pos: BlockPos, val data: CompoundTag) : PacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = PayloadType<OpenSupplyScreenPayload>(ModIdentity.loc("open_supply_screen"))
        val CODEC: PayloadCodec<OpenSupplyScreenPayload> = Codecs.composite(
            Codecs.BLOCK_POS,
            OpenSupplyScreenPayload::pos,
            Codecs.COMPOUND_TAG,
            OpenSupplyScreenPayload::data,
            ::OpenSupplyScreenPayload,
        )
    }
}

/** Client -> server: a Supply screen action at [pos]. */
class SupplyCmdPayload(val pos: BlockPos, val action: Int, val kit: Int, val option: Int) : PacketPayload {
    override fun type() = TYPE

    companion object {
        const val TAKE_KIT = 1 // kit = its index, option = the weapon's
        const val SET_SPAWN = 2
        const val SET_FACTION = 3 // creative only; kit = the faction's ordinal
        const val RESUPPLY_AMMO = 4 // kit = the class currently shown in the Supply screen

        val TYPE = PayloadType<SupplyCmdPayload>(ModIdentity.loc("supply_cmd"))
        val CODEC: PayloadCodec<SupplyCmdPayload> = Codecs.composite(
            Codecs.BLOCK_POS, SupplyCmdPayload::pos,
            Codecs.VAR_INT, SupplyCmdPayload::action,
            Codecs.VAR_INT, SupplyCmdPayload::kit,
            Codecs.VAR_INT, SupplyCmdPayload::option,
            ::SupplyCmdPayload,
        )
    }
}

/** Client -> server: a Diplomacy screen action. */
class DiplomacyCmdPayload(val action: Int, val faction: Int, val proposal: Int) : PacketPayload {
    override fun type() = TYPE

    companion object {
        const val REQUEST = 0
        const val PROPOSE = 1 // faction = the one offered an alliance
        const val ACCEPT = 2 // proposal = its id
        const val TOGGLE_LEAVE = 3

        val TYPE = PayloadType<DiplomacyCmdPayload>(ModIdentity.loc("diplomacy_cmd"))
        val CODEC: PayloadCodec<DiplomacyCmdPayload> = Codecs.composite(
            Codecs.VAR_INT,
            DiplomacyCmdPayload::action,
            Codecs.VAR_INT,
            DiplomacyCmdPayload::faction,
            Codecs.VAR_INT,
            DiplomacyCmdPayload::proposal,
            ::DiplomacyCmdPayload,
        )
    }
}

/** Server -> client: open/refresh the Diplomacy screen. */
class OpenDiplomacyPayload(val data: CompoundTag) : PacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = PayloadType<OpenDiplomacyPayload>(ModIdentity.loc("open_diplomacy"))
        val CODEC: PayloadCodec<OpenDiplomacyPayload> = Codecs.composite(
            Codecs.COMPOUND_TAG,
            OpenDiplomacyPayload::data,
            ::OpenDiplomacyPayload,
        )
    }
}

/** Client -> server: this client has a map mod and wants [MapFeedPayload]s. */
object MapSubscribePayload : PacketPayload {
    val TYPE = PayloadType<MapSubscribePayload>(ModIdentity.loc("map_subscribe"))
    val CODEC: PayloadCodec<MapSubscribePayload> = Codecs.unit(MapSubscribePayload)
    override fun type() = TYPE
}

/** Server -> client: what the player's map shows, once a second — see `MapFeed`. */
class MapFeedPayload(val data: CompoundTag) : PacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = PayloadType<MapFeedPayload>(ModIdentity.loc("map_feed"))
        val CODEC: PayloadCodec<MapFeedPayload> = Codecs.composite(
            Codecs.COMPOUND_TAG,
            MapFeedPayload::data,
            ::MapFeedPayload,
        )
    }
}
