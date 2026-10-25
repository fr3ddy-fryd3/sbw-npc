package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.squad.GroupOrders
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID
import java.util.function.Consumer

/** Where an attack's firing line stands: posts that see the defence (#93). */
@PrefixGameTestTemplate(false)
object FiringLineGameTests {
    /**
     * The attackers stand on a hill 14 blocks above the defenders' flat ground, and their slots end
     * up on posts that see the defence. A slot is planned at the objective's height, so the search
     * looks for the floor under it rather than from that height, where every candidate was blind
     * (#93). The defence stands far enough back that the hill's crest is outside a rifleman's
     * clearance even after it moves up a little: nearer, nothing outside the clearance can see down
     * the cliff. Whether a blind slot is searched again soon is a unit test of [com.sbwnpc.squad.ai.tactics.FireSlotSight.stillAnswers].
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1200, batch = "htn-hill-slots")
    fun htnSlotsOnAHillAboveTheDefenceSeeIt(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "htn-hill-slots")
        val manager = SquadManager.get(helper.level)
        val hill = (0..76).flatMap { x -> (0..190).flatMap { z -> (1..14).map { y -> BlockPos(x, y, z) } } }
        val ground = hill.associateWith { helper.getBlockState(it) }
        hill.forEach { helper.setBlock(it, net.minecraft.world.level.block.Blocks.STONE) }
        val groups = (0..1).map { group -> (0 until 8).map { index -> scene.spawn(Vec3(20.5 + index / 4 * 4, 15.0, 72.5 + group * 32 + index % 4 * 4)) } }
        val defenders = (0 until 8).map { index -> scene.spawn(Vec3(140.5 + index / 4 * 4, 1.0, 80.5 + index % 4 * 8), faction = com.sbwnpc.squad.npc.SquadFaction.COW) }
        val squads = groups.map { group -> manager.create(helper.level, UUID(90, 9), requireNotNull(group.first().spawnFaction), group.map { it.uuid }) }
        val defence = manager.create(helper.level, UUID(91, 9), requireNotNull(defenders.first().spawnFaction), defenders.map { it.uuid })
        val attackerIds = groups.flatten().mapTo(HashSet()) { it.uuid }
        val shooters = HashSet<UUID>()
        val rounds = Consumer<EntityJoinLevelEvent> { event ->
            val round = event.entity as? Projectile ?: return@Consumer
            val owner = round.owner?.uuid ?: return@Consumer
            if (event.level === helper.level && Ports.guns.isRound(round) && owner in attackerIds) shooters += owner
        }
        NeoForge.EVENT_BUS.addListener(rounds)
        scene.onClose = {
            NeoForge.EVENT_BUS.unregister(rounds)
            (squads + defence).forEach { manager.deleteSquad(helper.level, it.id) }
            ground.forEach { (pos, state) -> helper.setBlock(pos, state) }
        }
        val target = helper.absolutePos(BlockPos(142, 1, 92))
        manager.issueOrderAt(helper.level, defence.id, SquadOrder.DEFEND, target)
        GroupOrders.apply(helper.level, manager, squads.map { it.id }, SquadOrder.ATTACK, target.x, target.z)
        var firingSince: Long? = null
        var mostBlind = 0
        helper.onEachTick {
            // Who holds a blind post now: a miss is searched again, so a man can be blind for a moment.
            val blind = NativeTactics.slotsWithoutSight(helper.level, groups.flatten().filter { it.isAlive }.map { it.uuid })
            mostBlind = maxOf(mostBlind, blind)
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} attackerShooters=${shooters.size} blindNow=$blind mostBlind=$mostBlind")
            // Slots exist only once the defence is in contact, and the men fire from them.
            if (firingSince == null && shooters.size >= 4) firingSince = helper.tick
            if (firingSince?.let { helper.tick - it >= 100 } == true) {
                helper.assertTrue(blind == 0, "$blind men still hold a slot with no point that sees the defence from the hill")
                scene.result("PASS", "tick=${helper.tick} mostBlind=$mostBlind")
                helper.succeed()
            }
        }
    }

    /** Plateau 8 high, a pit with stairs down at x 37-43 and up at x 110-117; returns what it replaced. */
    private fun pit(helper: GameTestHelper): Map<BlockPos, net.minecraft.world.level.block.state.BlockState> {
        val height = { x: Int ->
            when {
                x <= 36 || x >= 118 -> 8
                x < 44 -> 8 - (x - 36)
                x < 110 -> 0
                else -> x - 109
            }
        }
        // The arena's own features above the pit would block the lines this scene is built around.
        val terrain = (0..170).flatMap { x -> (40..150).flatMap { z -> (1..20).map { y -> BlockPos(x, y, z) } } }
        val ground = terrain.associateWith { helper.getBlockState(it) }
        terrain.forEach { helper.setBlock(it, if (it.y <= height(it.x)) net.minecraft.world.level.block.Blocks.STONE else net.minecraft.world.level.block.Blocks.AIR) }
        return ground
    }

    /**
     * The defence holds a plateau beyond a pit 8 blocks deep, where the firing line's slots fall.
     * Nothing in the pit sees over its far rim, and the plateau behind is out of range of the
     * wider search: only the stairs up the far side, nearer than a rifleman's clearance, see the
     * defence. In-game 23.10 slots in a hollow below a hill found no sighted point outside the
     * clearance, and a third of the line stood unable to fire; a blind slot may now come nearer,
     * down to [com.sbwnpc.squad.ai.tactics.FireSlotSight.BLIND_MIN_DISTANCE], and its man may
     * stand there.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1600, batch = "htn-pit-slots")
    fun htnSlotsInAPitBelowTheDefenceComeNearerUntilTheySeeIt(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "htn-pit-slots")
        val manager = SquadManager.get(helper.level)
        val ground = pit(helper)
        val groups = (0..1).map { group -> (0 until 8).map { index -> scene.spawn(Vec3(20.5 + index / 4 * 4, 9.0, 72.5 + group * 32 + index % 4 * 4)) } }
        val defenders = (0 until 8).map { index -> scene.spawn(Vec3(160.5 + index / 4 * 4, 9.0, 80.5 + index % 4 * 8), faction = com.sbwnpc.squad.npc.SquadFaction.COW) }
        val squads = groups.map { group -> manager.create(helper.level, UUID(92, 9), requireNotNull(group.first().spawnFaction), group.map { it.uuid }) }
        val defence = manager.create(helper.level, UUID(93, 9), requireNotNull(defenders.first().spawnFaction), defenders.map { it.uuid })
        val attackerIds = groups.flatten().mapTo(HashSet()) { it.uuid }
        val shooters = HashSet<UUID>()
        val rounds = Consumer<EntityJoinLevelEvent> { event ->
            val round = event.entity as? Projectile ?: return@Consumer
            val owner = round.owner?.uuid ?: return@Consumer
            if (event.level === helper.level && Ports.guns.isRound(round) && owner in attackerIds) shooters += owner
        }
        NeoForge.EVENT_BUS.addListener(rounds)
        scene.onClose = {
            NeoForge.EVENT_BUS.unregister(rounds)
            (squads + defence).forEach { manager.deleteSquad(helper.level, it.id) }
            ground.forEach { (pos, state) -> helper.setBlock(pos, state) }
        }
        val target = helper.absolutePos(BlockPos(162, 9, 92))
        manager.issueOrderAt(helper.level, defence.id, SquadOrder.DEFEND, target)
        GroupOrders.apply(helper.level, manager, squads.map { it.id }, SquadOrder.ATTACK, target.x, target.z)
        var firingSince: Long? = null
        var mostBlind = 0
        helper.onEachTick {
            val blind = NativeTactics.slotsWithoutSight(helper.level, groups.flatten().filter { it.isAlive }.map { it.uuid })
            mostBlind = maxOf(mostBlind, blind)
            val nearest = groups.flatten().filter { it.isAlive }.minOfOrNull { helper.relativeVec(it.position()).x.let { x -> 160.5 - x } }
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} attackerShooters=${shooters.size} blindNow=$blind mostBlind=$mostBlind nearestDepth=$nearest")
            if (firingSince == null && shooters.size >= 6) firingSince = helper.tick
            if (firingSince?.let { helper.tick - it >= 100 } == true) {
                helper.assertTrue(blind == 0, "$blind men still hold a slot with no point that sees the defence from the pit")
                scene.result("PASS", "tick=${helper.tick} shooters=${shooters.size} mostBlind=$mostBlind nearestDepth=$nearest")
                helper.succeed()
            }
        }
    }
}
