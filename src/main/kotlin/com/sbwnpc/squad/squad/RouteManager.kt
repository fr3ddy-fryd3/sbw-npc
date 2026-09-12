package com.sbwnpc.squad.squad

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/** Server-wide registry of named patrol routes, per-player. */
class RouteManager : SavedData() {

    private val routes = LinkedHashMap<UUID, Route>()

    fun get(id: UUID?): Route? = id?.let { routes[it] }
    fun forOwner(owner: UUID): List<Route> = routes.values.filter { it.owner == owner }
    fun ownedBy(id: UUID, player: UUID): Boolean = get(id)?.owner == player

    fun create(owner: UUID, name: String, points: List<BlockPos>): Route {
        val route = Route(UUID.randomUUID(), owner, name.take(24).ifBlank { "Route" }, points)
        routes[route.id] = route
        setDirty()
        return route
    }

    fun delete(id: UUID) {
        if (routes.remove(id) != null) setDirty()
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val list = ListTag()
        routes.values.forEach { list.add(it.save()) }
        tag.put("Routes", list)
        return tag
    }

    companion object {
        private const val FILE = "sbwnpc_routes"

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): RouteManager {
            val mgr = RouteManager()
            tag.getList("Routes", Tag.TAG_COMPOUND.toInt()).forEach { e ->
                val route = Route.load(e as CompoundTag)
                mgr.routes[route.id] = route
            }
            return mgr
        }

        fun get(server: MinecraftServer): RouteManager =
            server.overworld().dataStorage.computeIfAbsent(
                SavedData.Factory({ RouteManager() }, ::load, null), FILE
            )

        fun get(level: ServerLevel): RouteManager = get(level.server)
    }
}
