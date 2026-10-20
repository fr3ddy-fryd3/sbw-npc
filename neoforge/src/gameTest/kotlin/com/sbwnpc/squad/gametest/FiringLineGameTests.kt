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
     * The attackers stand on a hill 14 blocks above the defenders' flat ground. A slot is planned at
     * the objective's height, so the floor under it is out of reach of a search from there; every
     * candidate counted as blind and the men were left on the blind post (#93, in-game 19.10).
     * The defence stands far enough back that the hill's crest is outside a rifleman's clearance
     * even after it moves up a little: nearer, nothing outside the clearance can see down the cliff.
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
            // Who holds a blind post now, not every search that missed: a miss is searched again,
            // and counting misses failed the scene for a man already on a post that sees.
            val blind = NativeTactics.slotsWithoutSight(helper.level, attackerIds)
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
}
