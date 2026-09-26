package com.sbwnpc.squad.integration.journeymap

import com.sbwnpc.squad.SquadMod
import com.sbwnpc.squad.client.MapState
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.network.SquadCmdPayload
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadOrder
import journeymap.api.v2.client.IClientAPI
import journeymap.api.v2.client.IClientPlugin
import journeymap.api.v2.client.JourneyMapPlugin
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
 * - Right-click on the map: "Squads → Alpha → Move here / Attack here / …". Right-click on a
 *   squad's own square: change its order in place.
 *
 * Only ever loaded by JourneyMap itself, so nothing else in the mod may refer to this class.
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
class SquadMapPlugin : IClientPlugin {
    private lateinit var api: IClientAPI

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
        MapShapes.ensureRegistered()
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
            if (own) marker.setOverlayListener(SquadListener(t.getUUID("Id").toString()))
            show(marker)

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
        runCatching { api.show(overlay) }.onFailure { SquadMod.LOGGER.debug("JourneyMap refused an overlay", it) }
    }

    private fun marker(
        dim: ResourceKey<Level>, at: BlockPos, shape: MapShapes, color: Int, size: Double, opacity: Float
    ): MarkerOverlay {
        val image = MapImage(shape.location, MapShapes.SIZE, MapShapes.SIZE)
            .setColor(color).setOpacity(opacity).centerAnchors()
            .setDisplayWidth(size).setDisplayHeight(size)
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
        val own = list(MapState.latest ?: return, "Squads").filter { it.getBoolean("Own") }
        if (own.isEmpty()) return
        val squads = menu.createSubItemList("Squads")
        for (t in own) {
            val id = t.getUUID("Id").toString()
            val sub = squads.createSubItemList(t.getString("Name"))
            for ((label, order) in POINT_ORDERS) {
                sub.addMenuItem(label) { pos -> sendOrder(id, order, pos) }
            }
        }
    }

    private fun sendOrder(squad: String, order: SquadOrder, pos: BlockPos) =
        PacketDistributor.sendToServer(SquadCmdPayload(SquadCmdPayload.MAP_ORDER, squad, order.ordinal, "${pos.x} ${pos.z}"))

    /** Right-click on a squad's square: switch its order, keeping the objective it has. */
    private class SquadListener(private val squad: String) : IOverlayListener {
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

        val POINT_ORDERS = listOf(
            "Move here" to SquadOrder.MOVE,
            "Attack here" to SquadOrder.ATTACK,
            "Defend here" to SquadOrder.DEFEND,
            "Retreat here" to SquadOrder.RETREAT
        )
    }
}
