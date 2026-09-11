package com.sbwnpc.squad.network

import com.sbwnpc.squad.client.ClientPayloadHandlers
import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.squad.SquadSelection
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
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
        r.playToServer(RequestHudPayload.TYPE, RequestHudPayload.CODEC) { _, ctx -> onRequestHud(ctx) }
        r.playToServer(HudOrderPayload.TYPE, HudOrderPayload.CODEC) { p, ctx -> onHudOrder(p, ctx) }

        r.playToClient(OpenCommandScreenPayload.TYPE, OpenCommandScreenPayload.CODEC) { p, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openCommandScreen(p.data)
        }
        r.playToClient(OpenHudPayload.TYPE, OpenHudPayload.CODEC) { p, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openHud(p.data)
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
            // Only ever resolves to a squad the sender actually owns — a squad id in a packet is
            // just a string the client chose to send, so this is the one place that matters.
            fun ownedSid(): UUID? {
                val id = runCatching { UUID.fromString(p.squad) }.getOrNull() ?: return null
                if (!mgr.ownedBy(id, player.uuid)) {
                    bar("Not your squad")
                    return null
                }
                return id
            }
            when (p.action) {
                SquadCmdPayload.CREATE -> {
                    val members = SquadSelection.looseOf(player.uuid).toList()
                    if (members.isEmpty()) return@enqueueWork
                    val color = SquadToolItem.readConfig(heldTool(player))?.color ?: SquadTeams.COLORS.first()
                    val squad = mgr.create(level, player.uuid, color, members)
                    if (squad == null) {
                        bar("Squad limit (${SquadManager.MAX_SQUADS_PER_OWNER}) reached")
                        return@enqueueWork
                    }
                    SquadSelection.clear(player.uuid)
                    SquadSelection.selectSquad(player.uuid, squad.id)
                    bar("Squad ${squad.name} formed")
                }
                SquadCmdPayload.DISBAND -> ownedSid()?.let { mgr.disband(level, it) }
                SquadCmdPayload.SET_ORDER -> ownedSid()?.let { mgr.setOrder(it, SquadOrder.byOrdinal(p.value)) }
                SquadCmdPayload.RENAME -> ownedSid()?.let { mgr.rename(it, p.text) }
                SquadCmdPayload.SELECT -> ownedSid()?.let {
                    SquadSelection.selectSquad(player.uuid, it)
                    bar("Commanding ${mgr.get(it)?.name ?: "squad"}")
                }
                SquadCmdPayload.ARM_OBJECTIVE -> ownedSid()?.let {
                    SquadSelection.selectSquad(player.uuid, it)
                    SquadSelection.armObjective(player.uuid, it)
                    bar("Aim and right-click to set ${mgr.get(it)?.name ?: "squad"}'s objective")
                }
                SquadCmdPayload.ARM_FOCUS -> ownedSid()?.let {
                    SquadSelection.selectSquad(player.uuid, it)
                    SquadSelection.armFocus(player.uuid, it)
                    bar("Right-click anything (including your own NPCs) to focus ${mgr.get(it)?.name ?: "squad"} on it")
                }
            }
        }
    }

    private fun onRequestHud(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val snap = buildSquadSnapshot(SquadManager.get(level), player.uuid, 0)
            sendToClient(player, OpenHudPayload(snap))
        }
    }

    /** Sets the order AND (in the same action) the objective, raycast from wherever the player is
     *  currently looking — the HUD's whole point is a single keypress instead of a separate arm
     *  + aim + click round trip. */
    private fun onHudOrder(p: HudOrderPayload, ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val mgr = SquadManager.get(level)
            val id = runCatching { UUID.fromString(p.squad) }.getOrNull() ?: return@enqueueWork
            if (!mgr.ownedBy(id, player.uuid)) return@enqueueWork
            mgr.setOrder(id, SquadOrder.byOrdinal(p.order))
            mgr.setObjective(level, id, lookedAtPos(player, level))
        }
    }

    private fun lookedAtPos(player: ServerPlayer, level: ServerLevel): BlockPos {
        val hit = player.pick(220.0, 1.0f, false)
        if (hit.type == HitResult.Type.BLOCK) return (hit as BlockHitResult).blockPos
        return groundAt(level, hit.location)
    }

    private fun groundAt(level: ServerLevel, loc: Vec3): BlockPos {
        var p = BlockPos.containing(loc)
        var guard = 0
        while (level.getBlockState(p).isAir && p.y > level.minBuildHeight && guard++ < 200) p = p.below()
        return p.above()
    }

    private fun heldTool(player: ServerPlayer) =
        listOf(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)
            .map { player.getItemBySlot(it) }
            .firstOrNull { it.item is SquadToolItem }
            ?: net.minecraft.world.item.ItemStack.EMPTY
}
