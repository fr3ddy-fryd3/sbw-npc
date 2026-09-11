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
