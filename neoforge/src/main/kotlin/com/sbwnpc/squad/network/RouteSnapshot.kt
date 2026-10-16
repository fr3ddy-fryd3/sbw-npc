package com.sbwnpc.squad.network

import com.sbwnpc.squad.squad.RouteManager
import com.sbwnpc.squad.squad.SquadManager
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import java.util.UUID

/** Server-side: pack the player's routes AND squads (for the in-screen assign picker) into a tag
 *  RoutesScreen reads — squads are included here rather than round-tripped separately since the
 *  screen needs both at once and this mirrors buildSquadSnapshot's existing shape. */
fun buildRouteSnapshot(routes: RouteManager, squads: SquadManager, owner: UUID): CompoundTag = CompoundTag().apply {
    put("Routes", ListTag().apply {
        routes.forOwner(owner).forEach { r ->
            add(CompoundTag().apply {
                putString("Id", r.id.toString())
                putString("Name", r.name)
                putInt("Points", r.points.size)
            })
        }
    })
    put("Squads", ListTag().apply {
        squads.forOwner(owner).forEach { s ->
            add(CompoundTag().apply {
                putString("Id", s.id.toString())
                putString("Name", s.name)
                putString("Faction", s.faction.name)
            })
        }
    })
}
