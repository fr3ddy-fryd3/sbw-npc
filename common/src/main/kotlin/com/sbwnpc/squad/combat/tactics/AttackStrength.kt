package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.ai.tactics.CombatStrength
import com.sbwnpc.squad.block.entity.SupplyPoints
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.SidearmKit
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.ResupplyAttack
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.squad.SquadReports
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * [CombatStrength] in the world: counts a squad's men, takes an attack's base when it starts, runs
 * the refill before an attack, and picks where a called-off attack falls back to.
 *
 * Squads with a barracks are left out, as they were of the losses before (#57): it replaces their
 * fallen, so they fight on whatever they lose.
 */
internal object AttackStrength {
    /** How far a called-off attack falls back when there is no Supply behind it. */
    const val DISTANCE = 100.0

    /** A refill that has not brought the squad nearer the Supply or a man's rifle back for this long is given up. */
    const val STALL_TICKS = 1200L

    /** A squad centre this much nearer its Supply is progress. */
    private const val PROGRESS_BLOCKS = 2.0

    private val NON_INFANTRY = setOf(NpcClass.TANK_CREW, NpcClass.HELICOPTER_PILOT, NpcClass.HELICOPTER_GUNNER, NpcClass.MORTAR)

    fun infantry(npcClass: NpcClass) = npcClass.canonical !in NON_INFANTRY

    /**
     * [squad]'s infantry now. A man on his way to a Supply or standing at one to refill counts as
     * able (owner, 30.10): he is back in a minute, and counted out, the men refilling together could
     * call off an attack they would have carried on. A member whose entity is not loaded counts as
     * able too: nothing is known of him, and the dead are no longer members.
     */
    fun count(squad: Squad, level: ServerLevel): CombatStrength.Count {
        var living = 0
        var able = 0
        for (id in squad.members) {
            val npc = level.getEntity(id) as? NpcEntity
            if (npc != null && (!npc.isAlive || !infantry(npc.npcClass))) continue
            living++
            if (npc == null || SidearmKit.hasPrimaryAmmo(npc) || npc.holds(NpcActivity.RESUPPLY)) able++
        }
        // A save from before the composition was kept has none: what it has now is all it had.
        val full = maxOf(squad.originalComposition.count(::infantry), living)
        return CombatStrength.Count(full, living, able)
    }

    private class Refill(var closest: Double, var able: Int, var lastProgress: Long)

    private val refills = HashMap<UUID, Refill>()

    fun clearAll() = refills.clear()

    /**
     * Takes the base of an attack that has none yet, and resumes one waiting on a refill; true when
     * the squad's order changed, so the caller collects its view again. An attack is measured only
     * with every member loaded: a base from part of the squad would make the rest look lost.
     */
    fun update(squad: Squad, level: ServerLevel, view: TacticalSnapshot): Boolean {
        if (squad.resupplyAttack != null) return resumeOnceRefilled(squad, level, view)
        if (squad.order != SquadOrder.ATTACK || squad.barracks != null || squad.attackBase != null) return false
        if (squad.members.any { level.getEntity(it) == null }) return false
        val count = count(squad, level)
        val manager = SquadManager.get(level)
        if (squad.attackUnmeasuredOnLoad) {
            squad.attackUnmeasuredOnLoad = false
            squad.attackBase = count.able
            manager.setDirty()
            return false
        }
        val supply = if (squad.attackResupplied) null else SupplyPoints.get(level).nearestServing(level, view.center, squad.faction, Double.POSITIVE_INFINITY)
        val start = CombatStrength.start(count, supply != null)
        DebugFlags.log(LogGroup.ORDER, "[tactics] {} squad={} event=attack_strength start={} full={} living={} able={} supply={}", squad.name, squad.id, start, count.full, count.living, count.able, supply)
        when (start) {
            CombatStrength.Start.REMNANT -> {
                squad.attackBase = count.able
                squad.attackRemnant = true
                SquadReports.remnantAttacking(level.server, squad, count.living, count.full)
            }

            CombatStrength.Start.RESUPPLY -> {
                squad.resupplyAttack = ResupplyAttack(squad.objective, squad.focusEntity)
                manager.setOrder(squad.id, SquadOrder.MOVE)
                manager.setObjective(level, squad.id, supply)
                refills[squad.id] = Refill(view.center.distanceTo(supply!!.center), count.able, level.gameTime)
                SquadReports.resupplyingFirst(level.server, squad, count.dry, count.living)
                return true
            }

            CombatStrength.Start.ATTACK -> {
                squad.attackBase = count.able
                if (CombatStrength.poorChances(count)) SquadReports.poorChances(level.server, squad, count.dry, count.living)
            }
        }
        manager.setDirty()
        return false
    }

    /**
     * Back to the attack once every man has his rifle back and none is still at the Supply, or once
     * the refill has stalled for [STALL_TICKS]. A fight on the way stops the clock (owner: "fight it
     * off and go on"): a man with a target does not walk.
     */
    private fun resumeOnceRefilled(squad: Squad, level: ServerLevel, view: TacticalSnapshot): Boolean {
        val waiting = squad.resupplyAttack!!
        val manager = SquadManager.get(level)
        if (squad.order != SquadOrder.MOVE) {
            squad.resupplyAttack = null
            refills.remove(squad.id)
            manager.setDirty()
            return false
        }
        val now = level.gameTime
        val count = count(squad, level)
        val men = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive && infantry(it.npcClass) }
        val supply = squad.objective?.center ?: view.center
        val refill = refills.getOrPut(squad.id) { Refill(view.center.distanceTo(supply), count.able, now) }
        val distance = view.center.distanceTo(supply)
        if (men.any { it.target != null } || distance < refill.closest - PROGRESS_BLOCKS || men.count(SidearmKit::hasPrimaryAmmo) > refill.able) {
            refill.closest = minOf(refill.closest, distance)
            refill.able = maxOf(refill.able, men.count(SidearmKit::hasPrimaryAmmo))
            refill.lastProgress = now
        }
        val refilled = men.isNotEmpty() && men.all { SidearmKit.hasPrimaryAmmo(it) && !it.holds(NpcActivity.RESUPPLY) }
        val stalled = now - refill.lastProgress >= STALL_TICKS
        if (!refilled && !stalled) return false
        refills.remove(squad.id)
        squad.resupplyAttack = null
        manager.setOrder(squad.id, SquadOrder.ATTACK)
        manager.setObjective(level, squad.id, waiting.objective)
        // After the objective: setting it clears the focus.
        waiting.focus?.let { manager.setFocus(squad.id, it) }
        squad.attackResupplied = true
        manager.setDirty()
        DebugFlags.log(LogGroup.ORDER, "[tactics] {} squad={} event=attack_resumed refilled={} able={}/{}", squad.name, squad.id, refilled, count.able, count.living)
        SquadReports.resupplied(level.server, squad, refilled)
        return true
    }

    /** Where a called-off attack goes, and whether it is a Supply. */
    data class Retreat(val point: Vec3, val toSupply: Boolean)

    /**
     * The nearest friendly Supply that lies away from the threat (owner, 30.10: the squad refills
     * where it falls back to), else [DISTANCE] behind the squad on standable ground.
     */
    fun fallBack(level: ServerLevel, squad: Squad, reference: NpcEntity, view: TacticalSnapshot): Retreat {
        val threat = view.visible.maxByOrNull { it.priority }?.position
            ?: view.contacts.maxByOrNull { it.seenAt }?.position
            ?: view.incoming.firstOrNull() ?: view.home
        val direction = away(view.center, threat, reference.lookAngle)
        val supply = SupplyPoints.get(level).nearestServing(level, view.center, squad.faction, Double.POSITIVE_INFINITY) { it.center.subtract(view.center).dot(direction) > 0.0 }
        if (supply != null) return Retreat(supply.center, true)
        return Retreat(rally(level, reference, view.center, view.center.add(direction.scale(DISTANCE)), direction), false)
    }

    private fun away(center: Vec3, threat: Vec3?, facing: Vec3): Vec3 {
        val delta = (threat?.let { center.subtract(it) } ?: facing.scale(-1.0)).multiply(1.0, 0.0, 1.0)
        return if (delta.lengthSqr() > 0.01) delta.normalize() else Vec3(0.0, 0.0, -1.0)
    }

    /** Keep the 100-block retreat depth, adjusting sideways for a blocked or flooded column. */
    private fun rally(level: ServerLevel, reference: NpcEntity, center: Vec3, desired: Vec3, direction: Vec3): Vec3 {
        val side = Vec3(-direction.z, 0.0, direction.x)
        for (offset in listOf(0.0, -4.0, 4.0, -8.0, 8.0, -12.0, 12.0)) {
            val raw = desired.add(side.scale(offset))
            val column = BlockPos.containing(raw)
            if (!level.chunkSource.hasChunk(column.x shr 4, column.z shr 4)) continue
            val point = Terrain.feetAt(level, raw, 32) {
                level.noCollision(reference, reference.getDimensions(reference.pose).makeBoundingBox(it))
            } ?: continue
            if (point.subtract(center).dot(direction) >= DISTANCE - 1.0) return point
        }
        // The existing order chunk loader and navigator handle a destination not loaded yet.
        return desired
    }
}
