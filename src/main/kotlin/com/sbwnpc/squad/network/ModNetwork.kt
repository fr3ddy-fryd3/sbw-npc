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
                        SquadTeams.byOrdinal(p.color)
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
            when (p.action) {
                SquadCmdPayload.CREATE -> {
                    val members = SquadSelection.looseOf(player.uuid).toList()
                    if (members.isEmpty()) return@enqueueWork
                    val color = SquadToolItem.readConfig(heldTool(player))?.color ?: SquadTeams.COLORS.first()
                    val squad = mgr.create(level, player.uuid, color, members, members.first())
                    SquadSelection.clear(player.uuid)
                    SquadSelection.selectSquad(player.uuid, squad.id)
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal("Squad ${squad.name} formed"), true)
                }
                SquadCmdPayload.DISBAND -> runCatching { mgr.disband(level, UUID.fromString(p.squad)) }
                SquadCmdPayload.SET_ORDER -> runCatching {
                    mgr.setOrder(UUID.fromString(p.squad), SquadOrder.byOrdinal(p.value))
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
