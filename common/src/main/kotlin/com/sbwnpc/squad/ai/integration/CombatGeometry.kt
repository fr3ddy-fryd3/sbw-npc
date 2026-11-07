package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.combat.CombatPosition
import com.sbwnpc.squad.combat.CombatPositionSafety
import com.sbwnpc.squad.combat.SidearmKit
import com.sbwnpc.squad.combat.SquadFormation
import com.sbwnpc.squad.combat.tactics.DefensiveOverwatch
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.util.Terrain
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import kotlin.math.hypot

/**
 * The distances and places of one man's fight that both his engage facts and its moves measure:
 * how near he stops, how far a downed pilot keeps away, the area a defender keeps to, where he
 * may shift, and the next step towards his place in the squad's firing area. One definition each,
 * so the fact that chose a move and the move that runs it never disagree.
 */
internal object CombatGeometry {
    /**
     * A downed pilot with a sidearm has no business closing in: he backs off to this far, shooting
     * as he goes.
     */
    const val KEEP_AWAY_DISTANCE = 40.0

    /** Ground distance between two points. */
    fun flat(a: Vec3, b: Vec3) = hypot(a.x - b.x, a.z - b.z)

    /** How near [npc] keeps from enemies: only a downed pilot keeps away from ordinary contact; zero for the rest. */
    fun keepAway(npc: NpcEntity) = if (npc.npcClass == NpcClass.HELICOPTER_PILOT && npc.vehicle == null) KEEP_AWAY_DISTANCE else 0.0

    /** Within this of his target he stops advancing and fights from a spot: his weapon's reach, or the enemy clearance if wider. */
    fun stoppingDistance(npc: NpcEntity) = maxOf(if (npc.usingSidearm) SidearmKit.FIRE_RANGE else npc.npcClass.assaultDistance, CombatPositionSafety.clearance(npc.npcClass) + CombatPositionSafety.APPROACH_MARGIN)

    /** The point a defender keeps to, or null when his squad does not defend. */
    fun defendHome(npc: NpcEntity): Vec3? = npc.homeCenter()?.takeIf { npc.currentSquad()?.order == SquadOrder.DEFEND }

    /**
     * How far from [defendHome] a defender may fight: his defend radius, and a sniper's or machine
     * gunner's overwatch radius beyond it while he has ammunition for his main weapon.
     */
    fun leash(npc: NpcEntity): Double {
        val overwatch = if (SidearmKit.hasPrimaryAmmo(npc) && npc.npcClass in OVERWATCH_ROLES) DefensiveOverwatch.RADIUS else 0.0
        return CombatPosition.defendRadius(npc.npcClass, npc.currentSquad()?.members?.size ?: 1) + overwatch
    }

    /** Where [npc] may shift to fire from, with [post] where his fight started. */
    fun bounds(npc: NpcEntity, post: Vec3): RelocationBounds {
        val squad = npc.currentSquad()
        return RelocationBounds.of(SquadTactics.task(npc), CombatPosition.holdsPosition(squad?.order), post, defendHome(npc), CombatPosition.defendRadius(npc.npcClass, squad?.members?.size ?: 1))
    }

    /**
     * The next leg towards his slot in the squad's firing area round [target]: opened out to
     * combat spacing, short, and stopping at the enemy clearance; null when no ground stands there.
     */
    fun slotStep(npc: NpcEntity, target: LivingEntity): Vec3? {
        val targetPos = target.position()
        // Opened out under fire: the marching interval puts the whole squad in one burst.
        val slot = SquadFormation.slotTarget(npc, targetPos, targetPos.subtract(npc.position()), false, SquadFormation.COMBAT_SPACING, combat = true)
        // Each leg ends after a few seconds and discards its path. A 100-block search to a distant
        // enemy is mostly unused; a short leg keeps the same heading and formation.
        val step = CombatPositionSafety.approach(
            npc.position(),
            CombatPosition.assaultStep(npc.position(), slot),
            targetPos,
            CombatPositionSafety.clearance(npc.npcClass) + CombatPositionSafety.APPROACH_MARGIN,
        )
        val level = npc.level() as? ServerLevel ?: return null
        return Terrain.standableOrNull(level, step.x, step.y + GROUND_PROBE_RISE, step.z, GROUND_PROBE_DEPTH)
    }

    private val OVERWATCH_ROLES = setOf(NpcClass.SNIPER, NpcClass.MACHINE_GUNNER)

    /** Ground under a planned point is looked for from this far above it... */
    private const val GROUND_PROBE_RISE = 4.0

    /** ...and this many blocks down. */
    private const val GROUND_PROBE_DEPTH = 12
}
