package com.sbwnpc.squad.network

import com.sbwnpc.squad.client.ClientPayloadHandlers
import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.squad.SquadSelection
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.api.distmarker.Dist
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

@EventBusSubscriber
object ModNetwork {

    @SubscribeEvent
    fun register(event: RegisterPayloadHandlersEvent) {
        val r = event.registrar("1")

        r.playToServer(ConfigureToolPayload.TYPE, ConfigureToolPayload.CODEC) { p, ctx -> onConfigureTool(p, ctx) }
        r.playToServer(SquadCmdPayload.TYPE, SquadCmdPayload.CODEC) { p, ctx -> onSquadCmd(p, ctx) }

        r.playToClient(OpenCommandScreenPayload.TYPE, OpenCommandScreenPayload.CODEC) { p, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openCommandScreen(p.data)
        }
    }

    private fun onConfigureTool(p: ConfigureToolPayload, ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            for (slot in listOf(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) {
                val stack = player.getItemBySlot(slot)
                if (stack.item is SquadToolItem) {
                    SquadToolItem.writeConfig(
                        stack,
                        NpcClass.byOrdinal(p.cls),
                        NpcRank.byOrdinal(p.rank),
                        SquadTeams.byOrdinal(p.color),
                        com.sbwnpc.squad.npc.SquadPreset.byOrdinal(p.preset)
                    )
                }
            }
        }
    }

    private fun onSquadCmd(p: SquadCmdPayload, ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val mgr = SquadManager.get(level)
            fun bar(msg: String) = player.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), true)
            fun sid() = runCatching { UUID.fromString(p.squad) }.getOrNull()
            when (p.action) {
                SquadCmdPayload.CREATE -> {
                    val members = SquadSelection.looseOf(player.uuid).toList()
                    if (members.isEmpty()) return@enqueueWork
                    val color = SquadToolItem.readConfig(heldTool(player))?.color ?: SquadTeams.COLORS.first()
                    val squad = mgr.create(level, player.uuid, color, members)
                    SquadSelection.clear(player.uuid)
                    SquadSelection.selectSquad(player.uuid, squad.id)
                    bar("Squad ${squad.name} formed")
                }
                SquadCmdPayload.DISBAND -> sid()?.let { mgr.disband(level, it) }
                SquadCmdPayload.SET_ORDER -> sid()?.let { mgr.setOrder(it, SquadOrder.byOrdinal(p.value)) }
                SquadCmdPayload.RENAME -> sid()?.let { mgr.rename(it, p.text) }
                SquadCmdPayload.SELECT -> sid()?.let {
                    SquadSelection.selectSquad(player.uuid, it)
                    bar("Commanding ${mgr.get(it)?.name ?: "squad"}")
                }
                SquadCmdPayload.ARM_OBJECTIVE -> sid()?.let {
                    SquadSelection.selectSquad(player.uuid, it)
                    SquadSelection.armObjective(player.uuid, it)
                    bar("Aim and right-click to set ${mgr.get(it)?.name ?: "squad"}'s objective")
                }
                SquadCmdPayload.ARM_FOCUS -> sid()?.let {
                    SquadSelection.selectSquad(player.uuid, it)
                    SquadSelection.armFocus(player.uuid, it)
                    bar("Right-click anything (including your own NPCs) to focus ${mgr.get(it)?.name ?: "squad"} on it")
                }
            }
        }
    }

    private fun heldTool(player: ServerPlayer) =
        listOf(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)
            .map { player.getItemBySlot(it) }
            .firstOrNull { it.item is SquadToolItem }
            ?: net.minecraft.world.item.ItemStack.EMPTY
}
