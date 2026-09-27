package com.sbwnpc.squad.integration.journeymap

import com.sbwnpc.squad.SquadMod
import com.sbwnpc.squad.client.MapState
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.network.RouteCmdPayload
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
import journeymap.api.v2.client.event.FullscreenMapEvent
import journeymap.api.v2.client.event.FullscreenRenderEvent
import journeymap.api.v2.common.event.ClientEventRegistry
import journeymap.api.v2.common.event.FullscreenEventRegistry
import journeymap.api.v2.client.fullscreen.IFullscreen
import journeymap.api.v2.common.waypoint.Waypoint
import journeymap.api.v2.common.waypoint.WaypointFactory
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW
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
 * - Left-click one of your squads' squares to select it (a frame marks it, a dot each of its men,
 *   and it becomes the squad the command tool and quick-command HUD work on too); Shift+click adds or removes squads
 *   for a group, and Shift+drag draws a box that selects every one of your squads inside it.
 *   Right-click anywhere on the map then gives "Move / Attack / Defend / Retreat
 *   here" for everything selected. Right-click on the square itself
 *   changes its order in place. A list of squads in the menu would have to scroll past a
 *   handful, and JourneyMap's menus don't.
 * - "Draw patrol route" in that menu turns clicks into route points: left-click adds one (a drag
 *   still pans the map), Backspace takes the last one back, right-click or Enter sends the route
 *   and the selected infantry patrol it, Esc drops it.
 * - Each of your squads' objectives is also a JourneyMap waypoint — on the minimap and in the
 *   world with its distance — kept only for the session.
 *
 * Only ever loaded by JourneyMap itself, so nothing else in the mod may refer to this class.
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
class SquadMapPlugin : IClientPlugin {
    private lateinit var api: IClientAPI

    /** The squads map orders go to, picked by clicking their squares. */
    private val selected = LinkedHashSet<String>()

    /** What the menu needs to know about the player's squads, kept from the last feed each was in. */
    private class Known(val name: String, val mortar: Boolean)
    private val known = HashMap<String, Known>()

    override fun getModId(): String = SquadMod.MODID

    override fun initialize(api: IClientAPI) {
        this.api = api
        MapState.mapModPresent = true
        MapState.onUpdate = ::redraw
        ClientEventRegistry.ENTITY_RADAR_UPDATE_EVENT.subscribe(modId) { event ->
            if (event.wrappedEntity.entityRef.get() is NpcEntity) event.wrappedEntity.setDisable(true)
        }
        ClientEventRegistry.FULLSCREEN_POPUP_MENU_EVENT.subscribe(modId) { event -> addOrderMenu(event.popupMenu) }
        FullscreenEventRegistry.FULLSCREEN_MAP_CLICK_EVENT.subscribe(modId) { event ->
            if (event.stage != FullscreenMapEvent.Stage.PRE || event.button != 0) return@subscribe
            if (routeDraft != null) {
                pendingPoint = event.location
                pendingScreen = null
            } else if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
                boxFrom = event.location
                boxTo = event.location
                boxScreenFrom = null
                boxDragged = false
            }
        }
        // Cancelled before JourneyMap starts a drag of its own, so the map stays put under the box.
        FullscreenEventRegistry.FULLSCREEN_MAP_DRAG_EVENT.subscribe(modId) { event ->
            if (boxFrom != null && event.button == 0) {
                boxTo = event.location
                boxDragged = true
                if (event.isCancellable) event.cancel()
            }
        }
        FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscribe(modId) { event ->
            drawBox(event)
            drawRouteHint(event)
        }
        NeoForge.EVENT_BUS.addListener(ScreenEvent.KeyPressed.Pre::class.java, ::onRouteKey)
        NeoForge.EVENT_BUS.addListener(ScreenEvent.MouseButtonPressed.Pre::class.java, ::onRouteRightClick)
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Closing::class.java) { event ->
            if (routeDraft != null && event.screen is IFullscreen) dropRoute()
        }
    }

    // --- Patrol route drawing ---

    /** Points of the route being drawn, or null when not drawing one. */
    private var routeDraft: MutableList<BlockPos>? = null
    private var routeSquads: List<String> = emptyList()
    /** A left-click waiting to learn whether it was a click (a point) or the start of a pan. */
    private var pendingPoint: BlockPos? = null
    private var pendingScreen: IntArray? = null

    private fun startRoute(ids: List<String>) {
        routeDraft = mutableListOf()
        routeSquads = ids
        pendingPoint = null
        net.minecraft.client.Minecraft.getInstance().tell { MapState.latest?.let(::redraw) }
    }

    private fun dropRoute() {
        routeDraft = null
        routeSquads = emptyList()
        pendingPoint = null
        redrawLater()
    }

    /** Not from inside JourneyMap's own render or input handling, which is still going over the
     *  overlays a redraw removes. */
    private fun redrawLater() = net.minecraft.client.Minecraft.getInstance().tell { MapState.latest?.let(::redraw) }

    private fun sendRoute() {
        val points = routeDraft ?: return
        if (points.size < 2) {
            tell("A patrol route needs at least 2 points")
            return
        }
        PacketDistributor.sendToServer(
            RouteCmdPayload(RouteCmdPayload.MAP_ROUTE, routeSquads.joinToString(","), points.joinToString(" ") { "${it.x} ${it.z}" })
        )
        dropRoute()
    }

    private fun tell(msg: String) =
        net.minecraft.client.Minecraft.getInstance().player?.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), true)

    private fun onRouteKey(event: ScreenEvent.KeyPressed.Pre) {
        val draft = routeDraft ?: return
        if (event.screen !is IFullscreen) return
        when (event.keyCode) {
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> sendRoute()
            GLFW.GLFW_KEY_BACKSPACE -> {
                draft.removeLastOrNull()
                redrawLater()
            }
            GLFW.GLFW_KEY_ESCAPE -> dropRoute()
            else -> return
        }
        event.isCanceled = true
    }

    /** Right-click finishes the route — taken before JourneyMap would open its menu for it. */
    private fun onRouteRightClick(event: ScreenEvent.MouseButtonPressed.Pre) {
        if (routeDraft == null || event.screen !is IFullscreen || event.button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return
        sendRoute()
        event.isCanceled = true
    }

    /** Settles a pending click once the button is up, and says what the keys do. */
    private fun drawRouteHint(event: FullscreenRenderEvent) {
        val draft = routeDraft ?: return
        pendingPoint?.let { point ->
            val start = pendingScreen ?: intArrayOf(event.mouseX, event.mouseY).also { pendingScreen = it }
            val window = net.minecraft.client.Minecraft.getInstance().window.window
            if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
                pendingPoint = null
                pendingScreen = null
                // Moved with the button down: that was the map being panned, not a point.
                val moved = Math.abs(event.mouseX - start[0]) + Math.abs(event.mouseY - start[1])
                if (moved <= PAN_SLOP && draft.size < MAX_ROUTE_POINTS) {
                    draft += point
                    redrawLater()
                }
            }
        }
        val font = net.minecraft.client.Minecraft.getInstance().font
        val text = "Patrol route: ${draft.size} point(s) — LMB add, Backspace undo, RMB/Enter send, Esc cancel"
        event.graphics.drawCenteredString(font, text, event.graphics.guiWidth() / 2, ROUTE_HINT_Y, SELECTION_COLOR)
    }

    // --- Box selection ---

    /** The map point a Shift+drag started at, and where it has got to. */
    private var boxFrom: BlockPos? = null
    private var boxTo: BlockPos? = null
    /** The same start on screen, for drawing — taken on the first frame after the click. */
    private var boxScreenFrom: IntArray? = null
    private var boxDragged = false

    private fun drawBox(event: FullscreenRenderEvent) {
        if (boxFrom == null) return
        val start = boxScreenFrom ?: intArrayOf(event.mouseX, event.mouseY).also { boxScreenFrom = it }
        // JourneyMap reports no release: the box closes on the first frame the button is up.
        val window = net.minecraft.client.Minecraft.getInstance().window.window
        if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) != org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            finishBox()
            return
        }
        if (!boxDragged) return
        val g = event.graphics
        val x0 = minOf(start[0], event.mouseX)
        val x1 = maxOf(start[0], event.mouseX)
        val y0 = minOf(start[1], event.mouseY)
        val y1 = maxOf(start[1], event.mouseY)
        g.fill(x0, y0, x1, y1, BOX_FILL)
        g.fill(x0, y0, x1, y0 + 1, BOX_EDGE)
        g.fill(x0, y1 - 1, x1, y1, BOX_EDGE)
        g.fill(x0, y0, x0 + 1, y1, BOX_EDGE)
        g.fill(x1 - 1, y0, x1, y1, BOX_EDGE)
    }

    /** Selects every one of the player's squads whose square is inside the box — in place of the
     *  selection there was. A Shift+click that never moved is left to the square's own toggle. */
    private fun finishBox() {
        val from = boxFrom
        val to = boxTo
        val dragged = boxDragged
        boxFrom = null
        boxTo = null
        boxScreenFrom = null
        boxDragged = false
        if (!dragged || from == null || to == null) return
        if (Math.abs(from.x - to.x) < MIN_BOX && Math.abs(from.z - to.z) < MIN_BOX) return
        val feed = MapState.latest ?: return
        val xs = minOf(from.x, to.x)..maxOf(from.x, to.x)
        val zs = minOf(from.z, to.z)..maxOf(from.z, to.z)
        val inside = list(feed, "Squads")
            .filter { it.getBoolean("Own") && it.getInt("X") in xs && it.getInt("Z") in zs }
            .map { it.getUUID("Id").toString() }
        selected.clear()
        selected.addAll(inside)
        // The command tool and the HUD work on one squad: the last one picked.
        inside.lastOrNull()?.let { PacketDistributor.sendToServer(SquadCmdPayload(SquadCmdPayload.SELECT, it, 0, "")) }
        net.minecraft.client.Minecraft.getInstance().tell { MapState.latest?.let(::redraw) }
    }

    // --- Drawing ---

    private fun redraw(feed: CompoundTag) {
        // Not removeAll(modId): that takes the mod's waypoints too, and the objective waypoints
        // were wiped a second after being made, every time.
        for (type in journeymap.api.v2.client.display.DisplayType.entries) api.removeAll(modId, type)
        if (!feed.contains("Dim")) {
            syncWaypoints(null, emptyMap())
            return
        }
        DebugFlags.log(
            "[map-debug] feed: squads={} loose={} vehicles={} enemies={}",
            list(feed, "Squads").size, list(feed, "Loose").size, list(feed, "Vehicles").size, list(feed, "Enemies").size
        )
        val dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(feed.getString("Dim")))
        val objectives = HashMap<String, ObjectiveMark>()

        for (t in list(feed, "Squads")) {
            val faction = faction(t) ?: continue
            val color = colorOf(faction)
            val own = t.getBoolean("Own")
            val order = runCatching { SquadOrder.valueOf(t.getString("Order")) }.getOrNull()
            val name = t.getString("Name")
            val at = BlockPos(t.getInt("X"), 0, t.getInt("Z"))
            val asleep = t.getBoolean("Asleep")
            val opacity = when {
                asleep -> ASLEEP_OPACITY
                own -> 1f
                else -> ALLY_OPACITY
            }
            val marker = marker(dim, at, MapShapes.SQUARE, color, SQUAD_SIZE, opacity)
            marker.setTitle("$name — ${order?.name ?: "?"} (${t.getInt("N")})" + if (asleep) " — out of range, last seen here" else "")
            marker.setLabel(name)
            marker.setTextProperties(TextProperties().setColor(color).setScale(0.8f).setOffsetY(10))
            val id = t.getUUID("Id").toString()
            if (own) known[id] = Known(name, t.getBoolean("Mortar"))
            if (own) marker.setOverlayListener(SquadListener(id))
            show(marker)
            if (own && id in selected) {
                // Each man of a selected squad, so a straggler can be found.
                t.getIntArray("Men").let { men ->
                    for (i in 0 until men.size / 2) {
                        show(marker(dim, BlockPos(men[2 * i], 0, men[2 * i + 1]), MapShapes.CIRCLE, SELECTION_COLOR, MEMBER_SIZE, 1f))
                    }
                }
                // A symbol on screen, like the square itself: an outline drawn in blocks shrank to
                // nothing inside the square once the map was zoomed out.
                show(marker(dim, at, MapShapes.FRAME, SELECTION_COLOR, SELECTION_SIZE, 1f).also {
                    it.setTitle("$name (selected)")
                    // It sits over the square, so a click on the square can land on it instead.
                    it.setOverlayListener(SquadListener(id))
                })
            }

            t.getIntArray("Obj").takeIf { it.size == 3 }?.let { obj ->
                if (own) objectives[id] = ObjectiveMark("$name: ${order?.name?.lowercase() ?: "?"}", BlockPos(obj[0], obj[1], obj[2]), color)
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
        routeDraft?.let { draft ->
            for (p in draft) show(outline(dim, diamond(p, OBJECTIVE_SIZE), SELECTION_COLOR, fill = 0.5f))
            if (draft.size >= 2) show(outline(dim, draft, SELECTION_COLOR))
        }
        syncWaypoints(dim, objectives)
    }

    // --- Objective waypoints ---

    private data class ObjectiveMark(val name: String, val pos: BlockPos, val color: Int)
    private val waypoints = HashMap<String, kotlin.Pair<ObjectiveMark, Waypoint>>()
    /** Cleared out of JourneyMap since joining this world. */
    private var staleWaypointsCleared = false

    /** Brings the waypoints in line with [marks]: only the ones that changed are replaced, so they
     *  don't flicker with every feed. */
    private fun syncWaypoints(dim: ResourceKey<Level>?, marks: Map<String, ObjectiveMark>) {
        val it = waypoints.entries.iterator()
        while (it.hasNext()) {
            val (id, entry) = it.next()
            if (marks[id] != entry.first) {
                runCatching { api.removeWaypoint(modId, entry.second) }
                it.remove()
            }
        }
        if (dim == null) {
            staleWaypointsCleared = false
            return
        }
        // A server running JourneyMap keeps them past the session, "not persistent" or not — and
        // one left from last time belongs to nothing this client knows of, so it never went away.
        if (!staleWaypointsCleared) {
            runCatching { api.removeAllWaypoints(modId) }
            staleWaypointsCleared = true
        }
        for ((id, mark) in marks) {
            if (id in waypoints) continue
            runCatching {
                val wp = WaypointFactory.createClientWaypoint(modId, mark.pos, mark.name, dim, false)
                wp.setColor(mark.color)
                wp.setShowBeacon(false)
                api.addWaypoint(modId, wp)
                waypoints[id] = mark to wp
            }.onFailure { SquadMod.LOGGER.warn("JourneyMap refused a waypoint: {}", it.toString()) }
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
        // JourneyMap refuses a polygon of fewer than 3 points — and a refusal thrown from its render
        // loop closes the map. A two-point route is drawn there and back.
        val ring = if (points.size == 2) listOf(points[0], points[1], points[0]) else points
        return PolygonOverlay(modId, dim, shape, MapPolygon(ring))
    }

    // --- Orders ---

    private fun addOrderMenu(menu: ModPopupMenu) {
        // A squad missing from the feed is not gone — its men are just out of loaded range right
        // now. Dropping it from the selection for that left the menu empty once the player came
        // back, with nothing to say why. The server takes orders for unloaded squads too, and
        // ignores ids that are no longer the player's.
        if (selected.isEmpty()) return
        val ids = selected.toList()
        val who = if (ids.size == 1) known[ids[0]]?.name ?: "Squad" else "${ids.size} squads"
        // The server turns this into each squad's own order and spot (GroupOrders); a barrage
        // only means something to mortars, so it's only offered when one is selected.
        val orders = POINT_ORDERS + if (ids.any { known[it]?.mortar == true }) listOf("Barrage here" to SquadOrder.BARRAGE) else emptyList()
        for ((label, order) in orders) {
            menu.addMenuItem("$who: $label") { pos -> sendOrder(ids, order, pos) }
        }
        // Only infantry walks a route; the server leaves the rest of a mixed selection as it is.
        if (ids.any { known[it]?.mortar != true }) menu.addMenuItem("$who: Draw patrol route") { _ -> startRoute(ids) }
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

    private fun sendOrder(squads: List<String>, order: SquadOrder, pos: BlockPos) =
        PacketDistributor.sendToServer(SquadCmdPayload(SquadCmdPayload.MAP_ORDER, squads.joinToString(","), order.ordinal, "${pos.x} ${pos.z}"))

    /** Left-click on a squad's square selects it; right-click switches its order, keeping the
     *  objective it has. */
    private inner class SquadListener(private val squad: String) : IOverlayListener {
        override fun onMouseClick(state: UIState, mouse: Point2D.Double, pos: BlockPos, button: Int, doubleClick: Boolean): Boolean {
            // A click while drawing a route is a route point, not a selection.
            if (button != 0 || routeDraft != null) return true
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
        const val ASLEEP_OPACITY = 0.4f
        const val OBJECTIVE_SIZE = 3
        const val BARRACKS_SIZE = 2
        const val CIRCLE_POINTS = 32
        const val SELECTION_SIZE = 22.0
        const val SELECTION_COLOR = 0xFFFF55
        const val MEMBER_SIZE = 5.0
        /** Blocks a box has to span on one side at least to be a box and not a slipped click. */
        const val MIN_BOX = 2
        const val BOX_FILL = 0x3366CCFF
        const val BOX_EDGE = 0xCC66CCFF.toInt()
        /** GUI pixels the mouse may move between press and release for it to still be a click. */
        const val PAN_SLOP = 3
        /** The server keeps no more than this many either. */
        const val MAX_ROUTE_POINTS = 32
        const val ROUTE_HINT_Y = 30

        val POINT_ORDERS = listOf(
            "Move here" to SquadOrder.MOVE,
            "Attack here" to SquadOrder.ATTACK,
            "Defend here" to SquadOrder.DEFEND,
            "Retreat here" to SquadOrder.RETREAT
        )
    }
}
