package com.sbwnpc.squad.client.screen

import com.sbwnpc.squad.npc.SquadFaction

/** Shared row shape between [RoutesScreen] (which parses the snapshot) and [AssignRouteScreen]
 *  (which just picks one) — kept as one public type so the two screens don't end up with two
 *  structurally-identical but distinct private data classes an unchecked cast can't actually bridge. */
data class AssignableSquad(val id: String, val name: String, val faction: SquadFaction)
