package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.GroupOrders
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.UUID

/**
 * Two groups attack one point; the second's flank sector lies across a strip of powder snow its men
 * cannot path through, as in the forest replay of 10.11, where a squad searched for posts against a
 * powder-snow field for 50 s under fire (#312).
 */
@PrefixGameTestTemplate(false)
object FlankSectorGameTests {
    /** The second squad's arc is refused once its men get no further round, and it attacks from its own side. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 2400, batch = "flank-sector-blocked")
    fun flankingSquadOffABlockedArcAttacksFromItsSide(helper: GameTestHelper) = blockedFlank(helper, recon = false)

    /**
     * A hidden recon squad nearest the point takes no sector: the infantry takes the head-on line and never
     * walks the arc. Found by the enemy, the recon squad joins the attack's sectors as any late group does.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 2400, batch = "flank-sector-recon")
    fun infantryBesideAHiddenReconSquadAttacksHeadOn(helper: GameTestHelper) = blockedFlank(helper, recon = true)

    private fun blockedFlank(helper: GameTestHelper, recon: Boolean) {
        val (front, flank) = setUp(helper, recon)
        val goal = helper.absoluteVec(Vec3(130.5, 1.0, 64.5))
        val trace = Path.of(System.getProperty("sbwnpc.gameTestReports")).resolve(if (recon) "flank-recon.csv" else "flank-blocked.csv")
        Files.createDirectories(trace.parent)
        Files.writeString(trace, "tick,squad,npc,x,z,distance,rule\n")
        var arced = false
        var arcTicks = 0
        val scouts = ReconWatch()
        helper.onEachTick {
            val living = flank.filter { it.isAlive }
            if (living.any { NativeTactics.dutyRule(it) == FLANK_RULE }) {
                arced = true
                arcTicks++
            }
            // Old code kept them on the blocked arc for a minute; refused, they leave it within the stall time.
            helper.assertTrue(arcTicks <= ARC_LIMIT, "The second squad stayed on its blocked flank arc for $arcTicks ticks")
            if (helper.tick % 20L == 0L) writeTrace(helper, trace, front, flank, goal)
            if (recon) {
                helper.assertTrue(!arced, "The infantry beside a hidden recon squad was sent round a flank arc")
                scouts.observe(helper.tick, front)
            }
            helper.assertTrue(living.none { it.position().subtract(goal).horizontalDistance() < CLOSEST }, "A flanker walked onto the objective")
            val distance = living.map { it.position().subtract(goal).horizontalDistance() }.average()
            val closedIn = helper.tick >= SETTLE && distance <= CLOSED_IN
            if (closedIn && if (recon) scouts.done else arced) helper.succeed()
            if (helper.tick == DEADLINE) {
                helper.fail("The second squad stayed ${"%.0f".format(distance)} blocks out (arc walked: $arced, recon: $scouts); see ${trace.fileName}")
            }
        }
    }

    /** Builds the arena, both squads and the enemy, and orders the attack; returns the front and flank squads' men. */
    private fun setUp(helper: GameTestHelper, recon: Boolean): Pair<List<NpcEntity>, List<NpcEntity>> {
        AmmunitionTacticsGameTests.floor(helper)
        for (x in 0..159) for (z in STRIP) helper.setBlock(BlockPos(x, 0, z), Blocks.POWDER_SNOW)
        val manager = SquadManager.get(helper.level)
        val owner = UUID.randomUUID()
        val front = (0 until 4).map { AttackDeploymentGameTests.spawn(helper, Vec3(95.5, 1.0, 60.5 + it * 2), SquadFaction.PIG, role = if (recon) NpcClass.SCOUT else NpcClass.RIFLEMAN) }
        val flank = (0 until 4).map { AttackDeploymentGameTests.spawn(helper, Vec3(82.5, 1.0, 66.5 + it * 2), SquadFaction.PIG) }
        val first = manager.create(helper.level, owner, SquadFaction.PIG, front.map { it.uuid }, if (recon) front.map { NpcClass.SCOUT } else null)
        val second = manager.create(helper.level, owner, SquadFaction.PIG, flank.map { it.uuid })
        val enemy = AttackDeploymentGameTests.spawn(helper, Vec3(130.5, 1.0, 64.5), SquadFaction.COW).apply {
            isNoAi = true
            getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 10000.0
            health = maxHealth
        }
        val oldGroups = DebugFlags.enabledGroups
        DebugFlags.enabledGroups = oldGroups + setOf(LogGroup.ORDER, LogGroup.PATH)
        AmmunitionTacticsGameTests.cleanup(helper) {
            DebugFlags.enabledGroups = oldGroups
            listOf(first, second).forEach { manager.deleteSquad(helper.level, it.id) }
            (front + flank + enemy).forEach(NpcEntity::discard)
        }
        val objective = helper.absolutePos(BlockPos(130, 1, 64))
        GroupOrders.apply(helper.level, manager, listOf(first.id, second.id), SquadOrder.ATTACK, objective.x, objective.z)
        return front to flank
    }

    /** Whether the scouts held their recon stage while hidden and, found at [DISCOVERED_TICK], joined the attack. */
    private class ReconWatch {
        private var watched = false
        private var joined = false
        val done get() = watched && joined

        fun observe(tick: Long, scouts: List<NpcEntity>) {
            val rules = scouts.filter { it.isAlive }.map(NativeTactics::dutyRule)
            if (tick < DISCOVERED_TICK && rules.any { it?.startsWith("recon_") == true }) watched = true
            if (tick > DISCOVERED_TICK && rules.any { it != null && !it.startsWith("recon_") }) joined = true
            if (tick == DISCOVERED_TICK) scouts.firstOrNull { it.isAlive }?.let(NativeTactics::scoutHit)
        }

        override fun toString() = "watched $watched, joined $joined"
    }

    private fun writeTrace(helper: GameTestHelper, trace: Path, front: List<NpcEntity>, flank: List<NpcEntity>, goal: Vec3) {
        val rows =
            (front + flank).filter { it.isAlive }.joinToString("") {
                val local = helper.relativeVec(it.position())
                val distance = it.position().subtract(goal).horizontalDistance()
                "${helper.tick},${if (it in front) "front" else "flank"},${it.uuid.toString().take(8)},${"%.1f".format(local.x)},${"%.1f".format(local.z)},${"%.0f".format(distance)},${NativeTactics.dutyRule(it)}\n"
            }
        Files.writeString(trace, rows, APPEND)
    }

    private val STRIP = 76..78
    private const val FLANK_RULE = "flank_march"
    private const val SETTLE = 200L
    private const val DISCOVERED_TICK = 400L

    /** [com.sbwnpc.squad.ai.tactics.ArcStall.STALL_TICKS] after their last step round the arc, and slack for the walk out. */
    private const val ARC_LIMIT = 600

    /** The flank squad starts about 48 blocks out; closed in to its attack line it stands about 30 or nearer. */
    private const val CLOSED_IN = 32.0
    private const val CLOSEST = 4.0
    private const val DEADLINE = 2300L
}
