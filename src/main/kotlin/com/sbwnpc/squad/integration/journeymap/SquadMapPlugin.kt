package com.sbwnpc.squad.integration.journeymap

import com.sbwnpc.squad.SquadMod
import com.sbwnpc.squad.client.MapState
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.network.SquadCmdPayload
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadOrder
import journeymap.api.v2.client.IClientAPI
import journeymap.api.v2.client.IClientPlugin
import journeymap.api.v2.common.JourneyMapPlugin
import journeymap.api.v2.client.display.IOverlayListener
import journeymap.api.v2.client.display.MarkerOverlay
import journeymap.api.v2.client.display.PolygonOverlay
import journeymap.api.v2.client.fullscreen.ModPopupMenu
import journeymap.api.v2.client.model.MapImage
import journeymap.api.v2.client.model.MapPolygon
import journeymap.api.v2.client.model.ShapeProperties
import journeymap.api.v2.client.model.TextProperties
import journeymap.api.v2.client.util.UIState
import journeymap.api.v2.common.event.ClientEventRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.neoforged.neoforge.network.PacketDistributor
import java.awt.geom.Point2D

/**
 * Draws the map feed (`MapFeed` on the server, [MapState] here) on JourneyMap and takes orders
 * from it.
 *
 * - A squad is a square at its middle, a lone NPC a small circle, a vehicle a triangle, a
 *   helicopter an X — all in the faction's colour. Enemies the side has in sight use the same
 *   symbols in theirs.
 * - Objectives, barrage zones and patrol routes are outlines in the squad's colour.
 * - JourneyMap's own radar would draw every NPC in range, enemies included, whether anyone on the
 *   player's side has seen them or not; our NPCs are taken off it and drawn from the feed instead.
 * - Left-click one of your squads' squares to select it (a ring marks it, and it becomes the
 *   squad the command tool and quick-command HUD work on too); Shift+click adds or removes squads
 *   for a group. Right-click anywhere on the map then gives "Move / Attack / Defend / Retreat
 *   here" for everything selected. Right-click on the square itself
 *   changes its order in place. A list of squads in the menu would have to scroll past a
 *   handful, and JourneyMap's menus don't.
 *
 * Only ever loaded by JourneyMap itself, so nothing else in the mod may refer to this class.
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
class SquadMapPlugin : IClientPlugin {
    private lateinit var api: IClientAPI

    /** The squads map orders go to, picked by clicking their squares. */
    private val selected = LinkedHashSet<String>()

    override fun getModId(): String = SquadMod.MODID

    override fun initialize(api: IClientAPI) {
        this.api = api
        MapState.mapModPresent = true
        MapState.onUpdate = ::redraw
        ClientEventRegistry.ENTITY_RADAR_UPDATE_EVENT.subscribe(modId) { event ->
            if (event.wrappedEntity.entityRef.get() is NpcEntity) event.wrappedEntity.setDisable(true)
        }
        ClientEventRegistry.FULLSCREEN_POPUP_MENU_EVENT.subscribe(modId) { event -> addOrderMenu(event.popupMenu) }
    }

    // --- Drawing ---

    private fun redraw(feed: CompoundTag) {
        api.removeAll(modId)
        if (!feed.contains("Dim")) return
        DebugFlags.log(
            "[map-debug] feed: squads={} loose={} vehicles={} enemies={}",
            list(feed, "Squads").size, list(feed, "Loose").size, list(feed, "Vehicles").size, list(feed, "Enemies").size
        )
        val dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(feed.getString("Dim")))

        for (t in list(feed, "Squads")) {
            val faction = faction(t) ?: continue
            val color = colorOf(faction)
            val own = t.getBoolean("Own")
            val order = runCatching { SquadOrder.valueOf(t.getString("Order")) }.getOrNull()
            val name = t.getString("Name")
            val at = BlockPos(t.getInt("X"), 0, t.getInt("Z"))
            val marker = marker(dim, at, MapShapes.SQUARE, color, SQUAD_SIZE, if (own) 1f else ALLY_OPACITY)
            marker.setTitle("$name — ${order?.name ?: "?"} (${t.getInt("N")})")
            marker.setLabel(name)
            marker.setTextProperties(TextProperties().setColor(color).setScale(0.8f).setOffsetY(10))
            val id = t.getUUID("Id").toString()
            if (own) marker.setOverlayListener(SquadListener(id))
            show(marker)
            if (own && id in selected) {
                show(outline(dim, square(at, SELECTION_RING), color).also { it.setTitle("$name (selected)") })
            }

            t.getIntArray("Obj").takeIf { it.size == 3 }?.let { obj ->
                val center = BlockPos(obj[0], 0, obj[2])
                val zone = t.getInt("Zone")
                if (zone > 0) show(outline(dim, circle(center, zone), color, fill = 0.12f))
                show(outline(dim, diamond(center, OBJECTIVE_SIZE), color))
            }
            t.getIntArray("Route").takeIf { it.size >= 4 }?.let { flat ->
                val points = (flat.indices step 2).map { BlockPos(flat[it], 0, flat[it + 1]) }
                show(outline(dim, points, color))
            }
            t.getIntArray("Barracks").takeIf { it.size == 2 }?.let { b ->
                show(outline(dim, square(BlockPos(b[0], 0, b[1]), BARRACKS_SIZE), color, fill = 0.35f))
            }
        }
        for (t in list(feed, "Loose")) {
            val faction = faction(t) ?: continue
            show(marker(dim, pos(t), MapShapes.CIRCLE, colorOf(faction), LOOSE_SIZE, 1f))
        }
        for (t in list(feed, "Vehicles")) {
            val faction = faction(t) ?: continue
            val shape = if (t.getBoolean("Air")) MapShapes.CROSS else MapShapes.TRIANGLE
            show(marker(dim, pos(t), shape, colorOf(faction), VEHICLE_SIZE, 1f))
        }
        for (t in list(feed, "Enemies")) {
            val faction = faction(t) ?: continue
            val shape = when (t.getInt("Kind")) {
                1 -> MapShapes.TRIANGLE
                2 -> MapShapes.CROSS
                else -> MapShapes.CIRCLE
            }
            val size = if (shape == MapShapes.CIRCLE) LOOSE_SIZE else VEHICLE_SIZE
            show(marker(dim, pos(t), shape, colorOf(faction), size, 1f).also { it.setTitle(faction.label) })
        }
    }

    private fun show(overlay: journeymap.api.v2.client.display.Displayable) {
        runCatching { api.show(overlay) }.onFailure { SquadMod.LOGGER.warn("JourneyMap refused an overlay: {}", it.toString()) }
    }

    private fun marker(
        dim: ResourceKey<Level>, at: BlockPos, shape: MapShapes, color: Int, size: Double, opacity: Float
    ): MarkerOverlay {
        // centerAnchors() centres on the size set so far, so it has to come after the resize —
        // before it, every symbol sat half a full-size texture up and left of its point.
        val image = MapImage(shape.location, MapShapes.SIZE, MapShapes.SIZE)
            .setColor(color).setOpacity(opacity)
            .setDisplayWidth(size).setDisplayHeight(size)
            .centerAnchors()
        return MarkerOverlay(modId, at, image).also { it.setDimension(dim) }
    }

    private fun outline(dim: ResourceKey<Level>, points: List<BlockPos>, color: Int, fill: Float = 0f): PolygonOverlay {
        val shape = ShapeProperties()
            .setStrokeColor(color).setStrokeOpacity(0.9f).setStrokeWidth(2f)
            .setFillColor(color).setFillOpacity(fill)
        return PolygonOverlay(modId, dim, shape, MapPolygon(points))
    }

    // --- Orders ---

    private fun addOrderMenu(menu: ModPopupMenu) {
        if (selected.isEmpty()) return
        val own = list(MapState.latest ?: return, "Squads").filter { it.getBoolean("Own") }
            .associateBy { it.getUUID("Id").toString() }
        // Squads that have since been disbanded or wiped out drop out of the selection.
        selected.retainAll(own.keys)
        if (selected.isEmpty()) return
        val ids = selected.toList()
        val who = if (ids.size == 1) own.getValue(ids[0]).getString("Name") else "${ids.size} squads"
        for ((label, order) in POINT_ORDERS) {
            menu.addMenuItem("$who: $label") { pos -> ids.forEach { sendOrder(it, order, pos) } }
        }
        menu.addMenuItem("Deselect $who") { _ -> select(null, add = false) }
    }

    /** A plain click selects [id] alone (or clears it if it was the only one); [add] toggles it
     *  in or out of the current group. */
    private fun select(id: String?, add: Boolean) {
        when {
            id == null -> selected.clear()
            add -> if (!selected.remove(id)) selected += id
            selected.size == 1 && id in selected -> selected.clear()
            else -> {
                selected.clear()
                selected += id
            }
        }
        // The command tool and the HUD work on one squad: the last one picked.
        if (id != null && id in selected) PacketDistributor.sendToServer(SquadCmdPayload(SquadCmdPayload.SELECT, id, 0, ""))
        // Not straight away: this runs inside JourneyMap's own click handling, while it is still
        // going over the overlays a redraw would remove.
        net.minecraft.client.Minecraft.getInstance().tell { MapState.latest?.let(::redraw) }
    }

    private fun sendOrder(squad: String, order: SquadOrder, pos: BlockPos) =
        PacketDistributor.sendToServer(SquadCmdPayload(SquadCmdPayload.MAP_ORDER, squad, order.ordinal, "${pos.x} ${pos.z}"))

    /** Left-click on a squad's square selects it; right-click switches its order, keeping the
     *  objective it has. */
    private inner class SquadListener(private val squad: String) : IOverlayListener {
        override fun onMouseClick(state: UIState, mouse: Point2D.Double, pos: BlockPos, button: Int, doubleClick: Boolean): Boolean {
            if (button != 0) return true
            select(squad, add = net.minecraft.client.gui.screens.Screen.hasShiftDown())
            // Handled — same answer JourneyMap's own overlays give for a click they take.
            return false
        }

        override fun onOverlayMenuPopup(state: UIState, mouse: Point2D.Double, pos: BlockPos, menu: ModPopupMenu) {
            for (order in listOf(SquadOrder.ATTACK, SquadOrder.DEFEND, SquadOrder.PATROL, SquadOrder.MOVE, SquadOrder.RETREAT)) {
                menu.addMenuItem("Order: ${order.name.lowercase().replaceFirstChar { it.uppercase() }}") {
                    PacketDistributor.sendToServer(SquadCmdPayload(SquadCmdPayload.SET_ORDER, squad, order.ordinal, ""))
                }
            }
        }
    }

    // --- Feed helpers ---

    private fun list(feed: CompoundTag, key: String): List<CompoundTag> =
        feed.getList(key, Tag.TAG_COMPOUND.toInt()).map { it as CompoundTag }

    private fun faction(t: CompoundTag): SquadFaction? = SquadFaction.entries.getOrNull(t.getInt("F"))

    private fun pos(t: CompoundTag) = BlockPos(t.getInt("X"), 0, t.getInt("Z"))

    private fun colorOf(faction: SquadFaction): Int = faction.accentColor.color ?: 0xFFFFFF

    private fun square(c: BlockPos, r: Int) = listOf(
        c.offset(-r, 0, -r), c.offset(r, 0, -r), c.offset(r, 0, r), c.offset(-r, 0, r)
    )

    private fun diamond(c: BlockPos, r: Int) = listOf(
        c.offset(0, 0, -r), c.offset(r, 0, 0), c.offset(0, 0, r), c.offset(-r, 0, 0)
    )

    private fun circle(c: BlockPos, r: Int) = (0 until CIRCLE_POINTS).map {
        val a = it * Math.PI * 2 / CIRCLE_POINTS
        c.offset((Math.cos(a) * r).toInt(), 0, (Math.sin(a) * r).toInt())
    }

    private companion object {
        const val SQUAD_SIZE = 12.0
        const val LOOSE_SIZE = 6.0
        const val VEHICLE_SIZE = 11.0
        const val ALLY_OPACITY = 0.7f
        const val OBJECTIVE_SIZE = 3
        const val BARRACKS_SIZE = 2
        const val CIRCLE_POINTS = 32
        const val SELECTION_RING = 4

        val POINT_ORDERS = listOf(
            "Move here" to SquadOrder.MOVE,
            "Attack here" to SquadOrder.ATTACK,
            "Defend here" to SquadOrder.DEFEND,
            "Retreat here" to SquadOrder.RETREAT
        )
    }
}
