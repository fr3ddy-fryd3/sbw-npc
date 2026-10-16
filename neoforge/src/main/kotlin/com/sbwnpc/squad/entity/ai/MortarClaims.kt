package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.entity.NpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import java.util.UUID

/** Two transient posts per tube, independent of the soldier's persistent class. */
object MortarClaims {
    enum class Role { OPERATOR, LOADER }
    private val operators = HashMap<UUID, UUID>()
    private val loaders = HashMap<UUID, UUID>()

    fun operator(mortar: UUID): UUID? = operators[mortar]
    fun loader(mortar: UUID): UUID? = loaders[mortar]
    fun role(mortar: UUID, npc: UUID): Role? =
        when (npc) {
            operators[mortar] -> Role.OPERATOR
            loaders[mortar] -> Role.LOADER
            else -> null
        }
    fun hasPlace(mortar: UUID, npc: UUID): Boolean = role(mortar, npc) != null || operators[mortar] == null || loaders[mortar] == null
    fun isOperatorClaimedByOther(mortar: UUID, npc: UUID) = operators[mortar]?.let { it != npc } == true
    fun isLoaderClaimedByOther(mortar: UUID, npc: UUID) = loaders[mortar]?.let { it != npc } == true

    fun available(npc: NpcEntity): Boolean =
        npc.npcClass == com.sbwnpc.squad.npc.NpcClass.MORTAR && npc.isAlive && !npc.isRemoved && !npc.isPassenger && !npc.vehicleTransport &&
            !npc.diggedIn && !npc.movementLockedByCover() && !npc.combatLockedByMedic() && !npc.evadingGrenade() &&
            npc.target?.let { it.isAlive && npc.distanceToSqr(it) <= 36.0 } != true

    fun refresh(level: ServerLevel) {
        for (map in listOf(operators, loaders)) {
            map.entries.removeIf { (mortar, id) ->
                val npc = level.getEntity(id) as? NpcEntity
                // Claims are server-wide; don't expire another dimension's posts here.
                val tube = level.getEntity(mortar)
                if (tube == null && npc == null) false else tube?.isAlive != true || npc == null || !available(npc)
            }
        }
    }

    fun assign(mortar: Entity, npc: NpcEntity): Role? {
        refresh(npc.level() as ServerLevel)
        val old = role(mortar.uuid, npc.uuid)
        val next = when {
            old == Role.OPERATOR -> old
            operators[mortar.uuid] == null -> Role.OPERATOR
            old == Role.LOADER -> old
            loaders[mortar.uuid] == null -> Role.LOADER
            else -> return null
        }
        if (next != old) {
            release(npc.uuid)
            (if (next == Role.OPERATOR) operators else loaders)[mortar.uuid] = npc.uuid
            trace(mortar, npc, next, "post_available")
        }
        npc.assignedMortarId = mortar.uuid
        return next
    }

    /** The loader takes over while the former operator fetches shells; no unowned fire tick. */
    fun handOver(mortar: Entity, away: NpcEntity) {
        if (operator(mortar.uuid) != away.uuid) return
        val other = (away.level() as ServerLevel).getEntity(loader(mortar.uuid) ?: return) as? NpcEntity ?: return
        if (!atPost(other, mortar) || other.mortarShellsLeft <= 0) return
        operators[mortar.uuid] = other.uuid
        loaders[mortar.uuid] = away.uuid
        trace(mortar, other, Role.OPERATOR, "operator_fetching_shells")
        trace(mortar, away, Role.LOADER, "supply_errand")
    }

    fun atPost(npc: NpcEntity, mortar: Entity): Boolean = available(npc) && !npc.mortarErrand && npc.distanceToSqr(mortar) <= 6.25

    fun activeCrew(mortar: Entity): Int {
        val level = mortar.level() as ServerLevel
        return listOfNotNull(operator(mortar.uuid), loader(mortar.uuid)).count { id ->
            val npc = level.getEntity(id) as? NpcEntity
            npc != null && atPost(npc, mortar) && (npc.mortarShellsLeft > 0 || com.sbwnpc.squad.domain.port.Ports.mortars.hasShell(mortar))
        }
    }

    fun release(npc: UUID) {
        operators.entries.removeIf { it.value == npc }
        loaders.entries.removeIf { it.value == npc }
    }
    fun releaseMortar(mortar: UUID) {
        operators.remove(mortar)
        loaders.remove(mortar)
    }
    fun clearAll() {
        operators.clear()
        loaders.clear()
    }
    private fun trace(mortar: Entity, npc: NpcEntity, role: Role, reason: String) {
        DebugFlags.log(LogGroup.FIRE_SUPPORT, "mortar={} npc={} event=crew_assignment role={} reason={}", mortar.uuid, npc.uuid, role, reason)
    }
}
