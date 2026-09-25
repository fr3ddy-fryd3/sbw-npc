package com.sbwnpc.squad.network

import com.sbwnpc.squad.block.entity.BarracksBlockEntity
import com.sbwnpc.squad.client.ClientPayloadHandlers
import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.PlayerFactionRegistry
import com.sbwnpc.squad.squad.RouteManager
import com.sbwnpc.squad.squad.RouteRecording
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.squad.SquadSelection
import com.sbwnpc.squad.util.StackData
import com.sbwnpc.squad.util.Terrain
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

    private const val OBJECTIVE_RAYCAST_RANGE = 1024.0
    /** Generous, but bounded — a config packet has to come from someone standing at the block. */
    private const val BARRACKS_REACH_SQR = 64.0 * 64.0

    @SubscribeEvent
    fun register(event: RegisterPayloadHandlersEvent) {
        val r = event.registrar("1")

        r.playToServer(ConfigureToolPayload.TYPE, ConfigureToolPayload.CODEC) { p, ctx -> onConfigureTool(p, ctx) }
        r.playToServer(ToggleToolModePayload.TYPE, ToggleToolModePayload.CODEC) { _, ctx -> onToggleToolMode(ctx) }
        r.playToServer(SquadCmdPayload.TYPE, SquadCmdPayload.CODEC) { p, ctx -> onSquadCmd(p, ctx) }
        r.playToServer(RequestHudPayload.TYPE, RequestHudPayload.CODEC) { _, ctx -> onRequestHud(ctx) }
        r.playToServer(HudOrderPayload.TYPE, HudOrderPayload.CODEC) { p, ctx -> onHudOrder(p, ctx) }
        r.playToServer(HudOrderAllPayload.TYPE, HudOrderAllPayload.CODEC) { p, ctx -> onHudOrderAll(p, ctx) }
        r.playToServer(ChooseFactionPayload.TYPE, ChooseFactionPayload.CODEC) { p, ctx -> onChooseFaction(p, ctx) }
        r.playToServer(RouteCmdPayload.TYPE, RouteCmdPayload.CODEC) { p, ctx -> onRouteCmd(p, ctx) }
        r.playToServer(ConfigureBarracksPayload.TYPE, ConfigureBarracksPayload.CODEC) { p, ctx -> onConfigureBarracks(p, ctx) }

        r.playToClient(OpenCommandScreenPayload.TYPE, OpenCommandScreenPayload.CODEC) { p, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openCommandScreen(p.data)
        }
        r.playToClient(OpenHudPayload.TYPE, OpenHudPayload.CODEC) { p, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openHud(p.data)
        }
        r.playToClient(OpenFactionPickPayload.TYPE, OpenFactionPickPayload.CODEC) { _, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openFactionPick()
        }
        r.playToClient(OpenRecruitScreenPayload.TYPE, OpenRecruitScreenPayload.CODEC) { _, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openRecruitScreenFromHeldItem()
        }
        r.playToClient(OpenRoutesScreenPayload.TYPE, OpenRoutesScreenPayload.CODEC) { p, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openRoutesScreen(p.data)
        }
        r.playToClient(OpenFinishRoutePayload.TYPE, OpenFinishRoutePayload.CODEC) { p, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openFinishRoute(p.pointCount)
        }
        r.playToClient(OpenBarracksScreenPayload.TYPE, OpenBarracksScreenPayload.CODEC) { p, _ ->
            if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandlers.openBarracksScreen(p.pos, p.config)
        }
    }

    private fun onConfigureTool(p: ConfigureToolPayload, ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            // Gate on having picked a default once — but during development everyone may freely
            // choose ANY faction per deploy after that (explicit user call: keep free choice for
            // now, the lock is only meant to bite once an admin-override permission exists later).
            PlayerFactionRegistry.get(level).requireOrPrompt(player) ?: return@enqueueWork
            for (slot in listOf(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) {
                val stack = player.getItemBySlot(slot)
                if (stack.item is SquadToolItem) {
                    SquadToolItem.writeConfig(
                        stack,
                        SquadToolItem.Config(
                            NpcClass.byOrdinal(p.cls),
                            NpcRank.byOrdinal(p.rank),
                            SquadFaction.byOrdinal(p.faction),
                            com.sbwnpc.squad.npc.SquadPreset.byOrdinal(p.preset),
                            p.vehicle,
                            com.sbwnpc.squad.npc.TransportVehicle.byOrdinal(p.vehicleModel),
                            com.sbwnpc.squad.npc.TankModel.byOrdinal(p.tankModel),
                            com.sbwnpc.squad.npc.HelicopterModel.byOrdinal(p.heliModel)
                        )
                    )
                }
            }
        }
    }

    private fun onToggleToolMode(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            for (slot in listOf(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) {
                val stack = player.getItemBySlot(slot)
                if (stack.item !is SquadToolItem) continue
                val next = if (SquadToolItem.mode(stack) == SquadToolItem.MODE_RECRUIT) SquadToolItem.MODE_COMMAND else SquadToolItem.MODE_RECRUIT
                StackData.update(stack) { it.putInt(SquadToolItem.KEY_MODE, next) }
                val name = if (next == SquadToolItem.MODE_COMMAND) "COMMAND" else "RECRUIT"
                player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("Mode: $name").withStyle(net.minecraft.ChatFormatting.YELLOW), true
                )
                return@enqueueWork
            }
        }
    }

    /** The player's one-time, permanent faction pick — idempotent, later attempts are ignored. */
    private fun onChooseFaction(p: ChooseFactionPayload, ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            PlayerFactionRegistry.get(level).set(player.uuid, SquadFaction.byOrdinal(p.faction))
        }
    }

    /**
     * Sets what a Barracks garrisons. Ownership is re-checked here rather than trusted from the
     * screen: a block position in a packet is just three numbers a client chose to send.
     *
     * Range-checked too, because unlike a squad id there is nothing about a position that ties it
     * to the sender — without it, a player could configure any Barracks whose owner they happened
     * to be.
     */
    private fun onConfigureBarracks(p: ConfigureBarracksPayload, ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            if (player.distanceToSqr(Vec3.atCenterOf(p.pos)) > BARRACKS_REACH_SQR) return@enqueueWork
            val be = level.getBlockEntity(p.pos) as? BarracksBlockEntity ?: return@enqueueWork
            if (be.owner != player.uuid) return@enqueueWork
            be.configure(level, SquadToolItem.readConfig(p.config))
        }
    }

    private fun onSquadCmd(p: SquadCmdPayload, ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val mgr = SquadManager.get(level)
            fun bar(msg: String) = player.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), true)
            // Set by the actions that change which squads exist — see the end of this handler.
            var reopen = false
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
                    // The selected NPCs already share one faction (enforced at loose-select time
                    // in SquadToolItem.interactLivingEntity) — read it off them directly rather
                    // than trusting whatever the tool's config happens to say right now.
                    val faction = members.firstNotNullOfOrNull { level.getEntity(it) }
                        ?.let { com.sbwnpc.squad.team.SquadTeams.factionOf(it) } ?: SquadFaction.DEFAULT
                    val squad = mgr.create(level, player.uuid, faction, members)
                    SquadSelection.clear(player.uuid)
                    SquadSelection.selectSquad(player.uuid, squad.id)
                    bar("Squad ${squad.name} formed")
                }
                SquadCmdPayload.DISBAND -> ownedSid()?.let { mgr.disband(level, it); reopen = true }
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
                SquadCmdPayload.DELETE_SQUAD -> ownedSid()?.let { id ->
                    mgr.deleteSquad(level, id)
                    SquadSelection.clear(player.uuid)
                    bar("Squad deleted")
                    reopen = true
                }
            }
            // Removing a squad leaves the player looking at a list that still has it in — and
            // closing the screen for them means reopening it for every squad they wanted gone.
            // Send the fresh list instead; the client swaps the screen out under them.
            if (reopen) {
                val snap = buildSquadSnapshot(mgr, player.uuid, SquadSelection.looseOf(player.uuid).size)
                sendToClient(player, OpenCommandScreenPayload(snap))
            }
        }
    }

    private fun onRequestHud(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val snap = buildSquadSnapshot(SquadManager.get(level), player.uuid, 0)
            // Lets the client show an accurate "ALL SQUADS (N)" count for the `0` key, which
            // (see onHudOrderAll) only actually orders squads matching this — not every squad the
            // player owns, since free-choice deploys mean those can now span multiple factions.
            PlayerFactionRegistry.get(level).get(player.uuid)?.let { snap.putInt("DefaultFaction", it.ordinal) }
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
            mgr.setObjective(level, id, Terrain.lookedAtPos(player, level, OBJECTIVE_RAYCAST_RANGE))
        }
    }

    /** Same as [onHudOrder] but for every squad the sender owns AND that matches their own
     *  recorded default faction — one shared look-direction raycast, applied as each squad's
     *  objective. Scoped to the player's own faction (not just ownership) so a test/OPFOR squad
     *  of a different faction under the same player doesn't get swept up in "order everyone". */
    private fun onHudOrderAll(p: HudOrderAllPayload, ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val mgr = SquadManager.get(level)
            val order = SquadOrder.byOrdinal(p.order)
            val pos = Terrain.lookedAtPos(player, level, OBJECTIVE_RAYCAST_RANGE)
            val defaultFaction = PlayerFactionRegistry.get(level).get(player.uuid)
            mgr.forOwner(player.uuid)
                .filter { defaultFaction == null || it.faction == defaultFaction }
                .forEach { squad ->
                    mgr.setOrder(squad.id, order)
                    mgr.setObjective(level, squad.id, pos)
                }
        }
    }

    private fun onRouteCmd(p: RouteCmdPayload, ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            val level = player.level() as? ServerLevel ?: return@enqueueWork
            val routes = RouteManager.get(level)
            val squads = SquadManager.get(level)
            fun bar(msg: String) = player.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), true)

            when (p.action) {
                RouteCmdPayload.START_RECORDING -> {
                    RouteRecording.start(player.uuid)
                    bar("Recording route — right-click blocks to add points, air-click to finish")
                }
                RouteCmdPayload.FINISH -> {
                    val points = RouteRecording.finish(player.uuid)
                    if (points.isNullOrEmpty()) {
                        bar("No points recorded — route not saved")
                    } else {
                        val route = routes.create(player.uuid, p.text, points)
                        bar("Route ${route.name} saved (${points.size} points)")
                    }
                }
                RouteCmdPayload.CANCEL -> {
                    RouteRecording.cancel(player.uuid)
                    bar("Recording cancelled")
                }
                RouteCmdPayload.ASSIGN -> {
                    val routeId = runCatching { UUID.fromString(p.route) }.getOrNull() ?: return@enqueueWork
                    val squadId = runCatching { UUID.fromString(p.text) }.getOrNull() ?: return@enqueueWork
                    if (!routes.ownedBy(routeId, player.uuid) || !squads.ownedBy(squadId, player.uuid)) return@enqueueWork
                    squads.assignRoute(squadId, routeId)
                    bar("${squads.get(squadId)?.name ?: "Squad"} now patrols ${routes.get(routeId)?.name ?: "route"}")
                }
                RouteCmdPayload.DELETE -> {
                    val routeId = runCatching { UUID.fromString(p.route) }.getOrNull() ?: return@enqueueWork
                    if (!routes.ownedBy(routeId, player.uuid)) return@enqueueWork
                    routes.delete(routeId)
                }
                RouteCmdPayload.REQUEST_LIST -> {
                    sendToClient(player, OpenRoutesScreenPayload(buildRouteSnapshot(routes, squads, player.uuid)))
                }
            }
        }
    }

}
