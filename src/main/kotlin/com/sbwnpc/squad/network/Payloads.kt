package com.sbwnpc.squad.network

import com.sbwnpc.squad.SquadMod
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** Client -> server: overwrite the held squad tool's config. */
class ConfigureToolPayload(val cls: Int, val rank: Int, val color: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ConfigureToolPayload>(SquadMod.loc("configure_tool"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ConfigureToolPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ConfigureToolPayload::cls,
            ByteBufCodecs.VAR_INT, ConfigureToolPayload::rank,
            ByteBufCodecs.VAR_INT, ConfigureToolPayload::color,
            ::ConfigureToolPayload
        )
    }
}

/** Client -> server: a command-mode squad action. action: 0=create from selection, 1=disband, 2=set order. */
class SquadCmdPayload(val action: Int, val squad: String, val value: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        const val CREATE = 0
        const val DISBAND = 1
        const val SET_ORDER = 2

        val TYPE = CustomPacketPayload.Type<SquadCmdPayload>(SquadMod.loc("squad_cmd"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, SquadCmdPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SquadCmdPayload::action,
            ByteBufCodecs.STRING_UTF8, SquadCmdPayload::squad,
            ByteBufCodecs.VAR_INT, SquadCmdPayload::value,
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
